# catalog-worker

A standalone Rust worker that builds and publishes the vessel catalog used by all
`seatracesrv` service pods. It runs independently, writes versioned records into Redis,
and flips the active catalog version atomically once a build is fully ready.

---

## What it does

On each refresh cycle the worker:

1. Fetches vessel data from all configured sources (see `src/sources/`)
2. Merges records by MMSI — first source wins per field, secondary sources fill blanks
3. Writes a new versioned catalog to Redis under `vessel_catalog:version:{v}:mmsi:{mmsi}`
4. Marks the version as `ready` in its metadata hash
5. Atomically sets `vessel_catalog:active_version` to the new version
6. Prunes versions beyond `CATALOG_VERSIONS_TO_KEEP` (default: 3)
7. Downloads nautical chart data from GEBCO, NOAA ENC, and EMODnet (stubs; see below)
8. Writes a `manifest.json` to `DATA_DIR`
9. Sleeps for `CATALOG_REFRESH_INTERVAL_SECS`, then repeats

Service pods never restart — they read `active_version` every 5 seconds and switch
to the new catalog automatically. Rollback is trivial: `SET vessel_catalog:active_version <old>`.

---

## Configuration

All configuration is via environment variables.

| Variable | Default | Description |
|----------|---------|-------------|
| `REDIS_URL` | `redis://127.0.0.1:6379` | Redis connection string |
| `CATALOG_REFRESH_INTERVAL_SECS` | `3600` | Time between full rebuild cycles |
| `CATALOG_VERSIONS_TO_KEEP` | `3` | How many old versions to keep for rollback |
| `DATA_DIR` | `/data` | Root directory for downloaded map/chart files |
| `RUST_LOG` | `info` | Log filter (`trace`, `debug`, `info`, `warn`, `error`) |

---

## Running

### Locally

```bash
# From workspace root
REDIS_URL=redis://localhost:6379 cargo run -p catalog-worker
```

### Docker

The Dockerfile must be built from the **workspace root** so the full Cargo workspace
is available for compilation:

```bash
docker build -f workers/catalog-worker/Dockerfile -t catalog-worker .

docker run \
  -e REDIS_URL=redis://redis:6379 \
  -e CATALOG_REFRESH_INTERVAL_SECS=3600 \
  -e CATALOG_VERSIONS_TO_KEEP=3 \
  -e RUST_LOG=info \
  -v /data:/data \
  catalog-worker
```

### Kubernetes (CronJob pattern)

The worker can be deployed as a long-running Deployment (it loops internally) or as a
Kubernetes `CronJob` by setting `CATALOG_REFRESH_INTERVAL_SECS` to a very large value
and letting the scheduler restart it on its own schedule.

---

## Redis data model

```
vessel_catalog:active_version           STRING   Currently active catalog version (RFC3339)
vessel_catalog:versions                 ZSET     All known versions; score = build unix timestamp
vessel_catalog:version:{v}:meta         HASH     Build metadata (see below)
vessel_catalog:version:{v}:mmsi:{mmsi}  HASH     Vessel fields (see below)
```

### Meta hash fields

| Field | Description |
|-------|-------------|
| `version` | The version string (same as the key component) |
| `status` | `building` → `ready` or `failed` |
| `source` | Source identifiers joined with `+`, e.g. `itu_mars+gfw` |
| `record_count` | Number of vessel records written |
| `created_at` | Build start time (RFC3339) |
| `completed_at` | Build completion time (RFC3339) |
| `schema_version` | Schema version of the Redis layout (`1`) |

### Vessel record hash fields

| Field | Notes |
|-------|-------|
| `mmsi` | MMSI as string |
| `imo` | IMO number, if known |
| `name` | Vessel name |
| `normalized_name` | Trimmed + uppercased name |
| `callsign` | Radio callsign |
| `flag` | ISO 3166-1 alpha-2, e.g. `DK` |
| `flag_name` | Full country name |
| `type_code` | AIS ship type code (0–255) |
| `type_name` | Human-readable ship type |
| `subtype` | Optional subtype |
| `length_m` | Length in metres |
| `width_m` | Width in metres |
| `draft_m` | Draft in metres |
| `year_built` | Year of build |
| `gross_tonnage` | Gross tonnage |
| `deadweight_t` | Deadweight tonnage |
| `source` | Source identifier that contributed this record |
| `updated_at` | RFC3339 timestamp of the source's last update |
| `catalog_version` | Redundant version string for debugging |

---

## Adding a vessel data source

1. Create `src/sources/your_source.rs`
2. Implement the `VesselSource` trait:
   ```rust
   #[async_trait]
   impl VesselSource for YourSource {
       fn name(&self) -> &str { "your_source" }
       async fn fetch(&self) -> Result<Vec<VesselRecord>> { ... }
   }
   ```
3. Register it in `src/main.rs`:
   ```rust
   let vessel_sources: Vec<Box<dyn VesselSource>> = vec![
       Box::new(ItuMarsSource::new(http.clone())),
       Box::new(YourSource::new(http.clone())), // ← add here
   ];
   ```

The `CatalogBuilder` merges sources by MMSI: the first source that provides a field
wins; subsequent sources only fill in `None` fields via `VesselRecord::merge_from`.

### ITU MARS (current stub)

`src/sources/itu.rs` is the scaffold for the ITU Maritime Mobile Service database.
ITU publishes official MMSI assignments as an Excel workbook at:
<https://www.itu.int/en/ITU-R/terrestrial/mars/>

To implement it, add `calamine` to `Cargo.toml` and follow the `TODO` comments
in `itu.rs` to fetch and parse the workbook.

---

## Map data (stubs)

The `src/maps/` module is scaffolded for downloading nautical chart and bathymetry data.
Each source implements the `MapSource` trait and returns `ManifestEntry` records.
A `manifest.json` is written to `DATA_DIR` after each run.

| Module | Source | Status |
|--------|--------|--------|
| `gebco.rs` | GEBCO global bathymetry grids | Stub — HTTP scraping not yet implemented |
| `noaa_enc.rs` | NOAA ENC catalog XML + ZIP bundles | Stub — download URLs defined, not fetched |
| `emodnet.rs` | EMODnet WCS/WMS/download links | Stub — capability URLs defined, not fetched |

To implement a map source, follow the `TODO` comments in the respective file.
Required crates (add to `Cargo.toml`): `scraper` (HTML parsing), `quick-xml` (WCS/WMS),
`calamine` (ITU MARS Excel), `tokio::fs` + `reqwest` byte streaming (file downloads).

---

## Source layout

```
src/
├── main.rs                  Entry point — config, Redis pool, run loop
├── config.rs                Config from env vars
├── sources/
│   ├── mod.rs               VesselRecord struct + VesselSource trait
│   └── itu.rs               ITU MARS stub
├── catalog/
│   ├── mod.rs               CatalogBuilder orchestrator
│   └── redis_writer.rs      All Redis I/O (pipeline writes, version management, pruning)
└── maps/
    ├── mod.rs               MapSource trait, Manifest types, run_all() orchestrator
    ├── gebco.rs             GEBCO stub
    ├── noaa_enc.rs          NOAA ENC stub
    └── emodnet.rs           EMODnet stub
```
