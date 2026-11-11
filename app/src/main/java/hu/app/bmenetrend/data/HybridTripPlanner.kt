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
    private data class RemoteKey(val from: GeoPoint, val to: GeoPoint, val time: Instant, val bus: Boolean, val plain: Boolean)
    internal enum class Stage { DIRECT, LOCAL_STARTED, LOCAL, GATEWAY_STARTED, GATEWAY }
    private data class Update(val plan: TripPlan? = null, val direct: Boolean = false,
                              val complete: Boolean = false, val failure: Exception? = null, val local: Boolean = false,
                              val localStarted: Boolean = false, val gatewayStarted: Boolean = false)

    suspend fun plan(
        origin: PlannerEndpoint, destination: PlannerEndpoint, requested: Instant, ready: Instant,
        nationalStops: List<TransitStop>, loadNetwork: suspend () -> TransitNetwork,
        localProviders: Set<String> = LocalPlanningService.providerIds,
        onProgress: (TripPlan) -> Unit = {},
    ): TripPlan = withContext(dispatcher) { coroutineScope {
        // Candidates stay private until one complete, feasible snapshot is selected. A good
        // direct answer never waits for walking, local verification or gateway fan-out.
        // The settle timer must not be a child of withTimeoutOrNull below, which would wait
        // for it before returning even after every direct answer has arrived.
        val outer = this
        val updates = Channel<Update>(Channel.UNLIMITED)
        val searchJob = launch {
            try {
                val complete = search(origin, destination, requested, ready, nationalStops, loadNetwork, localProviders) { plan, stage ->
                    updates.trySend(Update(plan.takeUnless { stage == Stage.LOCAL_STARTED }, direct = stage == Stage.DIRECT,
                        local = stage == Stage.LOCAL, localStarted = stage == Stage.LOCAL_STARTED,
                        gatewayStarted = stage == Stage.GATEWAY_STARTED))
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
        var directAnswers = 0
        // Short local rides (ViaBusz etc.) come from the local timetable search, which may
        // finish after both direct answers. Once it has started (network loaded, local stops
        // near an endpoint), wait for it up to LOCAL_WAIT_MILLIS. A hanging network load
        // never delays a good direct answer.
        var localPending = false
        var localDone = false
        var gatewayPending = false
        try {
            val chosen = withTimeoutOrNull(searchTimeoutMillis) {
                while (true) {
                    val update = select<Update?> {
                        updates.onReceive { it }
                        settle?.onAwait { null }
                    } ?: return@withTimeoutOrNull requireNotNull(best)
                    if (update.direct) directAnswers++
                    if (update.local) { localPending = false; localDone = true }
                    // Local search done; combined local + Volán/rail/BKK journeys follow. Give
                    // them their own bounded window; the search's completion releases at once.
                    if (update.gatewayStarted) {
                        localPending = true; localDone = true; gatewayPending = true
                        if (settle != null) {
                            val wait = maxOf(GATEWAY_WAIT_MILLIS, if (PlannerAlternatives.mayImprove(best?.itineraries.orEmpty()))
                                PlannerAlternatives.WALK_AVERSE_TIMEOUT_MILLIS else 0L)
                            settle?.cancel(); settle = outer.async { delay(wait) }
                        }
                    }
                    if (update.localStarted && !localDone) {
                        localPending = true
                        // Give the local search its own bounded window, even if a shorter one already started.
                        if (settle != null) {
                            val longWalk = PlannerAlternatives.mayImprove(best?.itineraries.orEmpty())
                            val wait = maxOf(LOCAL_WAIT_MILLIS, if (longWalk) PlannerAlternatives.WALK_AVERSE_TIMEOUT_MILLIS else 0L)
                            settle?.cancel(); settle = outer.async { delay(wait) }
                        }
                        continue
                    }
                    update.plan?.let { plan ->
                        latest = plan
                        val choices = TripPlanner.timelyChoices(plan.itineraries + best?.itineraries.orEmpty(), ready)
                        if (choices.isNotEmpty()) best = plan.copy(itineraries = choices)
                    }
                    if (best != null && (update.complete || directAnswers >= 2 && !localPending)) return@withTimeoutOrNull requireNotNull(best)
                    if (update.complete) {
                        update.failure?.let { throw it }
                        return@withTimeoutOrNull requireNotNull(latest).copy(itineraries = emptyList())
                    }
                    // Allow the other direct query a short chance to supply a better or
                    // different choice. Start once; gateway updates cannot prolong it.
                    // A long-walk answer may still be enriched by the other query's walk-averse search.
                    if (best != null && settle == null) {
                        val wait = maxOf(localSettleMillis,
                            if (PlannerAlternatives.mayImprove(best!!.itineraries)) PlannerAlternatives.WALK_AVERSE_TIMEOUT_MILLIS else 0L,
                            if (gatewayPending) GATEWAY_WAIT_MILLIS else if (localPending) LOCAL_WAIT_MILLIS else 0L)
                        settle = outer.async { delay(wait) }
                    }
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
        localProviders: Set<String>, onCandidate: (TripPlan, Stage) -> Unit,
    ): TripPlan = withContext(dispatcher) { coroutineScope {
        val journeys = mutableListOf<TripItinerary>()
        var partial = false
        var nationalSucceeded = false
        val remoteRequests = mutableMapOf<RemoteKey, Deferred<List<TripItinerary>>>()
        fun result() = TripPlan(origin, destination, requested, ready, TripPlanner.timelyChoices(journeys, ready), partial)
        // Local gateway IDs can refer to the exact same coordinates as an endpoint.
        // Share identical in-flight/completed calls within this search, with no stale
        // timetable/realtime cache between searches. Bus-only remains a separate query.
        suspend fun remote(from: PlannerEndpoint, to: PlannerEndpoint, time: Instant, bus: Boolean = false, plain: Boolean = false): List<TripItinerary> =
            remoteRequests.getOrPut(RemoteKey(from.position, to.position, time, bus, plain)) { async {
                try { (if (plain) backend.plainRoutes(from, to, time, bus) else backend.routes(from, to, time, bus)).also { nationalSucceeded = true } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { partial = true; emptyList() }
            } }.await()

        // The independent bus search prevents train-heavy source rankings from hiding Volán.
        val direct = listOf(false, true).map { bus -> async {
            remote(origin, destination, ready, bus).also { journeys += it; onCandidate(result(), Stage.DIRECT) }
        } }
        if (localProviders.isEmpty()) {
            onCandidate(result(), Stage.LOCAL)
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
            onCandidate(result(), Stage.LOCAL)
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
        if (access.isNotEmpty() || egress.isNotEmpty()) onCandidate(result(), Stage.LOCAL_STARTED)
        if (access.isEmpty() && egress.isEmpty()) {
            onCandidate(result(), Stage.LOCAL)
            direct.awaitAll()
            check(nationalSucceeded) { "Planning services unavailable" }
            return@coroutineScope result()
        }

        // Only local components reachable at either endpoint need walking-transfer work.
        val relevant = connectedStops(activeStops, network.routes.filter { it.id in active }, access + egress)
        val transferPairs = relevant.flatMap { a -> relevant.filter { b -> a.id != b.id &&
            distance(a.position, b.position) <= TRANSFER_WALK_METERS }.map { b -> a to b } }
        // Precise (BKK) walks are scarce: walks from the origin and to the destination come first,
        // nearest first — those decide whether the first bus is caught, often by seconds.
        val pairs = (access.map { origin.point() to it.point() } + egress.map { it.point() to destination.point() })
            .sortedBy { (a, b) -> distance(a.position, b.position) } +
            transferPairs.map { (a, b) -> a.point() to b.point() }
        val walking = try { backend.walking(pairs, ready) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { WalkingResult(emptyMap(), true) }
        partial = partial || walking.partial
        // Walking services may return nothing for a (near) zero-length walk, e.g. when the
        // destination IS the stop. Without a link that stop would be skipped and the rider sent
        // one stop further to walk back, so such tiny walks are filled in directly.
        fun link(a: PlanPoint, b: PlanPoint): WalkingLink? = walking.links[a.position to b.position]
            ?: distance(a.position, b.position).takeIf { it <= SAME_PLACE_METERS }?.let { meters ->
                WalkingLink(a, b, kotlin.math.ceil(meters / TripPlanner.WALK_SPEED).toLong(), meters)
            }
        val transferLinks = transferPairs.mapNotNull { (a, b) ->
            link(a.point(), b.point())?.let { (a.id to b.id) to it }
        }.toMap()
        val egressLinks = egress.mapNotNull { stop -> link(stop.point(), destination.point())?.let { stop.id to it } }.toMap()
        val router = withContext(worker) { LocalTimetableRouter(network.copy(verifiedProviderIds = network.verifiedProviderIds.intersect(localProviders)), ready) }
        val seeds = access.mapNotNull { stop -> link(origin.point(), stop.point())?.let { walk ->
            LocalPath(stop.id, ready.plusSeconds(walk.seconds), listOf(walk.leg(ready)))
        } }
        val reached = withContext(worker) { router.search(seeds, transferLinks) }
        journeys += withContext(worker) { router.finish(reached, egressLinks) }
        onCandidate(result(), Stage.GATEWAY_STARTED)

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
        // A journey ending after the selector's absolute delay limit (including the walk-sparing
        // allowance) cannot enter the final three. Prune only by this safe time bound, never by operator or distance.
        val latestUsefulArrival = journeys.minOfOrNull { it.end }
            ?.plusSeconds(PlannerAlternatives.LESS_WALK_MAX_DELAY_SECONDS)
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
                val middle = remote(start.endpoint, end.endpoint, time, plain = true)
                for (trip in middle) {
                    val legs = prefix + trip.legs
                    val added = if (end.stop == null) listOfNotNull(TripPlanner.itinerary(legs, ready))
                    else withContext(worker) {
                        router.finish(router.search(listOf(LocalPath(end.stop.id, trip.end, legs)), transferLinks), egressLinks)
                    }
                    journeys += added
                }
                onCandidate(result(), Stage.GATEWAY)
            } }.awaitAll()
        check(nationalSucceeded || journeys.isNotEmpty()) { "Planning services unavailable" }
        result()
    } }

    private fun gateways(stops: List<TransitStop>, references: List<TransitStop>, toward: GeoPoint,
                         cost: (TransitStop) -> Double): List<TransitStop> {
        val candidates = stops.mapNotNull { stop ->
            val nearby = references.filter { kotlin.math.abs(it.position.lat - stop.position.lat) < .004 &&
                kotlin.math.abs(it.position.lon - stop.position.lon) < .006 && distance(it.position, stop.position) <= GATEWAY_METERS }
            // Real interchanges sit right next to a national stop: prefer them, and only call a
            // stop a rail interchange when a platform is close (e.g. Biatorbágy Vasútállomás at
            // 37 m, not a school 327 m from the line).
            if (nearby.isEmpty()) null else Triple(stop, cost(stop) + distance(stop.position, toward) / 15.0 +
                nearby.minOf { distance(it.position, stop.position) } / 2.0,
                nearby.any { (RailSupport.isTrain(it.transportMode) || it.transportMode == "SUBURBAN_RAILWAY") &&
                    distance(it.position, stop.position) <= RAIL_GATEWAY_METERS })
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
        /** A local stop counts as a rail interchange only this close to a platform. */
        private const val RAIL_GATEWAY_METERS = 150.0
        /** Endpoints this close to a stop are reached without a routed walk. */
        private const val SAME_PLACE_METERS = 40.0
        private val LOCAL_PROVIDERS = setOf("viabusz", "kanyargo", "homm")
        /** Longest a search waits for the local timetable search after the first usable answer. */
        const val LOCAL_WAIT_MILLIS = 2_500L
        /** Longest a search waits for combined local + national journeys once local search finished. */
        const val GATEWAY_WAIT_MILLIS = 3_000L
        private fun distance(a: GeoPoint, b: GeoPoint) = StopClusters.distanceMeters(a, b)
        private fun PlannerEndpoint.point() = PlanPoint(name, position)
        private fun TransitStop.endpoint() = PlannerEndpoint(id, name, platformCode.orEmpty(), position)
    }
}
