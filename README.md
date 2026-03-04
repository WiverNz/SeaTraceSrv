# SeaTraceSrv

Real-time maritime vessel tracking server that ingests AIS (Automatic Identification System) data and broadcasts events to clients via WebSocket.

## Features

- **Real-time AIS data ingestion** from [AISStream.io](https://aisstream.io)
- **Spatial indexing** using Uber's H3 hexagonal grid system
- **Location-based subscriptions** - clients subscribe to specific geographic areas (H3 cells)
- **WebSocket streaming** for real-time event delivery
- **HTTP API** for health checks and snapshots

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
                        ┌─────────────────┐     ┌─────────────────┐
                        │     Clients     │◄────│   Control API   │
                        │  (WebSocket)    │     │  (Axum Server)  │
                        └─────────────────┘     └─────────────────┘
```

### Workspace Structure

| Crate | Description |
|-------|-------------|
| `core-model` | Data models generated from OpenAPI spec |
| `connectors` | AISStream WebSocket client |
| `delivery` | Event broadcasting with H3-based routing |
| `control-api` | HTTP/WebSocket server (Axum) |
| `aggregator` | Multi-source aggregation (placeholder) |
| `data-store` | Persistence layer (placeholder) |
| `integration-tests` | Cucumber BDD tests |

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

3. Build:
   ```bash
   cargo build
   ```

4. Run:
   ```bash
   cargo run
   ```

## Configuration

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `AISSTREAM_API_KEY` | Yes | - | API key from aisstream.io |
| `BIND_ADDR` | No | `0.0.0.0:8080` | Server bind address |
| `RUST_LOG` | No | `info` | Log level (trace, debug, info, warn, error) |

## API Endpoints

### HTTP

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/health` | Service health status |
| GET | `/sources` | List active data sources |
| POST | `/snapshot` | Request historical events for H3 cells |

### WebSocket

| Endpoint | Description |
|----------|-------------|
| `/realtime` | Real-time event stream |

**WebSocket Usage:**

1. Connect to `ws://localhost:8080/realtime`
2. Send subscription message:
   ```json
   {"h3_cells": ["8a2a1072b59ffff", "8a2a1072b5bffff"]}
   ```
3. Receive events as JSON:
   ```json
   {
     "event_id": "uuid",
     "h3_index": "8a2a1072b59ffff",
     "timestamp": "2024-01-01T00:00:00Z",
     "source": "aisstream",
     "confidence": 0.95,
     "payload": {
       "type": "VesselPosition",
       "mmsi": 123456789,
       "latitude": 51.5074,
       "longitude": -0.1278,
       "speed_over_ground": 12.5,
       "course_over_ground": 180.0
     }
   }
   ```

## Event Types

| Type | Description |
|------|-------------|
| `VesselPosition` | Vessel location with MMSI, coordinates, speed, course |
| `WeatherAlert` | Weather alerts (planned) |
| `SeaPhenomenon` | Sea phenomena observations (planned) |
| `Incident` | Maritime incident reports (planned) |

## Docker

Build and run with Docker:

```bash
# Build image
docker build -t seatracesrv .

# Run container
docker run -p 8080:8080 \
  -e AISSTREAM_API_KEY=your_key_here \
  seatracesrv
```

## Testing

```bash
# Run all tests
cargo test --workspace

# Run specific crate tests
cargo test -p delivery
cargo test -p control-api

# Run BDD integration tests
cargo test -p integration-tests --test cucumber
cargo test -p integration-tests --test ws_cucumber
```

## API Contracts

API specifications are defined in `api-contracts/`:
- `openapi.yaml` - REST API specification (used for code generation)
- `asyncapi.yaml` - WebSocket message protocol specification

## License

[Add license information]
