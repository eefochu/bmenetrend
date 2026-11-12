package hu.app.bmenetrend.map

import org.maplibre.android.constants.MapLibreConstants
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.GeoJsonOptions

/** Native vehicle icons use a provider-scoped line filter independently of source uploads. */
internal object VehicleLayers {
    const val RAIL_DOTS = "vehicle-dots-rail"
    const val ROAD_DOTS = "vehicle-dots-road"
    const val SYMBOLS = "vehicle-symbols"
    const val ARROWS = "vehicle-direction-arrows"
    /** Trains below [TransitMapVisibility.VEHICLE_MIN_ZOOM]: the same icon and arrow, smaller. */
    const val RAIL_ARROWS = "vehicle-direction-arrows-rail"

    fun install(style: Style, json: String) {
        style.addSource(GeoJsonSource(VehicleMapScene.SOURCE, json, GeoJsonOptions().withMaxZoom(12)))
        fun layer(id: String, dot: Boolean) = SymbolLayer(id, VehicleMapScene.SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get(if (dot) "dot" else "icon")),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconRotationAlignment("viewport"), PropertyFactory.iconPitchAlignment("viewport"),
            PropertyFactory.symbolSortKey(Expression.get("priority")),
            PropertyFactory.iconSize(Expression.get(if (dot) "dot-scale" else "large-scale")),
        )
        val rail = layer(RAIL_DOTS, true).withFilter(Expression.eq(Expression.get("rail"), Expression.literal(true)))
        val road = layer(ROAD_DOTS, true).withFilter(Expression.eq(Expression.get("rail"), Expression.literal(false)))
        fun arrows(id: String, scale: Expression) = SymbolLayer(id, VehicleMapScene.SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("arrow")), PropertyFactory.iconRotate(Expression.get("heading")),
            PropertyFactory.iconSize(scale),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconRotationAlignment("map"), PropertyFactory.iconPitchAlignment("viewport"),
            PropertyFactory.symbolSortKey(Expression.get("priority")),
        ).withFilter(Expression.has("arrow"))
        val overview = Expression.literal(VehicleMarkerLayout.OVERVIEW_FACTOR)
        rail.setProperties(PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconSize(Expression.product(Expression.get("large-scale"), overview)))
        val railArrows = arrows(RAIL_ARROWS, Expression.product(Expression.get("arrow-scale"), overview))
        listOf(rail, railArrows, road, arrows(ARROWS, Expression.get("arrow-scale")), layer(SYMBOLS, false)).forEach { layer ->
            val below = UserLocationLayer.below(style)
                ?: MapLibreConstants.LAYER_ID_ANNOTATIONS.takeIf { style.getLayer(it) != null }
            if (below != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
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
        // A highlighted line shows every vehicle at full size from any zoom, so the overview trains step aside.
        val overview = PropertyFactory.visibility(if (focus == null) "visible" else "none")
        val railFilter = Expression.all(Expression.eq(Expression.get("rail"), Expression.literal(true)), filter)
        style.getLayerAs<SymbolLayer>(RAIL_DOTS)?.apply {
            minZoom = TransitMapVisibility.TRAIN_VEHICLE_MIN_ZOOM; maxZoom = TransitMapVisibility.VEHICLE_SYMBOL_MIN_ZOOM
            setProperties(overview)
            setFilter(railFilter)
        }
        style.getLayerAs<SymbolLayer>(RAIL_ARROWS)?.apply {
            minZoom = TransitMapVisibility.TRAIN_VEHICLE_MIN_ZOOM; maxZoom = TransitMapVisibility.VEHICLE_SYMBOL_MIN_ZOOM
            setProperties(overview)
            setFilter(Expression.all(Expression.has("arrow"), railFilter))
        }
        style.getLayerAs<SymbolLayer>(ROAD_DOTS)?.apply {
            minZoom = TransitMapVisibility.OTHER_VEHICLE_MIN_ZOOM; maxZoom = TransitMapVisibility.VEHICLE_SYMBOL_MIN_ZOOM
            setProperties(dots)
            setFilter(Expression.all(Expression.eq(Expression.get("rail"), Expression.literal(false)), filter))
        }
        style.getLayerAs<SymbolLayer>(SYMBOLS)?.apply {
            minZoom = TransitMapVisibility.vehicleMinZoom(focus != null)
            setFilter(filter)
        }
        style.getLayerAs<SymbolLayer>(ARROWS)?.apply {
            minZoom = TransitMapVisibility.vehicleMinZoom(focus != null)
            setFilter(Expression.all(Expression.has("arrow"), filter))
        }
    }
}
