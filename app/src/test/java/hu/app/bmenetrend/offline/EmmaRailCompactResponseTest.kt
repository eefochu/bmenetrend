package hu.app.bmenetrend.offline

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate

class EmmaRailCompactResponseTest {
    private val day = LocalDate.of(2026, 11, 2)

    private fun route(includeDestination: Boolean): JSONObject {
        val station = JSONObject().put("gtfsId", "1:station").put("name", "Kelenföld")
            .put("lat", 47.46).put("lon", 19.02)
        val origin = JSONObject(station.toString()).put("gtfsId", "1:origin")
            .put("platformCode", "2").put("parentStation", station)
        val destination = JSONObject().put("gtfsId", "1:destination").put("name", "Biatorbágy")
            .put("lat", 47.48).put("lon", 18.83)
        val calls = JSONArray()
        listOf("1:origin", "1:destination").forEachIndexed { index, id ->
            calls.put(JSONObject().put("stop", JSONObject().put("gtfsId", id))
                .put("scheduledArrival", 28800 + index * 600).put("scheduledDeparture", 28860 + index * 600)
                .put("pickupType", "SCHEDULED").put("dropoffType", "SCHEDULED"))
        }
        return JSONObject().put("gtfsId", "1:route").put("mode", "RAIL").put("longName", "S10")
            .put("stops", JSONArray().put(origin).also { if (includeDestination) it.put(destination) })
            .put("trips", JSONArray().put(JSONObject().put("gtfsId", "1:trip")
                .put("activeDates", JSONArray().put("20261102")).put("stoptimes", calls)))
    }

    @Test fun compactCallsResolveMetadataFromRouteAndKeepParentStation() {
        val directory = Files.createTempDirectory("rail-compact").toFile()
        try {
            SnapshotWriter(directory, day, day).use { it.route(route(true)) }
            val stops = GtfsCsv(directory.resolve("stops.txt").reader()).use { csv ->
                buildList { while (true) add(csv.next() ?: break) }
            }
            assertEquals(3, stops.size)
            assertEquals("1:station", stops.first { it["stop_id"] == "1:origin" }["parent_station"])
            assertEquals("2", stops.first { it["stop_id"] == "1:origin" }["platform_code"])
            assertEquals("1", stops.first { it["stop_id"] == "1:station" }["location_type"])
            assertEquals(3, directory.resolve("stop_times.txt").readLines().size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun compactCallWithoutStopMetadataRejectsSnapshot() {
        val directory = Files.createTempDirectory("rail-missing-stop").toFile()
        try {
            val failure = runCatching { SnapshotWriter(directory, day, day).use { it.route(route(false)) } }.exceptionOrNull()
            assertNotNull("Incomplete metadata must not silently drop a train call", failure)
        } finally { directory.deleteRecursively() }
    }
}
