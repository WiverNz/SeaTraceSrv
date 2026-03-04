# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build entire workspace
cargo build

# Run the server (requires AISSTREAM_API_KEY env var)
cargo run

# Run all tests
cargo test --workspace

# Run tests for a specific crate
cargo test -p core-model
cargo test -p delivery

# Run BDD integration tests (Cucumber)
cargo test -p integration-tests --test cucumber
cargo test -p integration-tests --test ws_cucumber
```

## Environment Variables

Copy `.env.example` to `.env` before running:
- `AISSTREAM_API_KEY` (required) - API key from aisstream.io
- `BIND_ADDR` (optional, default: `0.0.0.0:8080`)
- `RUST_LOG` (optional, default: `info`)

## Architecture

SeaTraceSrv is a real-time maritime vessel tracking system that ingests AIS data and broadcasts events to clients via WebSocket.

```
AISStream.io ──WebSocket──► connectors ──► delivery ──► control-api ──► Clients
                                              │
                                         H3 Spatial Index
```

### Workspace Crates

| Crate | Purpose |
|-------|---------|
| `core-model` | Data models, auto-generated from `api-contracts/openapi.yaml` via Progenitor |
| `connectors` | AISStream WebSocket client, converts positions to H3-indexed events |
| `delivery` | `Broadcaster` trait and `InMemoryBroadcaster` for pub/sub by H3 cell |
| `control-api` | Axum HTTP/WebSocket server with `/health`, `/sources`, `/snapshot`, `/realtime` |
| `aggregator` | Placeholder for multi-source aggregation |
| `data-store` | Placeholder for persistence |
| `integration-tests` | Cucumber BDD tests |

### Code Generation

`core-model` uses a build script (`crates/core-model/build.rs`) to generate Rust types from `api-contracts/openapi.yaml` at compile time. Generated code is written to `$OUT_DIR/api_generated.rs` and included via `include!` macro.

### Data Flow

1. `AisStreamConnector` connects to AISStream WebSocket, parses AIS messages
2. Positions are converted to `Event` with H3 cell index (resolution 7)
3. Events are sent to the shared `Broadcaster`
4. `InMemoryBroadcaster` routes events to clients subscribed to matching H3 cells
5. `control-api` WebSocket handler streams events to connected clients

### Key Traits

- `Broadcaster` (in `delivery`): async trait for subscribe/broadcast operations, allows swapping implementations
