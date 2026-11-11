package hu.app.bmenetrend.map

import android.content.Context
import hu.app.bmenetrend.data.HevSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.constants.MapLibreConstants
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import java.util.Locale

/** Persistent track context only. Station and vehicle markers come from their normal sources. */
internal object MetroNetworkOverlay {
    const val ASSET = "map/budapest-metro.geojson"
    const val SOURCE = "budapest-metro"
    const val LINES_MIN_ZOOM = 8.5f
    val lines = setOf("M1","M2","M3","M4","H5","H6","H7","H8","H9")

    suspend fun load(context: Context): String {
        val text = withContext(Dispatchers.IO) { context.assets.open(ASSET).bufferedReader().use { it.readText() } }
        return withContext(Dispatchers.Default) { prepare(text) }
    }

    internal fun prepare(text: String): String {
        val features = FeatureCollection.fromJson(text).features().orEmpty().filter { feature ->
            feature.geometry() is LineString && feature.getStringProperty("line") in lines
        }
        features.forEach { feature ->
            val line = feature.getStringProperty("line")
            val color = if (line.startsWith("H")) HevSupport.color(line)
                else String.format(Locale.ROOT,"%06X",requireNotNull(MetroLineStyle.forLine(line,"SUBWAY")).color and 0xffffff)
            feature.addStringProperty("color","#$color")
        }
        return FeatureCollection.fromFeatures(features).toJson()
    }

    internal fun lineAnchor(layerIds: List<String>): String? {
        val foreground = setOf("selected-line-casing", "selected-line-stroke", "station-symbols", "station-selected",
            VehicleLayers.RAIL_DOTS, VehicleLayers.ROAD_DOTS, VehicleLayers.SYMBOLS, MapLibreConstants.LAYER_ID_ANNOTATIONS)
        return layerIds.firstOrNull { it in foreground }
    }

    fun install(style: Style, json: String, dark: Boolean) {
        if (style.getSource(SOURCE) == null) {
            style.addSource(GeoJsonSource(SOURCE,json))
            fun add(layer: LineLayer) {
                val anchor = lineAnchor(style.layers.map { it.id })
                if (anchor == null) style.addLayer(layer) else style.addLayerBelow(layer,anchor)
            }
            fun width(extra: Float) = Expression.interpolate(Expression.linear(), Expression.zoom(),
                Expression.stop(8.5,1.5f+extra),Expression.stop(10,3f+extra),
                Expression.stop(12,4f+extra),Expression.stop(16,5.5f+extra))
            add(LineLayer("metro-overview-casing",SOURCE).withProperties(
                PropertyFactory.lineWidth(width(2.5f)),PropertyFactory.lineCap("round"),PropertyFactory.lineJoin("round"))
                .apply { minZoom=LINES_MIN_ZOOM })
            add(LineLayer("metro-overview-lines",SOURCE).withProperties(
                PropertyFactory.lineColor(Expression.get("color")),PropertyFactory.lineWidth(width(0f)),
                PropertyFactory.lineCap("round"),PropertyFactory.lineJoin("round"))
                .apply { minZoom=LINES_MIN_ZOOM })
        }
        style.getLayerAs<LineLayer>("metro-overview-casing")?.setProperties(
            PropertyFactory.lineColor(if(dark) "#111D2C" else "#FFFFFF"))
    }
}
