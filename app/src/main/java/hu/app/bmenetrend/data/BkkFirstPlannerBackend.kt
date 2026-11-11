package hu.app.bmenetrend.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant

/** BKK's planner as seen by [BkkFirstPlannerBackend]; tests substitute a fake. */
internal interface BkkPlannerSource {
    suspend fun plan(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): BkkWorkerParser.PlanResult
    suspend fun walking(from: PlanPoint, to: PlanPoint, ready: Instant): WalkingLink?
    /** Stops of a bounded, grid-snapped area ([BkkWorkerRepository.queryArea]). */
    suspend fun stops(area: MapBounds): List<TransitStop>
    /** Planner calls the client budget still allows this minute. */
    fun planHeadroom(): Int

    companion object {
        fun of(repository: BkkWorkerRepository): BkkPlannerSource = object : BkkPlannerSource {
            override suspend fun plan(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean) =
                repository.planResult(from, to, ready, busesOnly)
            override suspend fun walking(from: PlanPoint, to: PlanPoint, ready: Instant) = repository.walking(from, to, ready)
            override suspend fun stops(area: MapBounds) = repository.areaStops(area).stops
            override fun planHeadroom() = repository.planHeadroom()
        }
    }
}

/**
 * BKK-first planning for any endpoints the Worker accepts (all of Hungary), bus-only included.
 *
 *  - A healthy, non-empty BKK answer is returned at once: EMMA's national planner is neither
 *    called nor awaited, unless every BKK journey walks a long way; then EMMA's walk-averse
 *    search is merged in, bounded by [PlannerAlternatives.WALK_AVERSE_TIMEOUT_MILLIS].
 *  - EMMA is asked only when BKK fails, times out (after [bkkTimeoutMillis]), is over its request
 *    budget, answers with nothing usable, or offered itineraries the app could not represent
 *    (malformed or unsupported legs). Its rows then follow BKK's; identical journeys appear once.
 *  - A completed result is reused briefly for the same endpoints, re-checked against the new ready
 *    time; reuse makes no network request.
 *  - Walking links and stop discovery also try BKK first, falling back to EMMA per missing item.
 */
