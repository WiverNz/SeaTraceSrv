# System Architecture

## Overview

SeaTrace is a real-time maritime vessel tracking platform. It ingests AIS data,
enriches vessel events with metadata and weather, and streams them to Android clients
that render the data on a nautical chart.

```
┌───────────────────────────────────────────────────────────────────────┐
│  External data sources                                                │
│                                                                       │
│  AISStream.io ──WS──┐    ITU MARS (vessel registry)                  │
│                      │    GEBCO (bathymetry) ─────────────────────┐   │
│                      │    NOAA ENC (charts) ──────────────────┐   │   │
│                      │    EMODnet (EU bathymetry) ────────┐   │   │   │
└──────────────────────┼────────────────────────────────────┼───┼───┼───┘
                       │                                    │   │   │
                       ▼                                    ▼   ▼   ▼
              ┌────────────────┐                   ┌─────────────────────┐
              │  connectors    │                   │   catalog-worker    │
              │ (AIS parser,   │                   │  (periodic rebuild) │
              │  H3 indexing)  │                   └──────────┬──────────┘
              └───────┬────────┘                              │
                      │ Event{h3_index, VesselPosition}       │ Redis HSET pipeline
                      ▼                                       ▼
              ┌────────────────┐              ┌──────────────────────────────┐
              │    delivery    │              │           Redis               │
              │  (Broadcaster, │              │  vessel_catalog:active_version│
              │  H3 pub/sub)   │              │  vessel_catalog:version:{v}:* │
              └───────┬────────┘              └──────────────────────────────┘
                      │                                       │
                      ▼                                       │ HGETALL
              ┌────────────────┐  vessel lookup               │
              │  control-api   │◄─────────────────────────────┘
              │ (Axum HTTP/WS) │
              │  enrichment    │◄── Open-Meteo (weather)
              │  pipeline      │
              └───────┬────────┘
                      │ WS events (JSON)
                      ▼
              ┌────────────────┐
              │  Android app   │
              │  (MapLibre,    │
              │  OpenSeaMap)   │
              └────────────────┘
```

---

## Components

### connectors (`crates/connectors/`)

Connects to AISStream.io via WebSocket. Parses incoming AIS NMEA/JSON messages and
converts each vessel position into an `Event`:

```rust
Event {
    event_id: Uuid,
    h3_index: u64,      // H3 cell at resolution 7 for the position
    timestamp: i64,     // Unix ms
    source: String,
    confidence: f64,
    payload: EventPayload::VesselPosition { mmsi, lat, lon, sog, cog },
}
```

The H3 index is computed using `h3o` at resolution 7 (~5 km² per cell).

### delivery (`crates/delivery/`)

Implements in-memory pub/sub keyed by H3 cell. The `Broadcaster` trait abstracts
the routing so it can be swapped for a distributed implementation later.

```
broadcast(Event) → routes to all subscribers for Event.h3_index
subscribe(cells: Vec<u64>) → returns a Stream<Event>
```

### control-api (`crates/control-api/`)

Axum server exposing:

| Endpoint | Description |
|----------|-------------|
| `GET /health` | Service health |
| `GET /sources` | Active AIS sources |
| `POST /snapshot` | Historical events for H3 cells |
| `GET /realtime` | WebSocket — subscribe and stream events |

The WebSocket handler runs an enrichment pipeline per event before sending.
Current enrichers: `WeatherClient` (Open-Meteo).

`AppState` holds:
- `broadcaster: Arc<dyn Broadcaster>` — shared with connectors
- `enricher_pipeline: EnrichmentPipeline` — weather, future: vessel catalog
- `redis_pool: bb8::Pool<RedisConnectionManager>` — for vessel catalog lookup
- `active_catalog_version: Arc<RwLock<Option<String>>>` — cached, refreshed every 5 s

The vessel catalog version is polled in a background task started from `main.rs`
(`vessel_catalog::start_catalog_poller`). Lookups use `vessel_catalog::lookup_mmsi`.

### catalog-worker (`workers/catalog-worker/`)

A completely separate binary (separate Docker image). Runs independently on a schedule.

Responsibilities:
1. Fetch vessel data from pluggable `VesselSource` implementations
2. Merge records by MMSI
3. Write versioned catalog to Redis (pipelined HSET, 500 records/batch)
4. Validate and publish the new version (atomic `SET active_version`)
5. Prune old versions (SCAN + DEL)
6. Download map/chart data from GEBCO, NOAA ENC, EMODnet (stubs)
7. Write `manifest.json`

