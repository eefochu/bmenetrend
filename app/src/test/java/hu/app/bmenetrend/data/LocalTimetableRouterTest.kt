package hu.app.bmenetrend.data

import hu.app.bmenetrend.data.LocalTimetableRouter.Companion.point
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LocalTimetableRouterTest {
    private val ready = Instant.parse("2026-10-22T05:00:00Z")
    private val a = TransitStop("a", "A", GeoPoint(47.4, 18.8), listOf("via"))
    private val b = TransitStop("b", "B", GeoPoint(47.42, 18.8), listOf("via", "kanyar"))
    private val c = TransitStop("c", "C", GeoPoint(47.44, 18.8), listOf("kanyar", "homm"))
    private val d = TransitStop("d", "D", GeoPoint(47.46, 18.8), listOf("homm"))
    private fun route(id: String, provider: String, from: String, to: String, restricted: Boolean = false) =
        TransitRoute(id, id, id, listOf(RouteDirection(id, to, listOf(from, to), restricted)), provider)
    private fun trip(id: String, from: String, to: String, depart: Int, arrive: Int, calendar: String = "school_term") =
        ScheduledTrip(id, id, id, listOf(StopTime(from, depart), StopTime(to, arrive)), calendar)
    private fun network() = TransitNetwork(listOf(a,b,c,d), listOf(route("via","viabusz","a","b"),
        route("kanyar","kanyargo","b","c"),route("homm","homm","c","d")),
        listOf(trip("via","a","b",422,432),trip("kanyar","b","c",435,445),trip("homm","c","d",448,458)),
        ready,"fixture", verifiedProviderIds = setOf("viabusz","kanyargo","homm"))
    private fun seed(stop: TransitStop, at: Instant = ready) = LocalPath(stop.id, at, emptyList())
    private fun source(name: String) = NetworkJson.decode(javaClass.getResource("/$name.json")!!.readText()).let {
        it.copy(verifiedProviderIds = it.routes.map { route -> route.providerId }.toSet())
    }

    @Test fun allThreeLocalOperatorsCanConnectWithTheirPublishedTimes() {
        val router = LocalTimetableRouter(network(),ready)
        val paths = router.search(listOf(seed(a)),emptyMap())
        val journeys = router.finish(paths,mapOf("d" to WalkingLink(d.point(),d.point(),0,0.0)))
        val trip = journeys.minWith(TripPlanner.order)
        assertEquals(listOf("viabusz","kanyargo","homm"),trip.legs.filterNot { it.walking }.map { it.route!!.providerId })
        assertEquals(ready.plusSeconds(38*60),trip.end)
        assertTrue(TripPlanner.feasible(trip.legs,ready))
        assertTrue(trip.legs.all { !it.realtime })
    }
    @Test fun saturdayOnlyUsesHommWeekendTableAndNeverInventsSchoolBuses() {
        val saturday = Instant.parse("2026-10-24T03:00:00Z")
        val n = NetworkMerger.merge(listOf(source("viabusz-calendar-network"),source("kanyargo-network"),source("homm-network")))
        val paths = LocalTimetableRouter(n,saturday).search(n.stops.map { seed(it,saturday) },emptyMap())
        val rides = paths.values.flatten().flatMap { it.legs }.filterNot { it.walking }
        assertTrue(rides.isNotEmpty())
        assertEquals(setOf("homm"),rides.map { it.route!!.providerId }.toSet())
    }
    @Test fun documentVerificationSurvivesANonOperatingFetchDateAndCacheRoundTrip() {
        val saturday = Instant.parse("2026-10-24T08:00:00Z")
        val via = ViabuszTimetableParser.validateNetwork(source("viabusz-calendar-network"),
            javaClass.getResource("/viabusz-timetable-current.html")!!.readText(),
            ViabuszTimetableParser.SCHOOL_SHA,ViabuszTimetableParser.BREAK_SHA,saturday)
        assertNull(via.serviceDate)
        val cached = NetworkJson.decode(NetworkJson.encode(via))
        assertEquals(setOf("viabusz"),cached.verifiedProviderIds)
        val monday = Instant.parse("2026-10-26T04:00:00Z")
        val paths = LocalTimetableRouter(cached,monday).search(cached.stops.map { seed(it,monday) },emptyMap())
        assertTrue(paths.values.flatten().any { it.legs.any { leg -> leg.route?.providerId == "viabusz" } })
    }
    @Test fun changedOrUnverifiedProviderCannotEnterAPlannedJourney() {
        val n = network().copy(verifiedProviderIds = setOf("viabusz","homm"))
        val reached = LocalTimetableRouter(n,ready).search(listOf(seed(a)),emptyMap())
        assertTrue(reached.containsKey("b"))
        assertFalse(reached.containsKey("c"))
        assertFalse(reached.containsKey("d"))
    }
    @Test fun unreviewedCalendarYearProducesNoLocalRides() {
        val future = Instant.parse("2027-01-04T06:00:00Z")
        val reached = LocalTimetableRouter(network(),future).search(listOf(seed(a,future)),emptyMap())
        assertEquals(setOf("a"),reached.keys)
    }
    @Test fun nearbyOppositePlatformsRequireADirectedVerifiedWalkAndTransferSlack() {
        val opposite = b.copy(id = "opposite",position = GeoPoint(47.4205,18.8))
        val n = network().let { it.copy(stops = it.stops + opposite,
            routes = it.routes.map { route -> if(route.id == "kanyar") route("kanyar","kanyargo","opposite","c") else route },
            trips = it.trips.map { trip -> if(trip.routeId == "kanyar") trip("kanyar","opposite","c",436,446) else trip }) }
        val router = LocalTimetableRouter(n,ready)
        assertFalse(router.search(listOf(seed(a)),emptyMap()).containsKey("c"))
        // Arrival 07:12, 2 minutes walking plus 3 minutes transfer: 07:16 cannot be caught.
        val slow = WalkingLink(b.point(),opposite.point(),120,80.0)
        assertFalse(router.search(listOf(seed(a)),mapOf(("b" to "opposite") to slow)).containsKey("c"))
        val fast = slow.copy(seconds=60)
        assertTrue(router.search(listOf(seed(a)),mapOf(("b" to "opposite") to fast)).containsKey("c"))
    }
    @Test fun restrictedSchoolServiceKeepsItsRegistrationRequirement() {
        val n = network().let { it.copy(routes = it.routes.map { route ->
            if(route.id == "via") route.copy(directions = route.directions.map { d -> d.copy(restrictedService = true) }) else route }) }
        val reached = LocalTimetableRouter(n,ready).search(listOf(seed(a)),emptyMap())
        assertTrue(reached.getValue("b").first().legs.single().restrictedService)
    }
    @Test fun laterPublicServiceIsNotDominatedByAFasterRegistrationOnlySchoolBus() {
        val school = route("school","viabusz","a","b",true)
        val public = route("public","viabusz","a","b")
        val n = network().copy(routes = listOf(school,public),trips = listOf(
            trip("school","a","b",422,430),trip("public","a","b",423,431)))
        val paths = LocalTimetableRouter(n,ready).search(listOf(seed(a)),emptyMap()).getValue("b")
        assertEquals(setOf(false,true),paths.map { it.legs.single().restrictedService }.toSet())
    }
    @Test fun overnightTripUsesItsServiceDayRatherThanNextDaysHoliday() {
        val time = Instant.parse("2026-10-22T21:55:00Z")
        val n = network().copy(trips = listOf(trip("via","a","b",1445,1455)))
        val reached = LocalTimetableRouter(n,time).search(listOf(seed(a,time)),emptyMap())
        assertEquals(Instant.parse("2026-10-22T22:15:00Z"),reached.getValue("b").first().arrival)
    }
    @Test fun timetableWallClockRemainsCorrectAfterSundayClockChange() {
        val time = Instant.parse("2026-10-25T04:00:00Z")
        val n = network().copy(trips = listOf(trip("homm","c","d",430,440,"school_break")))
        val reached = LocalTimetableRouter(n,time).search(listOf(seed(c,time)),emptyMap())
        assertEquals(Instant.parse("2026-10-25T06:10:00Z"),reached.getValue("d").first().legs.first().start)
    }
    @Test fun boardingAllowanceExcludesAlreadyMissedLocalTrip() {
        val n = network().copy(trips = listOf(trip("via","a","b",420,430)))
        assertFalse(LocalTimetableRouter(n,ready).search(listOf(seed(a)),emptyMap()).containsKey("b"))
    }
    @Test fun tripsAtTheExactEarliestBoardingInstantAreBothConsidered() {
        val e = TransitStop("e", "E", GeoPoint(47.48, 18.8), listOf("viabusz"))
        val f = TransitStop("f", "F", GeoPoint(47.50, 18.8), listOf("viabusz"))
        val n = network().copy(stops = listOf(a,e,f),
            routes = listOf(route("to-e", "viabusz", "a", "e"), route("to-f", "viabusz", "a", "f")),
            trips = listOf(trip("to-e", "a", "e", 421, 430), trip("to-f", "a", "f", 421, 431)))
        val reached = LocalTimetableRouter(n, ready).search(listOf(seed(a)), emptyMap())
        assertEquals(1, reached.getValue("e").size)
        assertEquals(1, reached.getValue("f").size)
    }

    @Test fun equalArrivalSeedsKeepTheShorterWalkInEitherOrder() {
        val short = LocalPath("b", ready, listOf(WalkingLink(a.point(), b.point(), 0, 10.0).leg(ready)))
        val long = LocalPath("b", ready, listOf(WalkingLink(a.point(), b.point(), 0, 20.0).leg(ready)))
        for (seeds in listOf(listOf(long, short), listOf(short, long))) {
            val path = LocalTimetableRouter(network(), ready).search(seeds, emptyMap()).getValue("b").single()
            assertEquals(10.0, path.legs.filter { it.walking }.sumOf { it.distance }, 0.0)
        }
    }
}
