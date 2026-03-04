package io.seatrace.sdk.connection

/**
 * Represents the current state of the client connection.
 */
sealed class ConnectionState {
    /** Not connected and not attempting to connect */
    data object Disconnected : ConnectionState()

    /** Attempting to establish connection */
    data class Connecting(val attempt: Int = 1) : ConnectionState()

    /** Connected and ready to send/receive messages */
    data object Connected : ConnectionState()

    /** Connection lost, attempting to reconnect */
    data class Reconnecting(
        val attempt: Int,
        val nextRetryMs: Long,
        val reason: String? = null
    ) : ConnectionState()

    /** Connection failed permanently */
    data class Failed(val error: Throwable) : ConnectionState()

    val isConnected: Boolean
        get() = this is Connected

    val isConnecting: Boolean
        get() = this is Connecting || this is Reconnecting
}

/**
 * Configuration for automatic reconnection behavior.
 */
data class ReconnectPolicy(
    /** Whether to automatically reconnect on connection loss */
    val enabled: Boolean = true,

    /** Initial delay before first reconnect attempt (ms) */
    val initialDelayMs: Long = 1_000,

    /** Maximum delay between reconnect attempts (ms) */
    val maxDelayMs: Long = 30_000,

    /** Multiplier for exponential backoff */
    val backoffMultiplier: Double = 2.0,

    /** Maximum jitter to add to delay (ms) */
    val jitterMs: Long = 1_000,

    /** Maximum number of reconnect attempts (null = unlimited) */
    val maxAttempts: Int? = null
) {
    companion object {
        /** Default reconnect policy with exponential backoff */
        val Default = ReconnectPolicy()

        /** No automatic reconnection */
        val Disabled = ReconnectPolicy(enabled = false)

        /** Aggressive reconnection for critical connections */
        val Aggressive = ReconnectPolicy(
            initialDelayMs = 500,
            maxDelayMs = 10_000,
            backoffMultiplier = 1.5
        )
    }

    /**
     * Calculate delay for a given attempt number.
     */
    fun calculateDelay(attempt: Int): Long {
        if (!enabled) return Long.MAX_VALUE

        val baseDelay = initialDelayMs * Math.pow(backoffMultiplier, (attempt - 1).toDouble())
        val cappedDelay = minOf(baseDelay.toLong(), maxDelayMs)
        val jitter = (Math.random() * jitterMs).toLong()

        return cappedDelay + jitter
    }

    /**
     * Check if another attempt should be made.
     */
    fun shouldRetry(attempt: Int): Boolean {
        if (!enabled) return false
        return maxAttempts == null || attempt < maxAttempts
    }
}
