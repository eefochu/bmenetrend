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
    @Test fun missingRealtimeSecondsAndCancelledRowsDoNotBecomeLiveEstimates() {
        val withIncompleteRealtime = response().apply {
            getJSONObject("data").getJSONObject("trip").put("stoptimes", JSONArray()
                .put(JSONObject().put("scheduledDeparture", 51600).put("realtime", true).put("realtimeState", "UPDATED"))
                .put(JSONObject().put("scheduledArrival", 52800).put("realtimeArrival", 53100).put("realtime", true).put("realtimeState", "CANCELED")))
        }

        val stopTimes = EmmaJourneyParser.parse(withIncompleteRealtime.toString(), "hkir:trip").stopTimes

        // Without source realtime seconds the parser must use the timetable value and avoid implying a live estimate.
        assertEquals("14:20", stopTimes[0]?.arrivalTime)
        assertNull(stopTimes[0]?.scheduledTime)
        assertFalse(stopTimes[0]?.isLive == true)
        // Canceled rows retain the scheduled time; their stale/different realtime seconds are not presented as live.
        assertEquals("14:40", stopTimes[1]?.arrivalTime)
        assertNull(stopTimes[1]?.scheduledTime)
        assertFalse(stopTimes[1]?.isLive == true)
        assertEquals(0, stopTimes[1]?.delayMinutes)
    }
    @Test fun liveEstimateKeepsOriginalWhenSecondsDifferAcrossDisplayedMinuteBoundary() {
        val root = response().apply {
            getJSONObject("data").getJSONObject("trip").put("stoptimes", JSONArray()
                .put(JSONObject().put("scheduledDeparture", 51600).put("realtimeDeparture", 51600).put("realtime", false))
                // 14:40:45 to 14:41:15: delayMinutes truncates to zero, while display minutes differ.
                .put(JSONObject().put("scheduledArrival", 52845).put("realtimeArrival", 52875).put("realtime", true).put("realtimeState", "UPDATED")))
        }

        val stopTime = EmmaJourneyParser.parse(root.toString(), "hkir:trip").stopTimes[1]

        assertEquals("14:41", stopTime?.arrivalTime)
        assertEquals("14:40", stopTime?.scheduledTime)
        assertTrue(stopTime?.isLive == true)
        assertEquals(0, stopTime?.delayMinutes)
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
        assertEquals(VehicleMarkerStyle.VIABUSZ_BUS, VehicleMarkerStyle.forVehicle(vehicle("viabusz", "BUS")))
        assertEquals(VehicleMarkerStyle.HOMM_BUS, VehicleMarkerStyle.forVehicle(vehicle("homm", null)))
        assertEquals(VehicleMarkerStyle.HOMM_BUS, VehicleMarkerStyle.forVehicle(vehicle("homm", "BUS")))
        assertEquals(VehicleMarkerStyle.KANYARGO_BUS, VehicleMarkerStyle.forVehicle(vehicle("kanyargo", null)))
        assertEquals(VehicleMarkerStyle.KANYARGO_BUS, VehicleMarkerStyle.forVehicle(vehicle("kanyargo", "BUS")))

        // Transport mode priority (train, tram, trolleybus) over operator
        assertEquals(VehicleMarkerStyle.TRAIN, VehicleMarkerStyle.forVehicle(vehicle("homm", "RAIL")))
        assertEquals(VehicleMarkerStyle.TRAIN, VehicleMarkerStyle.forVehicle(vehicle("kanyargo", "SUBWAY")))
        assertEquals(VehicleMarkerStyle.TRAIN, VehicleMarkerStyle.forVehicle(vehicle("viabusz", "TRAIN")))
        assertEquals(VehicleMarkerStyle.TRAM, VehicleMarkerStyle.forVehicle(vehicle("homm", "TRAM")))
        assertEquals(VehicleMarkerStyle.TRAM, VehicleMarkerStyle.forVehicle(vehicle("kanyargo", "TRAM")))
        assertEquals(VehicleMarkerStyle.TROLLEY, VehicleMarkerStyle.forVehicle(vehicle("homm", "TROLLEYBUS")))
        assertEquals(VehicleMarkerStyle.TROLLEY, VehicleMarkerStyle.forVehicle(vehicle("kanyargo", "TROLLEYBUS")))

        // forTransport mapping & mode priority
        assertEquals(VehicleMarkerStyle.HOMM_BUS, VehicleMarkerStyle.forTransport("homm", "BUS"))
        assertEquals(VehicleMarkerStyle.HOMM_BUS, VehicleMarkerStyle.forTransport("homm", null))
        assertEquals(VehicleMarkerStyle.KANYARGO_BUS, VehicleMarkerStyle.forTransport("kanyargo", "BUS"))
        assertEquals(VehicleMarkerStyle.KANYARGO_BUS, VehicleMarkerStyle.forTransport("kanyargo", null))
        assertEquals(VehicleMarkerStyle.VIABUSZ_BUS, VehicleMarkerStyle.forTransport("viabusz", "BUS"))
        assertEquals(VehicleMarkerStyle.VIABUSZ_BUS, VehicleMarkerStyle.forTransport("viabusz", null))
        assertEquals(VehicleMarkerStyle.TRAIN, VehicleMarkerStyle.forTransport("homm", "RAIL"))
        assertEquals(VehicleMarkerStyle.TRAIN, VehicleMarkerStyle.forTransport("kanyargo", "SUBURBAN_RAILWAY"))
        assertEquals(VehicleMarkerStyle.TRAM, VehicleMarkerStyle.forTransport("homm", "TRAM"))
        assertEquals(VehicleMarkerStyle.TROLLEY, VehicleMarkerStyle.forTransport("kanyargo", "TROLLEYBUS"))

        // Operator resources and hex colors
        assertEquals(hu.app.bmenetrend.R.drawable.vehicle_homm_bus, VehicleMarkerStyle.HOMM_BUS.drawableRes)
        assertEquals(hu.app.bmenetrend.R.drawable.vehicle_kanyargo_bus, VehicleMarkerStyle.KANYARGO_BUS.drawableRes)
        assertEquals(hu.app.bmenetrend.R.drawable.vehicle_viabusz_bus, VehicleMarkerStyle.VIABUSZ_BUS.drawableRes)
        assertEquals(0xFFBF5700.toInt(), VehicleMarkerStyle.HOMM_BUS.color)
        assertEquals(0xFF6750A4.toInt(), VehicleMarkerStyle.KANYARGO_BUS.color)
        assertEquals(0xFF205C48.toInt(), VehicleMarkerStyle.VIABUSZ_BUS.color)
    }
    @Test fun returnTripUsesItsLastStopEvenWhenRouteNameAndHeadsignPointTheOtherWay() {
        val root=response()
        root.getJSONObject("data").getJSONObject("trip").put("stops",JSONArray()
            .put(stop("hkir:etyek","Etyek")).put(stop("hkir:budapest","Budapest, Kelenföld")))
        val trip=EmmaJourneyParser.parse(root.toString(),"hkir:trip")
        assertEquals("Budapest, Kelenföld",trip.destination)
        assertEquals("Etyek → Budapest, Kelenföld",JourneyDirection.endpoints(trip.stops))
    }
    @Test fun shortTurnsUseTheirActualEndInsteadOfTheLinesFullRoute() {
        val root=response()
        root.getJSONObject("data").getJSONObject("trip").put("stops",JSONArray()
            .put(stop("hkir:first","Budapest")).put(stop("hkir:short","Biatorbágy")))
        assertEquals("Biatorbágy",EmmaJourneyParser.parse(root.toString(),"hkir:trip").destination)
    }

}
