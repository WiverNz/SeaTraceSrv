package io.seatrace.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.seatrace.android.data.ws.WsState
import io.seatrace.android.databinding.ActivityMainBinding
import io.seatrace.android.map.MapViewModel
import io.seatrace.android.map.ShipLayerManager
import io.seatrace.android.ui.LayersBottomSheet
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import android.app.PendingIntent
import android.os.Looper
import org.maplibre.android.location.engine.LocationEngine
import org.maplibre.android.location.engine.LocationEngineCallback
import org.maplibre.android.location.engine.LocationEngineDefault
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.engine.LocationEngineResult
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MapViewModel by viewModels()

    private var mapLibreMap: MapLibreMap? = null
    private var shipLayer: ShipLayerManager? = null

    // ── Permission request ────────────────────────────────────────────────────

    private val locationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false) ||
                          result.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false)
            if (granted) enableLocationComponent()
            else Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_SHORT).show()
        }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupMap(savedInstanceState)
        setupFabs()
        observeViewModel()
    }

    override fun onStart()   { super.onStart();   binding.mapView.onStart() }
    override fun onResume()  { super.onResume();  binding.mapView.onResume() }
    override fun onPause()   { super.onPause();   binding.mapView.onPause() }
    override fun onStop()    { super.onStop();    binding.mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.mapView.onDestroy()
    }

    // ── Map setup ─────────────────────────────────────────────────────────────

    private fun setupMap(savedInstanceState: Bundle?) {
        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync { map ->
            mapLibreMap = map

            val styleJson = assets.open("style_nautical.json").bufferedReader().readText()
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                shipLayer = ShipLayerManager(style)
                requestLocationPermission()
                map.addOnCameraIdleListener { notifyViewport(map) }
                // Trigger an initial subscription for the default camera position.
                notifyViewport(map)
            }

            // Tap a ship circle to show a toast with its MMSI.
            map.addOnMapClickListener { latLng ->
                val screenPoint = map.projection.toScreenLocation(latLng)
                val features = map.queryRenderedFeatures(
                    screenPoint,
                    ShipLayerManager.LAYER_ARROWS,
                )
                if (features.isNotEmpty()) {
                    val mmsi = features.firstOrNull()?.getNumberProperty("mmsi")
                    Toast.makeText(this, "MMSI: $mmsi", Toast.LENGTH_SHORT).show()
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun notifyViewport(map: MapLibreMap) {
        val bounds = map.projection.visibleRegion.latLngBounds
        viewModel.onViewportChanged(
            northLat = bounds.getLatNorth(),
            southLat = bounds.getLatSouth(),
            eastLon  = bounds.getLonEast(),
            westLon  = bounds.getLonWest(),
        )
    }

    // ── Location component ────────────────────────────────────────────────────

    private fun requestLocationPermission() {
        val fineGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            enableLocationComponent()
        } else {
            locationPermissionRequest.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun enableLocationComponent() {
        val map = mapLibreMap ?: return
        map.getStyle { style ->
            val lc = map.locationComponent

            // Wrap the default LocationEngine to suppress "Last location unavailable" exceptions
            val defaultEngine = LocationEngineDefault.getDefaultLocationEngine(this)
            val safeEngine = SafeLocationEngine(defaultEngine)

            // Configure the location engine request to ensure updates start properly.
            val request = LocationEngineRequest.Builder(1000L)
                .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
                .setMaxWaitTime(5000L)
                .build()

            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(this, style)
                    .locationEngine(safeEngine)
                    .locationEngineRequest(request)
                    .build()
            )
            lc.isLocationComponentEnabled = true
            lc.cameraMode  = CameraMode.NONE
            lc.renderMode  = RenderMode.COMPASS
        }
    }

    // ── FAB actions ───────────────────────────────────────────────────────────

    private fun setupFabs() {
        // Zoom to current location.
        binding.fabLocation.setOnClickListener {
            val lc = mapLibreMap?.locationComponent ?: return@setOnClickListener
            // Check if activated before accessing lastKnownLocation to avoid internal crashes
            val loc = if (lc.isLocationComponentActivated) lc.lastKnownLocation else null
            
            if (loc != null) {
                mapLibreMap?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 12.0)
                )
            } else {
                Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_SHORT).show()
            }
        }

        // Open layers panel.
        binding.fabLayers.setOnClickListener {
            LayersBottomSheet(
                initial = viewModel.layers.value,
                onLayerChanged = { nautical, ships ->
                    viewModel.setNauticalOverlayVisible(nautical)
                    viewModel.setShipsVisible(ships)
                },
            ).show(supportFragmentManager, LayersBottomSheet.TAG)
        }
    }

    // ── ViewModel observation ─────────────────────────────────────────────────

    private fun observeViewModel() {
        // Apply ship updates to the map layer.
        lifecycleScope.launch {
            viewModel.ships.collect { ships ->
                shipLayer?.update(ships)
            }
        }

        // Toggle layer visibility when user changes them in the bottom sheet.
        lifecycleScope.launch {
            viewModel.layers.collect { vis ->
                mapLibreMap?.getStyle { style ->
                    // Nautical overlay
                    style.getLayer("openseamap-overlay")?.setProperties(
                        PropertyFactory.visibility(if (vis.nauticalOverlay) "visible" else "none")
                    )
                    // Ships
                    shipLayer?.setLayersVisible(vis.ships)
                }
            }
        }

        // Show WebSocket connection state.
        lifecycleScope.launch {
            viewModel.wsState.collect { state ->
                binding.statusBar.visibility = when (state) {
                    is WsState.Connected    -> View.GONE
                    is WsState.Disconnected -> View.VISIBLE
                    is WsState.Connecting   -> View.VISIBLE
                    is WsState.Failed       -> View.VISIBLE
                }
                binding.statusText.text = when (state) {
                    is WsState.Connected    -> ""
                    is WsState.Disconnected -> getString(R.string.status_disconnected)
                    is WsState.Connecting   -> getString(R.string.status_connecting)
                    is WsState.Failed       -> getString(R.string.status_error, state.reason)
                }
            }
        }
    }
}

/**
 * A wrapper around MapLibre's LocationEngine that suppresses the "Last location unavailable" exception
 * which is frequently thrown and logged as an error on app startup.
 */
class SafeLocationEngine(private val delegate: LocationEngine) : LocationEngine {
    override fun getLastLocation(callback: LocationEngineCallback<LocationEngineResult>) {
        delegate.getLastLocation(object : LocationEngineCallback<LocationEngineResult> {
            override fun onSuccess(result: LocationEngineResult?) {
                callback.onSuccess(result)
            }

            override fun onFailure(exception: Exception) {
                if (exception.message?.contains("Last location unavailable") == true) {
                    // Suppress known benign exception.
                    // Returning null prevents the LocationComponent from logging a stacktrace.
                    callback.onSuccess(null)
                } else {
                    callback.onFailure(exception)
                }
            }
        })
    }

    override fun requestLocationUpdates(
        request: LocationEngineRequest,
        callback: LocationEngineCallback<LocationEngineResult>,
        looper: Looper?
    ) {
        delegate.requestLocationUpdates(request, callback, looper)
    }

    override fun requestLocationUpdates(
        request: LocationEngineRequest,
        pendingIntent: PendingIntent
    ) {
        delegate.requestLocationUpdates(request, pendingIntent)
    }

    override fun removeLocationUpdates(callback: LocationEngineCallback<LocationEngineResult>) {
        delegate.removeLocationUpdates(callback)
    }

    override fun removeLocationUpdates(pendingIntent: PendingIntent) {
        delegate.removeLocationUpdates(pendingIntent)
    }
}
