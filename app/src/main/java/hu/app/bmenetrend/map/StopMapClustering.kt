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

    fun samePlatform(a: TransitStop,b: TransitStop,bearings: Map<String,List<Double>> = emptyMap()): Boolean {
        if(a.providerIds.any { it in b.providerIds }) return false
        if(StopClusters.distanceMeters(a.position,b.position)>SHARED_PLATFORM_METERS) return false
        fun mode(stop: TransitStop) = stop.transportMode?.takeUnless { it in setOf("BUS","COACH") } ?: "BUS"
        if(mode(a)!=mode(b)) return false
        val ah=bearings[a.id].orEmpty(); val bh=bearings[b.id].orEmpty()
        if(ah.isNotEmpty() && bh.isNotEmpty() && ah.none { aHeading -> bh.any { StopDirections.difference(aHeading,it)<45 } }) return false
        return (listOf(a.name)+a.aliases).any { an -> (listOf(b.name)+b.aliases).any { bn -> StopClusters.areNamesCloselyMatching(an,bn) } }
    }

    @Suppress("UNUSED_PARAMETER")
    fun groups(stops: List<TransitStop>,bounds: MapBounds?,zoom: Double,bearings: Map<String,List<Double>> = emptyMap(),highlightedStopIds: Set<String>? = null): List<List<TransitStop>> {
        if(bounds==null) return emptyList()
        val visible=stops.filter { it.position.lat in bounds.south..bounds.north && it.position.lon in bounds.west..bounds.east }
            .distinctBy { it.id }.sortedBy { it.id }
        val parents=visible.mapNotNull { it.parentStationId }.toSet()
        val groups=mutableListOf<MutableList<TransitStop>>()
        // Both axes matter: latitude-only strips compare unrelated stops across the entire city.
        // The most extreme visible latitude makes longitude cells wide enough for every 30-m match.
        val latitudeStep=.0003
        val extremeLatitude=maxOf(abs(bounds.south),abs(bounds.north)).coerceAtMost(89.999)
        val longitudeStep=latitudeStep/cos(Math.toRadians(extremeLatitude)).coerceAtLeast(.00001)
        val buckets=mutableMapOf<Pair<Int,Int>,MutableList<MutableList<TransitStop>>>()
        for(stop in visible.filterNot { it.station && it.id in parents }) {
            val bucket=floor(stop.position.lat/latitudeStep).toInt() to floor(stop.position.lon/longitudeStep).toInt()
            val neighbours=sequence {
                for(dy in -1..1) for(dx in -1..1) yieldAll(buckets[bucket.first+dy to bucket.second+dx].orEmpty())
            }
            val shared=neighbours
                .filter { group -> group.all { samePlatform(it,stop,bearings) } }
                .sortedBy { group -> group.map { StopClusters.distanceMeters(it.position,stop.position) }.average() }.toList()
            val first=shared.firstOrNull()
            val chosen=first?.takeIf { shared.size==1 ||
                shared[1].map { StopClusters.distanceMeters(it.position,stop.position) }.average()-
                    first.map { StopClusters.distanceMeters(it.position,stop.position) }.average()>=4 }
            if(chosen!=null) chosen.add(stop) else {
                val group=mutableListOf(stop)
                groups.add(group)
                buckets.getOrPut(bucket) { mutableListOf() }.add(group)
            }
        }
        return if(highlightedStopIds==null) groups else groups.filter { group -> group.any { it.id in highlightedStopIds } }
    }
}
