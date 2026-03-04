# SeaTrace Android SDK

Android SDK for SeaTraceSrv - Real-time maritime vessel tracking.

## Features

- Real-time vessel position tracking via WebSocket
- Automatic reconnection with exponential backoff
- Type-safe Kotlin models
- Coroutines and Flow-based streaming API
- Debug message inspection
- Subscription-based filtering

## Installation

### Gradle (Maven Central)

```kotlin
dependencies {
    implementation("io.seatrace:sdk:1.0.0")
}
```

### Gradle (GitHub Packages)

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/your-org/seatrace-sdk-android")
        credentials {
            username = project.findProperty("gpr.user") ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("io.seatrace:sdk:1.0.0")
}
```

## Quick Start

### Basic Usage

```kotlin
// Create client
val client = SeaTraceClient(
    endpoint = "wss://api.seatrace.example/realtime",
    tokenProvider = { getAuthToken() }  // Optional
)

// Connect
client.connect()

// Subscribe to vessel positions
val subscription = client.subscribeVessels(
    bbox = BBox(west = -10.0, south = 35.0, east = 30.0, north = 60.0),
    minConfidence = 0.7f
)

// Collect updates
lifecycleScope.launch {
    client.vesselsFlow.collect { update ->
        val vessel = update.position
        Log.d("SeaTrace", "Vessel ${vessel.mmsi} at ${vessel.lat}, ${vessel.lon}")
    }
}

// Don't forget to clean up
client.close()
```

### Configuration Options

```kotlin
val config = SeaTraceConfig.Builder()
    .endpoint("wss://api.seatrace.example/realtime")
    .tokenProvider { authToken }
    .connectTimeout(30.seconds)
    .pingInterval(30.seconds)
    .reconnectPolicy(ReconnectPolicy.Default)
    .maxQueueSize(1000)
    .debugMode(true)
    .logLevel(LogLevel.DEBUG)
    .build()

val client = SeaTraceClient(config)
```

### Subscription Types

```kotlin
// Subscribe to vessel positions
client.subscribeVessels(
    bbox = BBox(...),
    minConfidence = 0.7f,
    mmsiFilter = listOf(123456789L)
)

// Subscribe to weather alerts
client.subscribeWeather(
    bbox = BBox(...),
    severities = listOf("severe", "extreme")
)

// Subscribe to all events (wildcard)
client.subscribeAll()

// Unsubscribe
subscription.cancel()
// or
client.clearSubscriptions()
```

### Connection State

```kotlin
lifecycleScope.launch {
    client.connectionState.collect { state ->
        when (state) {
            is ConnectionState.Disconnected -> showDisconnected()
            is ConnectionState.Connecting -> showConnecting()
            is ConnectionState.Connected -> showConnected()
            is ConnectionState.Reconnecting -> showReconnecting(state.attempt)
            is ConnectionState.Failed -> showError(state.error)
        }
    }
}

// Simple connected check
if (client.isConnected.value) {
    // Do something
}
```

### Error Handling

```kotlin
lifecycleScope.launch {
    client.errorsFlow.collect { error ->
        when (error) {
            is SeaTraceError.AuthError.InvalidToken -> refreshToken()
            is SeaTraceError.ConnectionError.Timeout -> showTimeoutMessage()
            is SeaTraceError.ConnectionError.NetworkUnavailable -> showOfflineMessage()
            else -> Log.e("SeaTrace", "Error: ${error.message}")
        }
    }
}
```

### Debug Features

```kotlin
// Raw message inspection
client.setRawMessageListener { direction, message ->
    val prefix = if (direction == MessageDirection.INBOUND) "<<" else ">>"
    Log.d("SeaTrace", "$prefix $message")
}

// Parsed event inspection
client.setParsedEventListener { event ->
    Log.d("SeaTrace", "Event: ${event.eventId} - ${event.payload.type}")
}
```

## Data Models

### VesselPosition

```kotlin
data class VesselPosition(
    val mmsi: Long,          // Maritime Mobile Service Identity
    val lat: Double,         // Latitude
    val lon: Double,         // Longitude
    val sog: Float?,         // Speed Over Ground (knots)
    val cog: Float?          // Course Over Ground (degrees)
)
```

### Event

```kotlin
data class Event(
    val eventId: String,     // Unique event ID
    val h3Index: Long,       // H3 spatial index
    val timestamp: Long,     // Unix timestamp (ms)
    val source: String,      // Data source
    val confidence: Float,   // Confidence score (0-1)
    val payload: EventPayloadRaw
)
```

## Reconnection Policy

```kotlin
// Default policy
ReconnectPolicy.Default

// Disable auto-reconnect
ReconnectPolicy.Disabled

// Aggressive reconnection
ReconnectPolicy.Aggressive

// Custom policy
ReconnectPolicy(
    enabled = true,
    initialDelayMs = 1_000,
    maxDelayMs = 30_000,
    backoffMultiplier = 2.0,
    jitterMs = 1_000,
    maxAttempts = 10
)
```

## Proguard

The SDK includes consumer Proguard rules that are automatically applied.

## Code Generation

The SDK uses OpenAPI Generator to create models from `api-contracts/openapi.yaml`:

```bash
./gradlew :sdk:openApiGenerate
```

Generated code is placed in `sdk/build/generated/openapi/` and automatically included in the build.

## Testing

### Unit Tests

```bash
./gradlew :sdk:testDebugUnitTest
```

### Integration Tests

Integration tests connect to a real SeaTraceSrv instance:

```bash
# Requires SeaTraceSrv running and Android emulator/device connected
./gradlew :sdk:connectedAndroidTest
```

Test configuration (in `sdk/build.gradle.kts`):
- `TEST_ENDPOINT`: WebSocket endpoint (default: `ws://asgard.fritz.box:8080/realtime`)
- `TEST_HTTP_ENDPOINT`: HTTP endpoint (default: `http://asgard.fritz.box:8080`)

### VS Code Launch Tasks

- **Android SDK: Integration Tests** - Run instrumented tests on device/emulator
- **Android SDK: Build** - Build the SDK AAR
- **Android SDK: Unit Tests** - Run unit tests

## Building

```bash
# Build debug AAR
./gradlew :sdk:assembleDebug

# Build release AAR
./gradlew :sdk:assembleRelease

# Build and publish to local Maven
./gradlew :sdk:publishToMavenLocal
```

## Requirements

- Android API 24+
- Kotlin 1.9+
- Gradle 8.4+
- JDK 17+

## License

MIT License
