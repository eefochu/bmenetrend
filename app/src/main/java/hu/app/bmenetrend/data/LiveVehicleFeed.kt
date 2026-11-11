package hu.app.bmenetrend.data

import hu.app.bmenetrend.ui.TransitCatalogueIndexes
import hu.app.bmenetrend.sameOr
import java.time.Duration
import java.time.Instant

/**
 * Merges the BKK Worker, EMMA and local-operator feeds into the list the map draws. Thousands of
 * vehicles are merged, named, age-filtered and compared here, on one background lane, instead of in
 * the main-thread UI recalculation that runs every few seconds. Not thread-safe: use a single lane.
 */
internal class LiveVehicleFeed {
    class Inputs(
        /** Local operators (ViaBusz, KanyarGO, HOMM) from the timetable refresh. */
        val local: List<Vehicle>,
        val emma: EmmaRepository.Result?,
        val bkk: BkkWorkerRepository.VehicleSnapshot?,
        val networkStops: List<TransitStop>,
        val emmaStops: List<TransitStop>,
        val nationwide: Boolean,
        val bundledRoutes: List<EmmaParser.Route>,
    )

    class Result(
        val vehicles: List<Vehicle>,
        /** EMMA-provider vehicles, which feed the live route catalogue. */
        val routeVehicles: List<Vehicle>,
    )

    private var mergedSources: Triple<Any?, Any?, Any?>? = null
    private var merged = emptyList<Vehicle>()
    private var routeVehicles = emptyList<Vehicle>()
    private var lastVehicles: List<Vehicle>? = null

    fun update(inputs: Inputs, now: Instant, previous: List<Vehicle>): Result {
        val emma = mergeRemote(inputs, now).let {
            if (inputs.nationwide) it else EmmaParser.localVehicles(it, inputs.bundledRoutes)
        }
        val vehicles = (inputs.local + emma).filter {
            Duration.between(it.observedAt, now).seconds in -30..180
        }.map { if (it.routeEstimate != null && Duration.between(it.observedAt, now).seconds > 90) it.copy(routeEstimate = null) else it }
            // Unchanged positions keep the list identity, so the map does not re-serialize every vehicle.
            .sameOr(previous)
        if (vehicles !== lastVehicles) {
            lastVehicles = vehicles
            routeVehicles = vehicles.filter { it.providerId in EMMA_PROVIDERS }.sameOr(routeVehicles)
        }
        return Result(vehicles, routeVehicles)
    }

    /** Identity-memoized: the merge reruns only when a feed or the stop catalogue changes. */
    private fun mergeRemote(inputs: Inputs, now: Instant): List<Vehicle> {
        val bkk = inputs.bkk?.takeIf { Duration.between(it.fetchedAt, now).seconds in 0..BKK_MAX_AGE_SECONDS }
        val sources = Triple(bkk, inputs.emma, inputs.emmaStops)
        mergedSources?.let { previous ->
            if (previous.first === bkk && previous.second === inputs.emma && previous.third === inputs.emmaStops) return merged
        }
        val combined = LiveVehicleMerge.merge(bkk?.vehicles.orEmpty(), bkk?.covered, inputs.emma?.vehicles.orEmpty())
        merged = if (bkk == null) combined else {
            val stops = TransitCatalogueIndexes.stops(inputs.networkStops, inputs.emmaStops).byId
            combined.map { vehicle ->
                val stop = vehicle.nextStopId?.takeIf { vehicle.nextStopName == null }?.let { stops[it] }
                if (stop == null) vehicle else vehicle.copy(nextStopName = stop.name, nextStopPoint = vehicle.nextStopPoint ?: stop.position)
            }
        }
        mergedSources = sources
        return merged
    }

    companion object {
        val EMMA_PROVIDERS = setOf("volan", "bkk", "hev", "mav")
        const val BKK_MAX_AGE_SECONDS = 30L
    }
}
