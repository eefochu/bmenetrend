package hu.app.bmenetrend.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PlannerAlternativesTest {
    private val ready = Instant.parse("2026-10-24T09:00:00Z")
    private fun journey(index:Int,mode:String,provider:String):TripItinerary {
        val start = ready.plusSeconds(60)
        val end = start.plusSeconds((index+1)*600L)
        val route = TransitRoute("$provider:$index","$index","route",emptyList(),provider,transportMode=mode)
        val leg = PlanLeg(mode,start,end,1_000.0,PlanPoint("A",GeoPoint(47.4,18.8)),PlanPoint("B",GeoPoint(47.5,18.8)),
            route,"B",false,false,emptyList())
        return TripItinerary("$index",start,end,listOf(leg))
    }
    @Test fun volanIsRetainedEvenWhenItsSourceOptionIsAfterTheFirstThreeTrains() {
        val result = TripPlanner.alternatives((0..10).map { journey(it,"RAIL","mav") } + journey(20,"COACH","volan"))
        assertTrue(result.any { it.legs.any { leg -> leg.route?.providerId == "volan" } })
        assertTrue(result.zipWithNext().all { (a,b) -> a.end <= b.end })
        assertEquals("0",result.first().id)
    }
    @Test fun alternativesRetainEachLocalOperatorWithoutFillingTheListWithRepeatedDepartures() {
        val source = (0..10).map { journey(it,"RAIL","mav") } + listOf("viabusz","kanyargo","homm").mapIndexed { i,p -> journey(20+i,"BUS",p) }
        val result = TripPlanner.alternatives(source)
        assertTrue(result.size <= 8)
        assertTrue(result.flatMap { it.legs }.mapNotNull { it.route?.providerId }.containsAll(listOf("viabusz","kanyargo","homm")))
    }
    @Test fun volanStillAppearsWhenTheFastestBusOnlyAlternativeIsBkk() {
        val source = (0..10).map { journey(it,"RAIL","mav") } + journey(12,"BUS","bkk") + journey(20,"COACH","volan")
        val providers = TripPlanner.alternatives(source).flatMap { it.legs }.map { it.route!!.providerId }
        assertTrue(providers.containsAll(listOf("bkk","volan","mav")))
    }
    @Test fun saturdayVolanResponseKeepsActualPublicBoardingStopsTimesAndCoachIdentity() {
        val text = javaClass.getResource("/planner/volan-weekend.json")!!.readText()
        val row = JSONObject(text).getJSONObject("data").getJSONObject("plan").getJSONArray("itineraries").getJSONObject(0)
        val from = PlannerEndpoint("from","Budapest, Kamaraerdő","",GeoPoint(47.434,18.975))
        val to = PlannerEndpoint("to","Biatorbágyi Innovatív Technikum","",GeoPoint(47.47959,18.8091914))
        val time = Instant.ofEpochMilli(row.getLong("startTime")).minusSeconds(60)
        val result = TripPlanner.parse(text,from,to,time,time)
        val journey = result.itineraries.single()
        val volan = journey.legs.single { it.route?.providerId == "volan" }
        assertEquals("762",volan.route!!.number)
        assertEquals("COACH",volan.mode)
        assertEquals("volan:hkir:hkir_V-R-200-762",volan.route!!.id)
        assertTrue(TripPlanner.feasible(journey.legs,time))
    }
    @Test fun busOnlySearchIncludesRegionalCoachAndCityBusAlongsideUnrestrictedTransitQuery() {
        assertTrue(TripPlanner.QUERY.contains("{mode:TRANSIT}"))
        assertTrue(TripPlanner.query(true).contains("{mode:COACH}"))
        assertTrue(TripPlanner.query(true).contains("{mode:BUS}"))
        assertFalse(TripPlanner.QUERY.contains("banned"))
    }
}
