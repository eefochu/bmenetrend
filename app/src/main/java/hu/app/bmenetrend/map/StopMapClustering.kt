package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.MapBounds
import hu.app.bmenetrend.data.StopClusters
import hu.app.bmenetrend.data.TransitStop
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor

/** Only co-located operator records share a dot. Opposite platforms never merge by name alone. */
object StopMapClustering {
    const val DETAIL_ZOOM = 12.5
    const val PLATFORM_ZOOM = 14.0
    const val SHARED_PLATFORM_METERS = 30.0
    const val CORRIDOR_PLATFORM_METERS = 95.0

    private val KNOWN_PAIRS: Set<Set<String>> = setOf(
        setOf("5", "hkir:hkir_777139_1"),
        setOf("81", "hkir:hkir_777139_2"),
    )

    fun isKnownPair(idA: String, idB: String): Boolean {
        val a = idA.removePrefix("emma:")
        val b = idB.removePrefix("emma:")
        return KNOWN_PAIRS.any { it.contains(a) && it.contains(b) }
    }

    fun samePlatform(a: TransitStop,b: TransitStop,bearings: Map<String,List<Double>> = emptyMap()): Boolean {
        if(a.providerIds.any { it in b.providerIds }) return false
        val dist = StopClusters.distanceMeters(a.position,b.position)
        if (dist > CORRIDOR_PLATFORM_METERS) return false
        fun mode(stop: TransitStop) = stop.transportMode?.takeUnless { it == "BUS" || it == "COACH" } ?: "BUS"
        if(mode(a)!=mode(b)) return false
        fun matches(name: String): Boolean {
            if (StopClusters.areNamesCloselyMatching(name, b.name)) return true
            for (alias in b.aliases) if (StopClusters.areNamesCloselyMatching(name, alias)) return true
            return false
        }
        val namesMatch = matches(a.name) || a.aliases.any(::matches)
        if(!namesMatch && !isKnownPair(a.id, b.id)) return false

        val ah=bearings[a.id].orEmpty(); val bh=bearings[b.id].orEmpty()
        val bearingsMatch = ah.isNotEmpty() && bh.isNotEmpty() && ah.any { aHeading -> bh.any { StopDirections.difference(aHeading,it)<45 } }
        val bearingsConflict = ah.isNotEmpty() && bh.isNotEmpty() && !bearingsMatch

        if (bearingsConflict) return false

        val maxDist = if (isKnownPair(a.id, b.id) || (namesMatch && bearingsMatch)) CORRIDOR_PLATFORM_METERS else SHARED_PLATFORM_METERS
        return dist <= maxDist
    }

    @Suppress("UNUSED_PARAMETER")
    fun groups(stops: List<TransitStop>,bounds: MapBounds?,zoom: Double,bearings: Map<String,List<Double>> = emptyMap(),highlightedStopIds: Set<String>? = null): List<List<TransitStop>> {
        if(bounds==null) return emptyList()
        val visible=stops.filter { it.position.lat in bounds.south..bounds.north && it.position.lon in bounds.west..bounds.east }
            .distinctBy { it.id }.sortedBy { it.id }
        val parents=visible.mapNotNull { it.parentStationId }.toSet()
        val groups=mutableListOf<MutableList<TransitStop>>()
        // Both axes matter: latitude-only strips compare unrelated stops across the entire city.
        // The most extreme visible latitude makes longitude cells wide enough for every match.
        // A cell is at least 100m on either axis throughout these bounds.
        // Since platforms can match only within 95m, only adjacent cells can
        // contain a match. This replaces 49 lookups per stop with nine.
        val latitudeStep=.0009
        val extremeLatitude=maxOf(abs(bounds.south),abs(bounds.north)).coerceAtMost(89.999)
        val longitudeStep=latitudeStep/cos(Math.toRadians(extremeLatitude)).coerceAtLeast(.00001)
        fun cellKey(y: Int, x: Int) = (y.toLong() shl 32) xor (x.toLong() and 0xffffffffL)
        val buckets = LongCellMap<MutableList<MutableList<TransitStop>>>(visible.size)
        for (stop in visible) {
            if (stop.station && stop.id in parents) continue
            val y = floor(stop.position.lat / latitudeStep).toInt()
            val x = floor(stop.position.lon / longitudeStep).toInt()
            var closest: MutableList<TransitStop>? = null
            var closestDistance = Double.POSITIVE_INFINITY
            var secondDistance = Double.POSITIVE_INFINITY
            // Each group belongs to exactly one bucket. Avoid per-stop sequences,
            // distinct sets, candidate lists and sorting just to find the nearest two.
            for (dy in -1..1) for (dx in -1..1) {
                val candidates = buckets[cellKey(y + dy, x + dx)] ?: continue
                for (group in candidates) {
                    if (!group.all { samePlatform(it, stop, bearings) }) continue
                    var total = 0.0
                    for (member in group) total += StopClusters.distanceMeters(member.position, stop.position)
                    val distance = total / group.size
                    if (distance < closestDistance) {
                        secondDistance = closestDistance
                        closestDistance = distance
                        closest = group
                    } else if (distance < secondDistance) secondDistance = distance
                }
            }
            if (closest != null && secondDistance - closestDistance >= 4) closest.add(stop)
            else {
                val group = mutableListOf(stop)
                groups.add(group)
                val key = cellKey(y, x)
                val bucket = buckets[key] ?: ArrayList<MutableList<TransitStop>>().also { buckets[key] = it }
                bucket.add(group)
            }
        }
        return if(highlightedStopIds==null) groups else groups.filter { group -> group.any { it.id in highlightedStopIds } }
    }
}
