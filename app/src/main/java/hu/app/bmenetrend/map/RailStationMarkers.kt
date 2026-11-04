package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.RailSupport
import hu.app.bmenetrend.data.StopClusters
import hu.app.bmenetrend.data.TransitStop

/** Map-only aggregation. Timetable/platform records remain intact for selection and routing. */
internal object RailStationMarkers {
    fun isRail(stop: TransitStop) = RailSupport.isTrain(stop.transportMode) ||
        (stop.transportMode == null && "mav" in stop.providerIds)

    fun mergePlatforms(groups: List<List<TransitStop>>): List<List<TransitStop>> {
        val result = ArrayList<MutableList<TransitStop>>(groups.size)
        val stations = HashMap<String, MutableList<MutableList<TransitStop>>>()
        for (group in groups) {
            val first = group.first()
            val rail = group.all(::isRail)
            val biatorbagy = rail && StopClusters.normalize(first.name).startsWith("biatorbagy") &&
                first.position.lat in 47.47..47.49 && first.position.lon in 18.82..18.85
            val key = if (biatorbagy) "biatorbagy-rail" else if (rail) first.parentStationId else null
            val candidates = key?.let { stations.getOrPut(it) { ArrayList() } }
            val existing = candidates?.firstOrNull {
                StopClusters.distanceMeters(it.first().position, first.position) <= 500.0
            }
            if (existing != null) existing.addAll(group)
            else {
                val copy = group.toMutableList()
                result.add(copy)
                candidates?.add(copy)
            }
        }
        return result
    }
}
