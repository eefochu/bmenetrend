package hu.viabusz.app.data

import hu.viabusz.app.map.VehicleMarkerStyle
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class EmmaJourneyTest {
    private fun stop(id: String, name: String) = JSONObject().put("gtfsId", id).put("name", name).put("lat", 47.46).put("lon", 19.02).put("platformCode", "5")
    private fun response() = JSONObject().put("data", JSONObject().put("trip", JSONObject().put("gtfsId", "hkir:trip")
        .put("tripHeadsign", "Etyek").put("route", JSONObject().put("gtfsId", "hkir:760").put("shortName", "760").put("longName", "Budapest – Etyek"))
        .put("stops", JSONArray().put(stop("hkir:first", "Kelenföld")).put(stop("hkir:last", "Etyek")).put(stop("hkir:first", "Kelenföld")))))

    @Test fun preservesActualTripDirectionAndRepeatedStopOrder() {
        val journey = EmmaJourneyParser.parse(response().toString(), "hkir:trip")
        assertEquals("760", journey.routeNumber)
        assertEquals("Etyek", journey.destination)
        assertEquals(listOf("emma:hkir:first", "emma:hkir:last", "emma:hkir:first"), journey.stops.map { it.id })
        assertEquals("5", journey.stops.first().platformCode)
    }
    @Test fun refusesResponsesForAnotherVehicleTrip() {
        assertTrue(runCatching { EmmaJourneyParser.parse(response().toString(), "hkir:other-trip") }.isFailure)
    }
    @Test fun refusesPartialErrorsAndUnavailableTripsInsteadOfInventingStops() {
        val error = response().put("errors", JSONArray().put(JSONObject().put("message", "failed")))
        assertTrue(runCatching { EmmaJourneyParser.parse(error.toString(), "hkir:trip") }.isFailure)
        assertTrue(runCatching { EmmaJourneyParser.parse("""{"data":{"trip":null}}""", "hkir:trip") }.isFailure)
    }
    @Test fun refusesMixedOperatorOrInvalidStopCoordinates() {
        val mixed = response().apply { getJSONObject("data").getJSONObject("trip").getJSONArray("stops").put(0, stop("BKK:other", "Other operator")) }
        assertTrue(runCatching { EmmaJourneyParser.parse(mixed.toString(), "hkir:trip") }.isFailure)
        val invalid = response().apply { getJSONObject("data").getJSONObject("trip").getJSONArray("stops").getJSONObject(0).put("lat", 0) }
        assertTrue(runCatching { EmmaJourneyParser.parse(invalid.toString(), "hkir:trip") }.isFailure)
    }
    @Test fun handlesSourceWithoutAStopListAsEmptyOnlyIfListIsPresent() {
        val empty = response().apply { getJSONObject("data").getJSONObject("trip").put("stops", JSONArray()) }
        assertTrue(EmmaJourneyParser.parse(empty.toString(), "hkir:trip").stops.isEmpty())
        val missing = response().apply { getJSONObject("data").getJSONObject("trip").remove("stops") }
        assertTrue(runCatching { EmmaJourneyParser.parse(missing.toString(), "hkir:trip") }.isFailure)
    }
    @Test fun iconUsesTransportModeBeforeRouteColourOrOperator() {
        fun vehicle(provider: String, mode: String?) = Vehicle("v", GeoPoint(47.46, 19.02), Instant.EPOCH, providerId = provider, transportMode = mode, markerColor = "000000")
        assertEquals(VehicleMarkerStyle.VOLAN_BUS, VehicleMarkerStyle.forVehicle(vehicle("volan", "COACH")))
        assertEquals(VehicleMarkerStyle.BKK_BUS, VehicleMarkerStyle.forVehicle(vehicle("bkk", "BUS")))
        assertEquals(VehicleMarkerStyle.TRAM, VehicleMarkerStyle.forVehicle(vehicle("bkk", "TRAM")))
        assertEquals(VehicleMarkerStyle.TROLLEY, VehicleMarkerStyle.forVehicle(vehicle("bkk", "TROLLEYBUS")))
        assertEquals(VehicleMarkerStyle.TRAIN, VehicleMarkerStyle.forVehicle(vehicle("bkk", "SUBWAY")))
        assertEquals(VehicleMarkerStyle.TRAIN, VehicleMarkerStyle.forVehicle(vehicle("mav", "RAIL")))
        assertEquals(VehicleMarkerStyle.BKK_BUS, VehicleMarkerStyle.forVehicle(vehicle("viabusz", null)))
    }
}
