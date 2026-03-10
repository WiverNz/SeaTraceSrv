# Map Layers Guide

Everything you need to know about the map rendering layer — how the current layers work,
and how to add, modify, or remove layers.

---

## How the map style works

MapLibre renders a map from a **style document**: a JSON file that declares sources
(where data comes from) and layers (how data is rendered). The style is loaded once at
startup. After that, individual sources and layer properties can be updated at runtime
without reloading.

In this app, the style is in `app/src/main/assets/style_nautical.json`.
It is loaded in `MainActivity.setupMap()`:
```kotlin
val styleJson = assets.open("style_nautical.json").bufferedReader().readText()
map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
    // style is ready — set up sources, layers, location component
}
```

---

## Current sources

```json
"sources": {
    "osm-raster": {
        "type": "raster",
        "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
        "tileSize": 256,
        "maxzoom": 19,
        "attribution": "..."
    },
    "openseamap-raster": {
        "type": "raster",
        "tiles": ["https://tiles.openseamap.org/seamark/{z}/{x}/{y}.png"],
        "tileSize": 256,
        "minzoom": 9,
        "maxzoom": 18,
        "attribution": "..."
    },
    "ships": {
        "type": "geojson",
        "data": { "type": "FeatureCollection", "features": [] }
    }
}
```

| Source ID | Type | Notes |
|-----------|------|-------|
| `osm-raster` | raster | OSM base map, always visible |
| `openseamap-raster` | raster | Nautical overlay; only renders from zoom 9 |
| `ships` | geojson | Live vessels; starts empty, updated by `ShipLayerManager` |

---

## Current layers

Layers are rendered in order — later entries are drawn on top.

| Layer ID | Type | Source | What it shows |
|----------|------|--------|---------------|
| `osm-base` | raster | `osm-raster` | Base land/water map |
| `openseamap-overlay` | raster | `openseamap-raster` | Seamarks, buoys, harbours |
| `ships-circles` | circle | `ships` | Blue circle per vessel |
| `ships-labels` | symbol | `ships` | MMSI number below each vessel |

---

## Updating the ships GeoJSON source at runtime

`ShipLayerManager.update()` replaces the GeoJSON data without touching the style:

```kotlin
val features = ships.values.map { ship ->
    Feature.fromGeometry(Point.fromLngLat(ship.lon, ship.lat)).also { f ->
        f.addStringProperty("mmsi_label", ship.mmsi.toString())
        f.addNumberProperty("mmsi", ship.mmsi)
        ship.sog?.let { f.addNumberProperty("sog", it) }
        ship.cog?.let { f.addNumberProperty("cog", it) }
    }
}
val fc = FeatureCollection.fromFeatures(features)
(style.getSource("ships") as? GeoJsonSource)?.setGeoJson(fc)
```

**Must be called on the main thread.**

Feature properties set here are accessible in `style_nautical.json` via MapLibre
data expressions: `["get", "mmsi_label"]`, `["get", "sog"]`, etc.

---

## Toggling layer visibility

```kotlin
style.getLayer("openseamap-overlay")?.setProperties(
    PropertyFactory.visibility("visible")   // or "none"
)
```

`ShipLayerManager.setLayersVisible(visible)` does this for both ship layers at once.
Visibility state is owned by `MapViewModel` (`LayerVisibility` data class) and applied
in `MainActivity.observeViewModel()`.

---

## How to add a new map layer

### Example: add a depth contour raster layer

**Step 1 — Add the source and layer to `style_nautical.json`**

```json
"sources": {
    ...,
    "depth-contours": {
        "type": "raster",
        "tiles": ["https://your-tile-server.com/depth/{z}/{x}/{y}.png"],
        "tileSize": 256,
        "minzoom": 8
    }
},
"layers": [
    ...,
    {
        "id": "depth-contours-layer",
        "type": "raster",
        "source": "depth-contours",
        "layout": { "visibility": "none" },
        "paint": { "raster-opacity": 0.6 }
    }
]
```

Place it in the layers array **before** the ships layers so ships render on top.

**Step 2 — Add a toggle to `MapViewModel`**

```kotlin
data class LayerVisibility(
    val nauticalOverlay: Boolean = true,
    val ships: Boolean = true,
    val depthContours: Boolean = false,   // ← add
)

fun setDepthContoursVisible(visible: Boolean) {
    _layers.value = _layers.value.copy(depthContours = visible)
}
```

**Step 3 — Apply the toggle in `MainActivity`**

