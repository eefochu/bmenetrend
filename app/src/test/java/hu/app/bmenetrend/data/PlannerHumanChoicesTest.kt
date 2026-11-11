package hu.app.bmenetrend.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PlannerHumanChoicesTest {
    private val ready = Instant.parse("2026-11-11T07:00:00Z") // 08:00 Budapest
    private val school = PlanPoint("Biatorbágyi Innovatív Technikum", GeoPoint(47.47959, 18.80919))
    private val square = PlanPoint("Fő tér", GeoPoint(47.47, 18.82))
    private val otherStop = PlanPoint("Másik megálló", GeoPoint(47.475, 18.825))
    private val home = PlanPoint("Otthon", GeoPoint(47.48, 18.84))

    private fun ride(id: String, walk: Long, arrival: Long, board: Long = 240,
                     stop: PlanPoint = square, number: String = "762"): TripItinerary {
        val end = ready.plusSeconds(arrival)
        val route = TransitRoute("volan:$number", number, number, emptyList(), "volan", transportMode = "COACH")
        val bus = PlanLeg("COACH", ready.plusSeconds(board), end.minusSeconds(walk), 3000.0,
            school, stop, route, stop.name, false, false, emptyList())
        val legs = listOf(bus) + if (walk == 0L) emptyList() else listOf(
            PlanLeg("WALK", bus.end, end, walk * TripPlanner.WALK_SPEED,
                stop, home, null, null, false, false, emptyList()))
        return TripItinerary(id, bus.start, end, legs)
    }

    @Test fun same0804SchoolBusWithDifferentAlightingAndLongWalksIsOneChoice() {
        val squareExit = ride("square", 17 * 60, 40 * 60)
        // Both pass the walk limit (1020 + 600 = 1620), so this exercises sameRide.
        val earlyExit = ride("early-exit", 27 * 60, 35 * 60, stop = otherStop)
        val twentyEightMinuteExit = ride("28-minute-exit", 28 * 60, 36 * 60, stop = otherStop)
        for (rows in listOf(listOf(earlyExit, squareExit), listOf(squareExit, earlyExit),
            listOf(twentyEightMinuteExit, squareExit))) {
            assertEquals(listOf(squareExit), PlannerAlternatives.select(rows, ready))
        }
    }

    @Test fun next762DepartureCollapsesWhenWalkingAndStopsAreEquivalent() {
        val first = ride("0804", 17 * 60, 40 * 60)
        val next = ride("0809", 17 * 60, 44 * 60, board = 9 * 60)
        // Duplicate departures on the same line sequence and nearby stops collapse.
        assertEquals(listOf(first), PlannerAlternatives.select(listOf(next, first), ready))
    }

    @Test fun sameRideMergesBoardingsOnlyWithinTwoMinutesWhenWalkingIsNotEquivalent() {
        val first = ride("0804", 1020, 2400)
        for (gap in listOf(120L, 121L)) {
            val next = ride("next", 780, 2640, board = 240 + gap)
            // Walking differs by 240 s and 276 m, bypassing equivalent(). Both
            // survive the walk limit; the later, less-walking choice ranks first.
            val expected = if (gap == 120L) listOf(next) else listOf(next, first)
            for (rows in listOf(listOf(first, next), listOf(next, first))) {
                assertEquals("boarding gap=$gap", expected, PlannerAlternatives.select(rows, ready))
            }
        }
    }

    @Test fun twentyMinuteWalkIsDroppedBeforeFastestIsChosenEvenWhenTransitIsMuchLater() {
        val longWalk = ride("long-walk", 1200, 30 * 60)
        val transport = ride("transport", 540, 75 * 60, board = 50 * 60, number = "766")
        assertTrue(TripPlanner.feasible(transport.legs, ready))
        assertEquals(listOf(transport), TripPlanner.timelyChoices(listOf(longWalk, transport), ready))
    }

    @Test fun leastWalkingOptionSurvivesWhenEveryJourneyWalksALot() {
        val least = ride("least", 1800, 50 * 60)
        val excessive = ride("excessive", 2460, 48 * 60, number = "766")
        assertEquals(listOf(least), PlannerAlternatives.select(listOf(excessive, least), ready))
    }

    @Test fun walkingPenaltyIsConvexAfterTenMinutes() {
        for ((seconds, penalty) in listOf(0L to 0L, 300L to 600L, 600L to 1200L,
            601L to 1204L, 900L to 2400L, 1500L to 4800L)) {
            assertEquals("walk=$seconds", penalty, PlannerAlternatives.walkPenalty(seconds))
        }
    }

    @Test fun convexWalkingReliefAllowsThirtyMinuteLaterRideWithinSensibleWalkLimit() {
        val fastest = ride("fastest", 1500, 40 * 60)
        val easier = ride("easier", 900, 70 * 60, board = 45 * 60, number = "766")
        // Both survive the 1500 s walk limit. Linear relief was 1200 s; convex
        // relief is 2400 s, admitting the 1800 s delay and lower overall cost.
        assertEquals(listOf(easier, fastest), PlannerAlternatives.select(listOf(fastest, easier), ready))
    }

    @Test fun convexWalkingReliefAlsoAdmitsLaterLessWalkingBackup() {
        val fastest = ride("fastest", 1500, 40 * 60)
        val base = ride("easier", 900, 70 * 60, board = 45 * 60, number = "766")
        val vehicle = base.legs.first()
        val connections = (0..3).map { index ->
            val start = vehicle.start.plusSeconds(index * 195L)
            vehicle.copy(start = start, end = start.plusSeconds(15),
                route = vehicle.route!!.copy(id = "connection:$index", number = "connection:$index"))
        }
        val easier = base.copy(legs = connections + base.legs.last())
        assertTrue(TripPlanner.feasible(easier.legs, ready))
        // The three changes keep fastest cheaper. Relief must still admit the
        // 1800-second-later backup (2400 s convex allowance, 1200 s linear).
        assertEquals(listOf(fastest, easier), PlannerAlternatives.select(listOf(easier, fastest), ready))
    }

    @Test fun busArrivingThirtyMinutesAfterTwentyFiveMinuteWalkIsRecommended() {
        val walking = TripItinerary("walk", ready, ready.plusSeconds(1500), listOf(
            PlanLeg("WALK", ready, ready.plusSeconds(1500), 1500 * TripPlanner.WALK_SPEED,
                school, home, null, null, false, false, emptyList())))
        val bus = ride("bus", 0, 55 * 60, board = 40 * 60)
        assertEquals(listOf(bus), TripPlanner.timelyChoices(listOf(walking, bus), ready))
    }

    @Test fun finalWalkTriggerIncludesSplitEgressButExcludesAccessAndWalkingOnly() {
        val trip = ride("egress", 480, 1800)
        val last = trip.legs.last()
        val split = trip.copy(legs = trip.legs.dropLast(1) + listOf(
            last.copy(end = last.start.plusSeconds(240)), last.copy(start = last.start.plusSeconds(240))))
        assertEquals(480L, PlannerAlternatives.finalWalkSeconds(split))
        assertTrue(PlannerAlternatives.mayImprove(listOf(split)))
        assertFalse(PlannerAlternatives.mayImprove(listOf(ride("short", 479, 1800))))
        val access = last.copy(start = ready, end = ready.plusSeconds(480), from = school, to = school)
        val accessRide = trip.legs.first().copy(start = access.end.plusSeconds(60), end = ready.plusSeconds(1800))
        val accessOnly = TripItinerary("access-only", access.start, accessRide.end, listOf(access, accessRide))
        assertEquals(0L, PlannerAlternatives.finalWalkSeconds(accessOnly))
        assertFalse(PlannerAlternatives.mayImprove(listOf(accessOnly)))
        assertEquals(0L, PlannerAlternatives.finalWalkSeconds(trip.copy(legs = listOf(last))))
        assertFalse(PlannerAlternatives.mayImprove(emptyList()))
    }
}
