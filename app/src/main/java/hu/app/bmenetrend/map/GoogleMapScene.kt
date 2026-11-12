package hu.app.bmenetrend.map

import android.content.Context
import android.graphics.Bitmap
import hu.app.bmenetrend.data.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.maplibre.geojson.FeatureCollection

internal data class GoogleMarkerSpec(
    val id: String,
    val point: GeoPoint,
    val icon: String,
    val dot: String? = null,
    val scale: Float = 1f,
    val vehicle: Vehicle? = null,
    val stops: List<TransitStop> = emptyList(),
    val selected: Boolean = false,
    val title: String? = null,
    val offset: StopMarkerLayout.Offset = StopMarkerLayout.Offset(),
    val arrow: String? = null,
    val heading: Float? = null,
)

internal data class GoogleScene(
    val markers: List<GoogleMarkerSpec>,
    val focusKey: String?,
    val offsets: Map<String, StopMarkerLayout.Offset> = emptyMap(),
)

/** Only the retained region enters the marker pool; focus never discards vehicles from it. */
internal object GoogleMapScene {
    suspend fun prepare(
        context: Context,
        vehicles: List<Vehicle>,
        region: MapBounds,
        selectedVehicleId: String?,
        stops: List<TransitStop>,
        focus: LineMapFocus?,
        highlightedStopId: String?,
        routes: Map<String, TransitRoute>,
        bearings: Map<String, List<Double>>,
        zoom: Double,
        dark: Boolean,
        routeColor: Int,
        showStops: Boolean,
        cached: Set<String>,
        pending: MutableMap<String, Bitmap>,
        viewport: MapBounds = region,
        previousOffsets: Map<String, StopMarkerLayout.Offset> = emptyMap(),
        selectedVehicle: Vehicle? = null,
        centerColor: Int = StationSprite.DEFAULT_CENTER_COLOR,
    ): GoogleScene {
        val nearby = VehicleSceneWindow.select(vehicles, region, selectedVehicleId, selectedVehicle)
        val json = VehicleMapScene.prepare(context, nearby, selectedVehicleId, cached, pending)
        val byId = nearby.associateBy { it.id }
        val markers = FeatureCollection.fromJson(json).features().orEmpty().map { feature ->
            val id = feature.getStringProperty("vehicle-id")
            val vehicle = byId.getValue(id)
            GoogleMarkerSpec("vehicle:$id", vehicle.position, feature.getStringProperty("icon"),
                dot = feature.getStringProperty("dot"), vehicle = vehicle, selected = id == selectedVehicleId,
                arrow = if (feature.hasProperty("arrow")) feature.getStringProperty("arrow") else null,
                heading = if (feature.hasProperty("heading")) feature.getNumberProperty("heading").toFloat() else null,
                title = listOfNotNull(vehicle.routeNumber, vehicle.destination).joinToString(" · ").ifEmpty { vehicle.providerId })
        }
        val lonMargin = (viewport.east - viewport.west) * .1
        val latMargin = (viewport.north - viewport.south) * .1
        val stopArea = MapBounds(viewport.west - lonMargin, viewport.south - latMargin,
            viewport.east + lonMargin, viewport.north + latMargin)
        val visibleStops = if (!TransitMapVisibility.anyStop(zoom, focus != null) || !showStops && focus == null) emptyList() else if (focus != null) focus.displayStops else stops.filter {
            it.position.lon in stopArea.west..stopArea.east && it.position.lat in stopArea.south..stopArea.north &&
                TransitMapVisibility.stop(it.transportMode, zoom)
        }
        val bounds = visibleStops.takeIf { it.isNotEmpty() }?.let { all -> MapBounds(
            all.minOf { it.position.lon }, all.minOf { it.position.lat },
            all.maxOf { it.position.lon }, all.maxOf { it.position.lat }) }
        val groups = RailStationMarkers.mergePlatforms(StopMapClustering.groups(visibleStops, bounds, 14.0, bearings))
        val offsets = if (focus != null) emptyMap() else StopMarkerLayout.offsets(groups.map(::stationAnchor), previousOffsets)
        val centerLon = (viewport.west + viewport.east) / 2
        val centerLat = (viewport.south + viewport.north) / 2
        // Stops go first, nearest the view centre first: the renderer creates markers in this order.
        val stopMarkers = ArrayList<GoogleMarkerSpec>(groups.size)
        for ((anchor, group) in groups.map { stationAnchor(it) to it }.sortedBy { (anchor, _) ->
            val dLon = anchor.position.lon - centerLon; val dLat = anchor.position.lat - centerLat
            dLon * dLon + dLat * dLat }) {
            currentCoroutineContext().ensureActive()
            val selected = group.any { it.id == highlightedStopId }
            val palette = StopMarkerColors.palette(group, routes)
            val sprite = StationSprite(palette, StopMarkerLayout.DIAMETER_DP.toInt(), focus != null,
                selected, dark, routeColor, StationMarkers.directions(group, bearings), centerColor)
            if (sprite.key !in cached && sprite.key !in pending) pending[sprite.key] = sprite.render()
            stopMarkers += GoogleMarkerSpec("stop:${anchor.id}", anchor.position, sprite.key,
                scale = StopMarkerLayout.diameterDp(zoom, focus != null).toFloat() / StopMarkerLayout.DIAMETER_DP.toFloat(),
                stops = group, selected = selected, title = anchor.name,
                offset = offsets[anchor.id] ?: StopMarkerLayout.Offset())
        }
        return GoogleScene(stopMarkers + markers, focus?.key, offsets)
    }

    /**
     * The padded area stop markers are created for. It is kept until the view leaves it, so an
     * ordinary pan reveals markers that already exist instead of waiting for new ones.
     */
    fun stopRegion(current: MapBounds?, viewport: MapBounds, zoom: Double): MapBounds {
        val lon = viewport.east - viewport.west
        val lat = viewport.north - viewport.south
        if (current != null && viewport.west >= current.west && viewport.east <= current.east &&
            viewport.south >= current.south && viewport.north <= current.north &&
            current.east - current.west <= lon * 4) return current
        // Zoomed out, a view already holds many stops: pad less so the marker count stays bounded.
        val share = if (zoom >= 14.5) .4 else if (zoom >= 13.75) .2 else .05
        return MapBounds(viewport.west - lon * share, viewport.south - lat * share,
            viewport.east + lon * share, viewport.north + lat * share)
    }

    /** Stop size follows zoom in quarter-level steps, so a pinch re-renders icons a few times, not per frame. */
    fun sceneZoom(zoom: Double) = kotlin.math.floor(zoom * 4) / 4

}
