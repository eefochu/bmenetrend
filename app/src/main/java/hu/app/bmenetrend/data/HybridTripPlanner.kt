package hu.app.bmenetrend.data

import hu.app.bmenetrend.data.LocalTimetableRouter.Companion.point
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

/** Join reviewed local timetables to national services using independently routed street walks. */
internal class HybridTripPlanner(
    private val backend: PlannerBackend,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val localSettleMillis: Long = 500,
    private val searchTimeoutMillis: Long = 10_000,
) {
    // Serialize orchestration state while keeping HTTP asynchronous and routing off the UI thread.
    private val dispatcher = worker.limitedParallelism(1)
    private data class Start(val endpoint: PlannerEndpoint, val path: LocalPath?)
    private data class End(val endpoint: PlannerEndpoint, val stop: TransitStop?)
    private data class RemoteKey(val from: GeoPoint, val to: GeoPoint, val time: Instant, val bus: Boolean)
    private data class Update(val plan: TripPlan? = null, val direct: Boolean = false,
                              val complete: Boolean = false, val failure: Exception? = null)

    suspend fun plan(
        origin: PlannerEndpoint, destination: PlannerEndpoint, requested: Instant, ready: Instant,
        nationalStops: List<TransitStop>, loadNetwork: suspend () -> TransitNetwork,
        localProviders: Set<String> = LocalPlanningService.providerIds,
        onProgress: (TripPlan) -> Unit = {},
    ): TripPlan = withContext(dispatcher) { coroutineScope {
        // Candidates stay private until one complete, feasible snapshot is selected. A good
        // direct answer never waits for walking, local verification or gateway fan-out.
        val updates = Channel<Update>(Channel.UNLIMITED)
        val searchJob = launch {
            try {
                val complete = search(origin, destination, requested, ready, nationalStops, loadNetwork, localProviders) { plan, direct ->
                    updates.trySend(Update(plan, direct))
                }
                updates.send(Update(complete, complete = true))
            } catch (cancelled: CancellationException) {
                updates.close(cancelled)
                throw cancelled
            } catch (failure: Exception) { updates.send(Update(complete = true, failure = failure)) }
        }
        var best: TripPlan? = null
        var latest: TripPlan? = null
        var settle: Deferred<Unit>? = null
        try {
            val chosen = withTimeoutOrNull(searchTimeoutMillis) {
                while (true) {
                    val update = select<Update?> {
                        updates.onReceive { it }
                        settle?.onAwait { null }
                    } ?: return@withTimeoutOrNull requireNotNull(best)
                    update.plan?.let { plan ->
                        latest = plan
                        TripPlanner.bestTimely(plan.itineraries + best?.itineraries.orEmpty(), ready)?.let { trip ->
                            best = plan.copy(itineraries = listOf(trip))
                        }
                    }
                    if (best != null && (update.direct || update.complete)) return@withTimeoutOrNull requireNotNull(best)
                    if (update.complete) {
                        update.failure?.let { throw it }
                        return@withTimeoutOrNull requireNotNull(latest).copy(itineraries = emptyList())
                    }
                    // Give an in-flight direct answer a brief chance to beat a local-only
                    // candidate. Start this timer once; gateway updates cannot prolong it.
                    if (best != null && settle == null) settle = async { delay(localSettleMillis) }
                }
                @Suppress("UNREACHABLE_CODE")
                error("Unreachable")
            } ?: best?.copy(partial = true) ?: error("Planning services unavailable")
            // Compatibility callback is a completion notification, never a stream of plans.
            onProgress(chosen)
            chosen
        } finally {
            settle?.cancelAndJoin()
            searchJob.cancelAndJoin()
            updates.close()
        }
    } }

    private suspend fun search(
        origin: PlannerEndpoint, destination: PlannerEndpoint, requested: Instant, ready: Instant,
        nationalStops: List<TransitStop>, loadNetwork: suspend () -> TransitNetwork,
        localProviders: Set<String>, onCandidate: (TripPlan, Boolean) -> Unit,
    ): TripPlan = withContext(dispatcher) { coroutineScope {
        val journeys = mutableListOf<TripItinerary>()
        var partial = false
        var nationalSucceeded = false
        val remoteRequests = mutableMapOf<RemoteKey, Deferred<List<TripItinerary>>>()
        fun result() = TripPlan(origin, destination, requested, ready, TripPlanner.alternatives(journeys, ready), partial)
        // Local gateway IDs can refer to the exact same coordinates as an endpoint.
        // Share identical in-flight/completed calls within this search, with no stale
        // timetable/realtime cache between searches. Bus-only remains a separate query.
        suspend fun remote(from: PlannerEndpoint, to: PlannerEndpoint, time: Instant, bus: Boolean = false): List<TripItinerary> =
            remoteRequests.getOrPut(RemoteKey(from.position, to.position, time, bus)) { async {
                try { backend.routes(from, to, time, bus).also { nationalSucceeded = true } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { partial = true; emptyList() }
            } }.await()

        // The independent bus search prevents train-heavy source rankings from hiding Volán.
        val direct = listOf(false, true).map { bus -> async {
            remote(origin, destination, ready, bus).also { journeys += it; onCandidate(result(), true) }
        } }
        if (localProviders.isEmpty()) {
            direct.awaitAll()
            check(nationalSucceeded) { "Planning services unavailable" }
            return@coroutineScope result()
        }
        val local = async {
            try { loadNetwork() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { partial = true; null }
        }
        val network = local.await()
        if (network == null) {
            direct.awaitAll()
            check(nationalSucceeded || journeys.isNotEmpty()) { "Planning services unavailable" }
            return@coroutineScope result()
        }
        val day = ready.atZone(TripPlanner.zone).toLocalDate()
        val nearby = network.stops.filter { stop ->
            minOf(distance(stop.position, origin.position), distance(stop.position, destination.position)) <= ACCESS_METERS
        }
        val neededProviders = nearby.flatMap { it.providerIds }.toSet().intersect(localProviders)
        if (neededProviders.any { provider -> !ServiceCalendar.isYearReviewed(day.year) ||
                day > java.time.LocalDate.of(2026, 12, 31) || provider !in network.verifiedProviderIds &&
                listOf(day, day.plusDays(1)).any { LocalTimetableRouter.calendar(provider, it) != null } }) partial = true
        val active = network.routes.filter { route -> route.providerId in localProviders && route.providerId in network.verifiedProviderIds &&
            listOf(day, day.plusDays(1)).any { LocalTimetableRouter.calendar(route.providerId, it) != null } }.map { it.id }.toSet()
        val activeStops = network.stops.filter { stop -> stop.routeIds.any { it in active } }
        val access = activeStops.filter { distance(it.position, origin.position) <= ACCESS_METERS }
        val egress = activeStops.filter { distance(it.position, destination.position) <= ACCESS_METERS }
        if (access.isEmpty() && egress.isEmpty()) {
            direct.awaitAll()
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
        val router = withContext(worker) { LocalTimetableRouter(network.copy(verifiedProviderIds = network.verifiedProviderIds.intersect(localProviders)), ready) }
        val seeds = access.mapNotNull { stop -> walking.links[origin.position to stop.position]?.let { walk ->
            LocalPath(stop.id, ready.plusSeconds(walk.seconds), listOf(walk.leg(ready)))
        } }
        val reached = withContext(worker) { router.search(seeds, transferLinks) }
        journeys += withContext(worker) { router.finish(reached, egressLinks) }
        onCandidate(result(), false)

        // Local access/walking does not depend on either national search. Join only when
        // their stop references are needed, so a slow bus/rail query cannot hold it up.
        direct.awaitAll()

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
        // A journey ending after the selector's absolute delay limit cannot enter the
        // final three. Prune only by this safe time bound, never by operator or distance.
        val latestUsefulArrival = journeys.minOfOrNull { it.end }
            ?.plusSeconds(PlannerAlternatives.MAX_ARRIVAL_DELAY_SECONDS)
        fun useful(path: LocalPath) = path.boardings > 0 &&
            (latestUsefulArrival == null || path.arrival <= latestUsefulArrival)
        val starts = listOf(Start(origin, null)) + gateways(relevant.filter { stop ->
            reached[stop.id].orEmpty().any(::useful)
        }, references, destination.position) { stop ->
            reached.getValue(stop.id).filter(::useful).minOf { Duration.between(ready, it.arrival).seconds.toDouble() }
        }.map { stop -> Start(stop.endpoint(), reached.getValue(stop.id).filter(::useful).minBy { it.arrival }) }
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
                    else withContext(worker) {
                        router.finish(router.search(listOf(LocalPath(end.stop.id, trip.end, legs)), transferLinks), egressLinks)
                    }
                    journeys += added
                }
                onCandidate(result(), false)
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
