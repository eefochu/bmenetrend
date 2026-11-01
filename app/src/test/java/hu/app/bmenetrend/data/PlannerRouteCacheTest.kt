package hu.app.bmenetrend.data

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlannerRouteCacheTest {
    private val ready = Instant.parse("2026-11-01T10:00:00Z")
    private val from = PlannerEndpoint("a", "A", "", GeoPoint(47.49, 18.74))
    private val to = PlannerEndpoint("b", "B", "", GeoPoint(47.51, 19.05))

    private class Fixture(val handler: (Int, Request) -> Response) : AutoCloseable {
        val calls = AtomicInteger()
        var now = Instant.parse("2026-11-01T10:00:00Z")
        val http = OkHttpClient.Builder().addInterceptor { chain -> handler(calls.incrementAndGet(), chain.request()) }.build()
        val backend = EmmaPlannerBackend(EmmaGraphqlClient(http, EmmaRequestBackoff())) { now }
        override fun close() { http.dispatcher.cancelAll(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll() }
    }

    private fun response(request: Request, status: Int = 200, startOffset: Long = 60): Response {
        val buffer = Buffer().also { request.body!!.writeTo(it) }
        val vars = JSONObject(buffer.readUtf8()).getJSONObject("variables")
        val requested = LocalDateTime.parse(vars.getString("date") + "T" + vars.getString("time"))
            .atZone(TripPlanner.zone).toInstant()
        val start = requested.plusSeconds(startOffset).toEpochMilli()
        val end = start + 120_000
        fun point(name: String) = JSONObject().put("name", name).put("lat", 47.5).put("lon", 19.0)
        val leg = JSONObject().put("mode", "WALK").put("startTime", start).put("endTime", end).put("distance", 100)
            .put("from", point("A")).put("to", point("B"))
        val row = JSONObject().put("startTime", start).put("endTime", end).put("legs", JSONArray().put(leg))
        val body = JSONObject().put("data", JSONObject().put("plan", JSONObject().put("itineraries", JSONArray().put(row))))
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body(body.toString().toResponseBody()).build()
    }

    @Test fun cachedOnlineRoutesReuseResultsButExpiryCoordinatesModeAndForceInvalidate() = runBlocking {
        Fixture { _, request -> response(request) }.use { f ->
            val first = f.backend.routes(from, to, ready, false)
            assertSame(first, f.backend.routes(from, to, ready, false))
            assertEquals(1, f.calls.get())
            f.backend.routes(from, to, ready, true)
            f.backend.routes(from.copy(position = GeoPoint(47.491, 18.74)), to, ready, false)
            assertEquals(3, f.calls.get())
            f.now = f.now.plusSeconds(11)
            f.backend.routes(from, to, ready, false)
            assertEquals(4, f.calls.get())
            f.backend.clearRouteCache()
            f.backend.routes(from, to, ready, false)
            assertEquals(5, f.calls.get())
        }
    }

    @Test fun advancingReadyTimeRefetchesUnusableRowsWithoutMovingTheirSourceTimes() = runBlocking {
        Fixture { _, request -> response(request, startOffset = 1) }.use { f ->
            val original = f.backend.routes(from, to, ready, false).single()
            val later = f.backend.routes(from, to, ready.plusSeconds(5), false).single()
            assertEquals(2, f.calls.get())
            assertEquals(ready.plusSeconds(1), original.start)
            assertEquals(ready.plusSeconds(6), later.start)
            assertTrue(TripPlanner.feasible(later.legs, ready.plusSeconds(5)))
        }
    }

    @Test fun concurrentCallersShareOneInFlightRequest() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture { _, request -> entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); response(request) }.use { f ->
            try {
                val owner = async(Dispatchers.Default) { f.backend.routes(from, to, ready, false) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { f.backend.routes(from, to, ready, false) }
                release.countDown()
                assertSame(withTimeout(5_000) { owner.await() }, withTimeout(5_000) { waiter.await() })
                assertEquals(1, f.calls.get())
            } finally { release.countDown() }
        }
    }

    @Test fun cancelledPrefetchOwnerDoesNotCancelAnActiveForegroundWaiter() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture { count, request ->
            if (count == 1) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            response(request)
        }.use { f ->
            try {
                val owner = async(Dispatchers.Default) { f.backend.routes(from, to, ready, false) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val waiter = async(start = CoroutineStart.UNDISPATCHED) { f.backend.routes(from, to, ready, false) }
                owner.cancelAndJoin()
                assertTrue(withTimeout(5_000) { waiter.await() }.isNotEmpty())
                assertEquals(2, f.calls.get())
            } finally { release.countDown() }
        }
    }

    @Test fun transientGatewayFailureGetsOneRecoveryAttempt() = runBlocking {
        Fixture { count, request -> response(request, status = if (count == 1) 502 else 200) }.use { f ->
            assertTrue(f.backend.routes(from, to, ready, false).isNotEmpty())
            assertEquals(2, f.calls.get())
        }
        Fixture { _, _ -> throw IOException("connection reset") }.use { f ->
            try { f.backend.routes(from, to, ready, false); fail("Failure must propagate after one retry") }
            catch (_: IOException) { assertEquals(2, f.calls.get()) }
        }
    }

    @Test fun serviceCooldownIsRespectedInsteadOfRetried() = runBlocking {
        Fixture { _, request -> response(request, status = 503) }.use { f ->
            try { f.backend.routes(from, to, ready, false); fail("Expected server failure") }
            catch (_: EmmaHttpException) { assertEquals(1, f.calls.get()) }
            try { f.backend.routes(from, to, ready, false); fail("Expected cooldown") }
            catch (_: EmmaCooldownException) { assertEquals(1, f.calls.get()) }
        }
    }
}
