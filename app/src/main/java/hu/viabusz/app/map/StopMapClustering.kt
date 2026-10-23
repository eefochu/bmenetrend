package hu.viabusz.app.map

import hu.viabusz.app.data.MapBounds
import hu.viabusz.app.data.TransitStop
import java.text.Normalizer
import kotlin.math.*

/** Presentation-only station groups. Timetables and source platform identities stay independent. */
object StopMapClustering {
    const val DETAIL_ZOOM = 12.5
    const val PLATFORM_ZOOM = 14.5
    private const val MAX_PLATFORM_DISTANCE_METRES = 250.0

    private val marksRegex = Regex("""\p{M}+""")
    private val nonAlphanumericRegex = Regex("""[^\p{L}\p{N}]+""")

    fun groups(stops: List<TransitStop>, bounds: MapBounds?, zoom: Double): List<List<TransitStop>> {
        if (bounds == null) return emptyList()
        val visible = stops.asSequence().filter { it.position.lat in bounds.south..bounds.north && it.position.lon in bounds.west..bounds.east }
            .distinctBy { it.id }.sortedBy { it.id }.toList()
        if (zoom >= PLATFORM_ZOOM) return visible.map { listOf(it) }
        val groups = mutableListOf<MutableList<TransitStop>>()
        val byIdentity = mutableMapOf<String, MutableSet<Int>>()
        fun keys(stop: TransitStop): List<String> = buildList {
            stop.parentStationId?.let { add("parent:$it") }
            val name = Normalizer.normalize(stop.name.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
                .replace(marksRegex, "").replace(nonAlphanumericRegex, " ").trim()
            if (name.isNotEmpty()) add("name:$name")
        }
        visible.forEach { stop ->
            val keys = keys(stop)
            val group = keys.flatMap { byIdentity[it].orEmpty() }.distinct().firstOrNull { index ->
                // Bound the whole group, preventing chains from joining distant same-name stations.
                groups[index].all { distance(it, stop) <= MAX_PLATFORM_DISTANCE_METRES }
            } ?: groups.size.also { groups.add(mutableListOf()) }
            groups[group].add(stop)
            keys.forEach { byIdentity.getOrPut(it) { mutableSetOf() }.add(group) }
        }
        return groups
    }

    private fun distance(a: TransitStop, b: TransitStop): Double {
        val dy = Math.toRadians(a.position.lat - b.position.lat)
        val dx = Math.toRadians(a.position.lon - b.position.lon) * cos(Math.toRadians((a.position.lat + b.position.lat) / 2))
        return hypot(dx, dy) * 6_371_000
    }
}
