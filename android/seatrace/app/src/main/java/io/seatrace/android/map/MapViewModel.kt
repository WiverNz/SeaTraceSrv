package io.seatrace.android.map

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.seatrace.android.BuildConfig
import io.seatrace.android.data.model.Ship
import io.seatrace.android.data.ws.SeaTraceWebSocket
import io.seatrace.android.data.ws.Viewport
import io.seatrace.android.data.ws.WsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "MapViewModel"

/**
 * Stale ship threshold: ships not updated within this window are removed from the map.
 */
private const val SHIP_TTL_MS = 5 * 60 * 1_000L // 5 minutes

data class LayerVisibility(
    val nauticalOverlay: Boolean = true,
    val ships: Boolean = true,
)

class MapViewModel(application: Application) : AndroidViewModel(application) {

    private val webSocket = SeaTraceWebSocket(BuildConfig.WS_BASE_URL, viewModelScope)

    /** Current live ship positions keyed by MMSI. */
    private val _ships = MutableStateFlow<Map<Long, Ship>>(emptyMap())
    val ships: StateFlow<Map<Long, Ship>> = _ships.asStateFlow()

    /** WebSocket connection state. */
    val wsState: StateFlow<WsState> = webSocket.state

    /** Layer visibility toggles. */
    private val _layers = MutableStateFlow(LayerVisibility())
    val layers: StateFlow<LayerVisibility> = _layers.asStateFlow()

    init {
        val activeShips = ConcurrentHashMap<Long, Ship>()

        // Accumulate ship updates and flush to the StateFlow once per second.
        viewModelScope.launch {
            var updated = false

            launch {
                webSocket.ships.collect { ship ->
                    activeShips[ship.mmsi] = ship
                    updated = true
                }
            }

            launch {
                while (true) {
                    kotlinx.coroutines.delay(1_000)
                    if (updated) {
                        _ships.value = activeShips.toMap()
                        updated = false
                    }
                }
            }
        }

        // Periodically evict stale ships.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000)
                val cutoff = System.currentTimeMillis() - SHIP_TTL_MS
                var evicted = false
                val it = activeShips.iterator()
                while (it.hasNext()) {
                    if (it.next().value.lastSeen < cutoff) { it.remove(); evicted = true }
                }
                if (evicted) _ships.value = activeShips.toMap()
            }
        }

        webSocket.connect()
    }

    override fun onCleared() {
        super.onCleared()
        webSocket.disconnect()
    }

    // ── Layer toggles ─────────────────────────────────────────────────────────

    fun setNauticalOverlayVisible(visible: Boolean) {
        _layers.value = _layers.value.copy(nauticalOverlay = visible)
    }

    fun setShipsVisible(visible: Boolean) {
        _layers.value = _layers.value.copy(ships = visible)
    }

    // ── Viewport subscription ─────────────────────────────────────────────────

    /**
     * Called whenever the visible map region changes.
     *
     * Computes the viewport diagonal using the Haversine formula. If it exceeds
     * [BuildConfig.MAX_VIEWPORT_KM] the subscription is skipped — the area is too
     * large to load ships meaningfully. Otherwise the bounding box is forwarded to
     * the server as a viewport subscription.
     */
    fun onViewportChanged(
        northLat: Double, southLat: Double,
        eastLon: Double,  westLon: Double,
    ) {
        val diagonalKm = haversineKm(southLat, westLon, northLat, eastLon)
        if (diagonalKm > BuildConfig.MAX_VIEWPORT_KM) {
            Log.d(TAG, "viewport ${diagonalKm.toInt()} km — too large, skipping ship load")
            return
        }
        Log.d(TAG, "viewport ${diagonalKm.toInt()} km — subscribing")
        webSocket.updateViewport(
            Viewport(north = northLat, south = southLat, east = eastLon, west = westLon)
        )
    }

    // ── Haversine ─────────────────────────────────────────────────────────────

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371.0
        val dLat = (lat2 - lat1).toRad()
        val dLon = (lon2 - lon1).toRad()
        val a = sin(dLat / 2).pow(2) +
                cos(lat1.toRad()) * cos(lat2.toRad()) * sin(dLon / 2).pow(2)
        return r * 2 * asin(sqrt(a))
    }

    private fun Double.toRad() = this * Math.PI / 180.0
}
