package hu.viabusz.app.map

import hu.viabusz.app.data.*

/** Line identity is provider-scoped; opposite directions share it, unrelated same-number lines do not. */
data class LineMapFocus(
    val key: String,
    val routeId: String?,
    val providerId: String,
    val number: String?,
    val mode: String?,
    val paths: List<List<GeoPoint>>,
    val stops: List<TransitStop>,
    val selectedVehicleId: String? = null,
) {
    fun includes(vehicle: Vehicle): Boolean = when {
        vehicle.id == selectedVehicleId -> true
        vehicle.providerId != providerId -> false
        routeId != null -> vehicle.routeId == routeId
        number != null -> vehicle.routeNumber == number && vehicle.transportMode == mode
        else -> false
    }

    companion object {
        fun create(vehicle: Vehicle?, boarding: BoardingSelection?, routes: List<TransitRoute>,
                   details: Map<String, LineDetails>, journey: VehicleJourney?, allStops: List<TransitStop>): LineMapFocus? {
            val routeId = vehicle?.routeId ?: boarding?.routeId
            if (vehicle == null && boarding == null) return null
            val route = routes.find { it.id == routeId }
            val line = details[routeId]
            val patterns = line?.patterns.orEmpty().let { all -> all.filter { it.serviceCount > 0 }.ifEmpty { all } }
            val actualJourney = journey?.takeIf { vehicle != null && it.tripId == vehicle.tripId }
            val localStops = route?.directions.orEmpty().flatMap { it.stopIds }.toSet()
            val stops = (patterns.flatMap { it.stops } + actualJourney?.stops.orEmpty() + boarding?.pattern?.stops.orEmpty()
                + allStops.filter { it.id in localStops || (routeId != null && routeId in it.routeIds) }).distinctBy { it.id }
            val paths = (patterns.map { it.geometry } + listOfNotNull(actualJourney?.geometry, boarding?.pattern?.geometry))
                .filter { it.size >= 2 }.distinct()
            val provider = vehicle?.providerId ?: route?.providerId ?: return null
            return LineMapFocus(routeId ?: "vehicle:${vehicle!!.id}", routeId, provider,
                vehicle?.routeNumber ?: route?.number, vehicle?.transportMode ?: route?.transportMode, paths, stops, vehicle?.id)
        }
    }
}
