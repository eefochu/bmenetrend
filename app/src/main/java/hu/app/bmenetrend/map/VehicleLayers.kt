package hu.app.bmenetrend.map

import org.maplibre.android.constants.MapLibreConstants
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.GeoJsonOptions

/** Native layers for [VehicleMapScene]: dots when zoomed out, icons close up. */
internal object VehicleLayers {
    const val RAIL_DOTS = "vehicle-dots-rail"
    const val ROAD_DOTS = "vehicle-dots-road"
    const val SYMBOLS = "vehicle-symbols"

    fun install(style: Style, json: String) {
        style.addSource(GeoJsonSource(VehicleMapScene.SOURCE, json, GeoJsonOptions().withMaxZoom(12)))
        fun layer(id: String, dot: Boolean) = SymbolLayer(id, VehicleMapScene.SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get(if (dot) "dot" else "icon")),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconRotationAlignment("viewport"), PropertyFactory.iconPitchAlignment("viewport"),
            PropertyFactory.symbolSortKey(Expression.get("priority")),
            PropertyFactory.iconSize(if (dot) Expression.get("dot-scale") else Expression.step(
                Expression.zoom(), Expression.get("small-scale"),
                Expression.stop(13.5, Expression.get("large-scale")))),
        )
        val rail = layer(RAIL_DOTS, true).withFilter(Expression.eq(Expression.get("rail"), Expression.literal(true)))
        val road = layer(ROAD_DOTS, true).withFilter(Expression.eq(Expression.get("rail"), Expression.literal(false)))
        listOf(rail, road, layer(SYMBOLS, false)).forEach { layer ->
            if (style.getLayer(MapLibreConstants.LAYER_ID_ANNOTATIONS) != null) style.addLayerBelow(layer, MapLibreConstants.LAYER_ID_ANNOTATIONS)
            else style.addLayer(layer)
        }
    }

    internal fun focusFilter(focus: LineMapFocus?): Expression {
        if (focus == null) return Expression.literal(true)
        val line = when {
            focus.routeId != null -> Expression.eq(Expression.get("route-id"), Expression.literal(focus.routeId))
            focus.number != null -> Expression.all(
                Expression.eq(Expression.get("route-number"), Expression.literal(focus.number)),
                Expression.eq(Expression.get("transport-mode"), Expression.literal(focus.mode.orEmpty())))
            else -> Expression.literal(false)
        }
        val providerLine = Expression.all(
            Expression.eq(Expression.get("provider-id"), Expression.literal(focus.providerId)), line)
        return focus.selectedVehicleId?.let { id -> Expression.any(
            Expression.eq(Expression.get("vehicle-id"), Expression.literal(id)), providerLine)
        } ?: providerLine
    }

    fun configure(style: Style, focus: LineMapFocus?) {
        val filter = focusFilter(focus)
        val dots = PropertyFactory.visibility("none")
        style.getLayerAs<SymbolLayer>(RAIL_DOTS)?.apply {
            minZoom = TransitMapVisibility.TRAIN_VEHICLE_MIN_ZOOM; maxZoom = TransitMapVisibility.VEHICLE_SYMBOL_MIN_ZOOM
            setProperties(dots)
            setFilter(Expression.all(Expression.eq(Expression.get("rail"), Expression.literal(true)), filter))
        }
        style.getLayerAs<SymbolLayer>(ROAD_DOTS)?.apply {
            minZoom = TransitMapVisibility.OTHER_VEHICLE_MIN_ZOOM; maxZoom = TransitMapVisibility.VEHICLE_SYMBOL_MIN_ZOOM
            setProperties(dots)
            setFilter(Expression.all(Expression.eq(Expression.get("rail"), Expression.literal(false)), filter))
        }
        style.getLayerAs<SymbolLayer>(SYMBOLS)?.apply {
            minZoom = TransitMapVisibility.VEHICLE_SYMBOL_MIN_ZOOM
            setFilter(filter)
        }
    }
}
