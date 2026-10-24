package hu.app.bmenetrend.data

import androidx.compose.runtime.Immutable

/** A physical station with its individual boarding platforms preserved for timetable queries. */
@Immutable
data class NearbyStation(
    val stop: TransitStop,
    val platforms: List<TransitStop>,
    val distanceMeters: Double,
    val departures: List<Departure> = emptyList(),
    val loading: Boolean = false,
    val unavailable: Boolean = false,
)

object NearbyDepartures {
    const val RADIUS_METERS = 1_500.0
    fun contains(bounds: MapBounds, point: GeoPoint): Boolean = point.lat in bounds.south..bounds.north && point.lon in bounds.west..bounds.east

    fun anchor(center: GeoPoint, bounds: MapBounds?, location: GeoPoint?): GeoPoint =
        location?.takeIf { (bounds == null || contains(bounds, it)) && StopClusters.distanceMeters(center, it) < 700 } ?: center

    /** Bound the number of station boards even in dense city centres or at country-level zoom. */
    fun select(stops: List<TransitStop>, anchor: GeoPoint, bounds: MapBounds?, maxStations: Int = 3): List<NearbyStation> {
        val candidates = stops.asSequence()
            .filter { !it.station && (bounds == null || contains(bounds, it.position)) }
            .filter { kotlin.math.abs(it.position.lat - anchor.lat) < 0.014 && kotlin.math.abs(it.position.lon - anchor.lon) < 0.022 }
            .map { it to StopClusters.distanceMeters(anchor, it.position) }
            .filter { it.second <= RADIUS_METERS }.sortedBy { it.second }.take(120).toList()
        return candidates.take(maxStations).map { (stop, distance) -> NearbyStation(stop, listOf(stop), distance) }
    }
}
