package hu.viabusz.app.map

import hu.viabusz.app.data.MapBounds
import hu.viabusz.app.data.TransitStop

/** Individual source platforms at every visible zoom level. */
object StopMapClustering {
    const val DETAIL_ZOOM = 12.5
    const val PLATFORM_ZOOM = 14.5
    fun groups(stops: List<TransitStop>, bounds: MapBounds?, zoom: Double): List<List<TransitStop>> {
        if (bounds == null) return emptyList()
        val visible = stops.asSequence().filter { it.position.lat in bounds.south..bounds.north && it.position.lon in bounds.west..bounds.east }
            .distinctBy { it.id }.sortedBy { it.id }.toList()
        // Every source platform, including each metro direction, keeps its own marker and hit target.
        val parents = visible.mapNotNull { it.parentStationId }.toSet()
        return visible.filterNot { it.station && it.id in parents }.map { listOf(it) }
    }
}
