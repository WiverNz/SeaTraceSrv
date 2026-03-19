package io.seatrace.android.map

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.uber.h3core.H3Core
import com.uber.h3core.util.LatLng
import io.seatrace.android.BuildConfig
import io.seatrace.android.data.model.Ship
import io.seatrace.android.data.ws.SeaTraceWebSocket
import io.seatrace.android.data.ws.WsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "MapViewModel"

/**
 * H3 resolution used for indexing on the server (h3o resolution 7).
 * The client must subscribe at the same resolution.
 */
private const val H3_RESOLUTION = 7

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

    // H3 is initialised lazily on a background thread the first time it is needed.
    private var h3: H3Core? = null

    init {
        val activeShips = java.util.concurrent.ConcurrentHashMap<Long, Ship>()

        // Receive ship updates from WebSocket and accumulate them to avoid GC thrashing.
        // The MapLibre map is only updated once per second instead of every single ship.
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
                    kotlinx.coroutines.delay(1000)
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
                val iterator = activeShips.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.value.lastSeen < cutoff) {
                        iterator.remove()
                        evicted = true
                    }
                }
                
                if (evicted) {
                    _ships.value = activeShips.toMap()
                }
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
     * Converts the bounding box to H3 cells and re-subscribes.
     */
    fun onViewportChanged(
        northLat: Double, southLat: Double,
        eastLon: Double,  westLon: Double,
    ) {
        viewModelScope.launch {
            val cells = computeH3Cells(northLat, southLat, eastLon, westLon)
            if (cells.isNotEmpty()) {
                Log.d(TAG, "viewport → ${cells.size} H3 cells")
                webSocket.updateCells(cells)
            }
        }
    }

    private fun computeH3Cells(
        northLat: Double, southLat: Double,
        eastLon: Double,  westLon: Double,
    ): List<Long> = try {
        val core = h3 ?: run {
            System.setProperty("h3.system.library", "true")
            H3Core.newInstance().also { h3 = it }
        }

        // Bounding box as a closed polygon (counter-clockwise for H3 polyfill).
        val ring = listOf(
            LatLng(southLat, westLon), // Bottom-Left
            LatLng(southLat, eastLon), // Bottom-Right
            LatLng(northLat, eastLon), // Top-Right
            LatLng(northLat, westLon), // Top-Left
            LatLng(southLat, westLon), // Bottom-Left
        )
        core.polygonToCells(ring, emptyList(), H3_RESOLUTION)
    } catch (e: Throwable) {
        Log.e(TAG, "H3 polyfill failed: ${e.message}")
        emptyList()
    }
}
