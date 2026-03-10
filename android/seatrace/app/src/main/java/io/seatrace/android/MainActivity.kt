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
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
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
                    ShipLayerManager.LAYER_CIRCLES,
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
            northLat = bounds.latNorth,
            southLat = bounds.latSouth,
            eastLon  = bounds.lonEast,
            westLon  = bounds.lonWest,
        )
    }

    // ── Location component ────────────────────────────────────────────────────

    private fun requestLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED -> enableLocationComponent()

            else -> locationPermissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun enableLocationComponent() {
        val map = mapLibreMap ?: return
        map.getStyle { style ->
            val lc = map.locationComponent
            lc.activateLocationComponent(
                LocationComponentActivationOptions.builder(this, style).build()
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
            val loc = lc.lastKnownLocation
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
