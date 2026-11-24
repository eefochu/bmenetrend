package hu.app.bmenetrend.map

import org.maplibre.android.constants.MapLibreConstants
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Native vehicle icons use a provider-scoped line filter independently of source uploads.
 * Every vehicle layer sits above every station layer: a bus waiting at a stop covers the stop.
 */
internal object VehicleLayers {
    /** Trains below [TransitMapVisibility.VEHICLE_MIN_ZOOM], where other vehicles are not drawn yet. Lowest vehicle layer. */
    const val RAIL_OVERVIEW = "vehicle-overview-rail"
    const val RAIL_OVERVIEW_ARROWS = "vehicle-overview-rail-arrows"
    const val SYMBOLS = "vehicle-symbols"
    const val ARROWS = "vehicle-direction-arrows"

    fun install(style: Style, json: String) {
        if (!style.isFullyLoaded) return
        runCatching {
            style.addSource(GeoJsonSource(VehicleMapScene.SOURCE, json, GeoJsonOptions().withMaxZoom(12)))
            fun icons(id: String) = SymbolLayer(id, VehicleMapScene.SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment("viewport"), PropertyFactory.iconPitchAlignment("viewport"),
                PropertyFactory.symbolSortKey(Expression.get("priority")),
            )
            fun arrows(id: String) = SymbolLayer(id, VehicleMapScene.SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.get("arrow")), PropertyFactory.iconRotate(Expression.get("heading")),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment("map"), PropertyFactory.iconPitchAlignment("viewport"),
                PropertyFactory.symbolSortKey(Expression.get("priority")),
            ).withFilter(Expression.has("arrow"))
            listOf(icons(RAIL_OVERVIEW), arrows(RAIL_OVERVIEW_ARROWS), arrows(ARROWS), icons(SYMBOLS)).forEach { layer ->
                val below = UserLocationLayer.below(style)
                    ?: MapLibreConstants.LAYER_ID_ANNOTATIONS.takeIf { style.getLayer(it) != null }
                if (below != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
            }
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

    /** [property] scaled to the overview size below [TransitMapVisibility.VEHICLE_FULL_SIZE_ZOOM]; a highlighted line keeps full size. */
    internal fun sizeExpression(property: String, focused: Boolean): Expression = if (focused) Expression.get(property) else
        Expression.step(Expression.zoom(),
            Expression.product(Expression.get(property), Expression.literal(VehicleMarkerLayout.OVERVIEW_FACTOR)),
            Expression.stop(TransitMapVisibility.VEHICLE_FULL_SIZE_ZOOM, Expression.get(property)))

    fun configure(style: Style, focus: LineMapFocus?) {
        if (!style.isFullyLoaded) return
        runCatching {
            val focused = focus != null
            val filter = focusFilter(focus)
            // A highlighted line shows every vehicle from any zoom in the main layers, so the overview trains step aside.
            val overview = PropertyFactory.visibility(if (focused) "none" else "visible")
            val railFilter = Expression.all(Expression.eq(Expression.get("rail"), Expression.literal(true)), filter)
            style.getLayerAs<SymbolLayer>(RAIL_OVERVIEW)?.apply {
                minZoom = TransitMapVisibility.TRAIN_VEHICLE_MIN_ZOOM; maxZoom = TransitMapVisibility.VEHICLE_MIN_ZOOM
                setProperties(overview, PropertyFactory.iconSize(Expression.product(Expression.get("large-scale"),
                    Expression.literal(VehicleMarkerLayout.OVERVIEW_FACTOR))))
                setFilter(railFilter)
            }
            style.getLayerAs<SymbolLayer>(RAIL_OVERVIEW_ARROWS)?.apply {
                minZoom = TransitMapVisibility.TRAIN_VEHICLE_MIN_ZOOM; maxZoom = TransitMapVisibility.VEHICLE_MIN_ZOOM
                setProperties(overview, PropertyFactory.iconSize(Expression.product(Expression.get("arrow-scale"),
                    Expression.literal(VehicleMarkerLayout.OVERVIEW_FACTOR))))
                setFilter(Expression.all(Expression.has("arrow"), railFilter))
            }
            style.getLayerAs<SymbolLayer>(SYMBOLS)?.apply {
                minZoom = TransitMapVisibility.vehicleMinZoom(focused)
                setProperties(PropertyFactory.iconSize(sizeExpression("large-scale", focused)))
                setFilter(filter)
            }
            style.getLayerAs<SymbolLayer>(ARROWS)?.apply {
                minZoom = TransitMapVisibility.vehicleMinZoom(focused)
                setProperties(PropertyFactory.iconSize(sizeExpression("arrow-scale", focused)))
                setFilter(Expression.all(Expression.has("arrow"), filter))
            }
        }
    }
}
