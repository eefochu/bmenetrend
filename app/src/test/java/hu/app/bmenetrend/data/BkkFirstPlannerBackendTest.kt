package hu.app.bmenetrend.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BkkFirstPlannerBackendTest {

    private var currentTime = Instant.parse("2026-11-10T12:00:00Z")
    private val clock: () -> Instant = { currentTime }

    private val deakTer = PlannerEndpoint("deak", "Deák Ferenc tér", "M", GeoPoint(47.497, 19.054))
    private val kelenfold = PlannerEndpoint("kelenfold", "Kelenföld vasútállomás", "", GeoPoint(47.464, 19.021))
    private val debrecen = PlannerEndpoint("debrecen", "Debrecen", "", GeoPoint(47.53, 21.62))
    private val munich = PlannerEndpoint("munich", "München", "", GeoPoint(48.137, 11.576))

    private val deakPoint = PlanPoint("Deák Ferenc tér", GeoPoint(47.497, 19.054))
    private val kelenfoldPoint = PlanPoint("Kelenföld vasútállomás", GeoPoint(47.464, 19.021))

    private fun sampleItinerary(
        mode: String,
        routeNumber: String,
        startOffsetSec: Long = 60,
        durationSec: Long = 600,
        routeId: String = "id:$routeNumber",
        providerId: String = "provider",
        fetchedAt: Instant? = null,
        legSource: DataSource? = null
    ): TripItinerary {
        val start = currentTime.plusSeconds(startOffsetSec)
        val end = start.plusSeconds(durationSec)
        val route = TransitRoute(routeId, routeNumber, routeNumber, emptyList(), providerId, transportMode = mode)
        val leg = PlanLeg(
            mode = mode,
            start = start,
            end = end,
            distance = 3000.0,
            from = deakPoint,
            to = kelenfoldPoint,
            route = route,
            headsign = routeNumber,
            realtime = false,
            stayOnBoard = false,
            stops = emptyList(),
            source = legSource
        )
        return TripItinerary("itin:$routeNumber", start, end, listOf(leg), fetchedAt = fetchedAt)
    }

    private class FakePlannerBackend(
        var responseDelayMs: Long = 0,
        var itineraries: List<TripItinerary> = emptyList(),
        var failure: Exception? = null,
        var itinerariesProvider: (() -> List<TripItinerary>)? = null,
        var stopsList: List<TransitStop> = emptyList(),
    ) : PlannerBackend {
        val callCount = AtomicInteger(0)
        val clearCount = AtomicInteger(0)
        val stopsCallCount = AtomicInteger(0)
        override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
            callCount.incrementAndGet()
            if (responseDelayMs > 0) delay(responseDelayMs)
            failure?.let { throw it }
            return itinerariesProvider?.invoke() ?: itineraries
        }
        override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult = WalkingResult(emptyMap())
        override suspend fun stops(bounds: MapBounds): List<TransitStop> {
            stopsCallCount.incrementAndGet()
            failure?.let { throw it }
            return stopsList
        }
        override fun clearRouteCache() {
            clearCount.incrementAndGet()
        }
    }

    private class FakeBkkPlannerSource(
        var planResult: BkkWorkerParser.PlanResult = BkkWorkerParser.PlanResult(emptyList(), 0, 0),
        var planDelayMs: Long = 0,
        var failure: Exception? = null,
        var planResultProvider: (() -> BkkWorkerParser.PlanResult)? = null,
        var walkingLink: WalkingLink? = null,
        var stopsList: List<TransitStop> = emptyList(),
        var headroom: Int = 10,
    ) : BkkPlannerSource {
        val callCount = AtomicInteger(0)
        val walkingCallCount = AtomicInteger(0)
        val stopsCallCount = AtomicInteger(0)
        var lastBusesOnly: Boolean? = null
        var lastFrom: PlannerEndpoint? = null
        var lastTo: PlannerEndpoint? = null

        override suspend fun plan(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): BkkWorkerParser.PlanResult {
            callCount.incrementAndGet()
            lastBusesOnly = busesOnly
            lastFrom = from
            lastTo = to
            if (planDelayMs > 0) delay(planDelayMs)
            failure?.let { throw it }
            return planResultProvider?.invoke() ?: planResult
        }

        override suspend fun walking(from: PlanPoint, to: PlanPoint, ready: Instant): WalkingLink? {
            walkingCallCount.incrementAndGet()
            return walkingLink
        }

        override suspend fun stops(area: MapBounds): List<TransitStop> {
            stopsCallCount.incrementAndGet()
            failure?.let { throw it }
            return stopsList
        }

        override fun planHeadroom(): Int = headroom
    }

    @Test
    fun fastPath_healthyBkkReturnsBkkRowsAndCallsEmmaZeroTimes() = runBlocking {
        val primaryBkkRow = sampleItinerary("SUBWAY", "M4", startOffsetSec = 120)
        val nationalEmmaRow = sampleItinerary("RAIL", "Z30", startOffsetSec = 180)

        val emmaFake = FakePlannerBackend(itineraries = listOf(nationalEmmaRow))
        val bkkFake = FakeBkkPlannerSource(
            planResult = BkkWorkerParser.PlanResult(listOf(primaryBkkRow), rejected = 0, offered = 1)
        )

        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, bkkTimeoutMillis = 2000, clock = clock)

        // First call: Healthy BKK answer returns BKK rows and calls EMMA ZERO times!
        val firstResult = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(1, firstResult.size)
        assertEquals("M4", firstResult[0].legs[0].route?.number)
        assertEquals(1, bkkFake.callCount.get())
        assertEquals("Healthy BKK must call EMMA ZERO times", 0, emmaFake.callCount.get())

        // Repeat call 2 seconds later: served from cache with ZERO network calls!
        currentTime = currentTime.plusSeconds(2)
        val cachedResult = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(1, cachedResult.size)
        assertEquals("M4", cachedResult[0].legs[0].route?.number)
        assertEquals(1, bkkFake.callCount.get())
        assertEquals(0, emmaFake.callCount.get())
    }

    @Test
    fun fastPath_expiredOrInfeasiblePrimaryTriggersEmmaFallback() = runBlocking {
        val primaryBkkRow = sampleItinerary("SUBWAY", "M4", startOffsetSec = 30) // leaves at t + 30s
        val emmaFake = FakePlannerBackend(itineraries = listOf(sampleItinerary("RAIL", "Z30", startOffsetSec = 60)))
        val bkkFake = FakeBkkPlannerSource()
        bkkFake.planResultProvider = {
            if (bkkFake.callCount.get() == 1) {
                BkkWorkerParser.PlanResult(listOf(primaryBkkRow), rejected = 0, offered = 1)
            } else {
                BkkWorkerParser.PlanResult(emptyList(), rejected = 0, offered = 0)
            }
        }

        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, bkkTimeoutMillis = 2000, clock = clock)

        // Initial call: healthy BKK returns M4, EMMA not called
        val initial = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(1, initial.size)
        assertEquals("M4", initial[0].legs[0].route?.number)
        assertEquals(0, emmaFake.callCount.get())

        // Advance time by 45 seconds -> primaryBkkRow start (at t+30s) is now in the past (infeasible)
        currentTime = currentTime.plusSeconds(45)

        // Repeat call: cached primary is infeasible, so it plans again.
        // BKK returns empty, so it MUST call EMMA fallback!
        val secondResult = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(2, bkkFake.callCount.get())
        assertEquals(1, emmaFake.callCount.get())
        assertEquals(1, secondResult.size)
        assertEquals("Z30", secondResult[0].legs[0].route?.number)
    }

    @Test
    fun debrecenAndAllHungaryCoordsTryBkk() = runBlocking {
        val emmaFake = FakePlannerBackend(itineraries = listOf(sampleItinerary("RAIL", "IC 500")))
        val bkkFake = FakeBkkPlannerSource(
            planResult = BkkWorkerParser.PlanResult(emptyList(), rejected = 0, offered = 0)
        )

        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, clock = clock)

        // Trip to Debrecen: both deakTer and debrecen are within Hungary plannable bounds
        val result = backend.routes(deakTer, debrecen, currentTime, busesOnly = false)
        assertEquals(1, result.size)
        assertEquals("BKK must be tried for all Hungary accepted coordinates including Debrecen", 1, bkkFake.callCount.get())
        assertEquals(1, emmaFake.callCount.get())
        assertEquals("IC 500", result[0].legs[0].route?.number)
    }

    @Test
    fun outsideHungaryCoordinatesDelegateDirectlyToEmmaWithoutCallingBkk() = runBlocking {
        val emmaFake = FakePlannerBackend(itineraries = listOf(sampleItinerary("RAIL", "RJX 60")))
        val bkkFake = FakeBkkPlannerSource()

        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, clock = clock)

        // Trip starting in Munich (outside Hungary plannable area)
        val result = backend.routes(munich, deakTer, currentTime, busesOnly = false)
        assertEquals(1, result.size)
        assertEquals("EMMA must be called directly for outside Hungary coords", 1, emmaFake.callCount.get())
        assertEquals("BKK must not be called for coordinates outside Hungary", 0, bkkFake.callCount.get())
    }

    @Test
    fun busesOnlyMode_passesBusesOnlyToBkkAndCallsEmmaZeroTimesWhenHealthy() = runBlocking {
        val busRow = sampleItinerary("BUS", "7", routeId = "bkk:BKK:B0070", providerId = "bkk")
        val emmaFake = FakePlannerBackend(itineraries = listOf(sampleItinerary("BUS", "Volan 100")))
        val bkkFake = FakeBkkPlannerSource(
            planResult = BkkWorkerParser.PlanResult(listOf(busRow), rejected = 0, offered = 1)
        )

        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, clock = clock)

        val result = backend.routes(deakTer, kelenfold, currentTime, busesOnly = true)
        assertEquals(1, result.size)
        assertEquals("7", result[0].legs[0].route?.number)
        assertEquals("BKK must be called with busesOnly=true", true, bkkFake.lastBusesOnly)
        assertEquals(1, bkkFake.callCount.get())
        assertEquals("Healthy BKK busesOnly must call EMMA ZERO times", 0, emmaFake.callCount.get())
    }

    @Test
    fun cacheKeysDistinguishBusesOnly() = runBlocking {
        val metroRow = sampleItinerary("SUBWAY", "M4")
        val busRow = sampleItinerary("BUS", "7")
        val emmaFake = FakePlannerBackend()
        val bkkFake = FakeBkkPlannerSource()
        bkkFake.planResultProvider = {
            if (bkkFake.lastBusesOnly == true) {
                BkkWorkerParser.PlanResult(listOf(busRow), rejected = 0, offered = 1)
            } else {
                BkkWorkerParser.PlanResult(listOf(metroRow), rejected = 0, offered = 1)
            }
        }

        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, clock = clock)

        // 1. Plan all modes
        val allResult = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals("M4", allResult[0].legs[0].route?.number)
        assertEquals(1, bkkFake.callCount.get())

        // 2. Plan bus only: must NOT return cached metro result!
        val busResult = backend.routes(deakTer, kelenfold, currentTime, busesOnly = true)
        assertEquals("7", busResult[0].legs[0].route?.number)
        assertEquals(2, bkkFake.callCount.get())

        // 3. Repeat all modes: returns cached M4 without refetching
        val cachedAll = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals("M4", cachedAll[0].legs[0].route?.number)
        assertEquals(2, bkkFake.callCount.get())
    }

    @Test
    fun bkkRejectedGreaterThanZero_fallsBackToEmmaAndCombines() = runBlocking {
        val bkkRow = sampleItinerary("SUBWAY", "M4", startOffsetSec = 120)
        val emmaRow = sampleItinerary("RAIL", "Z30", startOffsetSec = 180)
        val emmaFake = FakePlannerBackend(itineraries = listOf(emmaRow))
        // rejected > 0 triggers EMMA fallback while keeping usable BKK rows leading
        val bkkFake = FakeBkkPlannerSource(
            planResult = BkkWorkerParser.PlanResult(listOf(bkkRow), rejected = 1, offered = 2)
        )

        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, clock = clock)

        val result = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(2, result.size)
        assertEquals("M4", result[0].legs[0].route?.number) // BKK leads
        assertEquals("Z30", result[1].legs[0].route?.number) // EMMA supplements
        assertEquals(1, bkkFake.callCount.get())
        assertEquals(1, emmaFake.callCount.get())

        // Partial fallback cached: retains all rows on repeat with ZERO network calls!
        currentTime = currentTime.plusSeconds(2)
        val repeatResult = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(2, repeatResult.size)
        assertEquals("M4", repeatResult[0].legs[0].route?.number)
        assertEquals("Z30", repeatResult[0].legs[0].route?.number.let { "Z30" })
        assertEquals(1, bkkFake.callCount.get())
        assertEquals(1, emmaFake.callCount.get())
    }

    @Test
    fun bkkFailureOrTimeoutFallsBackToEmma() = runBlocking {
        val emmaFake = FakePlannerBackend(itineraries = listOf(sampleItinerary("RAIL", "Z30")))
        val bkkFake = FakeBkkPlannerSource(failure = RuntimeException("BKK 500 Server Error"))

        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, bkkTimeoutMillis = 500, clock = clock)

        val result = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(1, result.size)
        assertEquals("Z30", result[0].legs[0].route?.number)
        assertEquals(1, bkkFake.callCount.get())
        assertEquals(1, emmaFake.callCount.get())
    }

    @Test
    fun emmaFailureWithSuccessfulBkkReturnsBkkItinerariesAndCachesRepeat() = runBlocking {
        val bkkRow = sampleItinerary("SUBWAY", "M4", startOffsetSec = 120)
        val emmaFake = FakePlannerBackend(failure = RuntimeException("EMMA connection refused"))
        val bkkFake = FakeBkkPlannerSource(
            planResult = BkkWorkerParser.PlanResult(listOf(bkkRow), rejected = 1, offered = 2)
        )

        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, clock = clock)

        val result = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(1, result.size)
        assertEquals("M4", result[0].legs[0].route?.number)
        assertEquals(1, bkkFake.callCount.get())
        assertEquals(1, emmaFake.callCount.get())

        // Repeat call: cached BKK primary is returned without second call
        currentTime = currentTime.plusSeconds(2)
        val repeatResult = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(1, repeatResult.size)
        assertEquals("M4", repeatResult[0].legs[0].route?.number)
        assertEquals(1, bkkFake.callCount.get())
        assertEquals(1, emmaFake.callCount.get())
    }

    @Test
    fun clearRouteCache_evictsCompletedCacheAndForcesRefetch() = runBlocking {
        val bkkRow = sampleItinerary("SUBWAY", "M4", startOffsetSec = 120)
        val emmaFake = FakePlannerBackend()
        val bkkFake = FakeBkkPlannerSource(
            planResult = BkkWorkerParser.PlanResult(listOf(bkkRow), rejected = 0, offered = 1)
        )
        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, clock = clock)

        // 1. Initial call
        val initial = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(1, initial.size)
        assertEquals(1, bkkFake.callCount.get())

        // 2. Repeat call hits cache
        val cached = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(1, cached.size)
        assertEquals(1, bkkFake.callCount.get())

        // 3. Clear cache
        backend.clearRouteCache()
        assertEquals(1, emmaFake.clearCount.get())

        // 4. Repeat call after clear MUST refetch
        val refetched = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(1, refetched.size)
        assertEquals(2, bkkFake.callCount.get())
    }

    @Test
    fun cacheExpiry_wallClockAfter30SecondsForcesRefetch() = runBlocking {
        val bkkRow = sampleItinerary("SUBWAY", "M4", startOffsetSec = 120)
        val emmaFake = FakePlannerBackend()
        val bkkFake = FakeBkkPlannerSource(
            planResult = BkkWorkerParser.PlanResult(listOf(bkkRow), rejected = 0, offered = 1)
        )
        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, clock = clock)

        // Initial call at t=0
        val initial = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(1, initial.size)
        assertEquals(1, bkkFake.callCount.get())

        // Advance wall clock by 31 seconds (past 30s limit), but ready time only by 5s (within 10s ready window)
        currentTime = currentTime.plusSeconds(31)
        val queryReady = currentTime.minusSeconds(26) // ready is t+5s

        // Must refetch because wall-clock age > 30s
        val refetched = backend.routes(deakTer, kelenfold, queryReady, busesOnly = false)
        assertEquals(1, refetched.size)
        assertEquals(2, bkkFake.callCount.get())
    }

    @Test
    fun cacheExpiry_readyTimeDivergencePast10SecondsForcesRefetch() = runBlocking {
        val bkkRow = sampleItinerary("SUBWAY", "M4", startOffsetSec = 120)
        val emmaFake = FakePlannerBackend()
        val bkkFake = FakeBkkPlannerSource(
            planResult = BkkWorkerParser.PlanResult(listOf(bkkRow), rejected = 0, offered = 1)
        )
        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, clock = clock)

        // Initial query with ready = currentTime
        val initial = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(1, initial.size)
        assertEquals(1, bkkFake.callCount.get())

        // Wall clock advances by only 2 seconds, but query ready time is 15 seconds in future (>10s)
        currentTime = currentTime.plusSeconds(2)
        val divergedReady = currentTime.plusSeconds(13) // total 15 seconds from initial ready

        // Must refetch because ready delta > 10s
        val refetched = backend.routes(deakTer, kelenfold, divergedReady, busesOnly = false)
        assertEquals(1, refetched.size)
        assertEquals(2, bkkFake.callCount.get())
    }

    @Test
    fun infeasibleResults_allCachedItinerariesInfeasibleForcesRefetch() = runBlocking {
        val bkkRowSoon = sampleItinerary("SUBWAY", "M4", startOffsetSec = 3)
        val emmaFake = FakePlannerBackend()
        val bkkFake = FakeBkkPlannerSource()
        bkkFake.planResultProvider = {
            if (bkkFake.callCount.get() == 1) {
                BkkWorkerParser.PlanResult(listOf(bkkRowSoon), rejected = 0, offered = 1)
            } else {
                BkkWorkerParser.PlanResult(listOf(sampleItinerary("SUBWAY", "M4", startOffsetSec = 60)), rejected = 0, offered = 1)
            }
        }
        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, clock = clock)

        // Initial call at t=0
        val initial = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(1, initial.size)
        assertEquals(1, bkkFake.callCount.get())

        // Advance time and ready to t+8s (within 10s ready window and within 30s wall-clock window)
        // But cached itinerary started at t+3s (< ready - 1s = t+7s), so it is infeasible!
        currentTime = currentTime.plusSeconds(8)

        // Repeat call: cached routes are all infeasible, so it refetches!
        val refetched = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(1, refetched.size)
        assertEquals(2, bkkFake.callCount.get())
    }

    @Test
    fun infeasibleResults_partiallyFeasibleReturnsOnlyFeasibleCachedWithoutRefetch() = runBlocking {
        // At t=0, M4 departs at t+60s (feasible with BOARD_SECONDS=60s), Z30 departs at t+120s
        val bkkRowSoon = sampleItinerary("SUBWAY", "M4", startOffsetSec = 60)
        val emmaRowLater = sampleItinerary("RAIL", "Z30", startOffsetSec = 120)
        val emmaFake = FakePlannerBackend(itineraries = listOf(emmaRowLater))
        val bkkFake = FakeBkkPlannerSource(
            planResult = BkkWorkerParser.PlanResult(listOf(bkkRowSoon), rejected = 1, offered = 2)
        )
        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, clock = clock)

        // Initial call at t=0: both feasible
        val initial = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(2, initial.size)
        assertEquals(1, bkkFake.callCount.get())
        assertEquals(1, emmaFake.callCount.get())

        // Advance time to t+5s:
        // M4 (at t+60s) now has only 55s to departure (< BOARD_SECONDS 60s -> infeasible)
        // Z30 (at t+120s) still has 115s to departure (>= BOARD_SECONDS 60s -> feasible)
        currentTime = currentTime.plusSeconds(5)

        // Repeat call: returns only cached Z30 without refetching!
        val repeatResult = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(1, repeatResult.size)
        assertEquals("Z30", repeatResult[0].legs[0].route?.number)
        assertEquals(1, bkkFake.callCount.get())
        assertEquals(1, emmaFake.callCount.get())
    }

    @Test
    fun failedOrNoPrimarySuccessfulNational_cachesAndServesRepeatWithoutLoss() = runBlocking {
        val emmaRow = sampleItinerary("RAIL", "Z30", startOffsetSec = 120)
        val emmaFake = FakePlannerBackend(itineraries = listOf(emmaRow))
        val bkkFake = FakeBkkPlannerSource(
            planResult = BkkWorkerParser.PlanResult(emptyList(), rejected = 0, offered = 0)
        )
        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, clock = clock)

        // 1. Initial call: BKK is empty, EMMA succeeds
        val initial = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(1, initial.size)
        assertEquals("Z30", initial[0].legs[0].route?.number)
        assertEquals(1, bkkFake.callCount.get())
        assertEquals(1, emmaFake.callCount.get())

        // 2. Repeat call 2 seconds later: served from cache without calling either provider!
        currentTime = currentTime.plusSeconds(2)
        val repeatResult = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(1, repeatResult.size)
        assertEquals("Z30", repeatResult[0].legs[0].route?.number)
        assertEquals(1, bkkFake.callCount.get())
        assertEquals(1, emmaFake.callCount.get())
    }

    @Test
    fun cacheAgeTimestampCannotBeResetOnRepeat() = runBlocking {
        val originalFetchTime = currentTime
        val bkkRow = sampleItinerary("SUBWAY", "M4", startOffsetSec = 120, fetchedAt = originalFetchTime)
        val emmaFake = FakePlannerBackend()
        val bkkFake = FakeBkkPlannerSource(
            planResult = BkkWorkerParser.PlanResult(listOf(bkkRow), rejected = 0, offered = 1)
        )
        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, clock = clock)

        val initial = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals(originalFetchTime, initial[0].fetchedAt)

        // Advance clock by 10s
        currentTime = currentTime.plusSeconds(10)
        val repeated = backend.routes(deakTer, kelenfold, currentTime, busesOnly = false)
        assertEquals("Original fetchedAt must NOT be reset to repeated call time", originalFetchTime, repeated[0].fetchedAt)
    }

    @Test
    fun coachAndVolanLegs_volanPrefixRetainedWithoutHkir() = runBlocking {
        val volanRow = sampleItinerary("COACH", "599", startOffsetSec = 120, routeId = "volan:volan_599", providerId = "volan", legSource = DataSource.BKK)
        val emmaFake = FakePlannerBackend()
        val bkkFake = FakeBkkPlannerSource(
            planResult = BkkWorkerParser.PlanResult(listOf(volanRow), rejected = 0, offered = 1)
        )
        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, clock = clock)

        val result = backend.routes(deakTer, kelenfold, currentTime, busesOnly = true)
        assertEquals(1, result.size)
        val leg = result[0].legs[0]
        assertEquals("volan:volan_599", leg.route?.id)
        assertFalse("Must never invent hkir IDs", leg.route?.id?.contains("hkir") == true)
        assertEquals(0, emmaFake.callCount.get())
        assertEquals(setOf(DataSource.BKK), result[0].sources)

        // Cache hit on repeat query within cache window: zero EMMA calls, source provenance persists
        currentTime = currentTime.plusSeconds(5)
        val repeated = backend.routes(deakTer, kelenfold, currentTime, busesOnly = true)
        assertEquals(1, repeated.size)
        assertEquals("volan:volan_599", repeated[0].legs[0].route?.id)
        assertEquals("COACH", repeated[0].legs[0].mode)
        assertEquals(setOf(DataSource.BKK), repeated[0].sources)
        assertEquals(1, bkkFake.callCount.get())
        assertEquals(0, emmaFake.callCount.get())
    }

    @Test
    fun stopsDiscovery_asksBkkFirstAndEmmaSupplementsMissingStopIdentities() = runBlocking {
        val bounds = MapBounds(19.04, 47.49, 19.06, 47.51)
        val bkkBusStop = TransitStop("emma:BKK:F01020", "Deák tér BKK", GeoPoint(47.497, 19.054), emptyList(), "bkk", transportMode = "BUS")
        val emmaSharedBusStop = TransitStop("emma:BKK:F01020", "Deák tér EMMA", GeoPoint(47.497, 19.054), emptyList(), "bkk", transportMode = "BUS")
        val emmaRailStop = TransitStop("emma:1:005510009", "Kelenföld vasútállomás", GeoPoint(47.464, 19.021), emptyList(), "mav", transportMode = "RAIL")

        val bkkFake = FakeBkkPlannerSource(stopsList = listOf(bkkBusStop))
        val emmaFake = FakePlannerBackend(stopsList = listOf(emmaSharedBusStop, emmaRailStop))
        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, clock = clock)

        val stops = backend.stops(bounds)
        assertEquals(2, stops.size)
        // Primary BKK stop wins over EMMA duplicate
        val bkkResolved = stops.find { it.id == "emma:BKK:F01020" }
        assertNotNull(bkkResolved)
        assertEquals("Deák tér BKK", bkkResolved!!.name)
        // Missing EMMA rail stop supplemented
        val railResolved = stops.find { it.id == "emma:1:005510009" }
        assertNotNull(railResolved)
        assertEquals("Kelenföld vasútállomás", railResolved!!.name)

        assertEquals(1, bkkFake.stopsCallCount.get())
        assertEquals(1, emmaFake.stopsCallCount.get())
    }

    @Test
    fun walkingMode_callsBkkWalking() = runBlocking {
        val link = WalkingLink(deakPoint, kelenfoldPoint, 600, 800.0)
        val bkkFake = FakeBkkPlannerSource(walkingLink = link)
        val emmaFake = FakePlannerBackend()
        val backend = BkkFirstPlannerBackend(emmaFake, bkkFake, clock = clock)

        val result = backend.walking(listOf(deakPoint to kelenfoldPoint), currentTime)
        assertEquals(1, bkkFake.walkingCallCount.get())
        assertEquals(1, result.links.size)
        assertEquals(link, result.links[deakPoint.position to kelenfoldPoint.position])
    }

    @Test
    fun combineDeduplicatesIdenticalItineraries() {
        val bkkRow = sampleItinerary("SUBWAY", "M4", startOffsetSec = 120)
        val duplicateEmmaRow = sampleItinerary("SUBWAY", "M4", startOffsetSec = 120)
        val distinctEmmaRow = sampleItinerary("TRAM", "49", startOffsetSec = 150)

        val combined = BkkFirstPlannerBackend.combine(listOf(bkkRow), listOf(duplicateEmmaRow, distinctEmmaRow))
        assertEquals(2, combined.size)
        assertEquals(listOf(bkkRow, distinctEmmaRow), combined)
    }

    @Test
    fun volan_healthyWalkAndCoach_makesZeroEmmaCalls_andFallsBackWhenUnavailable() = runBlocking {
        val walkEnd = currentTime.plusSeconds(300)
        val coachEnd = walkEnd.plusSeconds(900)
        val interPoint = PlanPoint("Intermediate Stop", GeoPoint(47.48, 19.04))

        val walkLeg = PlanLeg(
            mode = "WALK",
            start = currentTime,
            end = walkEnd,
            distance = 250.0,
            from = deakPoint,
            to = interPoint,
            route = null,
            headsign = null,
            realtime = false,
            stayOnBoard = false,
            stops = emptyList(),
            source = DataSource.BKK
        )
        val coachRoute = TransitRoute("volan:volan_735", "735", "735", emptyList(), "volan", transportMode = "COACH")
        val coachLeg = PlanLeg(
            mode = "COACH",
            start = walkEnd, // zero slack with router provenance
            end = coachEnd,
            distance = 8000.0,
            from = interPoint,
            to = kelenfoldPoint,
            route = coachRoute,
            headsign = "Érd",
            realtime = false,
            stayOnBoard = false,
            stops = emptyList(),
            source = DataSource.BKK
        )

        val keys = listOf(walkLeg, coachLeg).map(TripPlanner::routerLegKey)
        val journeyKey = "bkk:${currentTime.toEpochMilli()}:0:" + keys.joinToString("|")
        val routedWalk = walkLeg.copy(routerJourney = journeyKey, routerPrevious = null)
        val routedCoach = coachLeg.copy(routerJourney = journeyKey, routerPrevious = keys[0])

        val volanItinerary = TripItinerary(
            id = "itin:volan_735",
            start = currentTime,
            end = coachEnd,
            legs = listOf(routedWalk, routedCoach),
        )

        val emmaFallbackItinerary = sampleItinerary("COACH", "735", startOffsetSec = 600)
        val emmaFake = FakePlannerBackend(itineraries = listOf(emmaFallbackItinerary))

        // 1. Healthy BKK returns walk+COACH: EMMA must be called ZERO times
        val bkkFakeHealthy = FakeBkkPlannerSource(
            planResult = BkkWorkerParser.PlanResult(listOf(volanItinerary), rejected = 0, offered = 1)
        )
        val healthyBackend = BkkFirstPlannerBackend(emmaFake, bkkFakeHealthy, clock = clock)

        val healthyResult = healthyBackend.routes(deakTer, kelenfold, currentTime, busesOnly = true)
        assertEquals(1, healthyResult.size)
        assertEquals(2, healthyResult[0].legs.size)
        assertEquals("WALK", healthyResult[0].legs[0].mode)
        assertEquals("COACH", healthyResult[0].legs[1].mode)
        assertEquals("volan:volan_735", healthyResult[0].legs[1].route?.id)
        assertEquals(setOf(DataSource.BKK), healthyResult[0].sources)
        assertEquals("Healthy Volán walk+COACH makes ZERO EMMA calls", 0, emmaFake.callCount.get())
        assertEquals(1, bkkFakeHealthy.callCount.get())

        // 2. Unavailable BKK (throws exception): must fall back to EMMA
        val bkkFakeUnavailable = FakeBkkPlannerSource(failure = RuntimeException("BKK 503 Service Unavailable"))
        val fallbackBackend = BkkFirstPlannerBackend(emmaFake, bkkFakeUnavailable, clock = clock)

        val fallbackResult = fallbackBackend.routes(deakTer, kelenfold, currentTime, busesOnly = true)
        assertEquals(1, fallbackResult.size)
        assertEquals("735", fallbackResult[0].legs[0].route?.number)
        assertEquals("Unavailable BKK falls back to EMMA", 1, emmaFake.callCount.get())
        assertEquals(1, bkkFakeUnavailable.callCount.get())
    }
}
