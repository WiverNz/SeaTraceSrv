# Vessel Catalog — Design Reference

The vessel catalog is a Redis-backed store of vessel metadata keyed by MMSI.
It is built and published by `catalog-worker` and read by all `seatracesrv` service pods.

---

## Design goals

- Fast lookup by MMSI at request time (single `HGETALL`)
- Support multiple catalog versions simultaneously in Redis
- Atomic version switch without pod restarts
- Easy rollback — flip one key
- Isolated builds — a new build does not affect readers until it is published
- Clean separation: the worker writes; service pods only read

---

## Redis key schema

```
vessel_catalog:active_version           STRING   Currently active version ID
vessel_catalog:versions                 ZSET     All versions; score = build unix timestamp
vessel_catalog:version:{v}:meta         HASH     Build metadata for version v
vessel_catalog:version:{v}:mmsi:{mmsi}  HASH     Vessel record for MMSI under version v
```

### `vessel_catalog:active_version`

A single string key. Its value is the version ID that all service pods currently use.
Switching versions: `SET vessel_catalog:active_version 2026-03-10T18:00:00Z`
This is the only key that needs to change to publish or roll back a catalog.

### `vessel_catalog:versions`

A sorted set of all known version IDs. Score is the Unix timestamp of the build start.
Used by the worker to prune old versions and by operators to list available versions.

```
ZADD vessel_catalog:versions 1741370400 "2026-03-07T18:00:00Z"
ZADD vessel_catalog:versions 1741284000 "2026-03-06T18:00:00Z"
```

### `vessel_catalog:version:{v}:meta`

Build metadata hash. Written at build start (`status=building`) and updated on
completion (`status=ready`) or failure (`status=failed`).

| Field | Example |
|-------|---------|
| `version` | `2026-03-07T18:00:00Z` |
| `status` | `building` / `ready` / `failed` |
| `source` | `itu_mars+gfw` |
| `record_count` | `1045821` |
| `created_at` | `2026-03-07T17:40:00Z` |
| `completed_at` | `2026-03-07T17:58:30Z` |
| `schema_version` | `1` |

### `vessel_catalog:version:{v}:mmsi:{mmsi}`

One hash per vessel record. Field names match the `VesselRecord` struct in `catalog-worker`.

Minimal fields (sufficient for most use cases):

| Field | Type | Description |
|-------|------|-------------|
| `mmsi` | string | MMSI identifier |
| `imo` | string | IMO number |
| `name` | string | Vessel name |
| `flag` | string | ISO 3166-1 alpha-2, e.g. `DK` |
| `type_code` | string | AIS ship type code |
| `type_name` | string | Human-readable type |
| `length_m` | string | Length in metres |
| `width_m` | string | Width in metres |
| `source` | string | Source identifier |
| `updated_at` | string | RFC3339 source timestamp |
| `catalog_version` | string | Redundant — useful for debugging |

Additional fields when available: `normalized_name`, `callsign`, `flag_name`,
`subtype`, `draft_m`, `year_built`, `gross_tonnage`, `deadweight_t`.

---

## Build and publish workflow

```
1. Worker generates version ID: e.g. "2026-03-07T18:00:00Z"

2. HSET vessel_catalog:version:{v}:meta
      version    = "2026-03-07T18:00:00Z"
      status     = "building"
      source     = "itu_mars"
      created_at = "2026-03-07T17:40:00Z"

3. Worker fetches from all VesselSource implementations.
   Records are merged by MMSI (first source wins per field).

4. Pipelined writes (500 records/pipeline flush):
   HSET vessel_catalog:version:{v}:mmsi:220625000
        mmsi "220625000" name "EXAMPLE VESSEL" flag "DK" ...

5. HSET vessel_catalog:version:{v}:meta
        status       = "ready"
        record_count = "1045821"
        completed_at = "2026-03-07T17:58:30Z"

6. SET vessel_catalog:active_version "2026-03-07T18:00:00Z"
   ← this is the atomic switch; all pods start using it within 5 seconds

7. ZADD vessel_catalog:versions <timestamp> "2026-03-07T18:00:00Z"

8. Prune: ZREVRANGE, keep N newest, SCAN+DEL older version keys + ZREM
```

---

## Service pod read path

```
startup:
  tokio::spawn(vessel_catalog::start_catalog_poller(state.clone()))
  ↓
  every 5 s:
    GET vessel_catalog:active_version → store in AppState.active_catalog_version

per request (when MMSI lookup needed):
  read cached active_catalog_version (RwLock)
  HGETALL vessel_catalog:version:{v}:mmsi:{mmsi}
  → if non-empty: return enriched fields
  → if empty:     return only mmsi (miss)
```

The poller is implemented in `crates/control-api/src/vessel_catalog.rs`.
It connects to Redis through `AppState.redis_pool` (bb8 connection pool).

---

## Rollback

Rolling back to a previous version requires one Redis command:

```bash
redis-cli SET vessel_catalog:active_version "2026-03-06T18:00:00Z"
```

All service pods will switch to the previous version within 5 seconds.
No pod restart, no redeployment, no rebuild required.

This works because:
- Old version keys are still present in Redis (kept for `CATALOG_VERSIONS_TO_KEEP` builds)
- Service pods always use whatever `active_version` says

---

## Versioning policy

The `catalog-worker` keeps the N most recent versions (`CATALOG_VERSIONS_TO_KEEP`, default 3).
On each successful build:

1. Add the new version to the sorted set
2. Prune versions ranked beyond N (oldest first) by scanning and deleting their keys

Recommended production policy:
- Keep 3 versions (2 rollback points)
- Retain failed builds' meta keys for debugging (but do not publish them)

---

## Operational queries

```bash
# Which version is active?
redis-cli GET vessel_catalog:active_version

# List all versions (newest first)
redis-cli ZREVRANGE vessel_catalog:versions 0 -1 WITHSCORES

# Check build status for a version
redis-cli HGETALL vessel_catalog:version:2026-03-07T18:00:00Z:meta

# Look up a vessel
redis-cli HGETALL vessel_catalog:version:2026-03-07T18:00:00Z:mmsi:220625000

# Count records in a version (approximate via key scan — slow on large datasets)
redis-cli --scan --pattern 'vessel_catalog:version:2026-03-07T18:00:00Z:mmsi:*' | wc -l

# Manually roll back
redis-cli SET vessel_catalog:active_version "2026-03-06T18:00:00Z"
```

---

## Future extensions

### Lookup caching

For very high query rates, cache `HGETALL` results in-process per H3 cell with a
short TTL. The `active_catalog_version` change already acts as a natural invalidation
boundary since the full Redis key changes.

### Write path (vessel catalog enricher in control-api)

Currently, `lookup_mmsi` in `control-api/src/vessel_catalog.rs` returns raw
`HashMap<String, String>` from Redis. To enrich vessel events automatically,
implement a `VesselCatalogEnricher` struct that implements the `Enricher` trait
and registers in the `EnrichmentPipeline` inside `AppState::new()`.

### Multiple source tiers

Add a priority tier to `VesselSource`:
- Tier 1 (authoritative): ITU MARS, national flag state registries
- Tier 2 (operational): MarineTraffic, VesselFinder, AIS aggregators
- Tier 3 (community): OpenCPN vessel database, crowdsourced corrections

The current `merge_from` logic already applies "first source wins per field" — just
register sources in priority order.
