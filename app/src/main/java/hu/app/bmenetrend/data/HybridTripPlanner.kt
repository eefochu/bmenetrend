package hu.app.bmenetrend.data

import hu.app.bmenetrend.data.LocalTimetableRouter.Companion.point
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

/** Join reviewed local timetables to national services using independently routed street walks. */
internal class HybridTripPlanner(private val backend: PlannerBackend) {
    // Serialize orchestration state while keeping HTTP asynchronous and routing off the UI thread.
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private data class Start(val endpoint: PlannerEndpoint, val path: LocalPath?)
    private data class End(val endpoint: PlannerEndpoint, val stop: TransitStop?)

    suspend fun plan(
        origin: PlannerEndpoint, destination: PlannerEndpoint, requested: Instant, ready: Instant,
        nationalStops: List<TransitStop>, loadNetwork: suspend () -> TransitNetwork,
        onProgress: (TripPlan) -> Unit = {},
    ): TripPlan = withContext(dispatcher) { coroutineScope {
        val journeys = mutableListOf<TripItinerary>()
        var partial = false
        var nationalSucceeded = false
        fun result() = TripPlan(origin, destination, requested, ready, TripPlanner.alternatives(journeys), partial)
        suspend fun remote(from: PlannerEndpoint, to: PlannerEndpoint, time: Instant, bus: Boolean = false): List<TripItinerary> = try {
            backend.routes(from, to, time, bus).also { nationalSucceeded = true }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { partial = true; emptyList() }

        // The independent bus search prevents train-heavy source rankings from hiding Volán.
        val direct = listOf(false, true).map { bus -> async {
            remote(origin, destination, ready, bus).also { journeys += it; onProgress(result()) }
        } }
        val local = async {
            try { loadNetwork() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { partial = true; null }
        }
        direct.awaitAll()
        val network = local.await()
        if (network == null) {
            check(nationalSucceeded || journeys.isNotEmpty()) { "Planning services unavailable" }
            return@coroutineScope result()
        }
        val day = ready.atZone(TripPlanner.zone).toLocalDate()
        val nearby = network.stops.filter { stop ->
            minOf(distance(stop.position, origin.position), distance(stop.position, destination.position)) <= ACCESS_METERS
        }
        val neededProviders = nearby.flatMap { it.providerIds }.toSet()
        if (neededProviders.any { provider -> !ServiceCalendar.isYearReviewed(day.year) ||
                day > java.time.LocalDate.of(2026, 12, 31) || provider !in network.verifiedProviderIds &&
                listOf(day, day.plusDays(1)).any { LocalTimetableRouter.calendar(provider, it) != null } }) partial = true
        val active = network.routes.filter { route -> route.providerId in network.verifiedProviderIds &&
            listOf(day, day.plusDays(1)).any { LocalTimetableRouter.calendar(route.providerId, it) != null } }.map { it.id }.toSet()
        val activeStops = network.stops.filter { stop -> stop.routeIds.any { it in active } }
        val access = activeStops.filter { distance(it.position, origin.position) <= ACCESS_METERS }
        val egress = activeStops.filter { distance(it.position, destination.position) <= ACCESS_METERS }
        if (access.isEmpty() && egress.isEmpty()) {
            check(nationalSucceeded) { "Planning services unavailable" }
            return@coroutineScope result()
        }

        // Only local components reachable at either endpoint need walking-transfer work.
        val relevant = connectedStops(activeStops, network.routes.filter { it.id in active }, access + egress)
        val transferPairs = relevant.flatMap { a -> relevant.filter { b -> a.id != b.id &&
            distance(a.position, b.position) <= TRANSFER_WALK_METERS }.map { b -> a to b } }
        val pairs = access.map { origin.point() to it.point() } + egress.map { it.point() to destination.point() } +
            transferPairs.map { (a, b) -> a.point() to b.point() }
        val walking = try { backend.walking(pairs, ready) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { WalkingResult(emptyMap(), true) }
        partial = partial || walking.partial
        val transferLinks = transferPairs.mapNotNull { (a, b) ->
            walking.links[a.position to b.position]?.let { (a.id to b.id) to it }
        }.toMap()
        val egressLinks = egress.mapNotNull { stop -> walking.links[stop.position to destination.position]?.let { stop.id to it } }.toMap()
        val router = withContext(Dispatchers.Default) { LocalTimetableRouter(network, ready) }
        val seeds = access.mapNotNull { stop -> walking.links[origin.position to stop.position]?.let { walk ->
            LocalPath(stop.id, ready.plusSeconds(walk.seconds), listOf(walk.leg(ready)))
        } }
        val reached = withContext(Dispatchers.Default) { router.search(seeds, transferLinks) }
        journeys += withContext(Dispatchers.Default) { router.finish(reached, egressLinks) }
        onProgress(result())

        // Preserve exact source identities: proximity selects a gateway, never merges its board.
        val indexed = nationalStops.filter { it.id.startsWith("emma:") && !it.station }.ifEmpty {
            // Planning must also work before the map's nationwide index finishes loading.
            val bounds = MapBounds(relevant.minOf { it.position.lon } - .006, relevant.minOf { it.position.lat } - .004,
                relevant.maxOf { it.position.lon } + .006, relevant.maxOf { it.position.lat } + .004)
            try { backend.stops(bounds).filterNot { it.station } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { partial = true; emptyList() }
        }
        val references = indexed + journeys.flatMap { trip ->
            trip.legs.filterNot { it.walking }.filter { it.route?.providerId !in LOCAL_PROVIDERS }.flatMap { leg ->
                listOf(leg.from, leg.to).map { point -> TransitStop("gateway:${point.name}", point.name, point.position,
                    emptyList(), transportMode = leg.mode) }
            }
        }
        val starts = listOf(Start(origin, null)) + gateways(relevant.filter { stop ->
            reached[stop.id].orEmpty().any { it.boardings > 0 }
        }, references, destination.position) { stop ->
            reached.getValue(stop.id).filter { it.boardings > 0 }.minOf { Duration.between(ready, it.arrival).seconds.toDouble() }
        }.map { stop -> Start(stop.endpoint(), reached.getValue(stop.id).filter { it.boardings > 0 }.minBy { it.arrival }) }
        val ends = listOf(End(destination, null)) + gateways(connectedStops(activeStops,
            network.routes.filter { it.id in active }, egress), references, origin.position) { stop ->
            distance(stop.position, destination.position) / 5.0
        }.map { End(it.endpoint(), it) }

        starts.flatMap { start -> ends.map { end -> start to end } }.filterNot { (a, b) -> a.path == null && b.stop == null }
            .map { (start, end) -> async {
                val prefix = start.path?.legs.orEmpty()
                // EMMA applies a 60s initial boarding allowance. Add the remaining 120s at a local/national boundary.
                val time = start.path?.arrival?.plusSeconds(TripPlanner.TRANSFER_SECONDS - TripPlanner.BOARD_SECONDS) ?: ready
                val middle = remote(start.endpoint, end.endpoint, time)
                for (trip in middle) {
                    val legs = prefix + trip.legs
                    val added = if (end.stop == null) listOfNotNull(TripPlanner.itinerary(legs, ready))
                    else withContext(Dispatchers.Default) {
                        router.finish(router.search(listOf(LocalPath(end.stop.id, trip.end, legs)), transferLinks), egressLinks)
                    }
                    journeys += added
                }
                onProgress(result())
            } }.awaitAll()
        check(nationalSucceeded || journeys.isNotEmpty()) { "Planning services unavailable" }
        result()
    } }

    private fun gateways(stops: List<TransitStop>, references: List<TransitStop>, toward: GeoPoint,
                         cost: (TransitStop) -> Double): List<TransitStop> {
        val candidates = stops.mapNotNull { stop ->
            val nearby = references.filter { kotlin.math.abs(it.position.lat - stop.position.lat) < .004 &&
                kotlin.math.abs(it.position.lon - stop.position.lon) < .006 && distance(it.position, stop.position) <= GATEWAY_METERS }
            if (nearby.isEmpty()) null else Triple(stop, cost(stop) + distance(stop.position, toward) / 15.0,
                nearby.any { RailSupport.isTrain(it.transportMode) || it.transportMode == "SUBURBAN_RAILWAY" })
        }.sortedBy { it.second }
        val selected = mutableListOf<TransitStop>()
        // Rail and road interchanges both participate; nearby opposite platforms do not consume all slots.
        (listOfNotNull(candidates.firstOrNull(), candidates.firstOrNull { it.third }, candidates.firstOrNull { !it.third }) + candidates)
            .forEach { candidate ->
                if (selected.size < 3 && selected.none { distance(it.position, candidate.first.position) < 120 }) selected += candidate.first
            }
        return selected
    }

    private fun connectedStops(stops: List<TransitStop>, routes: List<TransitRoute>, seeds: List<TransitStop>): List<TransitStop> {
        val ids = seeds.map { it.id }.toMutableSet()
        do {
            val count = ids.size
            routes.filter { route -> route.directions.any { direction -> direction.stopIds.any { it in ids } } }
                .forEach { route -> ids += route.directions.flatMap { it.stopIds } }
            val reached = stops.filter { it.id in ids }
            ids += stops.filter { stop -> stop.id !in ids && reached.any {
                distance(it.position, stop.position) <= TRANSFER_WALK_METERS
            } }.map { it.id }
        } while (count != ids.size)
        return stops.filter { it.id in ids }
    }

    companion object {
        private const val ACCESS_METERS = 1_200.0
        private const val TRANSFER_WALK_METERS = 200.0
        private const val GATEWAY_METERS = 400.0
        private val LOCAL_PROVIDERS = setOf("viabusz", "kanyargo", "homm")
        private fun distance(a: GeoPoint, b: GeoPoint) = StopClusters.distanceMeters(a, b)
        private fun PlannerEndpoint.point() = PlanPoint(name, position)
        private fun TransitStop.endpoint() = PlannerEndpoint(id, name, platformCode.orEmpty(), position)
    }
}