internal class BkkFirstPlannerBackend(
    private val emma: PlannerBackend,
    private val bkk: BkkPlannerSource,
    private val bkkTimeoutMillis: Long = 5_000,
    private val clock: () -> Instant = Instant::now,
) : PlannerBackend {
    private data class Key(val from: GeoPoint, val to: GeoPoint, val busesOnly: Boolean, val enriched: Boolean)
    private class Entry(val ready: Instant, val fetched: Instant, val rows: List<TripItinerary>)
    private val recent = object : LinkedHashMap<Key, Entry>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?) = size > 8
    }
    private val walks = object : LinkedHashMap<WalkKey, WalkingLink>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<WalkKey, WalkingLink>?) = size > 256
    }
    private val walkRequests = Semaphore(2)

    override fun clearRouteCache() {
        synchronized(recent) { recent.clear() }
        emma.clearRouteCache()
    }

    override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> =
        routes(from, to, ready, busesOnly, enrich = true)

    override suspend fun plainRoutes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> =
        routes(from, to, ready, busesOnly, enrich = false)

    private suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean, enrich: Boolean): List<TripItinerary> {
        if (!BkkWorkerRepository.plannable(from.position) || !BkkWorkerRepository.plannable(to.position))
            return emma.routes(from, to, ready, busesOnly)
        val key = Key(from.position, to.position, busesOnly, enrich)
        cachedRoutes(key, ready)?.let { return it }
        val primary = bkkRoutes(from, to, ready, busesOnly)
        if (primary != null && primary.itineraries.isNotEmpty() && primary.rejected == 0) {
            val rows = primary.itineraries
            if (!enrich) return remember(key, ready, rows)
            // Both improvements are bounded and run together, so they add at most one timeout.
            val (averse, connected) = coroutineScope {
                val averse = async { walkAverse(rows, from, to, ready, busesOnly) }
                val connected = async { connectingRides(rows, to, ready, busesOnly) }
                averse.await() to connected.await()
            }
            return remember(key, ready, combine(combine(rows, connected), averse))
        }
        val supplied = primary?.itineraries.orEmpty()
        val national = try { emma.routes(from, to, ready, busesOnly) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (supplied.isEmpty()) throw failure
            // EMMA is down: BKK's usable itineraries are still a real (partial) answer.
            return remember(key, ready, supplied)
        }
        val rows = combine(supplied, national)
        return if (rows.isEmpty()) rows else remember(key, ready, rows)
    }

    /**
     * BKK's router does not mind long walks and the Worker cannot ask it to. When every BKK
     * journey walks [PlannerAlternatives.LONG_WALK_SECONDS] or more, also ask EMMA to avoid
     * walking (bounded); failures or timeouts simply keep BKK's answer.
     */
    private suspend fun walkAverse(rows: List<TripItinerary>, from: PlannerEndpoint, to: PlannerEndpoint,
                                   ready: Instant, busesOnly: Boolean): List<TripItinerary> {
        if (rows.isEmpty()) return emptyList()
        if (rows.minOf { it.walkSeconds } < PlannerAlternatives.LONG_WALK_SECONDS) return emptyList()
        return try { withTimeoutOrNull(PlannerAlternatives.WALK_AVERSE_TIMEOUT_MILLIS) { emma.walkAverseRoutes(from, to, ready, busesOnly) }.orEmpty() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
    }

    /**
     * "Wait for a ride instead of walking": for the earliest journeys ending with a long walk,
     * ask BKK for a connection from the stop where they get off, and splice it in. The rider
     * then waits at that stop for a connecting vehicle rather than walking the rest.
     */
    private suspend fun connectingRides(rows: List<TripItinerary>, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
        if (bkk.planHeadroom() <= RESERVED_PLANS) return emptyList()
        val targets = rows.filter { PlannerAlternatives.finalWalkSeconds(it) >= PlannerAlternatives.EGRESS_WALK_SECONDS }
            .sortedBy { it.end }.distinctBy { trip -> trip.legs.last { !it.walking }.to.position }.take(MAX_CONNECTIONS)
        if (targets.isEmpty()) return emptyList()
        return coroutineScope { targets.map { trip -> async {
            val lastRide = trip.legs.last { !it.walking }
            val stop = PlannerEndpoint("egress:${lastRide.to.name}", lastRide.to.name, "", lastRide.to.position)
            // Leave the app's usual transfer time, so every spliced change is one the rider can make.
            val connectFrom = lastRide.end.plusSeconds(TripPlanner.TRANSFER_SECONDS)
            val tails = try {
                withTimeoutOrNull(PlannerAlternatives.WALK_AVERSE_TIMEOUT_MILLIS) { bkk.plan(stop, to, connectFrom, busesOnly) }?.itineraries.orEmpty()
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
            val walked = PlannerAlternatives.finalWalkSeconds(trip)
            val prefix = trip.legs.subList(0, trip.legs.lastIndexOf(lastRide) + 1)
            tails.filter { tail -> tail.legs.any { !it.walking } && !tail.start.isBefore(lastRide.end) &&
                !tail.legs.first { !it.walking }.start.isBefore(connectFrom) &&
                tail.walkSeconds + MIN_WALK_SAVED_SECONDS <= walked }
                .map { tail -> TripItinerary("${trip.id}+${tail.id}", trip.start, tail.end, prefix + tail.legs, fetchedAt = trip.fetchedAt) }
                // Checked against the requested departure, like every other journey.
                .filter { TripPlanner.feasible(it.legs, ready) }
        } }.awaitAll().flatten() }
    }

    /** Null when BKK failed, timed out or is over its request budget. */
    private suspend fun bkkRoutes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): BkkWorkerParser.PlanResult? =
        try { withTimeoutOrNull(bkkTimeoutMillis) { bkk.plan(from, to, ready, busesOnly) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    private fun remember(key: Key, ready: Instant, rows: List<TripItinerary>): List<TripItinerary> {
        synchronized(recent) { recent[key] = Entry(ready, clock(), rows) }
        return rows
    }

    /** A still-feasible recent answer, or null when nothing reusable remains (then plan again). */
    private fun cachedRoutes(key: Key, ready: Instant): List<TripItinerary>? {
        val cached = synchronized(recent) {
            recent[key]?.takeIf { Duration.between(it.ready, ready).toMillis() in 0..10_000 &&
                Duration.between(it.fetched, clock()).seconds in 0..30 }
        } ?: return null
        return cached.rows.filter { TripPlanner.feasible(it.legs, ready) }.takeIf { it.isNotEmpty() }
    }

    override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult = coroutineScope {
        val distinct = pairs.distinctBy { it.first.position to it.second.position }
        val found = HashMap<WalkKey, WalkingLink>()
        synchronized(walks) {
            distinct.forEach { (a, b) -> walks[a.position to b.position]?.let { found[a.position to b.position] = it.copy(from = a, to = b) } }
        }
        // Walking links are optional detail: keep most of the planner budget for journeys.
        val allowance = (bkk.planHeadroom() - RESERVED_PLANS).coerceIn(0, MAX_BKK_WALKS)
        // The few BKK walks go to the first pairs: callers list the most important first.
        val viaBkk = distinct.filter { (a, b) -> (a.position to b.position) !in found &&
            BkkWorkerRepository.plannable(a.position) && BkkWorkerRepository.plannable(b.position) }.take(allowance)
        viaBkk.map { (a, b) -> async {
            val link = try { walkRequests.withPermit { withTimeoutOrNull(bkkTimeoutMillis) { bkk.walking(a, b, ready) } } }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            if (link != null) (a.position to b.position) to link else null
        } }.awaitAll().filterNotNull().forEach { (key, link) ->
            found[key] = link
            synchronized(walks) { walks[key] = link }
        }
        val missing = distinct.filter { (a, b) -> (a.position to b.position) !in found }
        if (missing.isEmpty()) return@coroutineScope WalkingResult(found)
        val fallback = try { emma.walking(missing, ready) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { WalkingResult(emptyMap(), true) }
        WalkingResult(found + fallback.links, fallback.partial)
    }

    override suspend fun stops(bounds: MapBounds): List<TransitStop> {
        var primary = emptyList<TransitStop>()
        BkkWorkerRepository.queryArea(bounds)?.let { area ->
            try {
                primary = bkk.stops(area).filter { it.position.lat in bounds.south..bounds.north && it.position.lon in bounds.west..bounds.east }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { /* EMMA below. */ }
        }
        // A non-empty BKK response does not establish complete station coverage.
        // Supplement missing identities without replacing any BKK station.
        val fallback = try { emma.stops(bounds) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (primary.isEmpty()) throw failure else emptyList() }
        return CatalogueMerge.upsertStops(primary, fallback) { current, _ -> current == null }
    }

    companion object {
        private const val RESERVED_PLANS = 4
        private const val MAX_BKK_WALKS = 4
        /** Journeys whose final walk is looked at for a connecting ride, per search. */
        private const val MAX_CONNECTIONS = 2
        /** A connecting ride must spare at least this much walking to be offered. */
        private const val MIN_WALK_SAVED_SECONDS = 240L

        /** Same vehicles at the same minutes are one itinerary whichever planner found it. */
        private fun signature(itinerary: TripItinerary) = itinerary.legs.filterNot { it.walking }
            .map { Triple(it.mode, it.route?.number, it.start.epochSecond / 60) }

        fun combine(primary: List<TripItinerary>, secondary: List<TripItinerary>): List<TripItinerary> {
            if (primary.isEmpty()) return secondary
            val known = primary.mapTo(HashSet(), ::signature)
            return primary + secondary.filter { signature(it) !in known }
        }
    }
}
