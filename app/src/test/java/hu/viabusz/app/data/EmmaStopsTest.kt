package hu.viabusz.app.data

import hu.viabusz.app.map.StopMapClustering
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class EmmaStopsTest {
    private val now = Instant.ofEpochSecond(1_790_000_000)
    private val id = "emma:hkir:platform1"
    private fun route() = JSONObject().put("gtfsId", "hkir:route760").put("shortName", "760").put("longName", "Budapest – Etyek").put("mode", "COACH")
    private fun stop(source: String = "hkir:platform1") = JSONObject().put("gtfsId", source).put("name", "Kelenföld")
        .put("lat", 47.46).put("lon", 19.03).put("platformCode", "12").put("locationType", "STOP")
        .put("parentStation", JSONObject().put("gtfsId", "hkir:station"))
    private fun time(tripId: String = "hkir:trip1", seconds: Long = 86_500) = JSONObject()
        .put("scheduledDeparture", seconds).put("realtimeDeparture", seconds + 60).put("realtime", true)
        .put("realtimeState", "UPDATED").put("serviceDay", now.epochSecond - 86_400).put("pickupType", "SCHEDULED")
        .put("trip", JSONObject().put("gtfsId", tripId).put("tripHeadsign", "Etyek").put("route", route()))
    private fun response(vararg times: JSONObject) = JSONObject().put("data", JSONObject().put("stop", stop()
        .put("routes", JSONArray().put(route())).put("stoptimesWithoutPatterns", JSONArray(times.toList())))).toString()
    private fun index(vararg rows: JSONObject) = JSONObject().put("data", JSONObject().put("stops", JSONArray(rows.toList()))).toString()

    @Test fun streamsNationalIndexWithDistinctPlatformsAndSourceNamespaces() {
        val parsed = EmmaStopsParser.index(index(stop(), stop("hkir:platform2"), stop("BKK:platform1"), stop("1:rail"), stop("hkir:invalid").put("lat", 0)))
        assertEquals(listOf(id, "emma:hkir:platform2", "emma:BKK:platform1"), parsed.stops.map { it.id })
        assertEquals("12", parsed.stops.first().platformCode)
        assertEquals("emma:hkir:station", parsed.stops.first().parentStationId)
        assertEquals("bkk", parsed.stops.last().providerId)
    }
    @Test fun indexRejectsPartialErrorsNullAndEmptyResponses() {
        listOf("""{"data":{"stops":null}}""", """{"data":{"stops":[]}}""",
            JSONObject(index(stop())).put("errors", JSONArray().put(JSONObject().put("message", "failed"))).toString()).forEach { text ->
            assertTrue(runCatching { EmmaStopsParser.index(text) }.isFailure)
        }
    }
    @Test fun boardUsesServiceEpochAndSecondsPastMidnightAndPreservesTripIdentity() {
        val board = EmmaStopsParser.board(response(time(), time("hkir:trip2")), id, now, 60)
        assertEquals(2, board.departures.size)
        val departure = board.departures.first()
        assertEquals(now.plusSeconds(100), departure.scheduledAt)
        assertEquals(now.plusSeconds(160), departure.expectedAt)
        assertEquals(ArrivalKind.LIVE, departure.kind)
        assertEquals("volan:hkir:route760", departure.routeId)
        assertEquals("hkir:trip1", departure.directionId)
    }
    @Test fun scheduledRowsStayScheduledAndCancelledNonPickupAndOutOfWindowAreExcluded() {
        val scheduled = time().put("realtime", false)
        val board = EmmaStopsParser.board(response(scheduled, time("cancel").put("realtimeState", "CANCELED"),
            time("nopickup").put("pickupType", "NONE"), time("past", 80_000), time("future", 100_000)), id, now, 60)
        assertEquals(1, board.departures.size)
        assertEquals(ArrivalKind.SCHEDULED, board.departures.single().kind)
        assertEquals(now.plusSeconds(100), board.departures.single().expectedAt)
    }
    @Test fun successfulEmptyBoardRemainsValidAndDoesNotInventDepartures() {
        val text = JSONObject(response()).apply { getJSONObject("data").getJSONObject("stop").put("routes", JSONArray()) }.toString()
        val board = EmmaStopsParser.board(text, id, now, 60)
        assertTrue(board.departures.isEmpty())
        assertEquals(id, board.stopId)
        assertTrue(runCatching { EmmaStopsParser.board(text, "emma:BKK:other", now, 60) }.isFailure)
    }
    @Test fun oldAndWrongStopBoardsCannotLeakIntoCurrentSelection() {
        val board = EmmaStopsParser.board(response(time()), id, now, 60)
        assertEquals(1, EmmaStopsParser.freshDepartures(board, id, now.plusSeconds(20), 30).size)
        assertTrue(EmmaStopsParser.freshDepartures(board, id, now.plusSeconds(61), 60).isEmpty())
        assertTrue(EmmaStopsParser.freshDepartures(board, "emma:BKK:other", now, 60).isEmpty())
        assertTrue(EmmaStopsParser.freshDepartures(board, id, now.minusSeconds(1), 60).isEmpty())
    }
    @Test fun broadMapApproximatesStopsButDetailedMapKeepsPlatformsSeparate() {
        val stops = EmmaStopsParser.index(index(stop(), stop("hkir:platform2"), stop("BKK:other").put("lon", 19.2))).stops
        val bounds = MapBounds(19.0, 47.4, 19.1, 47.5)
        val broad = StopMapClustering.groups(stops, bounds, 8.0)
        assertEquals(1, broad.size)
        assertEquals(2, broad.single().size)
        assertEquals(2, StopMapClustering.groups(stops, bounds, 13.0).size)
        assertEquals(2, StopMapClustering.groups(stops, bounds, 15.0).size)
        assertTrue(StopMapClustering.groups(stops, null, 8.0).isEmpty())
    }
}
