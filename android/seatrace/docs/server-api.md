# Backend Server API

This document describes the `seatracesrv` API that the SeaTrace Android app consumes.
It covers everything the app needs to implement a client — no access to the server source
code is required to understand this document.

---

## Overview

`seatracesrv` is a Rust server that:
1. Ingests real-time AIS vessel positions from [AISStream.io](https://aisstream.io)
2. Indexes each position into an H3 hexagonal spatial grid at **resolution 7**
3. Routes positions to WebSocket clients that have subscribed to matching H3 cells
4. Optionally enriches events with weather data and vessel metadata

Clients connect to `/realtime`, subscribe to a set of H3 cells covering their visible
map area, and receive a stream of vessel position events for those cells.

---

## Connection

```
ws://<host>:<port>/realtime
```

Default port: `8080`. For TLS (production): `wss://`.

The server has no authentication on the WebSocket endpoint in the current version.

### Other HTTP endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Service health check |
| GET | `/sources` | Active AIS data sources |
| POST | `/snapshot` | Fetch historical events for H3 cells (JSON body: `{"h3_cells":[...]}`) |

These are not used by the Android app currently but may be useful for diagnostics.

---

## WebSocket protocol

The protocol is text-frame JSON. There are two directions.

### Client → Server: subscription message

Send immediately after connection and again whenever the visible map region changes.
Each new message **replaces** the previous subscription — no unsubscribe step exists.

```json
{
  "h3_cells": [613196289491894271, 613196289492418559]
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `h3_cells` | `uint64[]` | Yes | H3 cell indices at resolution 7. Max practical size: ~500 cells for a typical mobile viewport. Empty array is valid (receives nothing). |

**Critical**: cells must be at H3 **resolution 7**. Sending cells at a different resolution
results in no events being delivered. The server indexes incoming AIS data at resolution 7
using the `h3o` Rust library.

### Server → Client: control messages

```json
{ "type": "SubscribeAck" }
```
Sent after the server processes a subscription message. The app can use this to confirm
the subscription was received. Currently the app only logs it.

```json
{ "type": "Error", "message": "..." }
```
Sent when the server encounters a protocol error. The app logs it and continues.

### Server → Client: vessel event

The main message type. Sent whenever a vessel position update arrives for a subscribed cell.

```json
{
  "event_id": "550e8400-e29b-41d4-a716-446655440000",
  "h3_index": 613196289491894271,
  "timestamp": 1741183200000,
  "source": "AISStream",
  "confidence": 1.0,
  "payload": {
    "type": "VesselPosition",
    "mmsi": 220625000,
    "lat": 55.683,
    "lon": 12.597,
    "sog": 8.2,
    "cog": 270.0
  }
}
```

| Field | Type | Notes |
|-------|------|-------|
| `event_id` | string (UUID) | Unique event identifier |
| `h3_index` | uint64 | H3 cell at resolution 7 |
| `timestamp` | int64 | Unix milliseconds |
| `source` | string | AIS data source name, e.g. `"AISStream"` |
| `confidence` | float | `0.0`–`1.0`, usually `1.0` |
| `payload.type` | string | Currently always `"VesselPosition"` |
| `payload.mmsi` | int64 | Maritime Mobile Service Identity (9 digits) |
| `payload.lat` | float64 | WGS-84 latitude, decimal degrees |
| `payload.lon` | float64 | WGS-84 longitude, decimal degrees |
| `payload.sog` | float64? | Speed over ground in knots. May be `null`. |
| `payload.cog` | float64? | Course over ground in degrees [0, 360). May be `null`. |

### How to distinguish message types

```
top-level "type" == "SubscribeAck" → control ack
top-level "type" == "Error"        → server error
no "type" field                    → vessel event (read "event_id" + "payload")
```

---

## H3 spatial indexing

### What is H3?

H3 is Uber's hierarchical hexagonal spatial index. The globe is divided into hexagonal
cells at multiple resolutions. Higher resolution = smaller cells.

At resolution 7, each cell covers approximately **5.16 km²**. This is the resolution used
by `seatracesrv` for both indexing events and routing subscriptions.

### Computing cells for a viewport bounding box (Android)

```kotlin
import com.uber.h3core.H3Core
import com.uber.h3core.util.LatLng

val h3 = H3Core.newInstance()

val ring = listOf(
    LatLng(southLat, westLon),
    LatLng(northLat, westLon),
    LatLng(northLat, eastLon),
    LatLng(southLat, eastLon),
    LatLng(southLat, westLon),   // close the ring
)
val cells: List<Long> = h3.polygonToCells(ring, emptyList(), 7)
```

`MapViewModel.computeH3Cells()` wraps this logic. It is called from
`onViewportChanged()` which is triggered by `MapLibreMap.addOnCameraIdleListener`.

### Expected cell counts

| Zoom level | Approx. viewport size | H3 res-7 cells |
|------------|----------------------|----------------|
| 6 | 2000 km × 2000 km | ~800,000 (too many — consider not subscribing at this zoom) |
| 9 | 500 km × 500 km | ~50,000 |
| 11 | 100 km × 100 km | ~2,000 |
| 13 | 25 km × 25 km | ~120 |
| 15 | 5 km × 5 km | ~5 |

For very low zoom levels it is worth throttling or skipping the subscription update.
**TODO**: add a minimum-zoom guard in `MapViewModel.onViewportChanged()` before computing cells.

---

## Level of Detail (LOD) — future

The server supports optional enrichment via LOD flags. The current Android app does not
request any LOD enrichments, but the subscription message format supports them:

```json
{
  "h3_cells": [...],
  "lod": ["weather_current"]
}
```

| LOD value | What it adds to each event |
|-----------|---------------------------|
| `weather_current` | `weather.current` — temperature, wind speed, humidity at vessel position |
| `weather_hourly` | `weather.hourly` — 24-hour forecast (implies `weather_current`) |
| *(planned)* `vessel_metadata` | Vessel name, flag, type from the Redis vessel catalog |
| *(planned)* `depth` | Bathymetric depth at position from GEBCO data |

When LOD data is present, the event JSON gains additional top-level fields:

```json
{
  "event_id": "...",
  "payload": { ... },
  "weather": {
    "current": {
      "time": "2026-03-07T14:00",
      "temperature_2m": 6.1,
      "wind_speed_10m": 18.4,
      "relative_humidity_2m": 78.0
    }
  }
}
```

To add LOD support to the Android app:
1. Update `WsProtocol.kt` `SubscribeMessage` to include a `lod` field
2. Add the LOD fields to `ServerEnvelope` and create data classes for each enrichment
3. Extend `Ship` (or create a separate `VesselDetail` class) with the enrichment data
4. Update `MapViewModel` to request and handle LOD in the subscription

---

## Vessel catalog (Redis)

The backend maintains a Redis-based vessel catalog that maps MMSI to vessel metadata
(name, flag, type, dimensions, etc.). Service pods look up the active catalog version
and query `vessel_catalog:version:{v}:mmsi:{mmsi}` hashes.

The catalog is built by a separate `catalog-worker` process. The Android app does not
access Redis directly — vessel metadata will be delivered via a future LOD enrichment
(see above) or a dedicated `/v1/vessel/{mmsi}` REST endpoint.

---

## Connection management

The server holds a WebSocket connection per client. There is no explicit session;
reconnecting creates a fresh subscription state. The client must re-send its
subscription message after every reconnect.

`SeaTraceWebSocket.kt` handles this: `pendingCells` is re-sent in `onOpen()`.

Server-side idle timeout: not configured by default. The server will keep the
connection open indefinitely as long as the client is connected.

---

## Running the server locally

To develop against a real server on the same machine:

```bash
# Prerequisites: Rust, AISStream API key, Redis
cd seatracesrv
cp .env.example .env          # add AISSTREAM_API_KEY
docker run -p 6379:6379 redis:7-alpine   # start Redis

# Start catalog worker (optional)
REDIS_URL=redis://localhost:6379 cargo run -p catalog-worker

# Start server
AISSTREAM_API_KEY=<key> REDIS_URL=redis://localhost:6379 cargo run
```

Server listens on `0.0.0.0:8080`. From an Android emulator, reach it at `ws://10.0.2.2:8080`.
From a physical device on the same Wi-Fi, use the host machine's LAN IP.
