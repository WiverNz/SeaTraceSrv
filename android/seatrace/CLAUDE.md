# CLAUDE.md — SeaTrace Android

Guidance for Claude Code when working in this Android project.

---

## What this app does

SeaTrace Android is a real-time maritime vessel tracking app. It connects to a backend
server (`seatracesrv`) via WebSocket, receives AIS vessel position events for the area
currently visible on screen, and renders them on a nautical map.

Key user-facing features:
- Nautical chart (OpenStreetMap base + OpenSeaMap overlay via MapLibre raster tiles)
- Live ship positions as blue circles, labelled with MMSI
- Layer toggles: show/hide nautical overlay, show/hide ships
- "Locate me" FAB — centres map on GPS position
- Connection status bar (shown when disconnected or reconnecting)
- "Not for navigation" disclaimer (legal requirement — must not be removed)

For full context on the backend this app connects to, see [`docs/server-api.md`](docs/server-api.md).

---

## Build commands

```bash
cd android/seatrace

./gradlew :app:assembleDebug          # compile debug APK
./gradlew :app:installDebug           # compile + install on connected device/emulator
./gradlew :app:assembleRelease        # release build (needs signing config)
./gradlew :app:lint                   # lint check
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:connectedAndroidTest   # instrumented tests (needs running emulator)
./gradlew dependencies                # inspect resolved dependency tree
```

See [`docs/development.md`](docs/development.md) for emulator setup and local server instructions.

---

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── assets/
│   └── style_nautical.json       ← MapLibre style: all sources + layers defined here
├── java/io/seatrace/android/
│   ├── MainActivity.kt           ← single activity; owns MapView lifecycle
│   ├── data/
│   │   ├── model/Ship.kt         ← immutable data class; key = mmsi
│   │   └── ws/
│   │       ├── WsProtocol.kt     ← @Serializable types for JSON parsing
│   │       └── SeaTraceWebSocket.kt  ← OkHttp WS; auto-reconnect; SharedFlow<Ship>
│   ├── map/
│   │   ├── MapViewModel.kt       ← SSOT for ships, layers, viewport→H3 conversion
│   │   └── ShipLayerManager.kt   ← updates GeoJSON source; visibility toggles
│   └── ui/
│       └── LayersBottomSheet.kt  ← Material bottom sheet; callbacks to ViewModel
└── res/
    ├── layout/activity_main.xml       ← CoordinatorLayout: MapView + FABs + status bar
    ├── layout/bottom_sheet_layers.xml ← two SwitchMaterial rows + disclaimer
    ├── drawable/ic_layers.xml
    ├── drawable/ic_my_location.xml
    └── values/{strings,colors,themes}.xml
