package io.seatrace.sdk

import io.seatrace.sdk.connection.ReconnectPolicy
import io.seatrace.sdk.debug.LogLevel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration for SeaTraceClient.
 *
 * Use the builder pattern:
 * ```
 * val config = SeaTraceConfig.Builder()
 *     .endpoint("wss://api.seatrace.example/realtime")
 *     .tokenProvider { getAuthToken() }
 *     .reconnectPolicy(ReconnectPolicy.Default)
 *     .build()
 * ```
 */
data class SeaTraceConfig(
    /** WebSocket endpoint URL */
    val endpoint: String,

    /** Token provider for authentication (called on connect and reconnect) */
    val tokenProvider: (suspend () -> String)? = null,

    /** Connection timeout */
    val connectTimeout: Duration = 30.seconds,

    /** Read timeout for WebSocket messages */
    val readTimeout: Duration = 60.seconds,

    /** Ping interval to keep connection alive */
    val pingInterval: Duration = 30.seconds,

    /** Reconnection policy */
    val reconnectPolicy: ReconnectPolicy = ReconnectPolicy.Default,

    /** Maximum message queue size (for backpressure) */
    val maxQueueSize: Int = 1000,

    /** Whether to drop old messages when queue is full */
    val dropOnOverflow: Boolean = true,

    /** Enable debug mode (verbose logging, message inspection) */
    val debugMode: Boolean = false,

    /** Log level */
    val logLevel: LogLevel = LogLevel.WARN,

    /** Enable message compression (if server supports it) */
    val compressionEnabled: Boolean = true,

    /** Maximum message size in bytes */
    val maxMessageSize: Long = 16 * 1024 * 1024 // 16 MB
) {
    init {
        require(endpoint.isNotBlank()) { "Endpoint must not be blank" }
        require(endpoint.startsWith("ws://") || endpoint.startsWith("wss://")) {
            "Endpoint must start with ws:// or wss://"
        }
        require(maxQueueSize > 0) { "Max queue size must be positive" }
        require(maxMessageSize > 0) { "Max message size must be positive" }
    }

    /**
     * Builder for SeaTraceConfig.
     */
    class Builder {
        private var endpoint: String = ""
        private var tokenProvider: (suspend () -> String)? = null
        private var connectTimeout: Duration = 30.seconds
        private var readTimeout: Duration = 60.seconds
        private var pingInterval: Duration = 30.seconds
        private var reconnectPolicy: ReconnectPolicy = ReconnectPolicy.Default
        private var maxQueueSize: Int = 1000
        private var dropOnOverflow: Boolean = true
        private var debugMode: Boolean = false
        private var logLevel: LogLevel = LogLevel.WARN
        private var compressionEnabled: Boolean = true
        private var maxMessageSize: Long = 16 * 1024 * 1024

        /** Set the WebSocket endpoint URL */
        fun endpoint(url: String) = apply { this.endpoint = url }

        /** Set the token provider for authentication */
        fun tokenProvider(provider: suspend () -> String) = apply { this.tokenProvider = provider }

        /** Set connection timeout */
        fun connectTimeout(duration: Duration) = apply { this.connectTimeout = duration }

        /** Set read timeout */
        fun readTimeout(duration: Duration) = apply { this.readTimeout = duration }

        /** Set ping interval */
        fun pingInterval(duration: Duration) = apply { this.pingInterval = duration }

        /** Set reconnection policy */
        fun reconnectPolicy(policy: ReconnectPolicy) = apply { this.reconnectPolicy = policy }

        /** Set maximum message queue size */
        fun maxQueueSize(size: Int) = apply { this.maxQueueSize = size }

        /** Set whether to drop messages on overflow */
        fun dropOnOverflow(drop: Boolean) = apply { this.dropOnOverflow = drop }

        /** Enable or disable debug mode */
        fun debugMode(enabled: Boolean) = apply { this.debugMode = enabled }

        /** Set log level */
        fun logLevel(level: LogLevel) = apply { this.logLevel = level }

        /** Enable or disable compression */
        fun compressionEnabled(enabled: Boolean) = apply { this.compressionEnabled = enabled }

        /** Set maximum message size */
        fun maxMessageSize(bytes: Long) = apply { this.maxMessageSize = bytes }

        /** Build the configuration */
        fun build(): SeaTraceConfig {
            require(endpoint.isNotBlank()) { "Endpoint must be set" }
            return SeaTraceConfig(
                endpoint = endpoint,
                tokenProvider = tokenProvider,
                connectTimeout = connectTimeout,
                readTimeout = readTimeout,
                pingInterval = pingInterval,
                reconnectPolicy = reconnectPolicy,
                maxQueueSize = maxQueueSize,
                dropOnOverflow = dropOnOverflow,
                debugMode = debugMode,
                logLevel = logLevel,
                compressionEnabled = compressionEnabled,
                maxMessageSize = maxMessageSize
            )
        }
    }

    companion object {
        /**
         * Create a simple configuration with just an endpoint.
         */
        fun simple(endpoint: String) = SeaTraceConfig(endpoint = endpoint)

        /**
         * Create a configuration with endpoint and token provider.
         */
        fun withAuth(endpoint: String, tokenProvider: suspend () -> String) = SeaTraceConfig(
            endpoint = endpoint,
            tokenProvider = tokenProvider
        )
    }
}
