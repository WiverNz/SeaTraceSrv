# CLAUDE.md

Guidance for Claude Code when working in this repository.

---

## Repository map

```
seatracesrv/
├── src/main.rs                   Binary entry point (server)
├── crates/                       Rust workspace crates (server-side)
│   ├── core-model/               OpenAPI-generated data models
│   ├── connectors/               AISStream WebSocket client
│   ├── delivery/                 Broadcaster trait + InMemoryBroadcaster
│   ├── control-api/              Axum HTTP/WS server + enrichment pipeline
│   ├── aggregator/               Placeholder
│   ├── data-store/               Placeholder
│   └── integration-tests/        Cucumber BDD tests
├── workers/
│   └── catalog-worker/           Standalone binary — builds Redis vessel catalog
├── android/
│   └── seatrace/                 Android app (Kotlin, MapLibre, AIS overlay)
├── seatrace-sdk-android/         Kotlin SDK (separate from the app — library only)
├── scripts/                      Python client + CLI
├── api-contracts/                OpenAPI + AsyncAPI specs
├── helm/                         Kubernetes Helm chart
└── Dockerfile                    Server image
```

---

## Build commands

### Rust server (workspace root)

```bash
# Build entire Rust workspace
cargo build

# Run server (requires AISSTREAM_API_KEY + REDIS_URL)
cargo run

# All workspace tests
cargo test --workspace

# Specific crates
cargo test -p core-model
cargo test -p delivery
cargo test -p control-api

# BDD integration tests
cargo test -p integration-tests --test cucumber
cargo test -p integration-tests --test ws_cucumber

# Build catalog-worker only
cargo build -p catalog-worker
```

### Catalog worker Docker image

Must be built from the **workspace root** (the Dockerfile copies the full workspace):

```bash
docker build -f workers/catalog-worker/Dockerfile -t catalog-worker .
```

### Android app

Open `android/seatrace/` in Android Studio, or from the command line:

```bash
cd android/seatrace
./gradlew :app:assembleDebug          # build debug APK
./gradlew :app:assembleRelease        # build release APK
./gradlew :app:installDebug           # build + install on connected device/emulator
./gradlew :app:connectedAndroidTest   # instrumented tests
```

---

## Environment variables

### Server (`src/main.rs`)

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `AISSTREAM_API_KEY` | Yes | — | API key from aisstream.io |
| `BIND_ADDR` | No | `0.0.0.0:8080` | Server listen address |
| `REDIS_URL` | No | `redis://127.0.0.1:6379` | Redis connection string |
| `RUST_LOG` | No | `info` | Log filter |

### Catalog worker (`workers/catalog-worker/`)

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `REDIS_URL` | No | `redis://127.0.0.1:6379` | Redis connection string |
| `CATALOG_REFRESH_INTERVAL_SECS` | No | `3600` | Rebuild period |
| `CATALOG_VERSIONS_TO_KEEP` | No | `3` | Old versions kept for rollback |
| `DATA_DIR` | No | `/data` | Root for downloaded map/chart data |
| `RUST_LOG` | No | `info` | Log filter |

### Android app (`android/seatrace/app/build.gradle.kts`)

`WS_BASE_URL` is a `BuildConfig` field — override in `build.gradle.kts`:

```kotlin
buildConfigField("String", "WS_BASE_URL", "\"ws://your-server:8080\"")
```

Default (`ws://10.0.2.2:8080`) is the Android emulator alias for the host machine.

---

## Architecture overview

```
AISStream.io ──WS──► connectors ──► delivery ──► control-api ──► Mobile clients
                                       │              │
                                  H3 res-7        Redis pool
                                  spatial         ↕
                                  index       vessel_catalog:*
                                                   ↑
                                           catalog-worker
                                           (periodic rebuild)
```

Full diagram and data flows: [`docs/architecture.md`](docs/architecture.md).

---

## Key design decisions and gotchas

### AppState factory

`AppState::new()` requires a Redis pool and catalog version lock.
**Always use the `create_app_state(broadcaster, redis_url)` factory** from `control-api::lib` —
never construct `AppState` directly in `main.rs` or tests:

```rust
let state = create_app_state(broadcaster.clone(), &redis_url).await?;
```

The bb8 pool connects lazily, so this works in integration tests even without Redis running —
catalog lookups just return `None` silently.

### Redis async command return types

The redis crate's async commands cannot infer the return type without an annotation.
Always write:

```rust
let _: ()     = conn.hset_multiple(...).await.context("...")?;
let _: ()     = conn.set(...).await.context("...")?;
let _: usize  = conn.zadd(...).await.context("...")?;
let _: usize  = conn.del(keys).await.context("...")?;
```

### H3 resolution consistency

The server indexes events at **resolution 7** (`h3o` in Rust).
The Android app subscribes at **resolution 7** (`H3-Java 4.1.1`).
If you change the resolution anywhere, change it in both places:
- Server: `crates/connectors/src/` (where `Event.h3_index` is set)
- Android: `MapViewModel.kt` constant `H3_RESOLUTION`

### Vessel catalog key schema

```
vessel_catalog:active_version           STRING   current version (RFC3339 timestamp)
vessel_catalog:versions                 ZSET     score=unix-ts, member=version
vessel_catalog:version:{v}:meta         HASH     status, record_count, source, …
vessel_catalog:version:{v}:mmsi:{mmsi}  HASH     vessel fields
```

Service pods poll `active_version` every 5 s (see `vessel_catalog.rs`).
Workers write all records under a new version before flipping `active_version` (atomic switch).

### Adding a vessel data source

1. Create `workers/catalog-worker/src/sources/your_source.rs`
2. Implement the `VesselSource` trait (one async `fetch()` method)
3. Register it in `workers/catalog-worker/src/main.rs` `vessel_sources` vec

### Adding a map data source

1. Create `workers/catalog-worker/src/maps/your_source.rs`
2. Implement the `MapSource` trait
3. Register in `main.rs` `map_sources` vec and handle the name in `maps::run_all()`

### Android map style

The MapLibre style is `android/seatrace/app/src/main/assets/style_nautical.json`.
The `"ships"` GeoJSON source is defined there with empty initial data.
`ShipLayerManager.update()` replaces its data at runtime — no style reload needed.

To add a new map layer (e.g. depth contours), add a source + layer entry to the JSON
and expose a toggle in `MapViewModel` + `LayersBottomSheet`.

### Code generation (core-model)

`crates/core-model/build.rs` generates Rust types from `api-contracts/openapi.yaml`
at compile time using Progenitor. If you change the OpenAPI spec, run `cargo build`
and the generated code in `$OUT_DIR/api_generated.rs` rebuilds automatically.

---

## Testing notes

Integration tests spin up a real Axum server on a random port.
They call `create_app_state(broadcaster, "redis://127.0.0.1:6379").await.unwrap()` —
this works without Redis because bb8 pools connect lazily.

The `e2e_real_data` test requires `AISSTREAM_API_KEY` and is `#[ignore]` by default:

```bash
cargo test -p integration-tests --test e2e_real_data -- --ignored
```
