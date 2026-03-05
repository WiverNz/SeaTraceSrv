# SeaTraceSrv

Real-time maritime vessel tracking server that ingests AIS (Automatic Identification System) data and broadcasts events to clients via WebSocket. Clients can request enrichment **levels of detail (LOD)** to attach additional data — weather conditions, and more in the future — to each event.

## Features

- **Real-time AIS data ingestion** from [AISStream.io](https://aisstream.io)
- **Spatial indexing** using Uber's H3 hexagonal grid system
- **Location-based subscriptions** — clients subscribe to specific geographic areas (H3 cells)
- **WebSocket streaming** for real-time event delivery
- **Level of Detail (LOD)** — opt-in per-event enrichment (weather, and future: water conditions, depth, currents)
- **HTTP API** for health checks and snapshots
- **Python client library & CLI** for scripting and testing
- **Android SDK** for mobile integration (Kotlin, Coroutines/Flow)

## Architecture

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   AISStream.io  │────►│   Connectors    │────►│    Delivery     │
│   (WebSocket)   │     │  (AIS Parser)   │     │  (Broadcaster)  │
└─────────────────┘     └─────────────────┘     └────────┬────────┘
                                                         │
                               H3 Spatial Index ─────────┤
                                                         │
                                                         ▼
                        ┌─────────────────┐     ┌─────────────────┐     ┌──────────────┐
                        │     Clients     │◄────│   Control API   │◄────│  Open-Meteo  │
                        │  (WebSocket)    │     │  (Axum Server)  │     │  (Weather)   │
                        └─────────────────┘     └─────────────────┘     └──────────────┘
```

### Workspace Structure

| Crate | Description |
|-------|-------------|
| `core-model` | Data models generated from OpenAPI spec via Progenitor |
| `connectors` | AISStream WebSocket client, H3 cell conversion |
| `delivery` | `Broadcaster` trait, `InMemoryBroadcaster` with H3 routing |
| `control-api` | Axum HTTP/WebSocket server, LOD enrichment pipeline |
| `aggregator` | Multi-source aggregation (placeholder) |
| `data-store` | Persistence layer (placeholder) |
| `integration-tests` | Cucumber BDD tests |

### Client SDKs

| Path | Language | Description |
|------|----------|-------------|
| `scripts/` | Python | Client library and CLI tool |
| `seatrace-sdk-android/` | Kotlin | Android SDK (Coroutines/Flow) |

## Prerequisites

- Rust 1.86+
- [AISStream API key](https://aisstream.io)

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
