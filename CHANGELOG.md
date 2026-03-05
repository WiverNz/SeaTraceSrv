# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed
- Updated `README.md` with Python client usage, Android SDK quick start, and VS Code section

## [0.0.1] - 2026-03-05

### Added

#### Server (Rust)
- Workspace with six crates: `core-model`, `connectors`, `delivery`, `control-api`, `aggregator`, `data-store`
- `core-model`: OpenAPI-driven type generation via Progenitor from `api-contracts/openapi.yaml`
- `connectors`: AISStream WebSocket client that parses AIS messages and maps positions to H3 cells (resolution 7)
- `delivery`: `Broadcaster` async trait and `InMemoryBroadcaster` for H3-cell-based pub/sub routing
- `control-api`: Axum HTTP/WebSocket server with `/health`, `/sources`, `/snapshot`, and `/realtime` endpoints
- `aggregator`, `data-store`: placeholder crates for future multi-source aggregation and persistence
- API contract files: `api-contracts/openapi.yaml` (REST) and `api-contracts/asyncapi.yaml` (WebSocket protocol)

#### Infrastructure
- `Dockerfile` for containerised deployment
- `.env.example` configuration template with `AISSTREAM_API_KEY`, `BIND_ADDR`, and `RUST_LOG`
- VS Code launch configurations for running, debugging, and integration tests

#### Testing
- Cucumber BDD integration tests (`integration-tests` crate)
- End-to-end WebSocket integration test (`ws_cucumber`) with real AISStream data processing and delivery

#### Python Client (`scripts/`)
- `seatrace_client` library: `RealtimeClient` (async WebSocket) and `SeaTraceClient` (HTTP)
- `client.py` CLI with `stream`, `health`, and `sources` sub-commands
- H3-cell filtering, verbose mode, and event count limit options
- Dependencies: `websockets`, `httpx`

#### Android SDK (`seatrace-sdk-android/`)
- Kotlin SDK with Coroutines/Flow-based streaming API
- `SeaTraceClient` with automatic reconnection and exponential backoff
- `SeaTraceConfig.Builder` for endpoint, token provider, timeouts, reconnect policy, and debug mode
- Subscription types: vessel positions (with bbox and MMSI filter), weather alerts, wildcard
- Connection state flow and error flow for lifecycle-aware UI
- Raw and parsed message listeners for debug inspection
- Standard Gradle wrapper scripts (`gradlew`, `gradlew.bat`)
- Sample application demonstrating SDK integration
- Consumer Proguard rules included in AAR

#### Documentation
- `README.md`: full project overview, API reference, Python client usage, Android SDK quick start
- `CLAUDE.md`: guide for AI code assistants with build commands, architecture, and crate descriptions

[Unreleased]: https://github.com/your-org/seatracesrv/compare/v0.0.1...HEAD
[0.0.1]: https://github.com/your-org/seatracesrv/releases/tag/v0.0.1