See [`docs/vessel-catalog.md`](vessel-catalog.md) for the full Redis schema design.

### Android app (`android/seatrace/`)

Kotlin app targeting Android 7.0+ (API 24).

Key classes:

| Class | Role |
|-------|------|
| `MainActivity` | Activity — map lifecycle, FABs, permission flow |
| `MapViewModel` | Ships state, WebSocket, H3 viewport conversion |
| `SeaTraceWebSocket` | OkHttp WS client with auto-reconnect |
| `ShipLayerManager` | Updates MapLibre GeoJSON source |
| `LayersBottomSheet` | Layer visibility controls |

The map style (`assets/style_nautical.json`) uses OpenStreetMap raster tiles as the base
and OpenSeaMap raster tiles as the nautical overlay. Ships are rendered as a GeoJSON
`circle` layer that gets replaced in-place on every state update.

---

## H3 spatial indexing

H3 resolution 7 is used throughout the system:

| Component | Library | Usage |
|-----------|---------|-------|
| `connectors` | `h3o` (Rust) | Index each vessel position on ingest |
| `delivery` | — | Route events by H3 index |
| Android app | `h3` (Java/JNI) | Convert viewport bounds to cell list for subscription |

**Critical**: all three must use the same resolution. The constant is `7`.
If you change it, update: `connectors/src/` (event creation), `MapViewModel.kt` (`H3_RESOLUTION`).

---

## Enrichment pipeline

The `EnrichmentPipeline` in `control-api` is a chain of `Enricher` implementations
applied to each event before it is sent to the client.

Current enrichers:

| Enricher | LOD flag | Data source |
|----------|----------|-------------|
| `WeatherClient` | `weather_current`, `weather_hourly` | Open-Meteo (free, no key) |

Planned enrichers:

| Enricher | LOD flag | Data source |
|----------|----------|-------------|
| `VesselCatalogEnricher` | (default) | Redis vessel catalog |
| `DepthEnricher` | `depth` | GEBCO / PostGIS |
| `WaterCurrentsEnricher` | `water_currents` | EMODnet / Copernicus |

To add an enricher, implement the `Enricher` trait and register it in
`AppState::new()` via `.with(your_enricher)`.

---

## Data stores

### Redis

Used for:
- Vessel catalog (versioned MMSI→metadata hashes)
- Active catalog version key (polled by service pods every 5 s)

Not used for tile caching — tiles are served directly from OSM/OpenSeaMap CDNs in V1.

### PostgreSQL / PostGIS (planned)

Not yet implemented. Intended for:
- Vessel track history
- Seamarks and nautical POIs
- User reports
- App tile version management

See `crates/data-store/` (placeholder).

---

## Deployment

### Local development

```bash
# 1. Start Redis
docker run -p 6379:6379 redis:7-alpine

# 2. Run catalog worker (optional — vessels still stream without it)
REDIS_URL=redis://localhost:6379 cargo run -p catalog-worker

# 3. Run server
AISSTREAM_API_KEY=xxx REDIS_URL=redis://localhost:6379 cargo run

# 4. Open android/seatrace/ in Android Studio and run on emulator
```

### Production (Kubernetes)

- `helm/seatracesrv/` contains the Helm chart for the API server
- Catalog worker: deploy as a separate `Deployment` or `CronJob`
- Redis: managed Redis service or `bitnami/redis` Helm chart
- Android app: distribute via Google Play or internal tracks

### Docker images

| Image | Dockerfile | Built from |
|-------|------------|------------|
| `seatracesrv` | `Dockerfile` (root) | Workspace root |
| `catalog-worker` | `workers/catalog-worker/Dockerfile` | Workspace root |

Both are multi-stage builds: Rust builder → `debian:bookworm-slim` runtime.

---

## Security and legal

### Attribution requirements

Any deployment that shows map data derived from OpenStreetMap or OpenSeaMap must display:
> © OpenStreetMap contributors · © OpenSeaMap contributors

The Android app shows this string permanently at the bottom of the map view.

### Navigation disclaimer

The app must display "Not for navigation" or equivalent. This is shown in the layers panel.
It must also appear in any public-facing description of the app.

### Data licenses

| Data | License |
|------|---------|
| OpenStreetMap | ODbL 1.0 |
| OpenSeaMap | CC BY-SA |
| ITU MARS vessel database | ITU terms of use |
| GEBCO | CC BY 4.0 |
| NOAA ENC | Public domain (US government) |
| EMODnet | CC BY 4.0 |
| AISStream.io | Commercial terms |
| Open-Meteo | CC BY 4.0 |
