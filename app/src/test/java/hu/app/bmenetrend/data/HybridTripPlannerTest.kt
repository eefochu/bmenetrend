package hu.app.bmenetrend.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HybridTripPlannerTest {
    private val ready = Instant.parse("2026-10-22T05:00:00Z")
    private val a = TransitStop("a","A",GeoPoint(47.40,18.8),listOf("via"))
    private val b = TransitStop("b","B",GeoPoint(47.42,18.8),listOf("via"))
    private val c = TransitStop("c","C",GeoPoint(47.50,18.8),listOf("homm"))
    private val d = TransitStop("d","D",GeoPoint(47.52,18.8),listOf("homm","kanyar"))
    private val e = TransitStop("e","E",GeoPoint(47.54,18.8),listOf("kanyar"))
    private fun TransitStop.endpoint() = PlannerEndpoint(id,name,"",position)
    private fun TransitStop.point() = PlanPoint(name,position)
    private fun route(id:String,provider:String,from:String,to:String) = TransitRoute(id,id,id,
        listOf(RouteDirection(id,to,listOf(from,to))),provider)
    private fun trip(id:String,from:String,to:String,start:Int,end:Int) = ScheduledTrip(id,id,id,
        listOf(StopTime(from,start),StopTime(to,end)),"school_term")
    private fun network() = TransitNetwork(listOf(a,b,c,d,e),listOf(route("via","viabusz","a","b"),
        route("homm","homm","c","d"),route("kanyar","kanyargo","d","e")),
        listOf(trip("via","a","b",422,430),trip("homm","c","d",453,463),trip("kanyar","d","e",466,476)),
        ready,"fixture",verifiedProviderIds = setOf("viabusz","kanyargo","homm"))
    private val catalogue = listOf(b,c).map { it.copy(id="emma:${it.id}",providerId="volan",transportMode="COACH") }
    private fun coach(at:Instant = ready.plusSeconds(20*60)) = PlanLeg("COACH",at,at.plusSeconds(10*60),9_000.0,
        b.point(),c.point(),TransitRoute("volan:760","760","Volán",emptyList(),"volan",transportMode="COACH"),"C",false,false,emptyList())

    private inner class FakeBackend : PlannerBackend {
        val calls = mutableListOf<Pair<String,String>>()
        var middle = coach()
        var fail = false
        var noWalks = false
        override suspend fun stops(bounds:MapBounds) = catalogue
        override suspend fun routes(from:PlannerEndpoint,to:PlannerEndpoint,ready:Instant,busesOnly:Boolean):List<TripItinerary> {
            calls += from.id to to.id
            if(fail) error("offline")
            return if(from.id == "b" && to.id == "c") listOf(TripItinerary("volan",middle.start,middle.end,listOf(middle))) else emptyList()
        }
        override suspend fun walking(pairs:List<Pair<PlanPoint,PlanPoint>>,ready:Instant):WalkingResult =
            if(noWalks) WalkingResult(emptyMap(),true) else WalkingResult(pairs.associate { (from,to) ->
                val meters = StopClusters.distanceMeters(from.position,to.position)
                (from.position to to.position) to WalkingLink(from,to,kotlin.math.ceil(meters/TripPlanner.WALK_SPEED).toLong(),meters)
            })
    }

    @Test fun localNationalLocalJourneyCanUseViaBuszVolanHommAndKanyargoTogether() = runTest {
        val backend = FakeBackend()
        val result = HybridTripPlanner(backend).plan(a.endpoint(),e.endpoint(),ready,ready,catalogue,{network()})
        val journey = result.itineraries.first()
        assertEquals(listOf("viabusz","volan","homm","kanyargo"),journey.legs.filterNot { it.walking }.map { it.route!!.providerId })
        assertTrue(TripPlanner.feasible(journey.legs,ready))
        assertFalse(result.partial)
        assertTrue(backend.calls.size <= 17)
    }
    @Test fun nationalArrivalAfterLocalDepartureCannotMakeAnImpossibleConnection() = runTest {
        val backend = FakeBackend().apply { middle = coach(ready.plusSeconds(30*60)) }
        val result = HybridTripPlanner(backend).plan(a.endpoint(),e.endpoint(),ready,ready,catalogue,{network()})
        assertTrue(result.itineraries.isEmpty())
    }
    @Test fun localNationalConnectionsWorkBeforeTheMapStopIndexHasLoaded() = runTest {
        val result = HybridTripPlanner(FakeBackend()).plan(a.endpoint(),e.endpoint(),ready,ready,emptyList(),{network()})
        assertTrue(result.itineraries.any { it.legs.any { leg -> leg.route?.providerId == "volan" } })
        assertFalse(result.partial)
    }
    @Test fun failedStreetRoutingNeverCreatesStraightLineLocalAccessOrTransfers() = runTest {
        val backend = FakeBackend().apply { noWalks = true }
        val result = HybridTripPlanner(backend).plan(a.endpoint(),e.endpoint(),ready,ready,catalogue,{network()})
        assertTrue(result.itineraries.isEmpty())
        assertTrue(result.partial)
    }
    @Test fun localOnlyJourneySurvivesNationalServiceFailureWithPartialCoverageNotice() = runTest {
        val result = HybridTripPlanner(FakeBackend().apply { fail=true }).plan(a.endpoint(),b.endpoint(),ready,ready,catalogue,{network()})
        assertTrue(result.itineraries.any { it.legs.any { leg -> leg.route?.providerId == "viabusz" } })
        assertTrue(result.partial)
    }
    @Test fun localTimetableFailureDoesNotEraseSuccessfulNationalAlternatives() = runTest {
        val backend = object : PlannerBackend {
            override suspend fun routes(from:PlannerEndpoint,to:PlannerEndpoint,ready:Instant,busesOnly:Boolean) = listOf(
                coach(ready.plusSeconds(60)).let { TripItinerary("bus",it.start,it.end,listOf(it)) })
            override suspend fun walking(pairs:List<Pair<PlanPoint,PlanPoint>>,ready:Instant) = error("not needed")
        }
        val result = HybridTripPlanner(backend).plan(b.endpoint(),c.endpoint(),ready,ready,catalogue,{error("source changed")})
        assertEquals("volan",result.itineraries.first().legs.first().route!!.providerId)
        assertTrue(result.partial)
    }
    @Test fun cancellationPropagatesInsteadOfBeingReportedAsEmptyCoverage() = runTest {
        val backend = object : PlannerBackend {
            override suspend fun routes(from:PlannerEndpoint,to:PlannerEndpoint,ready:Instant,busesOnly:Boolean):List<TripItinerary> = throw CancellationException("new request")
            override suspend fun walking(pairs:List<Pair<PlanPoint,PlanPoint>>,ready:Instant) = WalkingResult(emptyMap())
        }
        try {
            HybridTripPlanner(backend).plan(a.endpoint(),e.endpoint(),ready,ready,catalogue,{network()})
            fail("cancellation must propagate")
        } catch (_:CancellationException) { }
    }

    @Test fun localResultsDoNotWaitForSlowNationalQueries() = runTest {
        val delegate = FakeBackend()
        val backend = object : PlannerBackend by delegate {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                delay(30_000)
                return emptyList()
            }
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
                delay(1_000)
                return delegate.walking(pairs, ready)
            }
        }
        var firstResultAt: Long? = null
        val result = HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
            a.endpoint(), b.endpoint(), ready, ready, catalogue, { delay(1_000); network() }) { progress ->
            if (progress.itineraries.isNotEmpty() && firstResultAt == null) firstResultAt = testScheduler.currentTime
        }
        assertEquals(2_000L, firstResultAt)
        assertTrue(result.itineraries.any { it.legs.any { leg -> leg.route?.providerId == "viabusz" } })
    }

    @Test fun lateLocalPrefixesArePrunedAndIdenticalGatewayRequestsAreShared() = runTest {
        val calls = mutableListOf<Pair<Pair<GeoPoint, GeoPoint>, Boolean>>()
        val direct = coach(ready.plusSeconds(60)).copy(from = a.point(), to = c.point())
        val backend = object : PlannerBackend by FakeBackend() {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                calls += (from.position to to.position) to busesOnly
                return if (from.position == a.position && to.position == c.position)
                    listOf(TripItinerary("direct", direct.start, direct.end, listOf(direct))) else emptyList()
            }
        }
        // The ViaBusz prefix reaches the interchange 40 minutes after ready. The
        // national journey ends at 11 minutes; even the maximum 20-minute allowance
        // cannot make a journey using this prefix selectable.
        val late = network().copy(trips = network().trips.map { trip ->
            if (trip.routeId == "via") trip.copy(stopTimes = listOf(StopTime("a", 422), StopTime("b", 460))) else trip
        })
        val result = HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
            a.endpoint(), c.endpoint(), ready, ready, catalogue, { late })
        assertTrue(result.itineraries.isNotEmpty())
        assertTrue(calls.none { it.first.first == b.position })
        // Origin -> destination and origin -> the same local gateway share their
        // unrestricted request. The independent bus-only request must still run.
        assertEquals(listOf(false, true), calls.filter { it.first == a.position to c.position }.map { it.second }.sorted())
    }
}
