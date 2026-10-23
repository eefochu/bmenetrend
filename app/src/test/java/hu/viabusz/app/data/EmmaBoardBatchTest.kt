package hu.viabusz.app.data

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class EmmaBoardBatchTest {
    private fun fixture() = JSONObject(javaClass.getResource("/rail/board.json")!!.readText())
    @Test fun batchedBoardIsTheSameSourceBoardWithoutExtraHttpRequests() {
        val root=fixture();val now=Instant.ofEpochSecond(root.getLong("_fixtureNow"));val id="emma:1:005501024_0"
        val direct=EmmaStopsParser.board(root.toString(),id,now,300)
        val response=JSONObject().put("data",JSONObject().put("board0",root.getJSONObject("data").getJSONObject("stop")))
        assertEquals(direct,EmmaStopsParser.batch(response.toString(),listOf(id),now,300)[id])
        val query=EmmaStopsParser.batchQuery(6)
        assertTrue(query.contains("board5:stop(id:\$id5)"))
        assertTrue(query.contains("numberOfDepartures:300"))
        assertThrows(IllegalArgumentException::class.java) { EmmaStopsParser.batchQuery(7) }
    }
    @Test fun aliasScopedFailurePreservesIndependentSuccessfulStations() {
        val root=fixture();val now=Instant.ofEpochSecond(root.getLong("_fixtureNow"));val id="emma:1:005501024_0"
        val response=JSONObject().put("data",JSONObject().put("board0",root.getJSONObject("data").getJSONObject("stop")).put("board1",JSONObject.NULL))
            .put("errors",JSONArray().put(JSONObject().put("message","station unavailable").put("path",JSONArray().put("board1").put("stoptimesForPatterns"))))
        val boards=EmmaStopsParser.batch(response.toString(),listOf(id,"emma:BKK:other"),now,300)
        assertTrue(boards[id]!!.departures.isNotEmpty());assertNull(boards["emma:BKK:other"])
        response.getJSONArray("errors").getJSONObject(0).remove("path")
        assertThrows(IllegalArgumentException::class.java) { EmmaStopsParser.batch(response.toString(),listOf(id,"emma:BKK:other"),now,300) }
    }
}
