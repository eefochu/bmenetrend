package hu.viabusz.app.data

/** A physical station with its individual boarding platforms preserved for timetable queries. */
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
    fun select(stops: List<TransitStop>, anchor: GeoPoint, bounds: MapBounds?): List<NearbyStation> {
        val candidates = stops.asSequence()
            .filter { !it.station && (bounds == null || contains(bounds, it.position)) }
            .filter { kotlin.math.abs(it.position.lat - anchor.lat) < 0.014 && kotlin.math.abs(it.position.lon - anchor.lon) < 0.022 }
            .map { it to StopClusters.distanceMeters(anchor, it.position) }
            .filter { it.second <= RADIUS_METERS }.sortedBy { it.second }.take(120).toList()
        val used = mutableSetOf<String>()
        val groups = mutableListOf<NearbyStation>()
        for ((stop, distance) in candidates) {
            if (stop.id in used) continue
            val platforms = StopClusters.clusterFor(stop, candidates.map { it.first }).filterNot { it.station }
                .sortedBy { StopClusters.distanceMeters(anchor, it.position) }.take(4)
            used.addAll(StopClusters.clusterFor(stop, candidates.map { it.first }).map { it.id })
            groups += NearbyStation(stop, platforms.ifEmpty { listOf(stop) }, distance)
            if (groups.size == 3) break
        }
        return groups
    }
}
