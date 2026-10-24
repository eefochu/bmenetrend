package hu.app.bmenetrend.data

/** MÁV's verified _0 boarding point aggregates its numbered tracks, including per-train platforms. */
object EmmaBoardTargets {
    fun select(stops: List<TransitStop>): List<TransitStop> = buildList {
        val unique = stops.distinctBy { it.id }
        addAll(unique.filter { it.providerId != "mav" && !it.station })
        unique.filter { it.providerId == "mav" }.groupBy { it.parentStationId ?: it.id }.values.forEach { station ->
            val aggregate = station.firstOrNull { !it.station && it.parentStationId != null && it.id == "${it.parentStationId}_0" }
            if (aggregate != null) add(aggregate) else addAll(station.filterNot { it.station }.ifEmpty { station.take(1) })
        }
        if (isEmpty()) addAll(unique.filter { it.station })
    }
}
