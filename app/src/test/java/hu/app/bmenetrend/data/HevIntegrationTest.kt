package hu.app.bmenetrend.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Captured public MÁVPlusz responses verify the actual namespace, markup and OTP shapes. */
class HevIntegrationTest {
    private fun fixture(name: String) = javaClass.getResource("/hev/$name.json")!!.readText()
    private val live by lazy { JSONObject(fixture("live")) }
    private val now by lazy {
        val rows = live.getJSONObject("data").getJSONArray("vehicles")
        Instant.ofEpochSecond((0 until rows.length()).maxOf { rows.getJSONObject(it).getLong("lastUpdated") })
    }
    @Test fun actualHevVehiclesKeepSourceCoordinatesAndIdentity() {
        val vehicles = EmmaParser.parse(live.toString(),now)
        assertTrue(vehicles.isNotEmpty())
        assertTrue(vehicles.all { it.providerId == "hev" && it.transportMode == "SUBURBAN_RAILWAY" && it.routeNumber!!.startsWith("H") })
        val row = live.getJSONObject("data").getJSONArray("vehicles").getJSONObject(0)
        val parsed = vehicles.single { it.id == "emma:${row.getString("vehicleId")}" }
        assertEquals(row.getDouble("lat"),parsed.position.lat,0.0)
        assertEquals(row.getDouble("lon"),parsed.position.lon,0.0)
        assertTrue(parsed.routeId!!.startsWith("hev:1:"))
        assertFalse(parsed.routeNumber!!.contains('<'))
    }
    @Test fun nationalRailAndHevUseDifferentProvidersInTheSharedNamespace() {
        val row = JSONObject(live.getJSONObject("data").getJSONArray("vehicles").getJSONObject(0).toString())
        val route = row.getJSONObject("trip").getJSONObject("route")
        route.put("mode","RAIL").put("shortName","S40").put("longName","National train")
        assertEquals("mav", EmmaParser.parse(JSONObject().put("data",JSONObject().put("vehicles",org.json.JSONArray().put(row))).toString(),now).single().providerId)
        route.put("mode","SUBURBAN_RAILWAY").put("longName","S-Bahn")
        assertTrue(EmmaParser.parse(JSONObject().put("data",JSONObject().put("vehicles",org.json.JSONArray().put(row))).toString(),now).isEmpty())
    }
    @Test fun catalogueRecognizesAllFiveLinesWithoutFontMarkup() {
        val routes = EmmaRouteParser.catalogue(fixture("routes"))
        assertTrue(routes.map { it.number }.containsAll(listOf("H5","H6","H7","H8","H9")))
        assertTrue(routes.filter { it.transportMode == "SUBURBAN_RAILWAY" }.all { it.providerId == "hev" })
        assertEquals("7B116B",routes.first { it.number == "H5" }.color)
    }
    @Test fun linePatternsIncludeBothDirectionsAndActualGeometry() {
        val details = EmmaRouteParser.parse(fixture("details"),"hev:1:V3922_109")
        assertEquals("H5",details.route.number)
        assertTrue(details.patterns.any { it.headsign.contains("Szentendre") })
        assertTrue(details.patterns.any { it.headsign.contains("Batthyány") })
        assertTrue(details.patterns.all { it.geometry.size > 2 && it.stops.all { stop -> stop.providerId == "hev" } })
        val tripId = JSONObject(fixture("details")).getJSONObject("data").getJSONObject("trip").getString("gtfsId")
        val journey = EmmaJourneyParser.parse(fixture("details"),tripId)
        assertEquals("H5",journey.routeNumber)
        assertEquals(17,journey.stops.size)
        assertTrue(journey.geometry.size > 2)
    }
    @Test fun actualBoardKeepsItsStopAndRealtimeFlags() {
        val data = JSONObject(fixture("board"))
        val boardNow = Instant.ofEpochSecond(data.getLong("_fixtureNow"))
        val board = EmmaStopsParser.board(data.toString(),"emma:1:003609100_B",boardNow,60)
        assertTrue(board.departures.isNotEmpty())
        assertTrue(board.departures.all { it.routeNumber == "H5" && it.routeId.startsWith("hev:1:") && it.stopId == board.stopId })
        assertEquals("SUBURBAN_RAILWAY",board.catalogue.stops.single().transportMode)
        assertFalse(board.catalogue.routes.any { it.number.contains('<') })
    }
    @Test fun indexPreservesHevAndRailSeparatelyButExcludesUnidentifiedForeignSuburban() {
        val text = """{"data":{"stops":[{"gtfsId":"1:h","name":"Aquincum H","lat":47.5,"lon":19.0,"vehicleMode":null},{"gtfsId":"1:rail","name":"Kelenföld","lat":47.5,"lon":19.0,"vehicleMode":"RAIL"},{"gtfsId":"1:008100","name":"Wien Mitte","lat":48.2,"lon":16.4,"vehicleMode":"SUBURBAN_RAILWAY"}]}}"""
        val stops = EmmaStopsParser.index(text).stops
        assertEquals(listOf("emma:1:h", "emma:1:rail"),stops.map { it.id })
        assertEquals(listOf("hev", "mav"),stops.map { it.providerId })
    }
    @Test fun hevAliasesAndLocalScopeKeepAllFiveLines() {
        val routes = EmmaRouteParser.catalogue(fixture("routes"))
        val found = TransitSearch.routes(routes,TransitSearch.query("H5 hév"))
        assertTrue(found.isNotEmpty())
        assertTrue(found.all { it.number == "H5" })
        val vehicles = EmmaParser.parse(live.toString(),now)
        assertEquals(vehicles,EmmaParser.localVehicles(vehicles,emptyList()))
        assertTrue(EmmaParser.QUERY.contains("SUBURBAN_RAILWAY"))
    }
}
