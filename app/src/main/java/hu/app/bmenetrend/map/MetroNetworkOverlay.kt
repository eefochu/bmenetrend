package hu.app.bmenetrend.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.Style
import org.maplibre.android.constants.MapLibreConstants
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import java.util.Locale

/** Static map context, independent of live vehicles, offline pack installation and selected routes. */
internal object MetroNetworkOverlay {
    const val ASSET = "map/budapest-metro.geojson"
    const val SOURCE = "budapest-metro"
    const val STATIONS = "metro-overview-stations"
    val stationLayers = Array(10) { if (it == 0) STATIONS else "$STATIONS-$it" }
    const val LINES_MIN_ZOOM = 8.5f
    const val STATIONS_MIN_ZOOM = 10f
    const val PLATFORMS_MIN_ZOOM = 14f

    suspend fun load(context: Context): String {
        val text = withContext(Dispatchers.IO) { context.assets.open(ASSET).bufferedReader().use { it.readText() } }
        return withContext(Dispatchers.Default) { prepare(text) }
    }

    internal fun prepare(text: String): String {
        val collection = FeatureCollection.fromJson(text)
        collection.features().orEmpty().forEach { feature ->
            val color = requireNotNull(MetroLineStyle.forLine(feature.getStringProperty("line"), "SUBWAY")).color
            feature.addStringProperty("color", String.format(Locale.ROOT, "#%06X", color and 0xffffff))
        }
        return collection.toJson()
    }

    fun stopIds(feature: Feature): List<String> = feature.getProperty("stop-ids")?.asJsonArray
        ?.map { it.asString }.orEmpty()

    internal fun lineAnchor(layerIds: List<String>, selectedRoute: Boolean = false): String? {
        val foreground = setOf(if (selectedRoute) STATIONS else "selected-line-casing",
            "station-symbols", "station-selected", "vehicle-dots-rail", "vehicle-symbols",
            MapLibreConstants.LAYER_ID_ANNOTATIONS)
        return layerIds.firstOrNull { it in foreground }
    }

    fun install(style: Style, json: String, dark: Boolean, focused: Boolean) {
        if (style.getSource(SOURCE) == null) {
            style.addSource(GeoJsonSource(SOURCE, json))
            fun add(layer: org.maplibre.android.style.layers.Layer) {
                // Effects may create the route, station and vehicle layers in any order.
                // Use the lowest existing foreground layer, not a preferred layer ID.
                val anchor = lineAnchor(style.layers.map { it.id })
                if (anchor == null) style.addLayer(layer) else style.addLayerBelow(layer, anchor)
            }
            fun width(extra: Float) = Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(8.5, 1.5f + extra), Expression.stop(10, 3f + extra),
                Expression.stop(12, 4f + extra), Expression.stop(16, 5.5f + extra))
            val isLine = Expression.eq(Expression.get("kind"), Expression.literal("line"))
            add(LineLayer("metro-overview-casing", SOURCE).withFilter(isLine).withProperties(
                PropertyFactory.lineWidth(width(2.5f)), PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round"))
                .apply { minZoom = LINES_MIN_ZOOM })
            add(LineLayer("metro-overview-lines", SOURCE).withFilter(isLine).withProperties(
                PropertyFactory.lineColor(Expression.get("color")), PropertyFactory.lineWidth(width(0f)),
                PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round"))
                .apply { minZoom = LINES_MIN_ZOOM })
            var belowStations = style.getLayer("selected-line-stroke")?.id ?: "metro-overview-lines"
            // Every real platform keeps its own line color and tap target. Small screen
            // offsets expose both directions, with separate rows for interchange lines.
            stationLayers.forEachIndexed { slot, id ->
                val stations = CircleLayer(id, SOURCE).withFilter(Expression.all(
                    Expression.eq(Expression.get("kind"), Expression.literal("station")),
                    Expression.eq(Expression.get("slot"), Expression.literal(slot))))
                    .withProperties(PropertyFactory.circleColor("#FFFFFF"), PropertyFactory.circleStrokeColor(Expression.get("color")),
                        PropertyFactory.circleStrokeWidth(2.5f), PropertyFactory.circleRadius(Expression.interpolate(
                            Expression.linear(), Expression.zoom(), Expression.stop(10, 4.5f),
                            Expression.stop(12, 6.5f), Expression.stop(14, 8f))),
                        PropertyFactory.circleTranslate(arrayOf(if (slot % 2 == 0) -11f else 11f, (slot / 2 - 2) * 11f)),
                        PropertyFactory.circleTranslateAnchor("viewport"))
                    .apply { minZoom = STATIONS_MIN_ZOOM; maxZoom = PLATFORMS_MIN_ZOOM }
                style.addLayerAbove(stations, belowStations)
                belowStations = id
            }

        }
        style.getLayerAs<LineLayer>("metro-overview-casing")?.setProperties(PropertyFactory.lineColor(if (dark) "#111D2C" else "#FFFFFF"))
        stationLayers.forEach { id ->
            style.getLayerAs<CircleLayer>(id)?.setProperties(PropertyFactory.visibility(if (focused) "none" else "visible"))
        }

    }
}