```

---

## Architecture patterns used

### MVVM with StateFlow

`MapViewModel` is the single source of truth:
- `ships: StateFlow<Map<Long, Ship>>` — current live vessels (keyed by MMSI)
- `layers: StateFlow<LayerVisibility>` — which layers are toggled on
- `wsState: StateFlow<WsState>` — connection state for the status bar

`MainActivity` collects all three flows in `lifecycleScope` and updates the UI.
No LiveData — only Kotlin coroutines + StateFlow/SharedFlow throughout.

### Single activity

There is one `Activity` (`MainActivity`). Fragments are only used for the
`LayersBottomSheet` (which extends `BottomSheetDialogFragment`). Do not add more
Activities — extend via Fragments or Compose screens if new views are needed.

### MapLibre lifecycle

The `MapView` requires explicit lifecycle calls. They are all in `MainActivity`:
```kotlin
onStart/onResume/onPause/onStop/onLowMemory/onSaveInstanceState/onDestroy
```
Missing any of these causes memory leaks or crash. Do not use `MapView` inside
a Fragment without carefully forwarding lifecycle calls.

### GeoJSON source update pattern

Ship positions are in the `"ships"` GeoJSON source defined in `style_nautical.json`.
`ShipLayerManager.update()` replaces the source data — it does **not** reload the style:
```kotlin
(style.getSource("ships") as? GeoJsonSource)?.setGeoJson(featureCollection)
```
This must be called on the **main thread**. `MainActivity` collects `ships` in
`lifecycleScope` (main-thread by default), so this is safe.

### WebSocket lifecycle

`SeaTraceWebSocket` is owned by `MapViewModel`, which is owned by the Android
`ViewModelStore`. It connects in `init {}` and disconnects in `onCleared()`.
It uses `viewModelScope` for reconnect jobs — they cancel automatically when the
ViewModel is cleared (app destroyed or Activity finished).

---

## Key files and what to touch for common tasks

### Changing the server URL

`app/build.gradle.kts` → `defaultConfig` → `buildConfigField("String", "WS_BASE_URL", ...)`

For multiple environments (dev/staging/prod), add product flavours:
```kotlin
productFlavors {
    create("dev")  { buildConfigField("String", "WS_BASE_URL", "\"ws://10.0.2.2:8080\"") }
    create("prod") { buildConfigField("String", "WS_BASE_URL", "\"wss://api.seatrace.io\"") }
}
```

### Adding a new map layer

1. Add a source + layer entry to `assets/style_nautical.json`
2. Add a toggle field to `LayerVisibility` in `MapViewModel.kt`
3. Add a setter in `MapViewModel` (`setXxxVisible`)
4. Add a `SwitchMaterial` row to `res/layout/bottom_sheet_layers.xml`
5. Wire the switch in `LayersBottomSheet.kt`
6. Apply visibility in `MainActivity` `observeViewModel()` block

Full guide: [`docs/map-layers.md`](docs/map-layers.md)

### Adding a new field to ship display

1. Add the field to `Ship.kt`
2. Add the field as a GeoJSON feature property in `ShipLayerManager.update()`
3. Reference it in `style_nautical.json` via `["get", "field_name"]` expressions
4. For tap-to-show: update the `queryRenderedFeatures` handler in `MainActivity`

### Adding a new WebSocket message type

1. Add a `@Serializable` data class in `WsProtocol.kt`
2. Handle the new `type` string in `SeaTraceWebSocket.parseMessage()`
3. Expose a new `SharedFlow` or `StateFlow` from `SeaTraceWebSocket` if needed
4. Collect it in `MapViewModel`

### Changing reconnect behaviour

`SeaTraceWebSocket.kt` constants at the top of the file:
```kotlin
private const val INITIAL_RECONNECT_MS = 2_000L
private const val MAX_RECONNECT_MS = 60_000L
```
Back-off doubles on each failure: 2s → 4s → 8s → … → 60s cap.

### Changing ship stale timeout

`MapViewModel.kt`:
```kotlin
private const val SHIP_TTL_MS = 5 * 60 * 1_000L // 5 minutes
```

---

## MapLibre-specific gotchas

### All style mutations must be on the main thread

`style.getSource(...)`, `style.getLayer(...)`, `source.setGeoJson(...)`,
`layer.setProperties(...)` — all must run on the main thread.
In coroutines, use `withContext(Dispatchers.Main)` if you are on a background dispatcher.

### Style is not available immediately after `getMapAsync`

The style callback (`map.setStyle(...) { style -> ... }`) fires asynchronously.
Store the `Style` reference in `ShipLayerManager` only inside that callback.
Guard all style access with null checks.

### GeoJSON Feature properties

Feature properties set via `addStringProperty` / `addNumberProperty` are the ones
available in style expressions like `["get", "mmsi_label"]`. The field names must
match exactly between Kotlin and `style_nautical.json`.

### MapLibre package names

The main packages are:
```kotlin
import org.maplibre.android.maps.*        // MapView, MapLibreMap, Style, OnMapReadyCallback
import org.maplibre.android.geometry.*    // LatLng, LatLngBounds
import org.maplibre.android.location.*    // LocationComponent, LocationComponentActivationOptions
import org.maplibre.android.location.modes.* // CameraMode, RenderMode
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.geojson.*             // Feature, FeatureCollection, Point
import org.maplibre.android.camera.CameraUpdateFactory
```

### MapLibre initialisation

`MapLibre.getInstance(this)` must be called before `MapView.onCreate()`.
It is called in `MainActivity.onCreate()` before `setContentView`. Do not move it.

---

## H3-Java gotchas

### JNI initialisation

`H3Core.newInstance()` loads a native library. It can throw on unsupported ABIs.
In `MapViewModel`, it is called lazily and wrapped in try/catch so a failure
returns an empty cell list (no subscription) rather than crashing.

### ABI filters

Set in `app/build.gradle.kts` under `ndk { abiFilters }`.
Current: `arm64-v8a`, `armeabi-v7a`, `x86_64`.
Add `x86` if you need Google Play emulator images (x86 system images).

### Resolution must match server

The server indexes AIS events at H3 resolution 7. The constant in `MapViewModel.kt`:
```kotlin
private const val H3_RESOLUTION = 7
```
Changing this here without changing the server (Rust `h3o` calls in `connectors/`)
will result in no events being delivered to the app.

---

## Permissions

Runtime permission flow in `MainActivity`:
1. After map style loads, call `requestLocationPermission()`
2. If already granted → `enableLocationComponent()` directly
3. Otherwise → `locationPermissionRequest.launch(...)` (ActivityResult API)
4. On grant → `enableLocationComponent()`
5. On deny → show toast; map still works, location dot hidden

Location permission is **never** requested at app start — only after the map is ready.

---

## Legal requirements (do not remove)

1. Attribution string `© OpenStreetMap contributors · © OpenSeaMap contributors` must
   be permanently visible on the map. It is in `activity_main.xml` as a `TextView` over
   the map.

2. "Not for navigation" text must remain in the layers bottom sheet
   (`bottom_sheet_layers.xml`) and should be visible whenever a user interacts with
   layer settings.

These are required by the OpenStreetMap ODbL and OpenSeaMap CC BY-SA licenses.

---

## Dependency upgrade notes

When upgrading **MapLibre GL Android** (`org.maplibre.gl:android-sdk`):
- Check the [migration guide](https://maplibre.org/maplibre-gl-native/android/api/) for API breaks
- The `LocationComponent` API changed significantly between versions 9→10
- GeoJSON class package names (`org.maplibre.geojson.*`) stabilised in v10

When upgrading **H3-Java** (`com.uber.h3:h3`):
- Check if `polygonToCells` signature changed (it did between v3 and v4)
- Verify the new version ships `.so` files for all required ABIs
- Test on a physical device — emulator JNI loading can differ

When upgrading **OkHttp**:
- WebSocket API is stable; no expected breaks
- Verify `readTimeout(0, ...)` behaviour still disables the read timeout for WS

---

## What is NOT in this project

This is a pure client app. It does not contain:
- The AIS data ingestion pipeline → `seatracesrv/crates/connectors/`
- The WebSocket API server → `seatracesrv/crates/control-api/`
- The vessel catalog builder → `seatracesrv/workers/catalog-worker/`
- Any tile generation or serving logic
- Any database schemas

For the full backend, see the `seatracesrv` repository.
For the backend API contract, see [`docs/server-api.md`](docs/server-api.md).
