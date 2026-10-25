package hu.app.bmenetrend.data

import hu.app.bmenetrend.map.VehicleMarkerStyle
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
    @Test fun parsesStopTimesWithArrivalAndDelay() {
        val withTimes = response().apply {
            getJSONObject("data").getJSONObject("trip").put("stoptimes", JSONArray()
                .put(JSONObject().put("scheduledDeparture", 51600).put("realtimeDeparture", 51600).put("realtime", false).put("realtimeState", "SCHEDULED"))
                .put(JSONObject().put("scheduledArrival", 52800).put("realtimeArrival", 53100).put("realtime", true).put("realtimeState", "UPDATED"))
                .put(JSONObject().put("scheduledArrival", 54000).put("realtimeArrival", 54000).put("realtime", true).put("realtimeState", "UPDATED")))
        }
        val journey = EmmaJourneyParser.parse(withTimes.toString(), "hkir:trip")
        assertEquals(3, journey.stopTimes.size)
        // First stop departure: 51600s = 14:20
        assertEquals("14:20", journey.stopTimes[0]?.arrivalTime)
        assertNull(journey.stopTimes[0]?.scheduledTime)
        assertFalse(journey.stopTimes[0]?.isLive == true)
        // Second stop: scheduled 52800 (14:40), realtime 53100 (14:45), delay 5 min
        assertEquals("14:45", journey.stopTimes[1]?.arrivalTime)
        assertEquals("14:40", journey.stopTimes[1]?.scheduledTime)
        assertTrue(journey.stopTimes[1]?.isLive == true)
        assertEquals(5, journey.stopTimes[1]?.delayMinutes)
        // Third stop: on time 54000 (15:00), live
        assertEquals("15:00", journey.stopTimes[2]?.arrivalTime)
        assertNull(journey.stopTimes[2]?.scheduledTime)
        assertTrue(journey.stopTimes[2]?.isLive == true)
        assertEquals(0, journey.stopTimes[2]?.delayMinutes)
    }
    @Test fun iconUsesTransportModeBeforeRouteColourOrOperator() {
        fun vehicle(provider: String, mode: String?) = Vehicle("v", GeoPoint(47.46, 19.02), Instant.EPOCH, providerId = provider, transportMode = mode, markerColor = "000000")
        assertEquals(VehicleMarkerStyle.VOLAN_BUS, VehicleMarkerStyle.forVehicle(vehicle("volan", "COACH")))
        assertEquals(VehicleMarkerStyle.BKK_BUS, VehicleMarkerStyle.forVehicle(vehicle("bkk", "BUS")))
        assertEquals(VehicleMarkerStyle.TRAM, VehicleMarkerStyle.forVehicle(vehicle("bkk", "TRAM")))
        assertEquals(VehicleMarkerStyle.TROLLEY, VehicleMarkerStyle.forVehicle(vehicle("bkk", "TROLLEYBUS")))
        assertEquals(VehicleMarkerStyle.TRAIN, VehicleMarkerStyle.forVehicle(vehicle("bkk", "SUBWAY")))
        assertEquals(VehicleMarkerStyle.TRAIN, VehicleMarkerStyle.forVehicle(vehicle("mav", "RAIL")))
        assertEquals(VehicleMarkerStyle.VIABUSZ_BUS, VehicleMarkerStyle.forVehicle(vehicle("viabusz", null)))
    }
}
