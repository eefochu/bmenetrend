package hu.app.bmenetrend.map

import org.maplibre.android.constants.MapLibreConstants
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource

/** Native style layers for [StationMarkers]: one source, arrow/dot/selected symbol layers. */
internal object StationMarkerLayers {
    private val allLayers = StationMarkers.ARROW_LAYERS + listOf(StationMarkers.RAIL_LAYER, StationMarkers.ROAD_LAYER, StationMarkers.SELECTED_LAYER)

    fun install(style: Style, json: String) {
        if (!style.isFullyLoaded) return
        runCatching {
            style.addSource(GeoJsonSource(StationMarkers.SOURCE, json, GeoJsonOptions().withMaxZoom(StationMarkers.SOURCE_MAX_ZOOM)))
            fun dots(id: String, rail: Boolean) = SymbolLayer(id, StationMarkers.SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")), PropertyFactory.iconOffset(Expression.get("offset")),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment("map"), PropertyFactory.iconPitchAlignment("viewport"),
                PropertyFactory.symbolZOrder("viewport-y"),
            ).withFilter(Expression.eq(Expression.get("rail"), Expression.literal(rail)))
            fun arrows(id: String, index: Int) = SymbolLayer(id, StationMarkers.SOURCE).withProperties(
                PropertyFactory.iconRotate(Expression.get("a$index")), PropertyFactory.iconOffset(Expression.get("o$index")),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment("map"), PropertyFactory.iconPitchAlignment("viewport"),
            ).withFilter(Expression.has("a$index"))
            val selected = SymbolLayer(StationMarkers.SELECTED_LAYER, StationMarkers.SOURCE).withProperties(
                PropertyFactory.iconOffset(Expression.get("offset")),
                PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconRotationAlignment("map"), PropertyFactory.iconPitchAlignment("viewport"),
            ).withFilter(Expression.eq(Expression.get("id"), Expression.literal("")))
            // Arrows sit under dots, as they did inside each baked sprite.
            val below = style.getLayer(VehicleLayers.RAIL_DOTS)?.id ?: UserLocationLayer.below(style)
                ?: MapLibreConstants.LAYER_ID_ANNOTATIONS.takeIf { style.getLayer(it) != null }
            (StationMarkers.ARROW_LAYERS.mapIndexed { index, id -> arrows(id, index) } +
                listOf(dots(StationMarkers.RAIL_LAYER, true), dots(StationMarkers.ROAD_LAYER, false), selected)).forEach { layer ->
                if (below != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
            }
        }
    }

    fun visible(style: Style, visible: Boolean) {
        if (!style.isFullyLoaded) return
        runCatching {
            allLayers.forEach { id -> style.getLayer(id)?.setProperties(
                PropertyFactory.visibility(if (visible) "visible" else "none")) }
        }
    }

    /** Size curve, arrow texture and zoom gates; GPU expressions, so camera changes never touch data. */
    fun configure(style: Style, focused: Boolean, arrowImage: String) {
        if (!style.isFullyLoaded) return
        runCatching {
            val iconScale = if (focused) Expression.literal(16f / StopMarkerLayout.DIAMETER_DP.toFloat()) else Expression.interpolate(
                Expression.linear(), Expression.zoom(), *StopMarkerLayout.sizeStops.map { (zoom, size) ->
                    Expression.stop(zoom, size / StopMarkerLayout.DIAMETER_DP)
                }.toTypedArray())
            allLayers.forEach { id -> style.getLayerAs<SymbolLayer>(id)?.setProperties(PropertyFactory.iconSize(iconScale)) }
            val stopMinZoom = TransitMapVisibility.stopMinZoom(focused)
            StationMarkers.ARROW_LAYERS.forEach { id ->
                style.getLayerAs<SymbolLayer>(id)?.apply { setProperties(PropertyFactory.iconImage(arrowImage)); minZoom = stopMinZoom }
            }
            style.getLayer(StationMarkers.RAIL_LAYER)?.minZoom = TransitMapVisibility.railStopMinZoom(focused)
            style.getLayer(StationMarkers.ROAD_LAYER)?.minZoom = stopMinZoom
        }
    }

    /** The highlighted stop only swaps filters and one texture; it never touches marker data. */
    fun highlight(style: Style, markerId: String?, image: String, minZoom: Float) {
        if (!style.isFullyLoaded) return
        runCatching {
            val selectedId = Expression.literal(markerId.orEmpty())
            style.getLayerAs<SymbolLayer>(StationMarkers.SELECTED_LAYER)?.apply {
                setProperties(PropertyFactory.iconImage(image))
                setFilter(Expression.eq(Expression.get("id"), selectedId))
                this.minZoom = minZoom
            }
            listOf(StationMarkers.RAIL_LAYER to true, StationMarkers.ROAD_LAYER to false).forEach { (id, rail) ->
                style.getLayerAs<SymbolLayer>(id)?.setFilter(Expression.all(
                    Expression.eq(Expression.get("rail"), Expression.literal(rail)), Expression.neq(Expression.get("id"), selectedId)))
            }
        }
    }
}

/**
 * Trailing debounce for marker uploads. Startup and map-area loading deliver stops, bearings and
 * route colours in bursts; each upload re-tiles the whole source natively. The first upload after a
 * quiet period goes out quickly; follow-ups inside a burst wait for it to settle, but never longer
 * than [MAX_WAIT_MS] after the first pending change.
 */
internal class UploadCoalescer(private val clock: () -> Long = android.os.SystemClock::uptimeMillis) {
    private var lastUpload = Long.MIN_VALUE / 2
    private var pendingSince = -1L

    fun delayMillis(): Long {
        val now = clock()
        if (pendingSince < 0) pendingSince = now
        if (now - lastUpload >= BURST_WINDOW_MS) return QUIET_DELAY_MS
        return minOf(BURST_DELAY_MS, MAX_WAIT_MS - (now - pendingSince)).coerceAtLeast(0)
    }

    fun uploaded() { lastUpload = clock(); pendingSince = -1 }

    /** Nothing needed uploading after all; the next change starts a fresh wait. */
    fun skipped() { pendingSince = -1 }

    companion object {
        const val QUIET_DELAY_MS = 30L
        const val BURST_DELAY_MS = 350L
        const val BURST_WINDOW_MS = 1_500L
        const val MAX_WAIT_MS = 800L
    }
}
