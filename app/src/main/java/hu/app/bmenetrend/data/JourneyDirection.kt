package hu.app.bmenetrend.data

/** The line catalogue's A–B name is not the direction of an individual vehicle. */
object JourneyDirection {
    fun destination(stops: List<TransitStop>, headsign: String?): String? {
        val first=stops.firstOrNull()
        val last=stops.lastOrNull()
        if (stops.size >= 2 && first != null && last != null) {
            val loop=first.id == last.id || first.parentStationId != null && first.parentStationId == last.parentStationId ||
                TransitSearch.normalize(first.name) == TransitSearch.normalize(last.name)
            // Loop headsigns can name a via point rather than the repeated terminal.
            if (!loop) return last.name
        }
        return headsign?.takeIf { it.isNotBlank() } ?: last?.name
    }
    fun endpoints(stops: List<TransitStop>): String? = if(stops.size < 2) null else "${stops.first().name} → ${stops.last().name}"
}
