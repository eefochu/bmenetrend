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
    @Test fun shortestDurationWinsOverEarlierArrival() {
        val text=fixture("metro"); val root=JSONObject(text); val rows=root.getJSONObject("data").getJSONObject("plan").getJSONArray("itineraries")
        val earlier=rows.getJSONObject(1)
        val later=rows.getJSONObject(2)
        // A later journey takes less total duration, so it should be placed at the top.
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
        assertEquals(laterDuration / 1000, result.itineraries.first().durationSeconds)
        assertTrue(result.itineraries.zipWithNext().all { (a,b)->a.durationSeconds <= b.durationSeconds })
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
    @Test fun preparationTimeExcludesAlreadyMissedStarts() {
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
    @Test fun walkingOnlyJourneyAcrossMidnightKeepsItsFullDuration() {
        val start=Instant.parse("2026-10-30T22:55:00Z")
        val walk=leg("WALK",start,1200,1000.0)
        val trip=TripItinerary("walk",start,walk.end,listOf(walk))
        assertEquals(1,trip.segments().size)
        assertEquals(1200,trip.walkSeconds)
        assertEquals(0,trip.transfers)
        assertNotEquals(trip.start.atZone(TripPlanner.zone).toLocalDate(),trip.end.atZone(TripPlanner.zone).toLocalDate())
    }
}
