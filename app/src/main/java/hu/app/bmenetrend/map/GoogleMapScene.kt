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
    ): GoogleScene {
        val nearby = VehicleSceneWindow.select(vehicles, region, selectedVehicleId)
        val json = VehicleMapScene.prepare(context, nearby, selectedVehicleId, cached, pending)
        val byId = nearby.associateBy { it.id }
        val markers = FeatureCollection.fromJson(json).features().orEmpty().map { feature ->
            val id = feature.getStringProperty("vehicle-id")
            val vehicle = byId.getValue(id)
            GoogleMarkerSpec("vehicle:$id", vehicle.position, feature.getStringProperty("icon"),
                dot = feature.getStringProperty("dot"), vehicle = vehicle, selected = id == selectedVehicleId,
                title = listOfNotNull(vehicle.routeNumber, vehicle.destination).joinToString(" · ").ifEmpty { vehicle.providerId })
        }.toMutableList()
        val lonMargin = (viewport.east - viewport.west) * .1
        val latMargin = (viewport.north - viewport.south) * .1
        val stopArea = MapBounds(viewport.west - lonMargin, viewport.south - latMargin,
            viewport.east + lonMargin, viewport.north + latMargin)
        val visibleStops = if (!TransitMapVisibility.stop(null, zoom, focus != null) || !showStops && focus == null) emptyList() else if (focus != null) focus.displayStops else stops.filter {
            it.position.lon in stopArea.west..stopArea.east && it.position.lat in stopArea.south..stopArea.north &&
                TransitMapVisibility.stop(it.transportMode, zoom)
        }
        val bounds = visibleStops.takeIf { it.isNotEmpty() }?.let { all -> MapBounds(
            all.minOf { it.position.lon }, all.minOf { it.position.lat },
            all.maxOf { it.position.lon }, all.maxOf { it.position.lat }) }
        val groups = RailStationMarkers.mergePlatforms(StopMapClustering.groups(visibleStops, bounds, 14.0, bearings))
        val offsets = if (focus != null) emptyMap() else StopMarkerLayout.offsets(groups.map(::stationAnchor), previousOffsets)
        for (group in groups) {
            currentCoroutineContext().ensureActive()
            val anchor = stationAnchor(group)
            val selected = group.any { it.id == highlightedStopId }
            val palette = StopMarkerColors.palette(group, routes)
            val sprite = StationSprite(palette, StopMarkerLayout.DIAMETER_DP.toInt(), focus != null,
                selected, dark, routeColor, if (zoom >= 14) StationMarkers.directions(group, bearings) else emptyList())
            if (sprite.key !in cached && sprite.key !in pending) pending[sprite.key] = sprite.render()
            markers += GoogleMarkerSpec("stop:${anchor.id}", anchor.position, sprite.key,
                scale = StopMarkerLayout.diameterDp(zoom, focus != null).toFloat() / StopMarkerLayout.DIAMETER_DP.toFloat(),
                stops = group, selected = selected, title = anchor.name,
                offset = offsets[anchor.id] ?: StopMarkerLayout.Offset())
        }
        return GoogleScene(markers, focus?.key, offsets)
    }

}
