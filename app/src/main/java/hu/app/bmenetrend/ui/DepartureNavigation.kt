package hu.app.bmenetrend.ui

import hu.app.bmenetrend.data.*

/** Never identify a departure by line number alone: two buses can share a line and stop. */
internal fun departureVehicle(departure: Departure, vehicles: List<Vehicle>): Vehicle? {
    fun id(value: String) = value.removePrefix("emma:")
    return departure.vehicleId?.let { key -> vehicles.firstOrNull { id(it.id)==id(key) } }
        // A BKK board names BKK's trip; a Volán bus merged with its EMMA record keeps EMMA's trip as
        // tripId and BKK's as bkkTripId.
        ?: departure.directionId?.let { trip -> vehicles.firstOrNull { it.tripId==trip || it.bkkTripId==trip } }
}

internal fun departureDirection(departure: Departure, route: TransitRoute?, details: LineDetails?): String? {
    fun id(value: String) = value.removePrefix("emma:")
    fun sameName(a: String,b: String) = a.isNotBlank() && b.isNotBlank() && StopClusters.normalize(a)==StopClusters.normalize(b)
    val patterns=details?.patterns.orEmpty()
    departure.patternCode?.let { code -> patterns.firstOrNull { code in it.codes }?.let { return it.id } }
    // The departure's own trip, when it was loaded as a variant (see TransitViewModel.loadDepartureTrip).
    departure.directionId?.let { trip -> patterns.firstOrNull { trip in it.codes }?.let { return it.id } }
    var candidates=patterns.filter { pattern -> pattern.stops.any { stop ->
        id(stop.id)==id(departure.stopId) || stop.sourceStopIds.any { id(it)==id(departure.stopId) } ||
            stop.parentStationId?.let { id(it)==id(departure.stopId) }==true
    } }
    departure.sourceDirectionId?.let { direction ->
        // EMMA's -1 (G10) is no direction; comparing it with BKK's 0/1 would discard every variant.
        if (candidates.any { it.directionId!=null && it.directionId!=NO_DIRECTION }) candidates=candidates.filter { it.directionId==direction }
    }
    val clean = DestinationFormatter.clean(departure.destination, departure.routeNumber)
    val destinationMatches=candidates.filter { sameName(it.headsign,departure.destination) ||
        sameName(it.headsign, clean) ||
        sameName(it.stops.lastOrNull()?.name.orEmpty(),departure.destination) ||
        sameName(it.stops.lastOrNull()?.name.orEmpty(), clean) }
    if (destinationMatches.isNotEmpty()) candidates=destinationMatches
    else if (departure.destination.isNotBlank() && candidates.any { it.headsign.isNotBlank() }) return null
    candidates.singleOrNull()?.let { return it.id }
    // Stop-skipping variants of one direction and terminus (G10's two Győr runs) are one line direction.
    candidates.groupBy { it.familyKey }.values.singleOrNull()?.let { return it.first().id }
    if (patterns.isNotEmpty()) return null
    route?.directions?.firstOrNull { it.id==departure.directionId || it.id==departure.sourceDirectionId }?.let { return it.id }
    val local=route?.directions.orEmpty().filter { departure.stopId in it.stopIds }
    return local.filter { sameName(it.name,departure.destination) || sameName(it.name, clean) }.singleOrNull()?.id ?: local.singleOrNull()?.id
}
