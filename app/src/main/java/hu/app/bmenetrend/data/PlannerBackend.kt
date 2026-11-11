package hu.app.bmenetrend.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.io.IOException
import java.io.InterruptedIOException

internal typealias WalkKey = Pair<GeoPoint, GeoPoint>
internal data class WalkingResult(val links: Map<WalkKey, WalkingLink>, val partial: Boolean = false)

internal interface PlannerBackend {
    suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean = false): List<TripItinerary>
    suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult
    suspend fun stops(bounds: MapBounds): List<TransitStop> = emptyList()
    /** Like [routes] without slower walk-sparing enrichment: for the many middle segments of combined journeys. */
    suspend fun plainRoutes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean = false): List<TripItinerary> =
        routes(from, to, ready, busesOnly)
    /** Journeys found by a router told to avoid walking; empty when the source has no such mode. */
    suspend fun walkAverseRoutes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean = false): List<TripItinerary> = emptyList()
    fun clearRouteCache() {}
}

/** Bound HTTP work separately from the number of walking aliases being routed. */
internal class PlannerRequestBudget {
    private val requests = Semaphore(4)
    private val walkingBatches = Semaphore(3)

    suspend fun <T> request(block: suspend () -> T): T = requests.withPermit { block() }
    suspend fun <T> walking(block: suspend () -> T): T = walkingBatches.withPermit { request(block) }
}

