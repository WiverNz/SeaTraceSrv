package io.seatrace.android.map

import io.seatrace.android.data.model.Ship
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Manages the "ships" GeoJSON source that is already declared in `style_nautical.json`.
 *
 * Call [update] on the main thread (MapLibre style mutations must be on main).
 */
class ShipLayerManager(private val style: Style) {

    companion object {
        const val SOURCE_ID = "ships"
        const val LAYER_CIRCLES = "ships-circles"
        const val LAYER_LABELS  = "ships-labels"
    }

    /**
     * Replace the GeoJSON source data with the current ship positions.
     * Must be called on the main thread.
     */
    fun update(ships: Map<Long, Ship>) {
        val features = ships.values.map { ship ->
            Feature.fromGeometry(
                Point.fromLngLat(ship.lon, ship.lat),
            ).also { feature ->
                feature.addNumberProperty("mmsi", ship.mmsi)
                // Label: show MMSI as a string so the style text-field can read it.
                feature.addStringProperty("mmsi_label", ship.mmsi.toString())
                ship.sog?.let { feature.addNumberProperty("sog", it) }
                ship.cog?.let { feature.addNumberProperty("cog", it) }
            }
        }

        val collection = FeatureCollection.fromFeatures(features)
        (style.getSource(SOURCE_ID) as? GeoJsonSource)?.setGeoJson(collection)
    }

    fun setLayersVisible(visible: Boolean) {
        listOf(LAYER_CIRCLES, LAYER_LABELS).forEach { layerId ->
            style.getLayer(layerId)?.setProperties(
                org.maplibre.android.style.layers.PropertyFactory.visibility(
                    if (visible) "visible" else "none"
                )
            )
        }
    }
}
