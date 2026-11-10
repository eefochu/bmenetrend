package hu.app.bmenetrend.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

class TripPlannerTest {
    private val origin = PlannerEndpoint("from", "Széll Kálmán tér", "", GeoPoint(47.5075,19.0246))
    private val destination = PlannerEndpoint("to", "Deák Ferenc tér", "", GeoPoint(47.4979,19.0548))
    private fun fixture(name:String) = javaClass.getResource("/planner/$name.json")!!.readText()
    private fun requested(text:String) = Instant.ofEpochMilli(JSONObject(text).getJSONObject("data").getJSONObject("plan")
        .getJSONArray("itineraries").getJSONObject(0).getLong("startTime")).minusSeconds(120)
    private fun parse(text:String) = TripPlanner.parse(text, origin,destination,requested(text))
    private fun leg(mode:String, start:Instant, seconds:Long, distance:Double=0.0, stay:Boolean=false) = PlanLeg(
        mode,start,start.plusSeconds(seconds),distance,PlanPoint("A",origin.position),PlanPoint("B",destination.position),null,null,false,stay,emptyList())

    @Test fun realMetroResponseIncludesStreetWalkingBoardingSlackAndSourceLine() {
        val text=fixture("metro"); val result=parse(text)
        assertTrue(result.itineraries.isNotEmpty())
        val first=result.itineraries.first()
        assertEquals("SUBWAY",first.legs.first { !it.walking }.mode)
        assertEquals("M2",first.legs.first { !it.walking }.route!!.number)
        assertTrue(first.walkMeters > 400)
        assertTrue(first.walkSeconds > 400)
        assertTrue(first.legs.all { !it.realtime })
        assertTrue(TripPlanner.feasible(first.legs,result.readyAt))
    }
    @Test fun emmaPlansBiatorbagyToKeletiWithTrainAndMetro() {
        // One trimmed live EMMA itinerary, preserving source times and line identities.
        val from = PlannerEndpoint("hkir:hkir_777140_3", "Biatorbágy, vasútállomás", "", GeoPoint(47.479964,18.83392))
        val to = PlannerEndpoint("BKK:F01336", "Budapest, Keleti pályaudvar", "", GeoPoint(47.499683,19.082881))
        val ready = Instant.parse("2026-10-23T20:38:26Z")
        val values = TripPlanner.variables(from, to, ready)
        assertEquals("47.479964,18.83392", values.getString("from"))
        assertEquals("47.499683,19.082881", values.getString("to"))
        assertTrue(TripPlanner.QUERY.contains("{mode:TRANSIT}"))
        val result = TripPlanner.parse(fixture("biatorbagy-keleti"), from, to, ready.minusSeconds(120), ready)
        val journey = result.itineraries.single()
        val rides = journey.legs.filterNot { it.walking }
        assertEquals(listOf("RAIL", "SUBWAY"), rides.map { it.mode })
        assertEquals("4941", rides.first().trainNumber)
        assertEquals("M4", rides.last().route!!.number)
        assertEquals(1, journey.transfers)
        assertTrue(TripPlanner.feasible(journey.legs, ready))
    }
    @Test fun realTransferResponseKeepsWalkingAndPracticalTransferTime() {
        val text=fixture("transfer"); val result=parse(text)
        assertTrue(result.itineraries.isNotEmpty())
        assertTrue(result.itineraries.all { it.transfers >= 1 })
        assertTrue(result.itineraries.all { TripPlanner.feasible(it.legs,result.readyAt) })
    }
    @Test fun earlierArrivalWinsOverAShortRideAfterALongWait() {
        val text=fixture("metro"); val root=JSONObject(text); val rows=root.getJSONObject("data").getJSONObject("plan").getJSONArray("itineraries")
        val earlier=rows.getJSONObject(1)
        val later=rows.getJSONObject(2)
        // A shorter ride departing an hour later must not outrank an earlier arrival.
        val legs=later.getJSONArray("legs")
        var clock=Instant.ofEpochMilli(earlier.getLong("endTime")).plusSeconds(3_600)
        later.put("startTime",clock.toEpochMilli())
        for(i in 0 until legs.length()) {
            val leg=legs.getJSONObject(i)
            if (i>0) clock=clock.plusSeconds(90)
            val seconds=if(leg.getString("mode")=="WALK") kotlin.math.ceil(leg.getDouble("distance")/TripPlanner.WALK_SPEED).toLong()+30 else 120L
            leg.put("startTime",clock.toEpochMilli()); clock=clock.plusSeconds(seconds); leg.put("endTime",clock.toEpochMilli())
        }
        later.put("endTime",clock.toEpochMilli())
        val laterDuration = later.getLong("endTime") - later.getLong("startTime")
        val earlierDuration = earlier.getLong("endTime") - earlier.getLong("startTime")
        assertTrue(laterDuration < earlierDuration)
        val result=parse(root.toString())
        assertTrue(result.itineraries.first().end.toEpochMilli() < later.getLong("endTime"))
        assertTrue(result.itineraries.zipWithNext().all { (a,b)->a.end <= b.end })
    }
    @Test fun cannotBoardAminuteAwayAfterTwoMinuteWalk() {
        val ready=Instant.parse("2026-10-23T12:00:00Z")
        val walk=leg("WALK",ready,120,100.0)
        val bus=leg("BUS",ready.plusSeconds(60),300)
        assertFalse(TripPlanner.feasible(listOf(walk,bus),ready))
        assertFalse(TripPlanner.feasible(listOf(walk,bus.copy(start=walk.end,end=walk.end.plusSeconds(300))),ready))
        assertTrue(TripPlanner.feasible(listOf(walk,bus.copy(start=walk.end.plusSeconds(60),end=walk.end.plusSeconds(360))),ready))
    }
    @Test fun transfersNeedThreeMinutesInAdditionToWalking() {
        val ready=Instant.parse("2026-10-23T12:00:00Z")
        val first=leg("BUS",ready.plusSeconds(60),300)
        val walk=leg("WALK",first.end,120,100.0)
        val tight=leg("TRAM",walk.end.plusSeconds(60),300)
        assertFalse(TripPlanner.feasible(listOf(first,walk,tight),ready))
        assertTrue(TripPlanner.feasible(listOf(first,walk,tight.copy(start=walk.end.plusSeconds(180),end=walk.end.plusSeconds(480))),ready))
    }
    @Test fun stayingOnSameVehicleDoesNotRequireAnArtificialTransfer() {
        val ready=Instant.parse("2026-10-23T12:00:00Z")
        val first=leg("BUS",ready.plusSeconds(60),300)
        assertTrue(TripPlanner.feasible(listOf(first,leg("BUS",first.end,300,stay=true)),ready))
    }
    @Test fun requestsUseBudapestDateAcrossMidnightAndWinterTime() {
        val values=TripPlanner.variables(origin,destination,Instant.parse("2026-12-31T23:30:00Z"))
        assertEquals("2027-01-01",values.getString("date")); assertEquals("00:30:00",values.getString("time"))
    }
    @Test fun malformedWalkTimesDoNotProduceImpossibleTrips() {
        val root=JSONObject(fixture("metro"));val rows=root.getJSONObject("data").getJSONObject("plan").getJSONArray("itineraries")
        for(i in 0 until rows.length()) {
            val legs=rows.getJSONObject(i).getJSONArray("legs")
            for(j in 0 until legs.length()) if(legs.getJSONObject(j).getString("mode")=="WALK")legs.getJSONObject(j).put("distance",100_000)
        }
        assertTrue(parse(root.toString()).itineraries.isEmpty())
    }
    @Test fun currentTimeExcludesAlreadyMissedStarts() {
        val text=fixture("metro")
        assertTrue(TripPlanner.parse(text,origin,destination,requested(text).plusSeconds(86_400)).itineraries.isEmpty())
    }
    @Test fun sourceErrorsAreNotTreatedAsSuccessfulEmptyResults() {
        assertThrows(Exception::class.java) { TripPlanner.parse("{\"errors\":[{\"message\":\"offline\"}]}",origin,destination,Instant.now()) }
    }
    @Test fun advancePlanningUsesChosenDepartureRatherThanFetchTime() {
        val now = Instant.parse("2026-10-23T12:00:00Z")
        val ready = TripPlanner.departure(LocalDate.of(2026,10,30), LocalTime.of(10,0), now)!!
        val values = TripPlanner.variables(origin,destination,ready)
        assertEquals("2026-10-30", values.getString("date"))
        assertEquals("10:00:00", values.getString("time"))
        val result = TripPlanner.parse(fixture("future"),origin,destination,now,ready)
        assertEquals(now,result.requestedAt)
        assertEquals(ready,result.readyAt)
        assertTrue(result.itineraries.isNotEmpty())
        assertTrue(result.itineraries.all { it.start >= ready })
        assertTrue(result.itineraries.flatMap { it.legs }.all { !it.realtime })
        assertTrue(TripPlanner.parse(fixture("future"),origin,destination,now,ready.plusSeconds(86_400)).itineraries.isEmpty())
    }
    @Test fun selectedDepartureHandlesBudapestSummerWinterAndPastTimes() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        assertEquals(Instant.parse("2026-07-10T08:00:00Z"),TripPlanner.departure(LocalDate.of(2026,7,10),LocalTime.of(10,0),now))
        assertEquals(Instant.parse("2026-12-10T09:00:00Z"),TripPlanner.departure(LocalDate.of(2026,12,10),LocalTime.of(10,0),now))
        assertNull(TripPlanner.departure(LocalDate.of(2025,12,31),LocalTime.NOON,now))
    }
    @Test fun wallTimeOnlyApiCannotReliablyRequestRepeatedOrNonexistentHours() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        assertNull(TripPlanner.departure(LocalDate.of(2026,3,29),LocalTime.of(2,30),now))
        assertNull(TripPlanner.departure(LocalDate.of(2026,10,25),LocalTime.of(2,30),now))
        assertNotNull(TripPlanner.departure(LocalDate.of(2026,10,25),LocalTime.of(3,30),now))
    }
    @Test fun journeySegmentsCoverWaitingWalkingAndRidingWithoutLostTime() {
        val trip=parse(fixture("transfer")).itineraries.first()
        val segments=trip.segments()
        assertTrue(segments.any { it.leg == null })
        assertTrue(segments.any { it.leg?.walking == true })
        assertTrue(segments.any { it.leg?.walking == false })
        assertEquals(trip.start,segments.first().start)
        assertEquals(trip.end,segments.last().end)
        assertTrue(segments.zipWithNext().all { (a,b)->a.end == b.start })
        assertEquals(Duration.between(trip.start,trip.end).toMillis(),segments.sumOf { Duration.between(it.start,it.end).toMillis() })
    }
    @Test fun initialWaitCountsTowardTotalWithoutChangingSourceLegTimes() {
        val ready = Instant.parse("2026-11-09T12:00:00Z")
        val bus = leg("BUS", ready.plusSeconds(600), 600)
        val trip = TripItinerary("bus", bus.start, bus.end, listOf(bus))
        assertEquals(600L, trip.durationSeconds)
        assertEquals(1200L, trip.totalSeconds(ready))
        val segments = trip.segments(ready)
        assertEquals(2, segments.size)
        assertNull(segments.first().leg)
        assertEquals(ready, segments.first().start)
        assertEquals(bus.start, segments.first().end)
        assertSame(bus, segments.last().leg)
        assertEquals(trip.totalSeconds(ready), segments.sumOf { Duration.between(it.start, it.end).seconds })
    }
    @Test fun parseDefaultsToActualNowWithoutTwoMinutePreparationDelay() {
        val text = fixture("metro")
        val now = requested(text)
        val plan = TripPlanner.parse(text, origin, destination, now)
        assertEquals(now, plan.readyAt)
        assertTrue(plan.itineraries.isNotEmpty())
    }
    @Test fun walkingOnlyJourneyAcrossMidnightKeepsItsFullDuration() {
        val start=Instant.parse("2026-10-30T22:55:00Z")
        val walk=leg("WALK",start,1200,1000.0)
        val trip=TripItinerary("walk",start,walk.end,listOf(walk))
        assertEquals(1,trip.segments().size)
        assertEquals(1200,trip.walkSeconds)
        assertEquals(0,trip.transfers)
        assertNotEquals(trip.start.atZone(TripPlanner.zone).toLocalDate(),trip.end.atZone(TripPlanner.zone).toLocalDate())
    }

    private fun bkkLeg(
        mode: String,
        start: Instant,
        seconds: Long,
        from: PlanPoint,
        to: PlanPoint,
        journey: String?,
        prev: String?,
        distance: Double = 0.0,
        source: DataSource? = DataSource.BKK
    ) = PlanLeg(
        mode = mode,
        start = start,
        end = start.plusSeconds(seconds),
        distance = distance,
        from = from,
        to = to,
        route = TransitRoute("bkk:1", "1", "1", emptyList(), "bkk", transportMode = mode),
        headsign = "Test",
        realtime = false,
        stayOnBoard = false,
        stops = emptyList(),
        source = source,
        routerJourney = journey,
        routerPrevious = prev
    )

    @Test
    fun bkkRouterProvenance_zeroSlackInitialWalking_isFeasible() {
        val ready = Instant.parse("2026-11-10T12:00:00Z")
        val originPt = PlanPoint("Start", GeoPoint(47.497, 19.054))
        val stopPt = PlanPoint("Stop A", GeoPoint(47.498, 19.055))
        val destPt = PlanPoint("Stop B", GeoPoint(47.505, 19.065))
        val journey = "bkk:test:journey:1"

        val walk = bkkLeg("WALK", ready, 120, originPt, stopPt, journey, null, distance = 100.0)
        val walkKey = TripPlanner.routerLegKey(walk)
        // Zero slack: bus departs immediately when walk arrives (start = walk.end)
        val bus = bkkLeg("BUS", walk.end, 300, stopPt, destPt, journey, walkKey)

        assertTrue("Zero-slack initial walk with BKK router provenance must be feasible",
            TripPlanner.feasible(listOf(walk, bus), ready))
    }

    @Test
    fun bkkRouterProvenance_intactTransferWithWalking_isFeasibleWithoutGeneric180sBuffer() {
        val ready = Instant.parse("2026-11-10T12:00:00Z")
        val stopA = PlanPoint("Stop A", GeoPoint(47.497, 19.054))
        val stopB = PlanPoint("Stop B", GeoPoint(47.500, 19.058))
        val stopC = PlanPoint("Stop C", GeoPoint(47.501, 19.059))
        val stopD = PlanPoint("Stop D", GeoPoint(47.510, 19.070))
        val journey = "bkk:test:journey:transfer"

        // Transit 1
        val bus1 = bkkLeg("BUS", ready.plusSeconds(60), 300, stopA, stopB, journey, null)
        val bus1Key = TripPlanner.routerLegKey(bus1)

        // Transfer Walk: 60 seconds duration between Stop B and Stop C (distance ~70m)
        val walk = bkkLeg("WALK", bus1.end, 60, stopB, stopC, journey, bus1Key, distance = 70.0)
        val walkKey = TripPlanner.routerLegKey(walk)

        // Transit 2 departs with only 30s slack after walk ends (total transfer window = 90s < generic 180s TRANSFER_SECONDS)
        val tram = bkkLeg("TRAM", walk.end.plusSeconds(30), 300, stopC, stopD, journey, walkKey)

        assertTrue("Intact transfer chain with walking must be feasible without 180s buffer",
            TripPlanner.feasible(listOf(bus1, walk, tram), ready))
    }

    @Test
    fun rejectGenericOrSourceOnlyFakeBkk_withoutProvenance_requiresGenericSlack() {
        val ready = Instant.parse("2026-11-10T12:00:00Z")
        val stopA = PlanPoint("Stop A", GeoPoint(47.497, 19.054))
        val stopB = PlanPoint("Stop B", GeoPoint(47.500, 19.058))
        val stopC = PlanPoint("Stop C", GeoPoint(47.501, 19.059))
        val stopD = PlanPoint("Stop D", GeoPoint(47.510, 19.070))

        // 1. Initial walk with 0 slack but NO router provenance (routerJourney/routerPrevious null)
        val fakeWalk = bkkLeg("WALK", ready, 120, stopA, stopB, journey = null, prev = null, distance = 100.0)
        val fakeBus = bkkLeg("BUS", fakeWalk.end, 300, stopB, stopC, journey = null, prev = null)
        assertFalse("Fake BKK initial walk without router provenance must be rejected with 0s slack (< 60s generic buffer)",
            TripPlanner.feasible(listOf(fakeWalk, fakeBus), ready))

        // 2. Transfer with only 30s slack after walk but NO router provenance
        val bus1 = bkkLeg("BUS", ready.plusSeconds(60), 300, stopA, stopB, journey = null, prev = null)
        val walk = bkkLeg("WALK", bus1.end, 60, stopB, stopC, journey = null, prev = null, distance = 70.0)
        val tram = bkkLeg("TRAM", walk.end.plusSeconds(30), 300, stopC, stopD, journey = null, prev = null)
        assertFalse("Transfer without router provenance must require 180s buffer and be rejected",
            TripPlanner.feasible(listOf(bus1, walk, tram), ready))

        // 3. Provenance present on next leg but source is EMMA
        val emmaWalk = bkkLeg("WALK", ready, 120, stopA, stopB, "bkk:itin", null, distance = 100.0, source = DataSource.EMMA)
        val emmaBus = bkkLeg("BUS", emmaWalk.end, 300, stopB, stopC, "bkk:itin", TripPlanner.routerLegKey(emmaWalk), source = DataSource.EMMA)
        assertFalse("Non-BKK source must not bypass generic slack",
            TripPlanner.feasible(listOf(emmaWalk, emmaBus), ready))
    }

    @Test
    fun rejectMixedJourneysAndMixedSources_whenSlackIsTight() {
        val ready = Instant.parse("2026-11-10T12:00:00Z")
        val stopA = PlanPoint("Stop A", GeoPoint(47.497, 19.054))
        val stopB = PlanPoint("Stop B", GeoPoint(47.500, 19.058))
        val stopC = PlanPoint("Stop C", GeoPoint(47.501, 19.059))
        val stopD = PlanPoint("Stop D", GeoPoint(47.510, 19.070))

        val bus1 = bkkLeg("BUS", ready.plusSeconds(60), 300, stopA, stopB, journey = "journey-A", prev = null)
        val bus1Key = TripPlanner.routerLegKey(bus1)
        // Walk belongs to journey-A
        val walk = bkkLeg("WALK", bus1.end, 60, stopB, stopC, journey = "journey-A", prev = bus1Key, distance = 70.0)
        val walkKey = TripPlanner.routerLegKey(walk)
        // Tram belongs to different journey "journey-B" -> router link broken!
        val tramMixedJourney = bkkLeg("TRAM", walk.end.plusSeconds(30), 300, stopC, stopD, journey = "journey-B", prev = walkKey)
        assertFalse("Mixed journeys must break router link and enforce generic 180s buffer",
            TripPlanner.feasible(listOf(bus1, walk, tramMixedJourney), ready))

        // Tram from EMMA -> router link broken!
        val tramMixedSource = bkkLeg("TRAM", walk.end.plusSeconds(30), 300, stopC, stopD, journey = "journey-A", prev = walkKey, source = DataSource.EMMA)
        assertFalse("Mixed feeds/sources must break router link and enforce generic 180s buffer",
            TripPlanner.feasible(listOf(bus1, walk, tramMixedSource), ready))
    }

    @Test
    fun rejectRemovedOrReorderedIntermediateWalk() {
        val ready = Instant.parse("2026-11-10T12:00:00Z")
        val stopA = PlanPoint("Stop A", GeoPoint(47.497, 19.054))
        val stopB = PlanPoint("Stop B", GeoPoint(47.500, 19.058))
        val stopC = PlanPoint("Stop C", GeoPoint(47.501, 19.059))
        val stopD = PlanPoint("Stop D", GeoPoint(47.510, 19.070))
        val journey = "journey-123"

        val bus1 = bkkLeg("BUS", ready.plusSeconds(60), 300, stopA, stopB, journey = journey, prev = null)
        val bus1Key = TripPlanner.routerLegKey(bus1)
        val walk = bkkLeg("WALK", bus1.end, 60, stopB, stopC, journey = journey, prev = bus1Key, distance = 70.0)
        val walkKey = TripPlanner.routerLegKey(walk)
        val tram = bkkLeg("TRAM", walk.end.plusSeconds(30), 300, stopC, stopD, journey = journey, prev = walkKey)

        // 1. Intermediate walk removed: bus1 -> tram (tram.routerPrevious points to walk, not bus1!)
        assertFalse("Removed intermediate walk breaks predecessor signature and must be rejected",
            TripPlanner.feasible(listOf(bus1, tram), ready))

        // 2. Reordered: walk -> bus1 -> tram
        assertFalse("Reordered legs must be rejected",
            TripPlanner.feasible(listOf(walk, bus1, tram), ready))
    }

    @Test
    fun rejectOverlappingLegs() {
        val ready = Instant.parse("2026-11-10T12:00:00Z")
        val stopA = PlanPoint("Stop A", GeoPoint(47.497, 19.054))
        val stopB = PlanPoint("Stop B", GeoPoint(47.500, 19.058))
        val stopC = PlanPoint("Stop C", GeoPoint(47.501, 19.059))
        val journey = "journey-overlap"

        val walk = bkkLeg("WALK", ready, 120, stopA, stopB, journey = journey, prev = null, distance = 100.0)
        val walkKey = TripPlanner.routerLegKey(walk)
        // Overlap: bus starts 10 seconds BEFORE walk ends
        val overlappingBus = bkkLeg("BUS", walk.end.minusSeconds(10), 300, stopB, stopC, journey = journey, prev = walkKey)

        assertFalse("Overlapping legs (start < previous.end) must be rejected",
            TripPlanner.feasible(listOf(walk, overlappingBus), ready))
    }

    @Test
    fun rejectSpatialJumps() {
        val ready = Instant.parse("2026-11-10T12:00:00Z")
        val stopA = PlanPoint("Stop A", GeoPoint(47.497, 19.054))
        val stopB = PlanPoint("Stop B", GeoPoint(47.500, 19.058))
        // Stop Far is 5km away
        val stopFar = PlanPoint("Stop Far", GeoPoint(47.550, 19.100))
        val stopEnd = PlanPoint("Stop End", GeoPoint(47.555, 19.105))
        val journey = "journey-jump"

        val bus1 = bkkLeg("BUS", ready.plusSeconds(60), 300, stopA, stopB, journey = journey, prev = null)
        val bus1Key = TripPlanner.routerLegKey(bus1)
        // Gap is 10 seconds, but distance from stopB to stopFar is ~6700 meters!
        // maxGapMeters = 20.0 + 10 * 2.2 = 42 meters << 6700 meters!
        val bus2Jump = bkkLeg("BUS", bus1.end.plusSeconds(10), 300, stopFar, stopEnd, journey = journey, prev = bus1Key)

        assertFalse("Spatial jump exceeding threshold must fail router link and require 180s buffer",
            TripPlanner.feasible(listOf(bus1, bus2Jump), ready))
    }

    @Test
    fun cachedRoutes_withValidProvenance_remainFeasible() {
        val ready = Instant.parse("2026-11-10T12:00:00Z")
        val stopA = PlanPoint("Stop A", GeoPoint(47.497, 19.054))
        val stopB = PlanPoint("Stop B", GeoPoint(47.498, 19.055))
        val stopC = PlanPoint("Stop C", GeoPoint(47.505, 19.065))
        val journey = "journey-cache"

        val walk = bkkLeg("WALK", ready.plusSeconds(60), 120, stopA, stopB, journey, null, distance = 100.0)
        val bus = bkkLeg("BUS", walk.end, 300, stopB, stopC, journey, TripPlanner.routerLegKey(walk))
        val itinerary = TripItinerary("cached-itin", walk.start, bus.end, listOf(walk, bus), fetchedAt = ready)

        // When retrieved from cache at ready time:
        assertTrue("Cached itinerary remains feasible", TripPlanner.feasible(itinerary.legs, ready))
        // Re-evaluating 30 seconds later (still before departure):
        assertTrue("Cached itinerary remains feasible before departure", TripPlanner.feasible(itinerary.legs, ready.plusSeconds(30)))
    }

    @Test
    fun bestTimely_accepts60MinuteWait_rejectsOver60Minutes() {
        val ready = Instant.parse("2026-11-10T10:00:00Z")
        val exact60 = ready.plusSeconds(3600)
        val leg60 = leg("BUS", exact60, 600)
        val trip60 = TripItinerary("exact-60", exact60, exact60.plusSeconds(600), listOf(leg60))
        assertNotNull("60-minute initial wait must be accepted", TripPlanner.bestTimely(listOf(trip60), ready))

        val over60 = ready.plusSeconds(3601)
        val legOver = leg("BUS", over60, 600)
        val tripOver = TripItinerary("over-60", over60, over60.plusSeconds(600), listOf(legOver))
        assertNull("Initial wait over 60 minutes must be rejected", TripPlanner.bestTimely(listOf(tripOver), ready))
    }

    @Test
    fun bestTimely_rejectsLongInitialWaitBeforeWalking() {
        val ready = Instant.parse("2026-11-10T10:00:00Z")
        // OTP sometimes schedules initial walk 4 hours later to catch a distant bus
        val walkStart = ready.plusSeconds(4 * 3600)
        val walk = leg("WALK", walkStart, 300, 200.0)
        val bus = leg("BUS", walk.end.plusSeconds(60), 600)
        val trip = TripItinerary("delayed-walk", walkStart, bus.end, listOf(walk, bus))
        assertNull("4-hour wait before first walk must be rejected by bestTimely", TripPlanner.bestTimely(listOf(trip), ready))
    }

    @Test
    fun bestTimely_acceptsLongWalkingAndLongRideDurations() {
        val ready = Instant.parse("2026-11-10T10:00:00Z")
        // Prompt start (60s wait), but long 2-hour walk and 3-hour train ride
        val walk = leg("WALK", ready.plusSeconds(60), 7200, 6000.0)
        val train = leg("RAIL", walk.end.plusSeconds(180), 10800)
        val trip = TripItinerary("long-journey", walk.start, train.end, listOf(walk, train))
        val chosen = TripPlanner.bestTimely(listOf(trip), ready)
        assertNotNull("Genuine long walking/ride duration must not be counted as idle wait", chosen)
        assertEquals("long-journey", chosen?.id)
    }

    @Test
    fun bestTimely_transferWaitLimits_andStayOnBoardExemption() {
        val ready = Instant.parse("2026-11-10T10:00:00Z")
        val bus1 = leg("BUS", ready.plusSeconds(60), 600)

        // 60-minute transfer wait between bus1 and bus2
        val bus2Ok = leg("BUS", bus1.end.plusSeconds(3600), 600)
        val tripOk = TripItinerary("transfer-ok", bus1.start, bus2Ok.end, listOf(bus1, bus2Ok))
        assertNotNull("Transfer wait of exactly 60 minutes is accepted", TripPlanner.bestTimely(listOf(tripOk), ready))

        // 61-minute transfer wait
        val bus2TooLong = leg("BUS", bus1.end.plusSeconds(3601), 600)
        val tripTooLong = TripItinerary("transfer-toolong", bus1.start, bus2TooLong.end, listOf(bus1, bus2TooLong))
        assertNull("Transfer wait exceeding 60 minutes must be rejected", TripPlanner.bestTimely(listOf(tripTooLong), ready))

        // stayOnBoard through-service is exempt from transfer wait limit
        val busStay = leg("BUS", bus1.end, 1800, stay = true)
        val tripStay = TripItinerary("stay-on-board", bus1.start, busStay.end, listOf(bus1, busStay))
        assertNotNull("Through-service stayOnBoard must be accepted", TripPlanner.bestTimely(listOf(tripStay), ready))
    }

    @Test
    fun bestTimely_prefersPublicServiceOverRestrictedService() {
        val ready = Instant.parse("2026-11-10T10:00:00Z")
        // Restricted service arrives 5 minutes earlier
        val restrictedLeg = PlanLeg("BUS", ready.plusSeconds(60), ready.plusSeconds(900), 5000.0,
            PlanPoint("A", origin.position), PlanPoint("B", destination.position), null, null,
            false, false, emptyList(), null, restrictedService = true)
        val restrictedTrip = TripItinerary("restricted-faster", restrictedLeg.start, restrictedLeg.end, listOf(restrictedLeg))

        // Public service arrives 5 minutes later
        val publicLeg = PlanLeg("BUS", ready.plusSeconds(120), ready.plusSeconds(1200), 5000.0,
            PlanPoint("A", origin.position), PlanPoint("B", destination.position), null, null,
            false, false, emptyList(), null, restrictedService = false)
        val publicTrip = TripItinerary("public-regular", publicLeg.start, publicLeg.end, listOf(publicLeg))

        val chosen = TripPlanner.bestTimely(listOf(restrictedTrip, publicTrip), ready)
        assertNotNull(chosen)
        assertEquals("Public service must be preferred over restricted service", "public-regular", chosen?.id)
    }

    @Test
    fun explicitLaterDeparture_doesNotShiftSourceTimes() {
        val ready = Instant.parse("2026-11-10T14:00:00Z")
        val fixedStart = Instant.parse("2026-11-10T14:15:00Z")
        val fixedEnd = Instant.parse("2026-11-10T14:45:00Z")
        val bus = leg("BUS", fixedStart, Duration.between(fixedStart, fixedEnd).seconds)
        val trip = TripItinerary("fixed-times", fixedStart, fixedEnd, listOf(bus))

        val best = TripPlanner.bestTimely(listOf(trip), ready)
        assertNotNull(best)
        assertEquals("Leg start time must remain untouched", fixedStart, best?.legs?.first()?.start)
        assertEquals("Leg end time must remain untouched", fixedEnd, best?.legs?.last()?.end)
        assertEquals("Itinerary start time must remain untouched", fixedStart, best?.start)
        assertEquals("Itinerary end time must remain untouched", fixedEnd, best?.end)
    }
}
