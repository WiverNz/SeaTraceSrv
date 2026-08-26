<p align="center">
  <img src="preview.png" width="800"
       alt="SeaTraceSrv Android app showing live AIS vessels on an OpenSeaMap nautical chart">
</p>

# SeaTraceSrv

Real-time maritime vessel tracking system. Ingests AIS data, enriches vessel events, and streams them to clients over WebSocket. Ships are identified by a versioned Redis vessel catalog rebuilt by a dedicated worker. A native Android app renders vessels on an OpenSeaMap nautical chart.

## Features

- **Real-time AIS data ingestion** from [AISStream.io](https://aisstream.io)
- **Spatial indexing** using Uber's H3 hexagonal grid (resolution 7)
- **Location-based subscriptions** — clients subscribe by H3 cell
- **WebSocket streaming** for real-time event delivery
- **Level of Detail (LOD)** — opt-in enrichment (weather, and future: vessel metadata, depth)
- **Redis vessel catalog** — versioned MMSI→metadata lookup; atomic version switching, rollback support
- **Catalog worker** — separate process that builds and publishes the vessel catalog and downloads nautical chart data
- **Android app** — native Kotlin app with MapLibre + OpenSeaMap + live AIS overlay
- **Python client library & CLI** for scripting and testing

## Architecture

```
AISStream.io ──WS──► connectors ──► delivery ──► control-api ──► Android app
                                       │              │               │
                                  H3 res-7        Redis pool     MapLibre
                                  spatial         ↕              OpenSeaMap
                                  index    vessel_catalog:*       overlay
                                                   ↑
                                           catalog-worker
                                           (hourly rebuild)
                                                   │
                                           external sources
                                           (ITU MARS, …)
```

Full system design: [`docs/architecture.md`](docs/architecture.md)
Vessel catalog design: [`docs/vessel-catalog.md`](docs/vessel-catalog.md)

### Rust workspace crates

| Crate | Description |
|-------|-------------|
| `core-model` | Data models generated from OpenAPI spec via Progenitor |
| `connectors` | AISStream WebSocket client, H3 cell conversion |
| `delivery` | `Broadcaster` trait, `InMemoryBroadcaster` with H3 routing |
| `control-api` | Axum HTTP/WS server, LOD enrichment, vessel catalog lookup |
| `aggregator` | Multi-source aggregation (placeholder) |
| `data-store` | Persistence layer (placeholder) |
| `integration-tests` | Cucumber BDD tests |

### Workers

| Path | Language | Description |
|------|----------|-------------|
| `workers/catalog-worker/` | Rust | Builds Redis vessel catalog; downloads GEBCO/NOAA ENC/EMODnet map data |

### Client apps & SDKs

| Path | Language | Description |
|------|----------|-------------|
| `android/seatrace/` | Kotlin | Full Android app — MapLibre map + AIS overlay + layer controls |
| `seatrace-sdk-android/` | Kotlin | Reusable Android SDK (Coroutines/Flow) |
| `scripts/` | Python | CLI client and Python library |

## Prerequisites

- Rust 1.86+
- [AISStream API key](https://aisstream.io)
- Redis 7+ (for vessel catalog; the server starts without it but enrichment is disabled)
- Android Studio Meerkat or later (for the Android app)

## Setup

1. Clone the repository:
   ```bash
   git clone <repository-url>
   cd seatracesrv
   ```

2. Configure environment:
   ```bash
   cp .env.example .env
   # Edit .env and add your AISSTREAM_API_KEY
   ```

3. Build and run:
   ```bash
   cargo build
   cargo run
   ```

   Or load `.env` inline without a shell plugin:
   ```bash
   set -a && source .env && set +a && cargo run
   ```

## Configuration

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `AISSTREAM_API_KEY` | Yes | — | API key from aisstream.io |
| `BIND_ADDR` | No | `0.0.0.0:8080` | Server bind address |
| `RUST_LOG` | No | `info` | Log level (`trace`, `debug`, `info`, `warn`, `error`) |

## API

### HTTP

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/health` | Service health and component status |
| GET | `/sources` | List active AIS data sources |
| POST | `/snapshot` | Historical events for given H3 cells |

### WebSocket — `/realtime`

Connect, then send **one** subscription message. The server pushes events until the connection closes.

#### Subscription message (client → server)

```json
{
  "h3_cells": [608431123508232191],
  "lod": ["weather_current"]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `h3_cells` | `uint64[]` | H3 cells to subscribe to. Empty array = all events (wildcard). |
| `lod` | `string[]` | Optional detail levels (see [Level of Detail](#level-of-detail)). Default: `[]`. |

#### Event (server → client)

```json
{
  "event_id": "550e8400-e29b-41d4-a716-446655440000",
  "h3_index": 608431123508232191,
  "timestamp": 1741183200000,
  "source": "AISStream",
  "confidence": 1.0,
  "payload": {
    "type": "VesselPosition",
    "mmsi": 123456789,
    "lat": 52.3702,
    "lon": 4.8952,
    "sog": 12.5,
    "cog": 270.0
  },
  "weather": {
    "current": {
      "time": "2026-03-05T14:00",
      "temperature_2m": 6.1,
      "wind_speed_10m": 18.4,
      "relative_humidity_2m": 78.0
    }
  }
}
```

The `weather` field is present only when a weather LOD is requested. Future enrichment fields follow the same pattern.

## Level of Detail

LOD flags control which enrichment data the server attaches to each event. They are composable — request any combination.

| LOD value | Description | Data source |
|-----------|-------------|-------------|
| `vessels` | Vessel position data (always included by default) | AISStream |
| `weather_current` | Current temperature, wind speed, humidity at the vessel position | [Open-Meteo](https://open-meteo.com) |
| `weather_hourly` | 24-hour hourly forecast (implies `weather_current`) | Open-Meteo |
| *(planned)* `water_conditions` | Buoy / channel sensor data | — |
| *(planned)* `depth` | Bathymetric depth at position | — |
| *(planned)* `water_currents` | Surface water current vector | — |

Weather data is **cached per H3 cell for 15 minutes** — all vessels in the same cell share one API call. Open-Meteo is free and requires no API key.

## Event Payload Types

| Type | Description |
|------|-------------|
| `VesselPosition` | MMSI, coordinates, speed over ground, course over ground |
| `WeatherAlert` | Kind, severity, affected area polygon |
| `SeaPhenomenon` | Kind, coordinates, optional evidence |
| `Incident` | Kind, coordinates, optional vessel MMSI |

## Python Client

### Installation

```bash
pip install websockets httpx
```

### CLI

```bash
# Stream all events — vessels only
python scripts/client.py stream

# Add current weather to each event
python scripts/client.py stream --lod weather_current

# Add current weather + 24-hour forecast
python scripts/client.py stream --lod weather_current weather_hourly --verbose

# Subscribe to specific H3 cells with weather
python scripts/client.py stream --cells 608431123508232191 --lod weather_current

# Connect to a specific host/port
python scripts/client.py --host localhost --port 8080 stream

# Stop after N events
python scripts/client.py stream --max-events 50

# HTTP commands
python scripts/client.py health
python scripts/client.py sources
```

### Library

```python
from seatrace_client import RealtimeClient, SeaTraceClient, Lod

# Stream with weather enrichment
async with RealtimeClient("localhost", 8080) as client:
    await client.subscribe(lod=[Lod.WEATHER_CURRENT])
    async for event in client:
        print(event.payload)
        if event.weather and event.weather.current:
            print(event.weather.current)   # "6.1°C  18.4 km/h  78%rh"

# Stream with full forecast
async with RealtimeClient("localhost", 8080) as client:
    await client.subscribe(lod=[Lod.WEATHER_CURRENT, Lod.WEATHER_HOURLY])
    async for event in client:
        if event.weather and event.weather.hourly:
            print(event.weather.hourly.at_hour(0))

# HTTP API
with SeaTraceClient("localhost", 8080) as client:
    health = client.get_health()
    sources = client.get_sources()
    snapshot = client.pull_snapshot([608431123508232191])
```

## Android SDK

The `seatrace-sdk-android/` directory contains a Kotlin SDK with Coroutines/Flow-based streaming, automatic reconnection, and LOD support.

See [seatrace-sdk-android/README.md](seatrace-sdk-android/README.md) for full documentation.

### Quick Start

```kotlin
val client = SeaTraceClient(endpoint = "wss://your-server/realtime")
client.connect()

// Vessels only
client.subscribeVessels()

// With current weather
client.subscribeVessels(lod = listOf(Lod.WEATHER_CURRENT))

// With full weather forecast
client.subscribeVessels(lod = listOf(Lod.WEATHER_CURRENT, Lod.WEATHER_HOURLY))

lifecycleScope.launch {
    client.vesselsFlow.collect { update ->
        Log.d("SeaTrace", "Vessel ${update.position.mmsi}")
        update.weather?.current?.let { w ->
            Log.d("SeaTrace", "Weather: ${w.temperature2m}°C  ${w.windSpeed10m} km/h")
        }
    }
}
```

### Enrichment Architecture

LOD enrichments live in the `model/enrichment/` package. Adding a new enrichment type (e.g. water conditions) requires:
1. A new `Lod` enum variant in `model/enrichment/Lod.kt`
2. A data class in `model/enrichment/` (e.g. `WaterConditions.kt`)
3. An optional field on `Event` in `model/Models.kt`
4. Server-side support in `control-api/src/weather.rs` (or a new enricher)

### Building

```bash
cd seatrace-sdk-android
./gradlew :sdk:assembleDebug     # debug AAR
./gradlew :sdk:assembleRelease   # release AAR
./gradlew :sdk:testDebugUnitTest # unit tests
```

## Catalog Worker

The catalog worker is a standalone Rust binary that runs separately from the API server.
It periodically fetches vessel data, writes versioned records into Redis, and atomically
switches the active catalog version. Service pods read the active version and look up
vessel metadata without any pod restart.

```bash
# Run locally (Redis must be accessible)
REDIS_URL=redis://localhost:6379 cargo run -p catalog-worker

# Build Docker image (from workspace root)
docker build -f workers/catalog-worker/Dockerfile -t catalog-worker .

# Run container
docker run \
  -e REDIS_URL=redis://redis:6379 \
  -e CATALOG_REFRESH_INTERVAL_SECS=3600 \
  -v /data:/data \
  catalog-worker
```

See [`workers/catalog-worker/README.md`](workers/catalog-worker/README.md) for full documentation.

## Android App

`android/seatrace/` is the native Android application. It renders an OpenSeaMap nautical
chart via MapLibre and overlays live AIS ship positions received from the server.

```bash
cd android/seatrace
./gradlew :app:assembleDebug
./gradlew :app:installDebug    # requires connected device or running emulator
```

The default WebSocket URL is `ws://10.0.2.2:8080` (emulator alias for the host machine).
Change `WS_BASE_URL` in `app/build.gradle.kts` for a real device or production server.

See [`android/seatrace/README.md`](android/seatrace/README.md) for full documentation.

## Docker

```bash
docker build -t seatracesrv .
docker run -p 8080:8080 -e AISSTREAM_API_KEY=your_key seatracesrv
```

## Testing

```bash
# All tests
cargo test --workspace

# Specific crates
cargo test -p delivery
cargo test -p control-api

# BDD integration tests
cargo test -p integration-tests --test cucumber
cargo test -p integration-tests --test ws_cucumber
```

## API Contracts

| File | Description |
|------|-------------|
| `api-contracts/openapi.yaml` | REST API spec — used for Rust code generation via Progenitor |
| `api-contracts/asyncapi.yaml` | WebSocket protocol spec — subscription message, enriched event, LOD enum, weather schemas |

## VS Code

The repository includes `.vscode/` launch configurations for running and debugging the server, running integration tests, and Android SDK build and test tasks.

## License

[Add license information]
