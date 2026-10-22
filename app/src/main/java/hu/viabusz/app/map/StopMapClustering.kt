package hu.viabusz.app.map

import hu.viabusz.app.data.MapBounds
import hu.viabusz.app.data.TransitStop
import kotlin.math.*

/** A geographic grid keeps the national index off MapLibre's per-point JNI projection path. */
object StopMapClustering {
    const val DETAIL_ZOOM = 14.0
    fun groups(stops: List<TransitStop>, bounds: MapBounds?, zoom: Double): List<List<TransitStop>> {
        if (bounds == null) return emptyList()
        val visible = stops.filter { it.position.lat in bounds.south..bounds.north && it.position.lon in bounds.west..bounds.east }
        if (zoom >= DETAIL_ZOOM) return visible.map { listOf(it) }
        // At wide zooms a small unlabelled dot approximates nearby platforms.
        val cell = 18.0 / (512.0 * 2.0.pow(zoom))
        return visible.groupBy {
            val latitude = Math.toRadians(it.position.lat.coerceIn(-85.0, 85.0))
            val x = (it.position.lon + 180.0) / 360.0
            val y = (1.0 - ln(tan(latitude) + 1.0 / cos(latitude)) / PI) / 2.0
            floor(x / cell).toLong() to floor(y / cell).toLong()
        }.values.toList()
    }
}