```kotlin
lifecycleScope.launch {
    viewModel.layers.collect { vis ->
        mapLibreMap?.getStyle { style ->
            style.getLayer("depth-contours-layer")?.setProperties(
                PropertyFactory.visibility(if (vis.depthContours) "visible" else "none")
            )
            // ... existing toggles
        }
    }
}
```

**Step 4 — Add a row to `bottom_sheet_layers.xml`**

```xml
<LinearLayout android:layout_height="56dp" ...>
    <TextView android:text="@string/layer_depth_contours" ... />
    <SwitchMaterial android:id="@+id/switchDepthContours" ... />
</LinearLayout>
```

**Step 5 — Wire in `LayersBottomSheet`**

```kotlin
binding.switchDepthContours.isChecked = initial.depthContours
binding.switchDepthContours.setOnCheckedChangeListener { _, _ -> notify() }
```

Update the `notify` lambda to pass the new value to the callback.

**Step 6 — Add the string resource**

```xml
<string name="layer_depth_contours">Depth Contours</string>
```

---

## How to add a GeoJSON data layer (e.g. ports, anchorages)

If the data is fetched from an API rather than streamed via WebSocket:

**Step 1 — Add source and layer to `style_nautical.json`**

```json
"sources": {
    ...,
    "ports": {
        "type": "geojson",
        "data": { "type": "FeatureCollection", "features": [] }
    }
},
"layers": [
    ...,
    {
        "id": "ports-layer",
        "type": "circle",
        "source": "ports",
        "layout": { "visibility": "visible" },
        "paint": {
            "circle-radius": 8,
            "circle-color": "#FF6600",
            "circle-stroke-color": "#FFFFFF",
            "circle-stroke-width": 1.5
        }
    }
]
```

**Step 2 — Create a repository class to fetch port data**

```kotlin
class PortRepository(private val apiBaseUrl: String) {
    private val client = OkHttpClient()

    suspend fun fetchPortsInBounds(bbox: LatLngBounds): List<Port> =
        withContext(Dispatchers.IO) {
            // GET /v1/ports?bbox=...
        }
}
```

**Step 3 — Update the source in the ViewModel**

```kotlin
fun onViewportChanged(...) {
    viewModelScope.launch {
        val ports = portRepository.fetchPortsInBounds(bounds)
        _ports.value = ports
    }
}
```

**Step 4 — Update the GeoJSON source in `MainActivity`**

Similar to `ShipLayerManager` — convert `List<Port>` to `FeatureCollection` and call
`(style.getSource("ports") as? GeoJsonSource)?.setGeoJson(collection)`.

---

## Style expression reference (common patterns)

MapLibre style expressions are data-driven. Features get the property values from
the GeoJSON `properties` object.

```json
// Static value
"circle-radius": 6

// Value from feature property
"circle-radius": ["get", "size"]

// Conditional
"circle-color": [
    "case",
    ["==", ["get", "type"], "tanker"], "#FF0000",
    "#0066CC"
]

// Zoom-dependent
"circle-radius": [
    "interpolate", ["linear"], ["zoom"],
    8, 4,
    14, 10
]

// Rotate icon by feature property
"icon-rotate": ["get", "cog"]
```

Full reference: [MapLibre Style Spec expressions](https://maplibre.org/maplibre-style-spec/expressions/)

---

## Raster tile sources: attribution requirements

Any raster source must include an `attribution` field. MapLibre shows this in the
attribution control. **Do not omit attribution** — it is a legal requirement for
OpenStreetMap and OpenSeaMap data.

```json
"attribution": "© <a href='https://openstreetmap.org/copyright'>OpenStreetMap contributors</a>"
```

For custom tile servers built from OSM/OpenSeaMap data, the same attribution applies.
For GEBCO: `"© General Bathymetric Chart of the Oceans (GEBCO)"`.

---

## Performance considerations

- **GeoJSON updates** on every ship event: `setGeoJson()` on a large `FeatureCollection`
  triggers a diff on the MapLibre render thread. For >1000 ships, consider throttling
  updates to ~2 Hz rather than every incoming event.

- **Raster tile caching**: MapLibre caches tiles on-disk automatically. Adjust
  `MapLibre.getDefaultResourceOptions().withTileStoreUsageMode(...)` if you need
  offline support.

- **Layer count**: each layer adds render overhead. Keep layer count reasonable;
  prefer combining layers with data expressions over duplicating layers.

- **OpenSeaMap tiles at low zoom**: the `minzoom: 9` on the OpenSeaMap source prevents
  fetching tiles where they contain no useful information. Adjust only if needed.
