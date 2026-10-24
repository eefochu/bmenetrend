package hu.app.bmenetrend.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Public EMMA responses captured on 2026-10-23, including train numbers, platforms and five-hour boards. */
class RailIntegrationTest {
    private fun fixture(name: String) = javaClass.getResource("/rail/$name.json")!!.readText()
    private val live by lazy { JSONObject(fixture("live")) }
    private val now by lazy { Instant.ofEpochSecond(live.getLong("_fixtureNow")) }

    @Test fun nationalRailAndTramTrainsKeepSourceLocationsAndIndividualNumbers() {
        val vehicles = EmmaParser.parse(live.toString(), now)
        assertTrue(vehicles.size >= 4)
        assertTrue(vehicles.all { it.providerId == "mav" && RailSupport.isTrain(it.transportMode) })
        assertTrue(vehicles.any { it.transportMode == "TRAMTRAIN" })
        assertTrue(vehicles.all { '<' !in it.routeNumber.orEmpty() && it.trainNumber != null && it.serviceName != null })
        val source = live.getJSONObject("data").getJSONArray("vehicles")
        vehicles.forEach { vehicle ->
            val row = (0 until source.length()).map { source.getJSONObject(it) }.single { "emma:${it.getString("vehicleId")}" == vehicle.id }
            assertEquals(row.getDouble("lat"), vehicle.position.lat, 0.0)
            assertEquals(row.getDouble("lon"), vehicle.position.lon, 0.0)
            assertEquals(Instant.ofEpochSecond(row.getLong("lastUpdated")), vehicle.observedAt)
            assertTrue(vehicle.displayNumber.contains(vehicle.trainNumber!!))
        }
        assertEquals(vehicles, EmmaParser.localVehicles(vehicles, emptyList()))
        assertTrue(EmmaParser.parse(live.toString(), now.plusSeconds(300)).isEmpty())
    }
    @Test fun railNamesStripFontMarkupWithoutLosingLineOrTrainNumber() {
        val glyph = "<span class=\"MNR2007\">&#335;</span>"
        assertEquals("S10", RailSupport.number(glyph, "S10"))
        assertEquals("IC", RailSupport.number(glyph, "IC SAVARIA"))
        assertEquals("Sz", RailSupport.number(glyph, "Sz 9030/9032/9038"))
        assertEquals("938", RailSupport.serviceNumber("938 SAVARIA InterCity"))
        assertEquals("8511", RailSupport.serviceNumber("8511 DÉLI <- PARTI InterRégió"))
        assertEquals("S10 4918", RailSupport.label("S10", "4918"))
        assertEquals("4918", RailSupport.label("4918", "4918"))
        assertNull(RailSupport.serviceNumber(null))
        assertEquals("?", RailSupport.number(glyph, null))
    }
    @Test fun railCatalogueAndStopsAreSearchableWithoutALiveVehicle() {
        val routes = EmmaRouteParser.catalogue(fixture("routes"))
        assertTrue(routes.any { it.providerId == "mav" && it.number == "S10" })
        assertTrue(TransitSearch.routes(routes, TransitSearch.query("S10 vonat")).any { it.number == "S10" })
        val stops = EmmaStopsParser.search(fixture("stops")).stops
        assertTrue(stops.any { it.providerId == "mav" && it.transportMode == "RAIL" && it.name == "Budapest-Kelenföld" })
        val vehicles = EmmaParser.parse(live.toString(), now)
        val train = vehicles.first { it.trainNumber != null }
        assertTrue(TransitSearch.vehicles(vehicles, TransitSearch.query("${train.trainNumber} vonat")).any { it.id == train.id })
        assertTrue(TransitSearch.vehicles(vehicles, TransitSearch.query("MÁV ${train.trainNumber}")).any { it.id == train.id })
    }
    @Test fun fiveHourBoardKeepsTrainNumbersPlatformsPredictionsAndLateDepartures() {
        val text = fixture("board")
        val clock = Instant.ofEpochSecond(JSONObject(text).getLong("_fixtureNow"))
        val board = EmmaStopsParser.board(text, "emma:1:005501024_0", clock, 300)
        assertTrue(board.departures.isNotEmpty())
        assertTrue(board.departures.any { it.expectedAt > clock.plusSeconds(4 * 3600) })
        assertTrue(board.departures.all { it.expectedAt <= clock.plusSeconds(5 * 3600) && it.trainNumber != null })
        assertTrue(board.departures.any { it.platformCode != null })
        assertTrue(board.departures.all { '<' !in it.routeNumber && it.routeId.startsWith("mav:") })
        assertTrue(board.departures.any { it.kind == ArrivalKind.LIVE })
        assertFalse(board.limitReached)
        val shorter = EmmaStopsParser.board(text, "emma:1:005501024_0", clock, 30)
        assertTrue(shorter.departures.size < board.departures.size)
        assertTrue(shorter.departures.all { it.expectedAt <= clock.plusSeconds(1800) })
    }
    @Test fun trainDetailsUseActualOrderedStopsDirectionsAndGeometry() {
        val root = JSONObject(fixture("trip"))
        val trip = root.getJSONObject("data").getJSONObject("trip")
        val journey = EmmaJourneyParser.parse(root.toString(), trip.getString("gtfsId"))
        assertEquals("S10", journey.routeNumber)
        assertEquals("Budapest-Déli", journey.stops.first().name)
        assertEquals("Győr", journey.stops.last().name)
        assertEquals(24, journey.stops.size)
        assertTrue(journey.stops.all { it.providerId == "mav" && it.transportMode == "RAIL" })
        assertTrue(journey.geometry.size > 100)
        val detailsRoot = JSONObject(fixture("details"))
        val routeId = "mav:${detailsRoot.getString("_fixtureRouteId")}"
        val details = EmmaRouteParser.parse(detailsRoot.toString(), routeId)
        assertEquals("S10", details.route.number)
        assertTrue(details.patterns.size > 1)
        assertTrue(details.patterns.any { it.serviceCount > 0 && it.geometry.size > 100 })
        assertTrue(details.patterns.all { it.stops.all { stop -> stop.providerId == "mav" } })
    }
    @Test fun internationalTrainStopsRemainValidOutsideHungaryButInvalidCoordinatesDoNot() {
        val root = JSONObject(fixture("trip"))
        val trip = root.getJSONObject("data").getJSONObject("trip")
        val stop = trip.getJSONArray("stops").getJSONObject(0)
        stop.put("lat", 48.14).put("lon", 11.57).put("name", "München Hbf")
        assertEquals("München Hbf", EmmaJourneyParser.parse(root.toString(), trip.getString("gtfsId")).stops.first().name)
        stop.put("lat", 100)
        assertThrows(IllegalArgumentException::class.java) { EmmaJourneyParser.parse(root.toString(), trip.getString("gtfsId")) }
    }
    @Test fun platformSpecificTrainNextStopMatchesItsSourceParentStation() {
        val a = TransitStop("emma:1:a_0", "A", GeoPoint(47.4,19.0), emptyList(), "mav", parentStationId = "emma:1:a")
        val b = a.copy(id="emma:1:b_0", name="B", parentStationId="emma:1:b")
        val journey = VehicleJourney("1:trip","S10",null,"B",listOf(a,b))
        val train = Vehicle("v",a.position,now,providerId="mav",transportMode="RAIL",tripId="1:trip",nextStopId="emma:1:b_14",
            nextStopParentId="emma:1:b",nextStopPoint=b.position,nextStopName="B",speedKmh=50.0)
        val progress = VehicleStopProgress.resolve(train,journey,now)!!
        assertEquals(a,progress.previous); assertEquals(b,progress.next); assertEquals(1,progress.nextIndex)
        assertNull(VehicleStopProgress.resolve(train.copy(providerId="bkk"),journey,now)!!.nextIndex)
    }
}
