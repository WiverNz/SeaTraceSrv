# SeaTrace Android SDK

> **LLM / GPT INSTRUCTIONS**
> This SDK is built using modern Android principles (Coroutines, StateFlow, SharedFlow, Kotlin Serialization, OkHttp WebSockets). When writing code against this SDK, please follow the guidelines and examples below closely.

## Core Concepts

1. **State & Subscriptions are Dynamic**: Client connects once via `WebSocketTransport`. You can call `subscribeVessels`, `subscribeWeather`, `subscribeEvents`, `subscribeAnimals`, or `subscribeAll` *at any time, multiple times* without disconnecting. The server responds with `SubscribeAck` or `Error`, which the SDK handles transparently.
2. **Level of Detail (LOD)**: Data enrichment is determined by `Lod` enums (e.g., `Lod.WEATHER_CURRENT`, `Lod.WEATHER_HOURLY`, `Lod.VESSELS`). Pass these to your subscription to receive appended data on the `Event` object.
3. **Flow-Based Architecture**: Do NOT use callbacks. The SDK exposes `SharedFlow`s (`vesselsFlow`, `weatherFlow`, `eventsFlow`, `allEventsFlow`) that you should `collect` inside a coroutine.
4. **Data Models are Data Classes**: Incoming JSON is deserialized into an `Event` envelope. The payload property is an `EventPayloadRaw`. We use extension/helper methods (`toVesselPosition()`, `toWeatherAlert()`, etc.) to get the typed payload securely.

---

## 1. Setup and Initialization

```kotlin
import io.seatrace.sdk.SeaTraceClient
import io.seatrace.sdk.SeaTraceConfig
import io.seatrace.sdk.model.enrichment.Lod
import io.seatrace.sdk.subscription.BBox
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

// 1a. Basic Configuration
val client = SeaTraceClient(
    endpoint = "wss://api.seatrace.example/realtime"
)

// 1b. Advanced Configuration
val config = SeaTraceConfig.Builder()
    .endpoint("wss://api.seatrace.example/realtime")
    .tokenProvider { "my_auth_token" }
    .connectTimeout(30.seconds)
    .pingInterval(30.seconds)
    .build()

val advancedClient = SeaTraceClient(config)
```

## 2. Connection Lifecycle

Do not subscribe until you have called `connect()`. The client handles reconnects automatically based on its `ReconnectPolicy`.

```kotlin
lifecycleScope.launch {
    // Collect connection state to drive UI
    client.connectionState.collect { state ->
        when (state) {
            is ConnectionState.Connected -> Log.i("App", "Connected!")
            is ConnectionState.Disconnected -> Log.w("App", "Disconnected")
            is ConnectionState.Reconnecting -> Log.w("App", "Reconnecting...")
            is ConnectionState.Failed -> Log.e("App", "Error: ${state.error}")
            else -> {}
        }
    }
}

// Initiate connection
lifecycleScope.launch {
    client.connect()
}
```

## 3. Creating Dynamic Subscriptions & Requesting LODs

Subscriptions can overlap and be called multiple times.

```kotlin
// Example: Requesting vessel positions in a bounding box, WITH weather enrichment
val subHandle = client.subscribeVessels(
    bbox = BBox(west = -10.0, south = 35.0, east = 30.0, north = 60.0),
    lod = listOf(Lod.VESSELS, Lod.WEATHER_CURRENT) // <-- Requests server to attach Weather
)

// Later: Cancel the subscription
// subHandle.cancel()
// client.unsubscribe(subHandle)
```

## 4. Collecting Events using Flows

You should consume the typed flows (e.g., `vesselsFlow`, `weatherFlow`), which already unwrap the `EventPayloadRaw` into its respective strong type (`VesselUpdate`, `WeatherUpdate`).

```kotlin
lifecycleScope.launch {
    client.vesselsFlow.collect { update ->
        val event = update.event
        val position = update.position // VesselPosition

        Log.d("Fleet", "Vessel ${position.mmsi} is at ${position.lat}, ${position.lon}")

        // If we subscribed with Lod.WEATHER_CURRENT, this will be populated:
        val weather = event.weather?.current
        if (weather != null) {
            Log.d("Fleet", "Temp: ${weather.temperature_2m} C | Wind: ${weather.wind_speed_10m} km/h")
        }
    }
}
```

## 5. Raw Event Payload Handling

If consuming `client.allEventsFlow`, you must manually parse the payload. The raw format encapsulates everything safely:

```kotlin
lifecycleScope.launch {
    client.allEventsFlow.collect { event ->
        // Use the built-in map methods on EventPayloadRaw
        event.payload.toVesselPosition()?.let { pos ->
            // handle VesselPosition
        }
        
        event.payload.toIncident()?.let { incident ->
            // handle Incident
        }
    } // Server SubscribeAck and Error messages don't reach here; SDK filters them natively!
}
```

## 6. Cleanup

```kotlin
override fun onDestroy() {
    super.onDestroy()
    client.close() // Disconnects WS and cancels CoroutineScopes
}
```

---

## Architectural Notes for Code Generators
- The underlying `WebSocketTransport` handles ping-pong and exponential backoff (`reconnectPolicy`). Do not re-implement reconnect logic.
- Avoid using `java.time` directly inside `@Serializable` classes without `@get:android.annotation.SuppressLint("NewApi")` to prevent Android linting errors below API 26.
- The repository follows a standard Android library structure (`seatrace-sdk-android/sdk/src/main/kotlin/...`).
- To build the SDK, use: `./gradlew :sdk:build`.
