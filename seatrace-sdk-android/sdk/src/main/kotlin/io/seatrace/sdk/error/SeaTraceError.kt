package io.seatrace.sdk.error

/**
 * Sealed class hierarchy for all SeaTrace SDK errors.
 *
 * Use pattern matching to handle specific error types:
 * ```
 * when (error) {
 *     is SeaTraceError.AuthError -> handleAuth(error)
 *     is SeaTraceError.ConnectionError -> handleConnection(error)
 *     is SeaTraceError.ProtocolError -> handleProtocol(error)
 *     ...
 * }
 * ```
 */
sealed class SeaTraceError(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * Authentication/authorization errors.
     */
    sealed class AuthError(message: String, cause: Throwable? = null) : SeaTraceError(message, cause) {
        /** Token is invalid or expired */
        data class InvalidToken(val details: String? = null) :
            AuthError("Invalid or expired authentication token: ${details ?: "unknown reason"}")

        /** Token provider failed to provide a token */
        data class TokenProviderFailed(override val cause: Throwable) :
            AuthError("Token provider failed: ${cause.message}", cause)

        /** Server rejected authentication */
        data class Unauthorized(val serverMessage: String? = null) :
            AuthError("Authentication rejected by server: ${serverMessage ?: "unauthorized"}")

        /** Insufficient permissions for requested operation */
        data class Forbidden(val operation: String? = null) :
            AuthError("Insufficient permissions${operation?.let { " for: $it" } ?: ""}")
    }

    /**
     * Connection/transport errors.
     */
    sealed class ConnectionError(message: String, cause: Throwable? = null) : SeaTraceError(message, cause) {
        /** Failed to establish connection */
        data class ConnectionFailed(val endpoint: String, override val cause: Throwable? = null) :
            ConnectionError("Failed to connect to $endpoint", cause)

        /** Connection was lost */
        data class ConnectionLost(val reason: String? = null, override val cause: Throwable? = null) :
            ConnectionError("Connection lost: ${reason ?: "unknown reason"}", cause)

        /** Connection timed out */
        data class Timeout(val operation: String, val timeoutMs: Long) :
            ConnectionError("$operation timed out after ${timeoutMs}ms")

        /** DNS resolution failed */
        data class DnsError(val host: String, override val cause: Throwable? = null) :
            ConnectionError("Failed to resolve host: $host", cause)

        /** TLS/SSL error */
        data class TlsError(val details: String, override val cause: Throwable? = null) :
            ConnectionError("TLS error: $details", cause)

        /** Network unavailable */
        data object NetworkUnavailable :
            ConnectionError("Network is unavailable")
    }

    /**
     * Protocol/message format errors.
     */
    sealed class ProtocolError(message: String, cause: Throwable? = null) : SeaTraceError(message, cause) {
        /** Failed to parse message */
        data class ParseError(val rawMessage: String?, override val cause: Throwable? = null) :
            ProtocolError("Failed to parse message: ${cause?.message ?: "unknown error"}", cause)

        /** Unknown message type received */
        data class UnknownMessageType(val type: String) :
            ProtocolError("Unknown message type: $type")

        /** Protocol version mismatch */
        data class VersionMismatch(val clientVersion: String, val serverVersion: String) :
            ProtocolError("Protocol version mismatch: client=$clientVersion, server=$serverVersion")

        /** Invalid message format */
        data class InvalidFormat(val details: String) :
            ProtocolError("Invalid message format: $details")
    }

    /**
     * Server-side errors.
     */
    sealed class ServerError(message: String, cause: Throwable? = null) : SeaTraceError(message, cause) {
        /** Server returned an error response */
        data class ErrorResponse(val code: Int, val serverMessage: String?) :
            ServerError("Server error $code: ${serverMessage ?: "no details"}")

        /** Server is overloaded */
        data class Overloaded(val retryAfterMs: Long? = null) :
            ServerError("Server overloaded${retryAfterMs?.let { ", retry after ${it}ms" } ?: ""}")

        /** Server is undergoing maintenance */
        data class Maintenance(val expectedEndTime: Long? = null) :
            ServerError("Server maintenance in progress")

        /** Internal server error */
        data class Internal(val details: String? = null) :
            ServerError("Internal server error: ${details ?: "unknown"}")
    }

    /**
     * Subscription-related errors.
     */
    sealed class SubscriptionError(message: String, cause: Throwable? = null) : SeaTraceError(message, cause) {
        /** Subscription was rejected */
        data class Rejected(val subscriptionId: String, val reason: String?) :
            SubscriptionError("Subscription $subscriptionId rejected: ${reason ?: "unknown reason"}")

        /** Subscription not found */
        data class NotFound(val subscriptionId: String) :
            SubscriptionError("Subscription not found: $subscriptionId")

        /** Too many active subscriptions */
        data class LimitExceeded(val limit: Int) :
            SubscriptionError("Subscription limit exceeded: max $limit subscriptions allowed")
    }

    /**
     * Configuration errors.
     */
    sealed class ConfigError(message: String) : SeaTraceError(message) {
        /** Invalid endpoint URL */
        data class InvalidEndpoint(val endpoint: String) :
            ConfigError("Invalid endpoint URL: $endpoint")

        /** Missing required configuration */
        data class MissingConfig(val field: String) :
            ConfigError("Missing required configuration: $field")
    }
}
