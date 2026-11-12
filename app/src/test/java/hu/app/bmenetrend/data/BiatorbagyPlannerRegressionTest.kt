package hu.app.bmenetrend.data

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Replay timings are embedded: these tests never require live routers or research files. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BiatorbagyPlannerRegressionTest {
    private val ready = Instant.parse("2026-11-11T08:48:00Z") // 09:48 Budapest
    private fun point(name: String, lat: Double, lon: Double = 18.82) = PlanPoint(name, GeoPoint(lat, lon))
    private fun PlanPoint.endpoint() = PlannerEndpoint(name, name, "", position)
    private fun PlanPoint.stop(routes: List<String> = emptyList(), mode: String? = null) =
        TransitStop(name, name, position, routes, transportMode = mode)

    private open class RecordingEmma : PlannerBackend {
        val walks = mutableListOf<Pair<PlanPoint, PlanPoint>>()
        override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean) = emptyList<TripItinerary>()
        override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
            walks += pairs
            return WalkingResult(pairs.associate { (a, b) ->
                (a.position to b.position) to WalkingLink(a, b, 322, 356.21)
            })
        }
    }

    private open class RecordingBkk : BkkPlannerSource {
        val walks = mutableListOf<Pair<PlanPoint, PlanPoint>>()
        override fun planHeadroom() = 8 // four reserved plans, four routed walks
        override suspend fun plan(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean) =
            BkkWorkerParser.PlanResult(emptyList(), 0, 0)
        override suspend fun stops(area: MapBounds) = emptyList<TransitStop>()
        override suspend fun walking(from: PlanPoint, to: PlanPoint, ready: Instant): WalkingLink? {
            walks += from to to
            return WalkingLink(from, to, 279, 356.0)
        }
    }

    @Test fun firstEligiblePairsInCallerOrderUseBkkAndRemainingPairsUseEmma() = runTest {
        val origin = point("Technikum", 47.47959)
        // Shorter walks come later deliberately: distance must not override caller priority.
        val eligible = listOf(900, 500, 700, 300, 1100, 600, 200).map { meters ->
            origin to point("stop-$meters", origin.position.lat + meters / 111_195.0)
        }
        val outsideHungary = origin to point("London", 51.5074, -0.1278)
        // Neither an ineligible pair nor a duplicate consumes the BKK allowance.
        val pairs = listOf(outsideHungary, eligible[0], eligible[0]) + eligible.drop(1)
        for (allowance in listOf(2, 4)) {
            val bkk = object : RecordingBkk() {
                override fun planHeadroom() = 4 + allowance
            }
            val emma = RecordingEmma()
            val result = BkkFirstPlannerBackend(emma, bkk).walking(pairs, ready)
            val primary = eligible.take(allowance)
            val fallback = listOf(outsideHungary) + eligible.drop(allowance)
            assertEquals("BKK must preserve caller priority with allowance=$allowance", primary, bkk.walks)
            assertEquals(fallback, emma.walks)
            assertEquals(8, result.links.size)
            primary.forEach { (a, b) -> assertEquals(279L, result.links.getValue(a.position to b.position).seconds) }
            fallback.forEach { (a, b) -> assertEquals(322L, result.links.getValue(a.position to b.position).seconds) }
            assertFalse(result.partial)
        }
    }

    @Test fun shortBkkAccessCatches0954Line1AndRecommends1PConnectionOverThirtyMinuteWalk762() = runTest {
        val origin = point("Technikum", 47.47959, 18.8091914)
        val destination = point("Iharos", 47.469424, 18.838232)
        val juhasz = point("Juhász Gyula utca", 47.478805, 18.811614)
        val station = point("Vasútállomás", 47.479821, 18.833979)
        val bridge = point("Völgyhíd", 47.47252111, 18.83224094)
        val decoys = (5..10).map { point("earlier-access-$it", origin.position.lat + it * .0008, origin.position.lon) }
        val route1 = TransitRoute("1", "1", "1", listOf(RouteDirection("out", station.name,
            (decoys + juhasz + station).map { it.name })), "viabusz")
        val route1P = TransitRoute("1P", "1P", "1P", listOf(RouteDirection("out", bridge.name,
            listOf(station.name, bridge.name))), "viabusz")
        val network = TransitNetwork((decoys + juhasz).map { it.stop(listOf("1")) } +
            station.stop(listOf("1", "1P")) + bridge.stop(listOf("1P")), listOf(route1, route1P), listOf(
            ScheduledTrip("0954", "1", "out", listOf(StopTime(juhasz.name, 594), StopTime(station.name, 603)), "school_term"),
            ScheduledTrip("1025", "1P", "out", listOf(StopTime(station.name, 625), StopTime(bridge.name, 629)), "school_term")
        ), ready, "synthetic replay", verifiedProviderIds = setOf("viabusz"))
        val coachEnd = ready.plusSeconds(15 * 60)
        val coach = PlanLeg("COACH", ready.plusSeconds(60), coachEnd, 3000.0, origin, station,
            TransitRoute("volan:762", "762", "762", emptyList(), "volan", transportMode = "COACH"),
            station.name, false, false, emptyList())
        val longWalk = WalkingLink(station, destination, 1800, 2070.0).leg(coachEnd)
        val direct = TripItinerary("762-long-walk", coach.start, longWalk.end, listOf(coach, longWalk))

        suspend fun replay(shortAccess: Boolean): Pair<TripPlan, List<Pair<PlanPoint, PlanPoint>>> {
            val emma = object : RecordingEmma() {
                override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean) =
                    if (from.id == origin.name && to.id == destination.name) listOf(direct) else emptyList()
                override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
                    walks += pairs
                    return WalkingResult(pairs.associate { (a, b) ->
                        val seconds = when {
                            a.position == origin.position && b.position == juhasz.position -> 322L
                            a.position == bridge.position && b.position == destination.position -> 548L
                            else -> 3600L
                        }
                        (a.position to b.position) to WalkingLink(a, b, seconds, seconds * TripPlanner.WALK_SPEED)
                    })
                }
            }
            val bkk = object : RecordingBkk() {
                override suspend fun walking(from: PlanPoint, to: PlanPoint, ready: Instant): WalkingLink? {
                    walks += from to to
                    return if (shortAccess && from.position == origin.position && to.position == juhasz.position)
                        WalkingLink(from, to, 279, 356.0) else null
                }
            }
            val result = HybridTripPlanner(BkkFirstPlannerBackend(emma, bkk), StandardTestDispatcher(testScheduler)).plan(
                origin.endpoint(), destination.endpoint(), ready, ready,
                listOf(station.stop(mode = "RAIL").copy(id = "emma:platform")), { network }, setOf("viabusz"))
            return result to bkk.walks
        }

        val (result, bkkWalks) = replay(shortAccess = true)
        assertEquals(4, bkkWalks.size)
        assertTrue("Origin → Juhász must receive one of the first four BKK walking slots",
            bkkWalks.take(4).any { it.first.position == origin.position && it.second.position == juhasz.position })
        assertTrue("Short stop-to-stop decoys must not consume BKK access/egress slots",
            bkkWalks.all { it.first.position == origin.position || it.second.position == destination.position })
        val recommendation = result.itineraries.first()
        val rides = recommendation.legs.filterNot { it.walking }
        assertEquals(listOf("1", "1P"), rides.map { it.route!!.number })
        assertEquals(Instant.parse("2026-11-11T08:54:00Z"), rides[0].start)
        assertEquals(station.name, rides[0].to.name)
        assertEquals(Instant.parse("2026-11-11T09:25:00Z"), rides[1].start)
        assertEquals(bridge.name, rides[1].to.name)
        assertEquals(Instant.parse("2026-11-11T09:38:08Z"), recommendation.end)
        assertEquals(827L, recommendation.walkSeconds) // 13 min 47 s
        assertTrue(TripPlanner.feasible(recommendation.legs, ready))
        // The 322 s fallback misses 09:54 once the 60 s boarding allowance is included.
        val (fallback, _) = replay(shortAccess = false)
        assertEquals(listOf("762-long-walk"), fallback.itineraries.map { it.id })
    }

    // Keep the private selection algorithm testable without widening production visibility.
    @Suppress("UNCHECKED_CAST")
    private fun gateways(stops: List<TransitStop>, references: List<TransitStop>, toward: GeoPoint,
                         cost: (TransitStop) -> Double): List<TransitStop> {
        val method = HybridTripPlanner::class.java.getDeclaredMethod("gateways", List::class.java,
            List::class.java, GeoPoint::class.java, kotlin.jvm.functions.Function1::class.java)
        method.isAccessible = true
        return method.invoke(HybridTripPlanner(RecordingEmma()), stops, references, toward, cost) as List<TransitStop>
    }

    @Test fun railSlotBelongsToStop37MetersFromPlatformRatherThanSchool327MetersAway() {
        for (mode in listOf("RAIL", "SUBURBAN_RAILWAY")) {
            val platform = point("platform", 47.48).stop(mode = mode)
            fun at(name: String, meters: Int) = point(name, 47.48 + meters / 111_195.0).stop()
            val station = at("station", 37)
            val school = at("school", -327)
            val road1 = at("road1", -1000)
            val road2 = at("road2", -1500)
            assertEquals(37.0, StopClusters.distanceMeters(station.position, platform.position), 1.0)
            assertEquals(327.0, StopClusters.distanceMeters(school.position, platform.position), 1.0)
            val selected = gateways(listOf(road1, school, road2, station),
                listOf(platform, road1.copy(id = "ref1"), road2.copy(id = "ref2")), road1.position) {
                when (it.id) { road1.id -> 0.0; road2.id -> 1.0; school.id -> 10.0; else -> 1000.0 }
            }
            assertEquals("mode=$mode", listOf(road1.id, station.id, road2.id), selected.map { it.id })
        }
    }

    @Test fun nearestNationalReferenceDistanceBreaksOtherwiseEqualGatewayScores() {
        val north = point("north", 47.486).stop()
        val south = point("south", 47.474).stop()
        val toward = GeoPoint(47.48, 18.82)
        fun reference(stop: TransitStop, meters: Int) = stop.copy(id = "ref:${stop.id}",
            position = stop.position.copy(lat = stop.position.lat + meters / 111_195.0))
        // Toward-distance is symmetric and local cost identical. Swapping proximity
        // must swap the winning gateway, regardless of the input order.
        for (near in listOf(north, south)) {
            val far = if (near == north) south else north
            val selected = gateways(listOf(far, near), listOf(reference(near, 37), reference(far, 327)), toward) { 0.0 }
            assertEquals(near.id, selected.first().id)
        }
    }
}
