package hu.app.bmenetrend.ui

import hu.app.bmenetrend.data.*

/** Never identify a departure by line number alone: two buses can share a line and stop. */
internal fun departureVehicle(departure: Departure, vehicles: List<Vehicle>): Vehicle? {
    fun id(value: String) = value.removePrefix("emma:")
    return departure.vehicleId?.let { key -> vehicles.firstOrNull { id(it.id)==id(key) } }
        ?: departure.directionId?.let { trip -> vehicles.firstOrNull { it.tripId==trip } }
}

internal fun departureDirection(departure: Departure, route: TransitRoute?, details: LineDetails?): String? {
    fun id(value: String) = value.removePrefix("emma:")
    fun sameName(a: String,b: String) = a.isNotBlank() && b.isNotBlank() && StopClusters.normalize(a)==StopClusters.normalize(b)
    val patterns=details?.patterns.orEmpty()
    departure.patternCode?.let { code -> patterns.firstOrNull { code in it.codes }?.let { return it.id } }
    var candidates=patterns.filter { pattern -> pattern.stops.any { stop ->
        id(stop.id)==id(departure.stopId) || stop.sourceStopIds.any { id(it)==id(departure.stopId) } ||
            stop.parentStationId?.let { id(it)==id(departure.stopId) }==true
    } }
    departure.sourceDirectionId?.let { direction ->
        if (candidates.any { it.directionId!=null }) candidates=candidates.filter { it.directionId==direction }
    }
    val clean = DestinationFormatter.clean(departure.destination, departure.routeNumber)
    val destinationMatches=candidates.filter { sameName(it.headsign,departure.destination) ||
        sameName(it.headsign, clean) ||
        sameName(it.stops.lastOrNull()?.name.orEmpty(),departure.destination) ||
        sameName(it.stops.lastOrNull()?.name.orEmpty(), clean) }
    if (destinationMatches.isNotEmpty()) candidates=destinationMatches
    else if (departure.destination.isNotBlank() && candidates.any { it.headsign.isNotBlank() }) return null
    candidates.singleOrNull()?.let { return it.id }
    if (patterns.isNotEmpty()) return null
    route?.directions?.firstOrNull { it.id==departure.directionId || it.id==departure.sourceDirectionId }?.let { return it.id }
    val local=route?.directions.orEmpty().filter { departure.stopId in it.stopIds }
    return local.filter { sameName(it.name,departure.destination) || sameName(it.name, clean) }.singleOrNull()?.id ?: local.singleOrNull()?.id
}
