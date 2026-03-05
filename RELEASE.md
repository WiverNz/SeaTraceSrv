# Release Notes — v0.0.1 (2026-03-05)

Initial prototype release of SeaTraceSrv — a real-time maritime vessel tracking system.

## Highlights

- **Real-time AIS ingestion**: Connects to [AISStream.io](https://aisstream.io) and streams live vessel positions.
- **H3 spatial routing**: Positions are indexed with Uber's H3 grid (resolution 7) and routed to subscribed clients.
- **WebSocket delivery**: Clients connect to `/realtime` and subscribe to geographic cells; matching events are pushed instantly.
- **Python client**: A library and CLI tool for scripting, testing, and exploring the API from the command line.
- **Android SDK**: Kotlin SDK with Coroutines/Flow, automatic reconnection, and subscription-based filtering.

## Server

### HTTP API
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/health` | Service health and component status |
| GET | `/sources` | Active AIS data sources |
| POST | `/snapshot` | Historical events for given H3 cells |

### WebSocket API (`/realtime`)
Subscribe by sending:
```json
{"h3_cells": ["8a2a1072b59ffff"]}
```
Events are delivered as JSON with `event_id`, `h3_index`, `timestamp`, `source`, `confidence`, and `payload`.

## Python Client

```bash
pip install websockets httpx

python scripts/client.py stream --host localhost --port 8080
python scripts/client.py health
python scripts/client.py sources
```

## Android SDK

```kotlin
val client = SeaTraceClient(endpoint = "wss://your-server/realtime")
client.connect()

lifecycleScope.launch {
    client.vesselsFlow.collect { update ->
        // handle VesselPosition update
    }
}
```

Build AAR:
```bash
cd seatrace-sdk-android
./gradlew :sdk:assembleRelease
```

## Deployment

```bash
docker build -t seatracesrv .
docker run -p 8080:8080 -e AISSTREAM_API_KEY=your_key seatracesrv
```

## Requirements

| Component | Requirement |
|-----------|-------------|
| Server | Rust 1.86+, AISStream API key |
| Python client | Python 3.10+, `websockets`, `httpx` |
| Android SDK | Android API 24+, Kotlin 1.9+, Gradle 8.4+, JDK 17+ |

## What's Next

- `aggregator` crate: multi-source AIS aggregation
- `data-store` crate: event persistence and historical replay
- Weather alert and sea phenomenon event types
