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
 * - [updateCells] sends a new H3 subscription; if not yet connected, the
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
    private var pendingCells: List<Long> = emptyList()
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
     * Subscribe to a new set of H3 cells (resolution 7).
     * Replaces any previous subscription. Safe to call before connect().
     */
    fun updateCells(cells: List<Long>) {
        pendingCells = cells
        socket?.let { sendSubscription(it, cells) }
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
                if (pendingCells.isNotEmpty()) sendSubscription(ws, pendingCells)
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

    private fun sendSubscription(ws: WebSocket, cells: List<Long>) {
        val msg = json.encodeToString(SubscribeMessage(cells))
        ws.send(msg)
        Log.d(TAG, "sent subscription for ${cells.size} cells")
    }

    private fun parseMessage(text: String) {
        try {
            val envelope = json.decodeFromString<ServerEnvelope>(text)
            when (envelope.type) {
                "SubscribeAck" -> Log.d(TAG, "subscription ack")
                "Error"        -> Log.w(TAG, "server error: $text")
                null -> {
                    // Vessel event
                    val el = envelope.payload ?: return
                    val payload = json.decodeFromJsonElement<VesselPositionPayload>(el)
                    if (payload.type != "VesselPosition") return

                    val ship = Ship(
                        mmsi     = payload.mmsi,
                        lat      = payload.lat,
                        lon      = payload.lon,
                        sog      = payload.sog,
                        cog      = payload.cog,
                        lastSeen = envelope.timestamp ?: System.currentTimeMillis(),
                    )
                    scope.launch { _ships.emit(ship) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "parse error: ${e.message}")
        }
    }
}