/** Shared budget for national searches and small, aliased street-walking batches. */
internal class EmmaPlannerBackend(private val client: EmmaGraphqlClient, private val clock: () -> Instant = Instant::now) : PlannerBackend {
    private val budget = PlannerRequestBudget()
    private data class RouteKey(val from: GeoPoint, val to: GeoPoint, val busesOnly: Boolean)
    private class RouteRequest(val ready: Instant, val started: Instant) {
        val result = CompletableDeferred<List<TripItinerary>>()
        var completed: Instant? = null
    }
    private val routeRequests = object : LinkedHashMap<RouteKey, RouteRequest>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RouteKey, RouteRequest>?) = size > 16
    }

    override fun clearRouteCache() = synchronized(routeRequests) { routeRequests.clear() }
    private val walks = object : LinkedHashMap<WalkKey, WalkingLink?>(512, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<WalkKey, WalkingLink?>?) = size > 1_024
    }

    override suspend fun stops(bounds: MapBounds): List<TransitStop> = budget.request {
        val text = client.execute(EmmaStopsParser.MAP_AREA_QUERY, JSONObject().put("south", bounds.south)
            .put("north", bounds.north).put("west", bounds.west).put("east", bounds.east))
        withContext(Dispatchers.Default) { EmmaStopsParser.area(text).stops }
    }

    override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
        val key = RouteKey(from.position, to.position, busesOnly)
        while (true) {
            val now = clock()
            val (entry, owner) = synchronized(routeRequests) {
                val previous = routeRequests[key]
                val reusable = previous != null && !previous.result.isCancelled &&
                    Duration.between(previous.ready, ready).toMillis() in 0..10_000 &&
                    Duration.between(previous.completed ?: previous.started, now).toMillis() in
                        0..(if (previous.completed == null) 30_000L else 10_000L)
                if (reusable) requireNotNull(previous) to false
                else RouteRequest(ready, now).also { routeRequests[key] = it } to true
            }
            if (owner) {
                try {
                    val rows = budget.request { fetchRoutes(from, to, ready, busesOnly) }
                    synchronized(routeRequests) { entry.completed = clock() }
                    entry.result.complete(rows)
                } catch (failure: Throwable) {
                    synchronized(routeRequests) { if (routeRequests[key] === entry) routeRequests.remove(key) }
                    entry.result.completeExceptionally(failure)
                    throw failure
                }
            }
            val rows = try { entry.result.await() } catch (cancelled: CancellationException) {
                // A cancelled prefetch owns its HTTP call, not the foreground caller.
                currentCoroutineContext().ensureActive()
                synchronized(routeRequests) { if (routeRequests[key] === entry) routeRequests.remove(key) }
                continue
            }
            val usable = if (entry.ready == ready) rows else rows.filter { TripPlanner.feasible(it.legs, ready) }
            if (usable.isNotEmpty() || rows.isEmpty() && entry.ready == ready) return usable
            // A later "now" can invalidate a cached boarding/walk. Query again at the
            // requested time rather than shifting source leg times or returning no route.
            synchronized(routeRequests) { if (routeRequests[key] === entry) routeRequests.remove(key) }
        }
    }

    /** Not shared with [routes]' request cache: it is a different query, asked only after a long-walk answer. */
    override suspend fun walkAverseRoutes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> =
        budget.request { fetchRoutes(from, to, ready, busesOnly, avoidWalking = true) }

    private suspend fun fetchRoutes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean,
                                    avoidWalking: Boolean = false): List<TripItinerary> {
        for (attempt in 0..1) {
            try {
                val text = client.execute(TripPlanner.query(busesOnly, avoidWalking), TripPlanner.variables(from, to, ready))
                return withContext(Dispatchers.Default) { TripPlanner.parse(text, from, to, ready, ready).itineraries }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: IOException) {
                val retry = attempt == 0 && failure !is EmmaCooldownException && failure !is InterruptedIOException &&
                    (failure !is EmmaHttpException || failure.status in setOf(502, 504) && failure.retryAt == null)
                if (!retry) throw failure
                delay(150)
            }
        }
        error("Unreachable routing retry")
    }

    override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult = coroutineScope {
        val distinct = pairs.distinctBy { it.first.position to it.second.position }
        val missing = distinct.filter { (a, b) -> synchronized(walks) { !walks.containsKey(a.position to b.position) } }
        val results = missing.chunked(8).map { batch -> async {
            try {
                val values = budget.walking {
                    val dateTime = TripPlanner.variables(endpoint(batch.first().first), endpoint(batch.first().second), ready)
                    val variables = JSONObject().put("date", dateTime.getString("date")).put("time", dateTime.getString("time"))
                    batch.forEachIndexed { index, (from, to) ->
                        variables.put("from$index", "${from.position.lat},${from.position.lon}")
                        variables.put("to$index", "${to.position.lat},${to.position.lon}")
                    }
                    val text = client.execute(walkingQuery(batch.size), variables)
                    withContext(Dispatchers.Default) { parseWalks(text, batch, ready) }
                }
                synchronized(walks) { values.links.forEach { (key, value) -> walks[key] = value } }
                values
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { WalkBatch(emptyMap(), true) }
        } }.awaitAll()
        val links = distinct.mapNotNull { (from, to) ->
            val key = from.position to to.position
            synchronized(walks) { walks[key] }?.copy(from = from, to = to)?.let { key to it }
        }.toMap()
        WalkingResult(links, results.any { it.partial })
    }

    private data class WalkBatch(val links: Map<WalkKey, WalkingLink?>, val partial: Boolean)
    private fun parseWalks(text: String, pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkBatch {
        val root = JSONObject(text)
        val errors = root.optJSONArray("errors")
        val failed = (0 until (errors?.length() ?: 0)).map { index ->
            val alias = errors!!.getJSONObject(index).optJSONArray("path")?.optString(0)
            require(alias in pairs.indices.map { "walk$it" }) { "Unscoped walking error" }
            alias
        }.toSet()
        val data = root.getJSONObject("data")
        val links = pairs.mapIndexedNotNull { index, (from, to) ->
            if ("walk$index" in failed || data.isNull("walk$index")) return@mapIndexedNotNull null
            val wrapped = JSONObject().put("data", JSONObject().put("plan", data.getJSONObject("walk$index")))
            val trip = TripPlanner.parse(wrapped.toString(), endpoint(from), endpoint(to), ready, ready).itineraries
                .firstOrNull { it.legs.all { leg -> leg.walking } }
            val walk = trip?.let { WalkingLink(from, to, Duration.between(it.start, it.end).seconds, it.walkMeters) }
            (from.position to to.position) to walk
        }.toMap()
        return WalkBatch(links, failed.isNotEmpty() || links.size < pairs.size)
    }

    companion object {
        private fun endpoint(point: PlanPoint) = PlannerEndpoint("walk", point.name, "", point.position)
        internal fun walkingQuery(count: Int): String {
            require(count in 1..8)
            val declarations = (0 until count).joinToString(",") { "${'$'}from$it:String!,${'$'}to$it:String!" }
            val fields = """itineraries { startTime endTime legs { mode startTime endTime distance
                from { name lat lon } to { name lat lon } intermediateStops { name lat lon }
            } }"""
            return "query WalkingLinks(${'$'}date:String!,${'$'}time:String!,$declarations){" + (0 until count).joinToString("\n") {
                "walk$it:plan(fromPlace:${'$'}from$it,toPlace:${'$'}to$it,date:${'$'}date,time:${'$'}time,arriveBy:false," +
                    "walkSpeed:1.15,numItineraries:1,transportModes:[{mode:WALK}]){$fields}"
            } + "}"
        }
    }
}
