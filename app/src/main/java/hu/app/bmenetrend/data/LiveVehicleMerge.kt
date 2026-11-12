package hu.app.bmenetrend.data

/**
 * BKK is the primary live source inside the area the Worker covered; direct EMMA supplements it.
 *
 *  - BKK bus/tram/trolleybus/metro and EMMA's BKK relay share `emma:BKK:<n>`; the BKK record wins.
 *    Vehicles missing from a partial BKK response remain available from EMMA.
 *  - BKK trains/HÉV are matched to EMMA by any underlying vehicle number (coupled sets and HÉV
 *    plate numbers included). The EMMA identity, trip, route, train number and next stop are kept
 *    (its journey/line queries understand them); BKK supplies position, heading and timestamp,
 *    unless the EMMA position is substantially fresher.
 *  - Worker-provided EMMA fallback trains only fill gaps not covered by BKK or direct EMMA.
 *  - Volán plates share the `emma:hkir:hkir_<plate>` identity; BKK's fields win, while the
 *    national trip/route identifiers remain available for fallback detail queries.
 *  - Everything outside the covered area stays as EMMA reported it unless BKK also found it.
 *  - Sources are carried per field group: a matched train is `positionSource = BKK`,
 *    `detailsSource = EMMA`, and keeps BKK's own trip as [Vehicle.bkkTripId] for trip details.
 */
object LiveVehicleMerge {
    fun merge(worker: List<WorkerVehicle>, covered: MapBounds?, emma: List<Vehicle>): List<Vehicle> {
        if (worker.isEmpty()) return emma
        val emmaById = HashMap<String, Vehicle>(emma.size * 2)
        val railByNumber = HashMap<Pair<String, String>, Vehicle>()
        // A BKK-only train/HÉV adopts EMMA's route for the same line, so the line list and line
        // details keep one entry per line instead of a parallel `BKK:` copy.
        val railRoutes = HashMap<Pair<String, String>, String>()
        for (vehicle in emma) {
            emmaById[vehicle.id] = vehicle
            if (vehicle.providerId in RAIL_PROVIDERS && vehicle.id.startsWith("emma:1:"))
                railByNumber[vehicle.providerId to vehicle.id.removePrefix("emma:1:")] = vehicle
            val number = vehicle.routeNumber
            if (vehicle.providerId in RAIL_PROVIDERS && number != null && vehicle.routeId != null)
                railRoutes.putIfAbsent(vehicle.providerId to number, vehicle.routeId)
        }
        val consumed = HashSet<String>()
        val seen = HashSet<String>()
        val numbers = HashSet<String>()
        val result = ArrayList<Vehicle>(emma.size + worker.size)
        for (candidate in worker) {
            if (candidate.fallback) continue
            val vehicle = candidate.vehicle
            val merged = if (vehicle.providerId in setOf("bkk", "volan")) {
                val existing = emmaById[vehicle.id]?.takeIf { it.providerId == vehicle.providerId }
                existing?.let { consumed += it.id }
                if (vehicle.providerId == "volan" && existing != null) vehicle.copy(
                    tripId = existing.tripId ?: vehicle.tripId,
                    routeId = existing.routeId?.takeIf { BkkWorkerParser.workerRouteId(it) == BkkWorkerParser.workerRouteId(vehicle.routeId.orEmpty()) }
                        ?: vehicle.routeId,
                ) else vehicle
            } else {
                val matches = candidate.numbers.mapNotNull { railByNumber[vehicle.providerId to it] }.distinct()
                consumed += matches.map { it.id }
                matches.firstOrNull()?.let { source ->
                    preserveFresherPosition(source.copy(position = vehicle.position, observedAt = vehicle.observedAt,
                        heading = vehicle.heading ?: source.heading, speedKmh = null,
                        destination = source.destination ?: vehicle.destination,
                        routeNumber = source.routeNumber ?: vehicle.routeNumber,
                        registration = source.registration ?: vehicle.registration,
                        // Position from BKK, trip/route details from EMMA: labelled as exactly that.
                        positionSource = vehicle.positionSource ?: DataSource.BKK,
                        detailsSource = source.detailsSource ?: DataSource.EMMA,
                        bkkTripId = vehicle.bkkTripId), source)
                } ?: vehicle.routeNumber?.let { railRoutes[vehicle.providerId to it] }?.let { vehicle.copy(routeId = it) } ?: vehicle
            }
            val fresh = emmaById[merged.id]?.let { preserveFresherPosition(merged, it) } ?: merged
            if (seen.add(fresh.id)) { result += fresh; numbers += candidate.numbers }
        }
        for (candidate in worker) {
            if (!candidate.fallback) continue
            val id = candidate.vehicle.id
            // Direct EMMA is the same source with richer fields; BKK numbers win any overlap.
            if (id in seen || id in emmaById || candidate.numbers.any { it in numbers }) continue
            seen += id; result += candidate.vehicle
        }
        for (vehicle in emma) {
            if (vehicle.id in consumed || vehicle.id in seen) continue
            result += vehicle
        }
        return result
    }

    private val RAIL_PROVIDERS = setOf("hev", "mav")
    // Relay timestamps can differ slightly; retain BKK priority unless its telemetry is a minute behind.
    private fun preserveFresherPosition(primary: Vehicle, fallback: Vehicle): Vehicle =
        if (fallback.observedAt.isAfter(primary.observedAt.plusSeconds(60))) primary.copy(
            position = fallback.position, observedAt = fallback.observedAt, heading = fallback.heading,
            speedKmh = fallback.speedKmh, routeEstimate = fallback.routeEstimate,
            positionSource = fallback.positionSource ?: DataSource.EMMA,
        ) else primary
}
