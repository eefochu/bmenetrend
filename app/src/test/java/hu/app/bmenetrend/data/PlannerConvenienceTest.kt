package hu.app.bmenetrend.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PlannerConvenienceTest {
    private val ready = Instant.parse("2026-11-11T07:00:00Z")
    private val a = PlanPoint("Kelenföld", GeoPoint(47.464, 19.021))
    private val b = PlanPoint("Keleti", GeoPoint(47.500, 19.084))

    private fun trip(id: String, mode: String, minutes: Long, walkSeconds: Long = 0, transfers: Int = 0): TripItinerary {
        val legs = mutableListOf<PlanLeg>()
        var time = ready
        if (walkSeconds > 0) {
            legs += PlanLeg("WALK", time, time.plusSeconds(walkSeconds), walkSeconds * 1.15,
                a, a, null, null, false, false, emptyList())
            time = legs.last().end
        }
        time = time.plusSeconds(60)
        val end = ready.plusSeconds(minutes * 60)
        val riding = (end.epochSecond - time.epochSecond - transfers * 180) / (transfers + 1)
        repeat(transfers + 1) { index ->
            val route = TransitRoute("$id:$index", id, id, emptyList(), "bkk", transportMode = mode)
            val arrive = if (index == transfers) end else time.plusSeconds(riding)
            legs += PlanLeg(mode, time, arrive, 5000.0, a, b, route, b.name, false, false, emptyList())
            time = arrive.plusSeconds(180)
        }
        return TripItinerary(id, legs.first().start, end, legs)
    }

    @Test fun practicalDirectMetroBeatsSmallTrainTimeSaving() {
        val rail = trip("rail", "RAIL", 25, 300)
        val metro = trip("M4", "SUBWAY", 28, 180)
        assertEquals("M4", TripPlanner.bestTimely(listOf(rail, metro), ready)?.id)
    }

    @Test fun muchFasterTrainStillWins_withoutHardcodingMetroPreference() {
        val rail = trip("rail", "RAIL", 16, 180)
        val metro = trip("M4", "SUBWAY", 32, 180)
        assertEquals("rail", TripPlanner.bestTimely(listOf(metro, rail), ready)?.id)
    }

    @Test fun simpleDirectRouteBeatsSmallSavingWithExtraChanges() {
        val changes = trip("two-changes", "BUS", 24, 120, 2)
        val direct = trip("direct", "SUBWAY", 28, 120)
        assertEquals("direct", TripPlanner.bestTimely(listOf(changes, direct), ready)?.id)
    }

    @Test fun nearbyBoardingBeatsLongAccessWalkForSmallTimeSaving() {
        val distant = trip("distant", "BUS", 30, 840)
        val nearby = trip("nearby", "BUS", 33, 180)
        assertEquals("nearby", TripPlanner.bestTimely(listOf(distant, nearby), ready)?.id)
    }

    @Test fun waitingAtNearestStopBeatsWalkingToEarlierMetro() {
        val nearby = trip("nearby", "BUS", 35, 60)
        val metro = trip("walk-to-metro", "SUBWAY", 28, 420)
        val journeys = listOf(nearby, metro)
        // Costs: nearby = 2100 + 2*60 = 2220; metro = 1680 + 2*420 = 2520.
        // Arrival delay 420 fits max(420, min(2700, 2*(420-60))) = 720.
        assertEquals("nearby", TripPlanner.bestTimely(journeys, ready)?.id)
        assertEquals(listOf("nearby", "walk-to-metro"), TripPlanner.timelyChoices(journeys, ready).map { it.id })
    }

    @Test fun nearestStopDoesNotJustifyUnboundedArrivalDelay() {
        val nearby = trip("nearby", "BUS", 48, 60)
        val metro = trip("walk-to-metro", "SUBWAY", 28, 240)
        val journeys = listOf(nearby, metro)
        // Costs: nearby = 2880 + 2*60 = 3000; metro = 1680 + 2*240 = 2160.
        // Arrival delay 1200 exceeds max(420, min(2700, 2*(240-60))) = 420,
        // and also exceeds the 900-second backup window.
        assertEquals("walk-to-metro", TripPlanner.bestTimely(journeys, ready)?.id)
        assertEquals(listOf("walk-to-metro"), TripPlanner.timelyChoices(journeys, ready).map { it.id })
    }

    @Test fun nearestStopPreferenceIsIndependentOfInputOrder() {
        val nearby = trip("nearby", "BUS", 35, 60)
        val metro = trip("walk-to-metro", "SUBWAY", 28, 420)
        // The same 2220 vs. 2520 costs and 420 <= 720 arrival window apply in either order.
        for (journeys in listOf(listOf(nearby, metro), listOf(metro, nearby))) {
            assertEquals("nearby", TripPlanner.bestTimely(journeys, ready)?.id)
            assertEquals(listOf("nearby", "walk-to-metro"), TripPlanner.timelyChoices(journeys, ready).map { it.id })
        }
    }

    @Test fun choiceOrderIsIndependentOfResponseOrder() {
        val rail = trip("rail", "RAIL", 25, 300)
        val metro = trip("M4", "SUBWAY", 28, 180)
        assertEquals(TripPlanner.timelyChoices(listOf(rail, metro), ready), TripPlanner.timelyChoices(listOf(metro, rail), ready))
    }

    @Test fun threeUsefulChoicesReserveNearestStopInsteadOfAnotherEarlierRoute() {
        val recommended = trip("recommended", "BUS", 30, 300)
        val other = trip("other", "BUS", 31, 300)
        val next = trip("next", "BUS", 32, 300)
        val nearest = trip("nearest", "BUS", 42, 60)
        // Costs: 2400, 2460, 2520, 2640. Nearest's 720 s delay exceeds the
        // recommendation window max(450, 2*240) = 480, but fits the 900 s backup window.
        // Saving 240 walking seconds reserves its slot ahead of the cheaper "next" backup.
        assertEquals(listOf("recommended", "other", "nearest"),
            TripPlanner.timelyChoices(listOf(next, nearest, other, recommended), ready).map { it.id })
    }

    @Test fun nearZeroWalkingIsRecommendedDespiteSeventeenMinuteLaterArrival() {
        val fastest = trip("fastest", "BUS", 30, 600)
        val firstBackup = trip("first-backup", "BUS", 31, 600)
        val nextBackup = trip("next-backup", "BUS", 32, 600)
        val nearest = trip("nearest", "BUS", 47, 30)
        // Nearest costs 2820 + 60 = 2880 versus fastest's 1800 + 1200 = 3000,
        // and its 1020 s delay fits max(450, min(2700, 2*570)) = 1140 s.
        // It is now recommended; the 3000- and 3060-cost earlier trips fill the backups.
        assertEquals(listOf("nearest", "fastest", "first-backup"),
            TripPlanner.timelyChoices(listOf(nextBackup, firstBackup, nearest, fastest), ready).map { it.id })
    }

    @Test fun onlyTwoChoicesWhenThirdIsFiveMinutesLaterWithNoAdvantage() {
        val best = trip("best", "BUS", 30, 120)
        val backup = trip("backup", "BUS", 31, 120)
        val pointless = trip("pointless", "BUS", 36, 120)
        // Costs 2040 < 2100 < 2400; all fit the 900 s backup window. Pointless
        // arrives 300 s after backup (360 s after best), with equal walking and transfers.
        assertEquals(listOf("best", "backup"),
            TripPlanner.timelyChoices(listOf(pointless, backup, best), ready).map { it.id })
    }

    @Test fun biatorbagyMorningDropsWorseSameArrivalVariantsAndExcessiveWalking() {
        // Relative to the 08:30 request: walk arrives 09:08; all buses arrive 09:36.
        val walkEnd = ready.plusSeconds(38 * 60)
        val walking = TripItinerary("walking-only", ready, walkEnd, listOf(
            PlanLeg("WALK", ready, walkEnd, 2280 * TripPlanner.WALK_SPEED,
                a, b, null, null, false, false, emptyList())))
        val recommended = trip("recommended", "BUS", 66, 840).let { original ->
            val access = original.legs.first().copy(end = ready.plusSeconds(6 * 60))
            val ride = original.legs.last().copy(start = access.end.plusSeconds(60),
                end = original.end.minusSeconds(8 * 60),
                route = original.legs.last().route!!.copy(number = "2"))
            val egress = access.copy(start = ride.end, end = original.end, distance = 480 * TripPlanner.WALK_SPEED,
                from = b, to = b)
            original.copy(legs = listOf(access.copy(distance = 360 * TripPlanner.WALK_SPEED), ride, egress))
        }
        val oneTransfer = trip("one-transfer", "BUS", 66, 1020, 1)
        val twoTransfers = trip("two-transfers", "BUS", 66, 1080, 2)
        // The 38-minute walk exceeds minWalk(840) + 600 = 1440 seconds.
        // The walk limit runs before fastest selection; worse same-arrival
        // transit variants do not earn a backup slot either.
        assertEquals(840L, recommended.walkSeconds)
        assertEquals(0, recommended.transfers)
        val candidates = listOf(twoTransfers, walking, oneTransfer, recommended)
        for (offset in candidates.indices) {
            val rotated = candidates.drop(offset) + candidates.take(offset)
            for (source in listOf(rotated, rotated.reversed())) {
                assertEquals(listOf(recommended), TripPlanner.timelyChoices(source, ready))
            }
        }
    }

    @Test fun firstBackupWithSameArrivalAndWalkingButExtraTransferIsDropped() {
        val recommended = trip("recommended", "BUS", 30, 120)
        val extraTransfer = trip("extra-transfer", "BUS", 30, 120, 1)
        assertEquals(listOf(recommended),
            TripPlanner.timelyChoices(listOf(extraTransfer, recommended), ready))
    }

    @Test fun firstBackupWithFiftySecondsMoreWalkingAtSameArrivalIsKept() {
        val recommended = trip("recommended", "BUS", 30, 120)
        val backup = trip("backup", "BUS", 30, 170)
        // 50 s more walking is below the inclusive 60 s strictly-worse threshold.
        assertEquals(listOf(recommended, backup),
            TripPlanner.timelyChoices(listOf(backup, recommended), ready))
    }

    @Test fun firstBackupWithSixtySecondsMoreWalkingAtSameArrivalIsDropped() {
        val recommended = trip("recommended", "BUS", 30, 120)
        val backup = trip("backup", "BUS", 30, 180)
        assertEquals(listOf(recommended),
            TripPlanner.timelyChoices(listOf(backup, recommended), ready))
    }

    @Test fun firstBackupOneMinuteLaterWithIdenticalWalkingAndTransfersIsKept() {
        val recommended = trip("recommended", "BUS", 30, 120)
        val nextDeparture = trip("next-departure", "BUS", 31, 120)
        // Costs 2040/2100; a later arrival alone is not strictly worse.
        assertEquals(listOf(recommended, nextDeparture),
            TripPlanner.timelyChoices(listOf(nextDeparture, recommended), ready))
    }

    @Test fun onlyOneChoiceWhenOtherRoutesMissTheirBackupWindows() {
        val best = trip("best", "BUS", 30, 600)
        val sameWalking = trip("same-walking", "BUS", 46, 600)
        val noWalking = trip("no-walking", "BUS", 51)
        // Same-walking is 960 s late > 900; no-walking is 1260 s late >
        // max(900, min(2700, 2*600)) = 1200. Neither can fill a spare slot.
        assertEquals(listOf("best"),
            TripPlanner.timelyChoices(listOf(noWalking, best, sameWalking), ready).map { it.id })
    }

    @Test fun manyCandidatesAreCappedAtThreeRegardlessOfInputOrder() {
        val candidates = (30L..37L).map { trip("route-$it", "BUS", it, 120) }
        // Costs start at 2040, 2100, 2160 and rise by 60. All fit the 900 s
        // backup window; the first three differ by <300 s, so none is outclassed.
        // Exercise every rotation in both directions, changing which candidate arrives first.
        for (offset in candidates.indices) {
            val rotated = candidates.drop(offset) + candidates.take(offset)
            for (source in listOf(rotated, rotated.reversed())) {
                assertEquals(listOf("route-30", "route-31", "route-32"),
                    TripPlanner.timelyChoices(source, ready).map { it.id })
            }
        }
    }

    @Test fun usefulTrainBackupIsNotHiddenWhenMetroIsMuchMoreDirect() {
        val metro = trip("M4", "SUBWAY", 19, 300)
        val rail = trip("rail", "RAIL", 32, 180)
        assertEquals(listOf("M4", "rail"), TripPlanner.timelyChoices(listOf(rail, metro), ready).map { it.id })
    }

    @Test fun lessWalkingBackupCanArriveMoreThanTwentyMinutesAfterFastest() {
        val fastest = trip("fastest", "BUS", 40, 900)
        val lessWalking = trip("less-walking", "BUS", 65, 0, transfers = 4)
        // Both survive the 900-second walk limit. Convex walk relief is 2400 s:
        // fastest costs 2400 + 2400 = 4800; backup 3900 + 4*300 = 5100.
        // The backup's 1500 s delay is allowed without making it the recommendation.
        assertEquals(listOf("fastest", "less-walking"),
            TripPlanner.timelyChoices(listOf(lessWalking, fastest), ready).map { it.id })
    }

    private fun biatorbagyAfternoon(): Pair<TripItinerary, TripItinerary> {
        val requested = Instant.parse("2026-11-11T13:30:00Z") // 14:30 Budapest
        val walkEnd = requested.plusSeconds(38 * 60) // 15:08
        val walking = TripItinerary("walk", requested, walkEnd, listOf(
            PlanLeg("WALK", requested, walkEnd, 2280 * TripPlanner.WALK_SPEED, a, b, null, null, false, false, emptyList())))
        val board = requested.plusSeconds(48 * 60) // 15:18
        val access = PlanLeg("WALK", board.minusSeconds(300), board.minusSeconds(60), 276.0,
            a, a, null, null, false, false, emptyList())
        val arrival = requested.plusSeconds(63 * 60 + 39) // 15:33:39
        val ride = PlanLeg("COACH", board, arrival.minusSeconds(240), 5000.0, a, b,
            TransitRoute("volan:766", "766", "766", emptyList(), "volan", transportMode = "COACH"),
            b.name, false, false, emptyList())
        val egress = access.copy(start = ride.end, end = arrival, from = b, to = b)
        return walking to TripItinerary("766", access.start, arrival, listOf(access, ride, egress))
    }

    @Test fun biatorbagyAfternoonRecommends766AndDropsThirtyEightMinuteWalk() {
        val (walking, bus) = biatorbagyAfternoon()
        // The bus walks 480 seconds, so the walk limit is 1080 seconds.
        // The 2280-second walking-only suggestion is dropped before ranking.
        assertEquals(480L, bus.walkSeconds)
        for (source in listOf(listOf(walking, bus), listOf(bus, walking))) {
            assertEquals(listOf("766"), TripPlanner.timelyChoices(source, walking.start).map { it.id })
        }
    }

    @Test fun walkLimitRunsBeforeArrivalAllowanceSoLongWalkCannotExcludeOnlySensibleBus() {
        val (walking, bus) = biatorbagyAfternoon()
        val shift = java.time.Duration.between(bus.end, walking.end.plusSeconds(2700))
        // Keep boarding at 15:18; lengthen the ride so the boundary is tested
        // without creating an initial wait above the independent one-hour limit.
        val boundary = bus.copy(end = bus.end.plus(shift), legs = listOf(bus.legs[0],
            bus.legs[1].copy(end = bus.legs[1].end.plus(shift)),
            bus.legs[2].copy(start = bus.legs[2].start.plus(shift), end = bus.end.plus(shift))))
        val tooLate = boundary.copy(end = boundary.end.plusSeconds(1), legs = listOf(boundary.legs[0],
            boundary.legs[1].copy(end = boundary.legs[1].end.plusSeconds(1)),
            boundary.legs[2].copy(start = boundary.legs[2].start.plusSeconds(1), end = boundary.end.plusSeconds(1))))
        // Both buses board within an hour. Once the excessive walk is filtered,
        // each is the fastest usable journey; the removed walk cannot set its arrival cutoff.
        assertEquals(listOf("766"), TripPlanner.timelyChoices(listOf(walking, boundary), walking.start).map { it.id })
        assertEquals(listOf("766"), TripPlanner.timelyChoices(listOf(walking, tooLate), walking.start).map { it.id })
    }

    @Test fun movementExcludesWaitsAndWalkingDoesNotCountAsBoarding() {
        val first = PlanLeg("WALK", ready, ready.plusSeconds(60), 70.0, a, a, null, null, false, false, emptyList())
        val second = first.copy(start = first.end, end = first.end.plusSeconds(120), distance = 140.0)
        val vehicle = trip("M4", "SUBWAY", 12).legs.single().copy(start = second.end.plusSeconds(180))
        val finalWalk = first.copy(start = vehicle.end.plusSeconds(120), end = vehicle.end.plusSeconds(180), from = b, to = b)
        val journey = TripItinerary("walk-wait-ride", first.start, finalWalk.end, listOf(first, second, vehicle, finalWalk))
        val movement = journey.travelLegs()
        assertEquals(listOf("WALK", "SUBWAY", "WALK"), movement.map { it.mode })
        assertEquals(0, journey.transfers)
        assertEquals(240L, journey.walkSeconds)
        val movementSeconds = movement.sumOf { java.time.Duration.between(it.start, it.end).seconds }
        assertEquals(300L, journey.totalSeconds(ready) - movementSeconds)
        assertEquals(first.start, journey.legs.first().start)
        assertEquals(4, journey.legs.size) // Rendering aggregation never edits the source itinerary.
    }

    @Test fun healthyPlannerKeepsRecommendationAndDifferentBackupTogether() = runTest {
        val rail = trip("rail", "RAIL", 25, 300)
        val metro = trip("M4", "SUBWAY", 28, 180)
        val backend = object : PlannerBackend {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean) = listOf(rail, metro)
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant) = WalkingResult(emptyMap())
        }
        val result = HybridTripPlanner(backend).plan(
            PlannerEndpoint("a", a.name, "", a.position), PlannerEndpoint("b", b.name, "", b.position),
            ready, ready, emptyList(), { error("No local network needed") }, localProviders = emptySet())
        assertEquals(listOf("M4", "rail"), result.itineraries.map { it.id })
    }
}
