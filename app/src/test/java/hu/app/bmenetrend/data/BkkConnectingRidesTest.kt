package hu.app.bmenetrend.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class BkkConnectingRidesTest {
    private val ready = Instant.parse("2026-11-11T07:00:00Z")
    private val origin = PlannerEndpoint("school", "School", "", GeoPoint(47.48, 18.81))
    private val destination = PlannerEndpoint("home", "Home", "", GeoPoint(47.49, 18.84))
    private val stop = PlanPoint("Fő tér", GeoPoint(47.47, 18.82))
    private val nearHome = PlanPoint("Near home", GeoPoint(47.487, 18.837))
    private fun PlannerEndpoint.point() = PlanPoint(name, position)

    private fun bus(number: String, from: PlanPoint, to: PlanPoint, start: Instant, end: Instant) =
        PlanLeg("BUS", start, end, 2000.0, from, to,
            TransitRoute("bkk:$number", number, number, emptyList(), "bkk", transportMode = "BUS"),
            to.name, false, false, emptyList(), source = DataSource.BKK)

    private fun routed(id: String, legs: List<PlanLeg>): TripItinerary = TripItinerary(
        id, legs.first().start, legs.last().end, legs.mapIndexed { index, leg ->
            leg.copy(source = DataSource.BKK, routerJourney = id,
                routerPrevious = legs.getOrNull(index - 1)?.let(TripPlanner::routerLegKey))
        }, fetchedAt = ready)

    private fun primary(id: String = "762", at: PlanPoint = stop, arrival: Long = 600, walk: Long = 1020): TripItinerary {
        val ride = bus(id, origin.point(), at, ready.plusSeconds(240), ready.plusSeconds(arrival))
        val egress = PlanLeg("WALK", ride.end, ride.end.plusSeconds(walk), walk * TripPlanner.WALK_SPEED,
            at, destination.point(), null, null, false, false, emptyList())
        return routed(id, listOf(ride, egress))
    }

    private fun tail(walk: Long = 360, wait: Long = 180): TripItinerary {
        val ride = bus("2", stop, nearHome, ready.plusSeconds(600 + wait), ready.plusSeconds(900 + wait))
        val egress = PlanLeg("WALK", ride.end, ride.end.plusSeconds(walk), walk * TripPlanner.WALK_SPEED,
            nearHome, destination.point(), null, null, false, false, emptyList())
        return routed("tail", listOf(ride, egress))
    }

    private data class Call(val from: PlannerEndpoint, val to: PlannerEndpoint, val ready: Instant, val bus: Boolean)
    private class Source(val rows: List<TripItinerary>, val headroom: Int = 10,
                         val answer: suspend (Call) -> List<TripItinerary> = { emptyList() }) : BkkPlannerSource {
        val calls = mutableListOf<Call>()
        override suspend fun plan(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): BkkWorkerParser.PlanResult {
            val call = Call(from, to, ready, busesOnly)
            calls += call
            val result = if (calls.size == 1) rows else answer(call)
            return BkkWorkerParser.PlanResult(result, 0, result.size)
        }
        override fun planHeadroom() = headroom
        override suspend fun walking(from: PlanPoint, to: PlanPoint, ready: Instant): WalkingLink? = null
        override suspend fun stops(area: MapBounds): List<TransitStop> = emptyList()
    }
    private val emma = object : PlannerBackend {
        override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> =
            error("Healthy BKK must not use ordinary EMMA fallback")
        override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant) = WalkingResult(emptyMap())
    }
    private fun backend(source: Source) = BkkFirstPlannerBackend(emma, source, clock = { ready })

    @Test fun seventeenMinuteEgressPlansAfterTransferBufferAndSplicesBusWithSixMinuteWalk() = runTest {
        for (busesOnly in listOf(false, true)) {
            val original = primary()
            val connection = tail()
            val source = Source(listOf(original)) { listOf(connection) }
            val result = backend(source).routes(origin, destination, ready, busesOnly)
            assertEquals(2, source.calls.size)
            val request = source.calls.last()
            assertEquals(stop.position, request.from.position)
            assertEquals(stop.name, request.from.name)
            assertEquals(destination, request.to)
            assertEquals(original.legs.first().end.plusSeconds(180), request.ready)
            assertEquals(busesOnly, request.bus)
            val spliced = result.single { it.id == "762+tail" }
            assertEquals(listOf(original.legs.first()) + connection.legs, spliced.legs)
            assertEquals(original.start, spliced.start)
            assertEquals(connection.end, spliced.end)
            assertEquals(original.fetchedAt, spliced.fetchedAt)
            assertEquals(360L, spliced.walkSeconds)
            assertTrue(TripPlanner.feasible(spliced.legs, ready))
            assertTrue(result.contains(original))
        }
    }

    @Test fun exactlyEightMinuteEgressTriggersWithFivePlansHeadroom() = runTest {
        val original = primary(walk = 480)
        val source = Source(listOf(original), headroom = 5) { listOf(tail(walk = 240)) }
        val result = backend(source).routes(origin, destination, ready)
        assertEquals(2, source.calls.size)
        assertTrue(result.any { it.id == "762+tail" })
    }

    @Test fun sameStopTailsBoardingBeforeThreeMinuteTransferBufferAreIgnored() = runTest {
        val original = primary()
        assertTrue(TripPlanner.feasible(original.legs, ready))
        for (wait in listOf(120L, 179L)) {
            val connection = tail(wait = wait)
            assertTrue(TripPlanner.feasible(connection.legs, original.legs.first().end))
            val source = Source(listOf(original)) { listOf(connection) }
            val result = backend(source).routes(origin, destination, ready)
            // Each answer is feasible alone, but splicing requires a new transfer.
            assertEquals("transfer wait=$wait", listOf(original), result)
            assertEquals(original.legs.first().end.plusSeconds(180), source.calls.last().ready)
        }
    }

    @Test fun connectingAndWalkAverseSearchesShareOneFourSecondWindow() = runTest {
        val original = primary()
        val source = Source(listOf(original)) { delay(4001); emptyList() }
        var averseCalls = 0
        val slowEmma = object : PlannerBackend by emma {
            override suspend fun walkAverseRoutes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                averseCalls++
                delay(4001)
                return emptyList()
            }
        }
        val result = BkkFirstPlannerBackend(slowEmma, source, clock = { ready }).routes(origin, destination, ready)
        assertEquals(listOf(original), result)
        assertEquals(1, averseCalls)
        assertEquals(2, source.calls.size)
        assertEquals(4000L, testScheduler.currentTime)
    }

    @Test fun requiresAtLeastFourMinutesWalkSavedIncludingExactBoundary() = runTest {
        for (saved in listOf(239L, 240L)) {
            val original = primary()
            val connection = tail(walk = 1020 - saved)
            val result = backend(Source(listOf(original)) { listOf(connection) }).routes(origin, destination, ready)
            assertEquals("saved=$saved", saved == 240L, result.any { it.id == "762+tail" })
            assertTrue(result.contains(original))
        }
    }

    @Test fun tailMustRideAndMustNotStartBeforeAlighting() = runTest {
        val original = primary()
        val connection = tail()
        val walkingOnly = routed("walk", listOf(connection.legs.last()))
        val tooEarly = routed("too-early", listOf(
            connection.legs.first().copy(start = original.legs.first().end.minusSeconds(1)), connection.legs.last()))
        val source = Source(listOf(original)) { listOf(walkingOnly, tooEarly) }
        assertEquals(listOf(original), backend(source).routes(origin, destination, ready))
    }

    @Test fun headroomAtOrBelowFourSkipsConnectingCalls() = runTest {
        for (headroom in listOf(0, 4)) {
            val original = primary()
            val source = Source(listOf(original), headroom) { error("No budget for a connection") }
            assertEquals(listOf(original), backend(source).routes(origin, destination, ready))
            assertEquals(1, source.calls.size)
        }
    }

    @Test fun shortFinalWalkDoesNotTriggerConnectingCalls() = runTest {
        val original = primary(walk = 479)
        val source = Source(listOf(original)) { error("Under eight minutes must not trigger") }
        assertEquals(listOf(original), backend(source).routes(origin, destination, ready))
        assertEquals(1, source.calls.size)
    }

    @Test fun connectionFailureKeepsOriginalRows() = runTest {
        val original = primary()
        val source = Source(listOf(original)) { throw IllegalStateException("offline") }
        assertEquals(listOf(original), backend(source).routes(origin, destination, ready))
        assertEquals(2, source.calls.size)
    }

    @Test fun connectionTimeoutKeepsOriginalRowsAfterFourSeconds() = runTest {
        val original = primary()
        val source = Source(listOf(original)) { delay(4001); listOf(tail()) }
        assertEquals(listOf(original), backend(source).routes(origin, destination, ready))
        assertEquals(4000L, testScheduler.currentTime)
        assertEquals(2, source.calls.size)
    }

    @Test fun connectsOnlyTwoEarliestDistinctAlightingStops() = runTest {
        val secondStop = stop.copy(name = "Second", position = GeoPoint(47.475, 18.825))
        val thirdStop = stop.copy(name = "Third", position = GeoPoint(47.48, 18.83))
        val earliest = primary("first", arrival = 600)
        val alias = primary("same-position", at = stop.copy(name = "Fő tér alias"), arrival = 620)
        val second = primary("second", at = secondStop, arrival = 660)
        val third = primary("third", at = thirdStop, arrival = 720)
        val source = Source(listOf(third, second, alias, earliest))
        backend(source).routes(origin, destination, ready)
        assertEquals(3, source.calls.size)
        assertEquals(setOf(stop.position, secondStop.position), source.calls.drop(1).map { it.from.position }.toSet())
        assertEquals(setOf(ready.plusSeconds(780), ready.plusSeconds(840)), source.calls.drop(1).map { it.ready }.toSet())
    }
}
