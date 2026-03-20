package io.seatrace.android.data.ws

import android.util.Log
import io.seatrace.android.data.model.Ship
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

private const val TAG = "SeaTraceWS"
private const val INITIAL_RECONNECT_MS = 2_000L
private const val MAX_RECONNECT_MS = 60_000L

sealed class WsState {
    data object Connecting : WsState()
    data object Connected : WsState()
    data class Failed(val reason: String) : WsState()
    data object Disconnected : WsState()
}

private val json = Json { ignoreUnknownKeys = true }

/**
 * Manages a persistent WebSocket connection to `<baseUrl>/realtime`.
 *
 * - Auto-reconnects with exponential back-off on failure.
 * - [updateViewport] sends a new viewport subscription; if not yet connected, the
 *   subscription is applied on the next successful open.
 * - Inject [scope] from the ViewModel's `viewModelScope` so cleanup is automatic.
 */
class SeaTraceWebSocket(
    private val baseUrl: String,
    private val scope: CoroutineScope,
) {

    private val _ships = MutableSharedFlow<Ship>(extraBufferCapacity = 256)
    /** Emit of every incoming vessel-position update. */
    val ships: SharedFlow<Ship> = _ships

    private val _state = MutableStateFlow<WsState>(WsState.Disconnected)
    /** Current connection state — safe to observe on the main thread. */
    val state: StateFlow<WsState> = _state

    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // keep-alive
        .build()

    private var socket: WebSocket? = null
    private var pendingViewport: Viewport? = null
    private var reconnectDelay = INITIAL_RECONNECT_MS
    private var reconnectJob: Job? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun connect() {
        reconnectJob?.cancel()
        doConnect()
    }

    fun disconnect() {
        reconnectJob?.cancel()
        socket?.close(1000, "app closed")
        socket = null
        _state.value = WsState.Disconnected
    }

    /**
     * Send a new viewport subscription to the server.
     * Replaces any previous subscription. Safe to call before connect().
     */
    fun updateViewport(viewport: Viewport) {
        pendingViewport = viewport
        socket?.let { sendSubscription(it, viewport) }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun doConnect() {
        _state.value = WsState.Connecting
        Log.i(TAG, "connecting to $baseUrl/realtime")

        val request = Request.Builder().url("$baseUrl/realtime").build()
        socket = httpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "connected")
                reconnectDelay = INITIAL_RECONNECT_MS
                _state.value = WsState.Connected
                pendingViewport?.let { sendSubscription(ws, it) }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                parseMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "closing code=$code reason=$reason")
                ws.close(1000, null)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "failure: ${t.message}")
                _state.value = WsState.Failed(t.message ?: "connection failed")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.i(TAG, "reconnecting in ${reconnectDelay}ms")
            delay(reconnectDelay)
            reconnectDelay = minOf(reconnectDelay * 2, MAX_RECONNECT_MS)
            doConnect()
        }
    }

    private fun sendSubscription(ws: WebSocket, viewport: Viewport) {
        val msg = json.encodeToString(ViewportMessage(viewport))
        ws.send(msg)
        Log.d(TAG, "sent viewport subscription N=${viewport.north} S=${viewport.south} E=${viewport.east} W=${viewport.west}")
    }

    private fun parseMessage(text: String) {
        try {
            Log.d(TAG, "raw msg (${text.length} chars): ${text.take(200)}")
            val envelope = json.decodeFromString<ServerEnvelope>(text)
            when (envelope.type) {
                "SubscribeAck" -> Log.d(TAG, "subscription ack")
                "Error"        -> Log.w(TAG, "server error: $text")
                null -> {
                    // Vessel event
                    val el = envelope.payload ?: run {
                        Log.w(TAG, "vessel event with null payload")
                        return
                    }
                    val payload = json.decodeFromJsonElement<VesselPositionPayload>(el)
                    if (payload.type != "VesselPosition") {
                        Log.w(TAG, "unknown payload type: ${payload.type}")
                        return
                    }

                    val ship = Ship(
                        mmsi     = payload.mmsi,
                        lat      = payload.lat,
                        lon      = payload.lon,
                        sog      = payload.sog,
                        cog      = payload.cog,
                        lastSeen = envelope.timestamp ?: System.currentTimeMillis(),
                    )
                    Log.d(TAG, "ship ${ship.mmsi} @ ${ship.lat},${ship.lon}")
                    scope.launch { _ships.emit(ship) }
                }
                else -> Log.w(TAG, "unknown envelope type: ${envelope.type}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "parse error: ${e.message}")
        }
    }
}
