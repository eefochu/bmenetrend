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
 *    called nor awaited.
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
    private data class Key(val from: GeoPoint, val to: GeoPoint, val busesOnly: Boolean)
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

    override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
        if (!BkkWorkerRepository.plannable(from.position) || !BkkWorkerRepository.plannable(to.position))
            return emma.routes(from, to, ready, busesOnly)
        val key = Key(from.position, to.position, busesOnly)
        cachedRoutes(key, ready)?.let { return it }
        val primary = bkkRoutes(from, to, ready, busesOnly)
        if (primary != null && primary.itineraries.isNotEmpty() && primary.rejected == 0)
            return remember(key, ready, primary.itineraries)
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
