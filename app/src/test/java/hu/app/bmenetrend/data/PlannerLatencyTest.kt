package hu.app.bmenetrend.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.time.Instant

/** Deterministic orchestration timings: simulated HTTP waits, actual bundled stop graph. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlannerLatencyTest {
    @Test fun bundledNetworkSearchLatency() = runTest {
        val ready = Instant.parse("2026-10-26T06:00:00Z")
        val network = NetworkMerger.merge(listOf("viabusz-calendar", "kanyargo", "homm").map { name ->
            NetworkJson.decode(File("src/main/assets/$name-network.json").readText())
        }).copy(verifiedProviderIds = setOf("viabusz", "homm", "kanyargo"))
        val origin = PlannerEndpoint("origin", "Budaörs", "", GeoPoint(47.460, 18.946))
        val destination = PlannerEndpoint("destination", "Technikum", "", GeoPoint(47.47959, 18.8091914))
        val budget = PlannerRequestBudget()
        var walkingPairs = 0
        var routeCalls = 0
        var walkingStarted = -1L
        val direct = TripItinerary("direct", ready.plusSeconds(60), ready.plusSeconds(20 * 60), listOf(
            PlanLeg("COACH", ready.plusSeconds(60), ready.plusSeconds(20 * 60), 15_000.0,
                PlanPoint(origin.name, origin.position), PlanPoint(destination.name, destination.position),
                TransitRoute("volan:test", "762", "Volán", emptyList(), "volan", transportMode = "COACH"),
                destination.name, false, false, emptyList())))
        val backend = object : PlannerBackend {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> = budget.request {
                routeCalls++
                delay(5_000)
                if (from.id == origin.id && to.id == destination.id) listOf(direct) else emptyList()
            }
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult = coroutineScope {
                walkingStarted = testScheduler.currentTime
                walkingPairs += pairs.size
                pairs.chunked(8).map { async { budget.walking { delay(1_000) } } }.awaitAll()
                WalkingResult(pairs.associate { (a, b) -> (a.position to b.position) to
                    WalkingLink(a, b, kotlin.math.ceil(StopClusters.distanceMeters(a.position, b.position) / TripPlanner.WALK_SPEED).toLong(),
                        StopClusters.distanceMeters(a.position, b.position)) })
            }
        }
        val progress = mutableListOf<Long>()
        val result = HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(origin, destination, ready, ready,
            network.stops.map { it.copy(id = "emma:${it.id}") }, { delay(1_000); network }) {
            progress += testScheduler.currentTime
        }
        println("PLANNER_LATENCY walkingPairs=$walkingPairs routeCalls=$routeCalls walkingStartedMs=$walkingStarted totalMs=${testScheduler.currentTime} progress=$progress")
        assertEquals("direct", result.itineraries.first().id)
        assertEquals(231, walkingPairs)
        assertEquals(17, routeCalls)
        assertEquals(1_000, walkingStarted)
        assertTrue("Route search should finish within 32 simulated seconds, took ${testScheduler.currentTime}", testScheduler.currentTime <= 32_000)
    }

    @Test fun requestBudgetBoundsHttpAndWalkingWork() = runTest {
        val budget = PlannerRequestBudget()
        var requests = 0
        var walking = 0
        var maxRequests = 0
        var maxWalking = 0
        suspend fun work(walk: Boolean) {
            requests++
            if (walk) walking++
            maxRequests = maxOf(maxRequests, requests)
            maxWalking = maxOf(maxWalking, walking)
            try { delay(100) } finally {
                requests--
                if (walk) walking--
            }
        }
        (List(8) { async { budget.request { work(false) } } } +
            List(8) { async { budget.walking { work(true) } } }).awaitAll()
        assertEquals(4, maxRequests)
        assertEquals(3, maxWalking)
        assertEquals(0, requests)
    }
}
