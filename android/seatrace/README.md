# SeaTrace Android

Native Android application for real-time maritime vessel tracking.

- **Map**: [MapLibre GL Native](https://maplibre.org/) with OpenStreetMap base tiles and
  [OpenSeaMap](https://www.openseamap.org/) nautical overlay
- **Live vessels**: AIS positions received over WebSocket from `seatracesrv`, rendered as
  a GeoJSON layer updated in real-time
- **Spatial subscriptions**: the visible map region is converted to H3 resolution-7 cells
  and sent to the server so only relevant vessels are streamed
- **Layer controls**: toggles for the nautical overlay and ship layer
- **Location button**: centres the map on the device's current GPS position

---

## Project structure

```
android/seatrace/
├── app/
│   ├── build.gradle.kts                  Dependencies, BuildConfig fields
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   └── style_nautical.json       MapLibre style (OSM + OpenSeaMap + ships GeoJSON)
│       ├── java/io/seatrace/android/
│       │   ├── MainActivity.kt           Map lifecycle, FABs, permission handling
│       │   ├── data/
│       │   │   ├── model/Ship.kt         Runtime vessel data class
│       │   │   └── ws/
│       │   │       ├── WsProtocol.kt     @Serializable message types
│       │   │       └── SeaTraceWebSocket.kt  OkHttp WS + auto-reconnect
│       │   ├── map/
│       │   │   ├── MapViewModel.kt       Ships StateFlow, H3 subscription, layer toggles
│       │   │   └── ShipLayerManager.kt   Updates GeoJSON source on the map
│       │   └── ui/
│       │       └── LayersBottomSheet.kt  Layer toggle bottom sheet
│       └── res/
│           ├── layout/{activity_main,bottom_sheet_layers}.xml
│           ├── drawable/{ic_layers,ic_my_location}.xml
│           └── values/{strings,colors,themes}.xml
├── build.gradle.kts                      Plugin declarations
├── settings.gradle.kts
├── gradle.properties
└── gradle/wrapper/gradle-wrapper.properties
```

---

## Building

### Prerequisites

- Android Studio Meerkat (2024.3) or later
- Android SDK 35, min SDK 24 (Android 7.0)
- Java 17

### From Android Studio

Open the `android/seatrace/` directory as a project. Android Studio will sync Gradle
automatically. Run the `app` configuration on a device or emulator.

### From the command line

```bash
cd android/seatrace

# Debug build
./gradlew :app:assembleDebug

# Install on a connected device/emulator
./gradlew :app:installDebug

# Release build (requires signing config)
./gradlew :app:assembleRelease
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

---

## Configuration

### WebSocket server URL

Set via `BuildConfig.WS_BASE_URL` in `app/build.gradle.kts`:

```kotlin
buildConfigField("String", "WS_BASE_URL", "\"ws://10.0.2.2:8080\"")
```

| Scenario | Value |
|----------|-------|
| Android emulator → host machine | `ws://10.0.2.2:8080` (default) |
| Physical device on same Wi-Fi | `ws://192.168.x.x:8080` |
| Production | `wss://your-domain.com` |

For production builds, use a product flavour or a separate `buildConfigField` override
rather than editing the default directly.

---

## Key dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| `org.maplibre.gl:android-sdk` | 11.7.0 | Map rendering, GeoJSON layer management |
| `com.uber.h3:h3` | 4.1.1 | H3 spatial indexing (JNI — see ABI note below) |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | WebSocket client |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.7.3 | JSON message parsing |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.8.7 | ViewModel + coroutine scope |
| `com.google.android.material:material` | 1.12.0 | FABs, bottom sheets, switches |

### H3-Java ABI note

`com.uber.h3:h3` ships native `.so` files via JNI. The `ndk.abiFilters` in
`app/build.gradle.kts` are set to `arm64-v8a`, `armeabi-v7a`, `x86_64`.
If you see `UnsatisfiedLinkError` at runtime, verify that your device/emulator ABI
is covered. Add `x86` for older emulators if needed.

---

## Architecture

### Data flow

```
MapViewModel
  │
  ├── SeaTraceWebSocket (OkHttp WS)
  │     └── parseMessage() → Ship → MutableSharedFlow<Ship>
  │
  ├── collect ships → _ships: MutableStateFlow<Map<Long, Ship>>
  │
  └── MapViewModel.onViewportChanged()
        └── H3Core.polygonToCells(bbox, resolution=7)
              └── webSocket.updateCells(cells) → {"h3_cells":[...]}

MainActivity
  ├── collect ships → ShipLayerManager.update()
  │     └── GeoJsonSource.setGeoJson(FeatureCollection)
  ├── collect layers → style layer visibility
  └── collect wsState → status bar
```

### Map style

`assets/style_nautical.json` is a MapLibre style JSON loaded at runtime. It defines:

| Source ID | Type | Content |
|-----------|------|---------|
| `osm-raster` | raster | OpenStreetMap base tiles |
| `openseamap-raster` | raster | OpenSeaMap nautical overlay (minzoom 9) |
| `ships` | geojson | Live ship positions (empty initially) |

Layers: `osm-base`, `openseamap-overlay`, `ships-circles`, `ships-labels`.

`ShipLayerManager` calls `GeoJsonSource.setGeoJson()` on the `"ships"` source whenever
the ships `StateFlow` emits. This is the only live update; no style reload is needed.

### WebSocket protocol

The server is `seatracesrv /realtime`. Same H3 resolution (7) as the server's indexing.

**Client → server** (subscription, sent on connect and on every viewport change):
```json
{ "h3_cells": [613196289491894271, 613196289491894272] }
```

**Server → client** (control messages):
```json
{ "type": "SubscribeAck" }
{ "type": "Error" }
```

**Server → client** (vessel event):
```json
{
  "event_id": "...",
  "h3_index": 613196289491894271,
  "timestamp": 1741183200000,
  "source": "AISStream",
  "confidence": 1.0,
  "payload": {
    "type": "VesselPosition",
    "mmsi": 220625000,
    "lat": 55.7,
    "lon": 12.5,
    "sog": 8.2,
    "cog": 270.0
  }
}
```

`SeaTraceWebSocket` auto-reconnects with exponential back-off (initial 2 s, max 60 s).
Sending a new `h3_cells` message replaces the entire subscription — no unsubscribe needed.

---

## Permissions

| Permission | Reason |
|------------|--------|
| `INTERNET` | WebSocket connection + tile downloads |
| `ACCESS_FINE_LOCATION` | Show device position on map (requested at runtime) |
| `ACCESS_COARSE_LOCATION` | Fallback location |

Location is entirely optional — the app works without it, just without the blue dot
and the "locate me" FAB function.

---

## Legal notices

The app shows a mandatory attribution string at the bottom of the map:

> © OpenStreetMap contributors · © OpenSeaMap contributors

OpenStreetMap data is licensed under the [ODbL](https://www.openstreetmap.org/copyright).
OpenSeaMap data is published under [CC BY-SA](https://www.openseamap.org/index.php?title=OpenSeaMap:Copyrights).

The layers panel includes a "Not for navigation" disclaimer, which must remain visible.
This app is for situational awareness only and must not be presented as an official
navigation tool.

---

## Roadmap / planned features

- [ ] Tap ship to show vessel metadata (name, flag, type) from the Redis vessel catalog
- [ ] Ship trail — show last N positions for a selected vessel
- [ ] Cluster markers at low zoom
- [ ] Offline tile caching for frequently visited areas
- [ ] Depth contour layer (from GEBCO data, served by catalog-worker)
- [ ] Weather overlay (temperature, wind) per vessel using LOD
- [ ] Push notifications for vessels entering/leaving a user-defined area
