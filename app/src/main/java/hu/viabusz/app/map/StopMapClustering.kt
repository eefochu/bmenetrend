package hu.viabusz.app.map

import hu.viabusz.app.data.MapBounds
import hu.viabusz.app.data.TransitStop

/** Keep every visible source platform independent; wide views use a transparent tile overlay. */
object StopMapClustering {
    const val DETAIL_ZOOM = 12.5
    fun groups(stops: List<TransitStop>, bounds: MapBounds?, zoom: Double): List<List<TransitStop>> {
        if (bounds == null) return emptyList()
        val visible = stops.filter { it.position.lat in bounds.south..bounds.north && it.position.lon in bounds.west..bounds.east }
        return visible.distinctBy { it.id }.map { listOf(it) }
    }
}
