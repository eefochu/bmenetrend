package hu.app.bmenetrend.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

class EmmaTest {
    private val now = Instant.parse("2026-10-22T18:33:28Z")
    private val local = listOf(EmmaParser.Route("760", "Budapest – Biatorbágy – Etyek", 2810, "https://www.mavcsoport.hu/"))
    private fun bus(id: String = "hkir:hkir_TEST001") = JSONObject().apply {
        put("vehicleId", id); put("lat", 47.46); put("lon", 19.03)
        put("lastUpdated", now.minusSeconds(10).epochSecond); put("label", "TEST001")
        put("speed", 10); put("heading", 90)
        put("trip", JSONObject().put("gtfsId", "hkir:trip").put("tripHeadsign", "Etyek").put("route", JSONObject()
            .put("gtfsId", "hkir:hkir_V-R-200-760").put("shortName", "760").put("longName", "Budapest – Etyek")
            .put("mode", "COACH").put("color", "FF0000")))
    }
    private fun bkk(mode: String, routeId: String = "BKK:3040") = bus("BKK:$mode").apply {
        getJSONObject("trip").put("gtfsId", "BKK:trip").getJSONObject("route")
            .put("gtfsId", routeId).put("shortName", "4").put("mode", mode).put("color", "FFD800")
    }
    private fun response(vararg rows: JSONObject) = JSONObject().put("data", JSONObject()
        .put("vehicles", JSONArray(rows.toList()))).toString()

