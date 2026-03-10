# Development Guide

Practical instructions for setting up a development environment, running the app,
connecting to a real or local backend, and debugging common issues.

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| Android Studio | Meerkat (2024.3)+ | Earlier versions may not support AGP 8.7 |
| JDK | 17 | Must match `kotlinOptions.jvmTarget = "17"` in `app/build.gradle.kts` |
| Android SDK | API 35 (compile), API 24 (min) | Install via SDK Manager |
| NDK | Latest stable | Required for H3-Java native libs |
| Android emulator | x86_64 or arm64 image | Pixel 6 API 35 recommended |

**Rust + the backend server** — only needed if you want to run the full stack locally.
See [`docs/server-api.md`](server-api.md#running-the-server-locally).

---

## First-time setup

1. **Clone** and open `android/seatrace/` as a project in Android Studio
   (use *File → Open*, navigate into the `android/seatrace/` folder, not the parent).

2. **Sync Gradle** — happens automatically on first open. If it fails:
   - Check *File → Project Structure → SDK Location* points to a valid Android SDK
   - Run `./gradlew dependencies` from terminal to see the error in full

3. **Create an emulator** — *Device Manager → Create Virtual Device*
   - Recommended: Pixel 6, API 35, x86_64 system image
   - For arm64 host Macs: use arm64 system image

4. **Run** — select the `app` configuration, press ▶

The app launches and shows a map. It will show "Connecting to server…" in the status bar
until a real server is reachable at the configured URL.

---

## Connecting to a local backend server

The app is pre-configured to talk to `ws://10.0.2.2:8080`, which is the Android
emulator's loopback alias for the host machine's `localhost`.

### Start the server on the host

```bash
# From the seatracesrv workspace root (requires Rust + AISStream API key)
cp .env.example .env             # fill in AISSTREAM_API_KEY
docker run -d -p 6379:6379 redis:7-alpine
REDIS_URL=redis://localhost:6379 AISSTREAM_API_KEY=<key> cargo run
```

Server starts on `0.0.0.0:8080`. The emulator can reach it at `ws://10.0.2.2:8080`.

### Physical device

On a physical device the host's `localhost` is not reachable via `10.0.2.2`. Options:

**Option A** — same Wi-Fi network:
```kotlin
buildConfigField("String", "WS_BASE_URL", "\"ws://192.168.1.x:8080\"")
```
Replace with your host machine's LAN IP (`ipconfig` on Windows, `ifconfig` on macOS/Linux).

**Option B** — ADB port forwarding (USB cable or Wi-Fi ADB):
```bash
adb reverse tcp:8080 tcp:8080
```
After this, `ws://localhost:8080` and `ws://127.0.0.1:8080` on the device reach the host.
Update `WS_BASE_URL` accordingly.

### Changing the URL without editing source

For ad-hoc URL changes while developing, add a product flavour or a debug-only
override mechanism. Quick hack for local testing:

```kotlin
// app/build.gradle.kts
defaultConfig {
    buildConfigField("String", "WS_BASE_URL", "\"ws://10.0.2.2:8080\"")
}
```

Change the value, sync, rebuild, reinstall. The value is baked in at build time.

---

## Running without a backend

The app works without a live server — you just won't see any ships and the status bar
will show "Connecting to server…" indefinitely (auto-reconnect keeps retrying in the
background with exponential back-off: 2s → 4s → 8s → … → 60s).

The map renders from OSM and OpenSeaMap tile servers which are always reachable over
the internet as long as the device is online.

---

## Emulator tips

### Map tiles not loading

Make sure the emulator has internet access. In AVD settings, check network configuration.
If tile loading fails, check the logcat filter `MapLibre` or `OkHttp` for HTTP errors.

### Location in emulator

Use *Extended Controls → Location* in the emulator to set a fake GPS position.
After setting a location, tap the location FAB in the app — it should zoom to
the fake position.

### H3 on x86 emulator

H3-Java ships `.so` files for `x86_64` (covered by the ABI filter). If you use an older
`x86` (32-bit) emulator image and see `UnsatisfiedLinkError`, add `x86` to `abiFilters`:
```kotlin
abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
```

### Slow first build

The first Gradle sync downloads all dependencies (~300 MB including MapLibre native libs).
Subsequent builds use the local cache and are fast.

---

## Debugging

### WebSocket messages

Enable verbose WebSocket logging by setting the OkHttp log level. In `SeaTraceWebSocket.kt`,
add a `HttpLoggingInterceptor`:
```kotlin
val httpClient = OkHttpClient.Builder()
    .readTimeout(0, TimeUnit.MILLISECONDS)
    .addInterceptor(HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    })
    .build()
```
Add `com.squareup.okhttp3:logging-interceptor:4.12.0` to `app/build.gradle.kts` first.

Filter logcat to tag `SeaTraceWS` for connection events:
```
I SeaTraceWS: connecting to ws://10.0.2.2:8080/realtime
I SeaTraceWS: connected
D SeaTraceWS: sent subscription for 42 H3 cells
```

### Ships not appearing

1. Check `SeaTraceWS` logs — is it connected? Is a subscription being sent?
2. Check the server logs — is it receiving the subscription? Are there vessels in those cells?
3. Verify H3 resolution: log the `cells` list in `MapViewModel.computeH3Cells()` and
   validate one cell at [H3 Inspector](https://h3geo.org/#hex=8f283082a8d0d25).
4. Check `ShipLayerManager.update()` is called — add a log line temporarily.
5. Check that the `"ships"` source exists in the loaded style.

### Map not rendering

Filter logcat to `MapLibre`. Common issues:
- Style JSON syntax error → `MapLibre: Failed to load style`
- Bad tile URL → HTTP 4xx/5xx in logcat
- Attribution too long (unlikely)

Validate `style_nautical.json` with [Maputnik](https://maputnik.github.io/) (open source
MapLibre style editor) before debugging in the app.

### Location component crash

If `enableLocationComponent()` is called before the style is loaded or before the
permission is granted, it will throw. The current code guards against this:
- Called inside the style-loaded callback
- Called only after runtime permission is granted

If you restructure the flow, ensure these two conditions are both true before calling it.

### `UnsatisfiedLinkError` (H3)

H3-Java failed to load its native library. Causes:
- Device ABI not in `abiFilters` (fix: add the missing ABI)
- Running on an emulator with a system image not covered by the filters

The current code wraps `H3Core.newInstance()` in try/catch — on failure it returns an
empty cell list, so the app still runs, just without sending any subscription.

---

## Project-specific conventions

### StateFlow not LiveData

Use `MutableStateFlow` / `MutableSharedFlow` in ViewModels.
Collect in `lifecycleScope.launch { flow.collect { ... } }` in Activities/Fragments.
Do not introduce LiveData — the codebase is Flow-only.

### Coroutine dispatchers

- Network/IO work → `Dispatchers.IO` (OkHttp and H3 heavy work)
- Map mutations → main thread (no dispatcher change needed in `lifecycleScope`)
- ViewModel logic → `viewModelScope` default (main)

### Naming

- Layer IDs in `style_nautical.json` use kebab-case: `ships-circles`
- Source IDs use kebab-case: `openseamap-raster`
- Kotlin constants use SCREAMING_SNAKE_CASE: `H3_RESOLUTION`
- Feature property names use snake_case: `mmsi_label`, `sog`

---

## Running tests

```bash
# Unit tests (no device needed)
./gradlew :app:testDebugUnitTest

# Instrumented tests (emulator/device must be running)
./gradlew :app:connectedAndroidTest

# Lint
./gradlew :app:lint
# Report: app/build/reports/lint-results-debug.html
```

There are no unit tests yet. When adding tests:
- Unit-testable logic lives in `MapViewModel` and `SeaTraceWebSocket` — use `kotlinx-coroutines-test`
- MapLibre and Android APIs need instrumented tests or robolectric

---

## Common development tasks

### Updating a dependency version

Edit `app/build.gradle.kts` and resync. Check the changelog for the library for breaking
API changes. Key watch-outs:
- **MapLibre** major versions may rename or remove APIs (check migration guide)
- **H3-Java** v3→v4 changed `polygonToCells` signature
- **OkHttp** 4.x is stable; no expected breaks on minor upgrades

### Viewing the MapLibre style in a visual editor

1. Copy `assets/style_nautical.json`
2. Open [Maputnik](https://maputnik.github.io/) → *Open* → paste JSON
3. The raster layers won't render in Maputnik (tile server CORS), but structure is visible
4. Validate JSON syntax before putting it back

### Adding a new string resource

Add to `res/values/strings.xml`. The existing strings follow this convention:
- `layer_*` — layer names in the bottom sheet
- `status_*` — WebSocket status bar texts
- `fab_*_desc` — content descriptions for FABs

### Adding a new colour

Add to `res/values/colors.xml`. The colour palette uses `sea_blue` (#0066CC) as the
brand colour. FABs and circles use this colour. Status bar uses semi-transparent
`#CC333333`.

---

## Release checklist

Before publishing a release build:

- [ ] Set `WS_BASE_URL` to the production server URL (`wss://...`)
- [ ] Configure a signing config in `app/build.gradle.kts`
- [ ] Verify attribution text is visible on the map
- [ ] Verify "Not for navigation" text is in the layers panel
- [ ] Test on a physical device (not just emulator)
- [ ] Test with location permission denied — app should still show the map
- [ ] Test with server unreachable — app should show the status bar and reconnect
- [ ] Run lint: `./gradlew :app:lint`
- [ ] Bump `versionCode` and `versionName` in `app/build.gradle.kts`
