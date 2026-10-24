package hu.app.bmenetrend.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class PlannerBackendTest {
    private val ready = Instant.parse("2026-10-24T09:00:00Z")
    private val a = PlanPoint("A",GeoPoint(47.4,18.8))
    private val b = PlanPoint("B",GeoPoint(47.401,18.8))
    private fun backend(handler:(JSONObject)->JSONObject):EmmaPlannerBackend {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
            val json = handler(JSONObject(buffer.readUtf8()))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toString().toResponseBody()).build()
        }.build()
        return EmmaPlannerBackend(EmmaGraphqlClient(http,EmmaRequestBackoff()))
    }
    private fun walk(from:PlanPoint,to:PlanPoint,seconds:Long=120):JSONObject {
        fun point(p:PlanPoint) = JSONObject().put("name",p.name).put("lat",p.position.lat).put("lon",p.position.lon)
        val leg = JSONObject().put("mode","WALK").put("startTime",ready.toEpochMilli())
            .put("endTime",ready.plusSeconds(seconds).toEpochMilli()).put("distance",150.0)
            .put("from",point(from)).put("to",point(to))
        return JSONObject().put("itineraries",JSONArray().put(JSONObject().put("startTime",ready.toEpochMilli())
            .put("endTime",ready.plusSeconds(seconds).toEpochMilli()).put("legs",JSONArray().put(leg))))
    }
    @Test fun streetLinksAreDirectedAndReuseTheirSourceDurationWhenReplanned() = runBlocking {
        val calls=AtomicInteger()
        val backend=backend { request ->
            calls.incrementAndGet()
            assertTrue(request.getString("query").contains("transportModes:[{mode:WALK}]"))
            assertTrue(request.getString("query").contains("walk0:plan"))
            JSONObject().put("data",JSONObject().put("walk0",walk(a,b,120)).put("walk1",walk(b,a,180)))
        }
        val first=backend.walking(listOf(a to b,b to a),ready)
        assertEquals(120,first.links.getValue(a.position to b.position).seconds)
        assertEquals(180,first.links.getValue(b.position to a.position).seconds)
        val later=ready.plusSeconds(3600)
        val again=backend.walking(listOf(a to b),later)
        assertEquals(later.plusSeconds(120),again.links.getValue(a.position to b.position).leg(later).end)
        assertEquals(1,calls.get())
    }
    @Test fun failedWalkingAliasKeepsOtherVerifiedLinksAndReportsPartialCoverage() = runBlocking {
        val backend=backend {
            JSONObject().put("data",JSONObject().put("walk0",walk(a,b)).put("walk1",JSONObject.NULL))
                .put("errors",JSONArray().put(JSONObject().put("message","unavailable").put("path",JSONArray().put("walk1"))))
        }
        val result=backend.walking(listOf(a to b,b to a),ready)
        assertEquals(setOf(a.position to b.position),result.links.keys)
        assertTrue(result.partial)
    }
    @Test fun missingStreetRouteDoesNotTurnIntoADistanceBasedWalk() = runBlocking {
        val backend=backend { JSONObject().put("data",JSONObject().put("walk0",JSONObject().put("itineraries",JSONArray()))) }
        val result=backend.walking(listOf(a to b),ready)
        assertTrue(result.links.isEmpty())
        assertFalse(result.partial)
    }
    @Test fun unscopedSourceErrorsCannotBeTreatedAsSuccessfulWalkingLinks() = runBlocking {
        val backend=backend { JSONObject().put("errors",JSONArray().put(JSONObject().put("message","offline"))) }
        val result=backend.walking(listOf(a to b),ready)
        assertTrue(result.links.isEmpty())
        assertTrue(result.partial)
    }
}