    @Test fun readsOtpSecondsAndMetresPerSecondAndPreservesSourceIdentity() {
        val vehicle = EmmaParser.parse(response(bus()), now).single()
        assertEquals("emma:hkir:hkir_TEST001", vehicle.id)
        assertEquals("volan:hkir:hkir_V-R-200-760", vehicle.routeId)
        assertEquals("760", vehicle.routeNumber)
        assertEquals("hkir:trip", vehicle.tripId)
        assertEquals(now.minusSeconds(10), vehicle.observedAt)
        assertEquals(36.0, vehicle.speedKmh!!, 0.0001)
        assertEquals(90.0, vehicle.heading!!, 0.0001)
        assertEquals("TEST001", vehicle.registration)
        assertNull(vehicle.markerColor) // Volán always uses the app's yellow, not source route colours.
        assertFalse(vehicle.positionMayBeInterpolated)
    }
    @Test fun preservesSourceNextStopAndItsTripPosition() {
        val row = bus().put("nextStop", JSONObject().put("stopPosition", 4).put("stop", JSONObject()
            .put("gtfsId", "hkir:stop").put("name", "Páty").put("lat", 47.51).put("lon", 18.82)))
        val parsed = EmmaParser.parse(response(row), now).single()
        assertEquals("emma:hkir:stop", parsed.nextStopId)
        assertEquals(4, parsed.nextStopPosition)
        assertEquals("Páty", parsed.nextStopName)
        assertEquals(GeoPoint(47.51, 18.82), parsed.nextStopPoint)
    }
    @Test fun acceptsBkkModesThroughEmmaWithOfficialColoursButRejectsOtherProvidersAndRail() {
        val rows = listOf("BUS", "TRAM", "TROLLEYBUS", "SUBWAY").map(::bkk)
        val result = EmmaParser.parse(response(*(rows + bkk("RAIL") + bus("VBUSZ:123") + bus("1:train")).toTypedArray()), now)
        assertEquals(4, result.size)
        assertTrue(result.all { it.providerId == "bkk" && it.markerColor == "FFD800" })
        assertNull(EmmaParser.parse(response(bkk("BUS").apply { getJSONObject("trip").getJSONObject("route").put("color", "bad value") }), now).single().markerColor)
    }
    @Test fun rejectsStaleFutureUnknownAndInvalidPositionsWithoutDiscardingValidRows() {
        val invalid = listOf(bus().put("lastUpdated", now.minusSeconds(181).epochSecond),
            bus().put("lastUpdated", now.plusSeconds(31).epochSecond), bus().put("lastUpdated", JSONObject.NULL),
            bus().put("lastUpdated", now.toEpochMilli()), bus().put("lastUpdated", "not a time"),
            bus().put("lat", JSONObject.NULL), bus().put("lon", 0), bus().put("vehicleId", "hkir:"),
            bus().apply { getJSONObject("trip").getJSONObject("route").put("gtfsId", "BKK:wrong") })
        assertEquals(1, EmmaParser.parse(response(*(invalid + bus()).toTypedArray()), now).size)
    }
    @Test fun keepsNewestDuplicateAndAcceptsVehiclesWithNoTripAndNoPlate() {
        val old = bus().put("lastUpdated", now.minusSeconds(90).epochSecond)
        val result = EmmaParser.parse(response(bus(), old, bus("hkir:hkir_UNKNOWN").put("trip", JSONObject.NULL)
            .put("label", JSONObject.NULL).put("speed", -1).put("heading", JSONObject.NULL)), now)
        assertEquals(2, result.size)
        assertEquals(now.minusSeconds(10), result.first().observedAt)
        assertEquals("UNKNOWN", result.last().registration)
        assertNull(result.last().routeId)
        assertNull(result.last().speedKmh)
    }
    @Test fun nationwideIncludesDistantVolanAndLocalScopeUsesSourceRouteIds() {
        val distant = bus("hkir:SZEGED").put("lat", 46.25).put("lon", 20.14).apply {
            getJSONObject("trip").getJSONObject("route").put("gtfsId", "hkir:hkir_V-R-200-9000").put("shortName", "9000")
        }
        val result = EmmaParser.parse(response(bus(), distant, bkk("TRAM")), now)
        assertEquals(3, result.size)
        val filtered = EmmaParser.localVehicles(result, local)
        assertEquals(2, filtered.size)
        assertTrue(filtered.any { it.providerId == "volan" && it.position.lon == 19.03 })
        assertTrue(filtered.any { it.providerId == "bkk" })
    }
    @Test fun queryUsesOnlyMavGraphqlForAllSupportedModesWithNationalBounds() {
        val request = JSONObject(EmmaParser.request())
        val query = request.getString("query")
        assertTrue(EmmaParser.ENDPOINT.startsWith("https://mavplusz.hu/"))
        assertTrue(query.contains("modes:[BUS,COACH,TRAM,TROLLEYBUS,SUBWAY,SUBURBAN_RAILWAY,RAIL,TRAMTRAIN]"))
        assertTrue(query.contains("swLat:45.7, swLon:16.1, neLat:48.6, neLon:22.9"))
        assertTrue(request.getJSONObject("variables").length() == 0)
        assertTrue(EmmaParser.parse(response(), now).isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun graphqlPartialErrorsAreNotSuccessfulEmptySnapshots() {
        EmmaParser.parse(JSONObject(response(bus())).put("errors", JSONArray().put(JSONObject().put("message", "failed"))).toString(), now)
    }
    @Test(expected = org.json.JSONException::class) fun missingListIsNotAnEmptySuccess() {
        EmmaParser.parse("""{"data":{"vehicles":null}}""", now)
    }
    @Test fun liveRouteCataloguePreservesTimetableLinksAndAvoidsShortNameCollisions() {
        val fixed = TransitRoute("volan:${local.single().sourceId}", "760", "Local", emptyList(), "volan", local.single().url)
        val rows = EmmaParser.parse(response(bus(), bkk("TRAM"), bkk("BUS", "BKK:0040")), now)
        val result = EmmaParser.routeCatalogue(rows, listOf(fixed))
        assertEquals(3, result.size)
        assertEquals(fixed.sourceUrl, result.first().sourceUrl)
        assertEquals(2, result.count { it.number == "4" })
        assertEquals(36, EmmaParser.catalogue(File("src/main/assets/volan-routes.json").readText()).size)
    }
}
