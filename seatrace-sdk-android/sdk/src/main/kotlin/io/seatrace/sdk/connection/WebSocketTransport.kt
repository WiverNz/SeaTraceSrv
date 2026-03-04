package io.seatrace.sdk.connection

import io.seatrace.sdk.SeaTraceConfig
import io.seatrace.sdk.debug.DefaultLogger
import io.seatrace.sdk.debug.LogLevel
import io.seatrace.sdk.debug.MessageDirection
import io.seatrace.sdk.debug.RawMessageListener
import io.seatrace.sdk.error.SeaTraceError
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * WebSocket transport using OkHttp.
 */
internal class WebSocketTransport(
    private val config: SeaTraceConfig,
    private val scope: CoroutineScope
) {
    private val tag = "SeaTraceWS"

    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    private val _errors = MutableSharedFlow<SeaTraceError>(extraBufferCapacity = 10)
    val errors: SharedFlow<SeaTraceError> = _errors.asSharedFlow()

    var rawMessageListener: RawMessageListener? = null

    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    /**
     * Connect to the WebSocket server.
     */
    suspend fun connect() {
        if (_connectionState.value.isConnected) {
            log(LogLevel.DEBUG, "Already connected")
            return
        }

        _connectionState.value = ConnectionState.Connecting()
        reconnectAttempt = 0

        try {
            establishConnection()
        } catch (e: Exception) {
            handleConnectionError(e)
        }
    }

    /**
     * Disconnect from the server.
     */
    fun disconnect() {
        reconnectJob?.cancel()
        reconnectJob = null

        webSocket?.close(1000, "Client disconnect")
        webSocket = null

        okHttpClient?.dispatcher?.executorService?.shutdown()
        okHttpClient = null

        _connectionState.value = ConnectionState.Disconnected
        log(LogLevel.INFO, "Disconnected")
    }

    /**
     * Send a message to the server.
     */
    fun send(message: String): Boolean {
        val ws = webSocket ?: return false

        rawMessageListener?.onRawMessage(MessageDirection.OUTBOUND, message)
        log(LogLevel.DEBUG, "Sending: $message")

        return ws.send(message)
    }

    private suspend fun establishConnection() {
        val client = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .pingInterval(config.pingInterval.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .build()

        okHttpClient = client

        val request = Request.Builder()
            .url(config.endpoint)
            .apply {
                // Add auth token if provider is set
                config.tokenProvider?.let { provider ->
                    try {
                        val token = provider()
                        header("Authorization", "Bearer $token")
                    } catch (e: Exception) {
                        throw SeaTraceError.AuthError.TokenProviderFailed(e)
                    }
                }
            }
            .build()

        log(LogLevel.INFO, "Connecting to ${config.endpoint}")

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                log(LogLevel.INFO, "Connected")
                _connectionState.value = ConnectionState.Connected
                reconnectAttempt = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                rawMessageListener?.onRawMessage(MessageDirection.INBOUND, text)
                log(LogLevel.DEBUG, "Received: ${text.take(200)}...")

                scope.launch {
                    _messages.send(text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                log(LogLevel.INFO, "Server closing connection: $code $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                log(LogLevel.INFO, "Connection closed: $code $reason")
                handleDisconnect(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log(LogLevel.ERROR, "Connection failure: ${t.message}", t)
                handleConnectionError(t)
            }
        }

        webSocket = client.newWebSocket(request, listener)
    }

    private fun handleDisconnect(reason: String?) {
        if (!config.reconnectPolicy.enabled) {
            _connectionState.value = ConnectionState.Disconnected
            return
        }

        scheduleReconnect(reason)
    }

    private fun handleConnectionError(error: Throwable) {
        val seaTraceError = when (error) {
            is SeaTraceError -> error
            is java.net.UnknownHostException -> SeaTraceError.ConnectionError.DnsError(
                config.endpoint, error
            )
            is java.net.SocketTimeoutException -> SeaTraceError.ConnectionError.Timeout(
                "connect", config.connectTimeout.inWholeMilliseconds
            )
            is javax.net.ssl.SSLException -> SeaTraceError.ConnectionError.TlsError(
                error.message ?: "Unknown TLS error", error
            )
            else -> SeaTraceError.ConnectionError.ConnectionFailed(
                config.endpoint, error
            )
        }

        scope.launch {
            _errors.emit(seaTraceError)
        }

        if (config.reconnectPolicy.enabled && config.reconnectPolicy.shouldRetry(reconnectAttempt + 1)) {
            scheduleReconnect(error.message)
        } else {
            _connectionState.value = ConnectionState.Failed(seaTraceError)
        }
    }

    private fun scheduleReconnect(reason: String?) {
        reconnectJob?.cancel()

        reconnectAttempt++
        val delay = config.reconnectPolicy.calculateDelay(reconnectAttempt)

        _connectionState.value = ConnectionState.Reconnecting(
            attempt = reconnectAttempt,
            nextRetryMs = delay,
            reason = reason
        )

        log(LogLevel.INFO, "Scheduling reconnect attempt $reconnectAttempt in ${delay}ms")

        reconnectJob = scope.launch {
            delay(delay)
            try {
                establishConnection()
            } catch (e: Exception) {
                handleConnectionError(e)
            }
        }
    }

    private fun log(level: LogLevel, message: String, throwable: Throwable? = null) {
        if (level.ordinal >= config.logLevel.ordinal) {
            DefaultLogger.log(level, tag, message, throwable)
        }
    }
}
