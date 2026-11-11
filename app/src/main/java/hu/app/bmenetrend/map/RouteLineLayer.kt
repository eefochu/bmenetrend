package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.GeoPoint
import org.maplibre.android.constants.MapLibreConstants
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** The highlighted line or planned route: a casing under a coloured stroke, optionally dashed. */
internal object RouteLineLayer {
    private const val SOURCE = "selected-line"
    private const val CASING = "selected-line-casing"
    private const val STROKE = "selected-line-stroke"

    fun hide(style: Style) {
        listOf(CASING, STROKE).forEach { style.getLayer(it)?.setProperties(PropertyFactory.visibility("none")) }
    }

    /** CPU work; call off the main thread. */
    fun geoJson(paths: List<List<GeoPoint>>, ensureActive: () -> Unit): String =
        FeatureCollection.fromFeatures(paths.filter { it.size >= 2 }.map { path ->
            ensureActive()
            Feature.fromGeometry(LineString.fromLngLats(path.map { Point.fromLngLat(it.lon, it.lat) }))
        }).toJson()

    fun show(style: Style, json: String, color: Int, darkTheme: Boolean, dotted: Boolean) {
        val source = style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>(SOURCE)
        if (source != null) source.setGeoJson(json) else {
            style.addSource(org.maplibre.android.style.sources.GeoJsonSource(SOURCE, json))
            val round = arrayOf(PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round"))
            val casing = LineLayer(CASING, SOURCE).withProperties(*round)
            val stroke = LineLayer(STROKE, SOURCE).withProperties(*round)
            // Below every station layer (arrows are the lowest), above the basemap.
            val above = style.getLayer(StationMarkers.ARROW_LAYERS.first())?.id ?: style.getLayer(VehicleLayers.RAIL_DOTS)?.id
                ?: MapLibreConstants.LAYER_ID_ANNOTATIONS
            if (style.getLayer(above) != null) style.addLayerBelow(casing, above) else style.addLayer(casing)
            style.addLayerAbove(stroke, CASING)
        }
        // A solid dash array resets a previously dotted route.
        val dashes = PropertyFactory.lineDasharray(if (dotted) arrayOf(2f, 2f) else arrayOf(1f, 0f))
        style.getLayerAs<LineLayer>(CASING)?.setProperties(
            PropertyFactory.lineColor(if (darkTheme) "#17212D" else "#FFFFFF"),
            PropertyFactory.lineWidth(if (dotted) 6f else 8f), dashes, PropertyFactory.visibility("visible"))
        style.getLayerAs<LineLayer>(STROKE)?.setProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(if (dotted) 4f else 5f), dashes, PropertyFactory.visibility("visible"))
    }
}
