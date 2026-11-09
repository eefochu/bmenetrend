package hu.app.bmenetrend.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EmmaGraphqlTrafficTest {
    @Test fun forbiddenBackgroundQueryDoesNotDisableIndependentTripPlanning() = runBlocking {
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val body = okio.Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            val denied = body.contains("LiveVehicles")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (denied) 403 else 200).message(if (denied) "Forbidden" else "OK")
                .body("response".toResponseBody()).build()
        }.build()
        EmmaGraphqlClient.sharedBackoff.reset()
        try {
            val background = EmmaGraphqlClient(http)
            try { background.execute("query LiveVehicles"); fail("Expected denial") } catch (_: EmmaHttpException) { }
            assertEquals("response", EmmaGraphqlClient(http).execute("query TripPlan"))
            try { background.execute("query LiveVehicles"); fail("Denied operation must still cool down") }
            catch (_: EmmaCooldownException) { }
            assertEquals(2, calls.get())
        } finally { EmmaGraphqlClient.sharedBackoff.reset() }
    }

    @Test fun differentVariablesAndQueriesRemainIndependentWhileInFlight() = runBlocking {
        val entered = CountDownLatch(3)
        val release = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("response".toResponseBody()).build()
        }.build()
        val client = EmmaGraphqlClient(http, EmmaRequestBackoff())
        val jobs = listOf("query Stop" to "A", "query Stop" to "B", "query Lines" to "A").map { (query, id) ->
            async(start = CoroutineStart.UNDISPATCHED) { client.execute(query, JSONObject().put("id", id)) }
        }
        try { assertTrue("Different information must use separate requests", entered.await(5, TimeUnit.SECONDS)) }
        finally { release.countDown(); jobs.forEach { it.await() } }
    }

    @Test fun identicalRequestsShareHttpButSubsequentRefreshStillFetches() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("response".toResponseBody()).build()
        }.build()
        val backoff = EmmaRequestBackoff()
        val firstClient = EmmaGraphqlClient(http, backoff)
        val secondClient = EmmaGraphqlClient(http, backoff)
        val first = async(start = CoroutineStart.UNDISPATCHED) { firstClient.execute("query Stop", JSONObject().put("id", "A")) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = async(start = CoroutineStart.UNDISPATCHED) { secondClient.execute("query Stop", JSONObject().put("id", "A")) }
            first.cancelAndJoin()
            release.countDown()
            assertEquals("response", withTimeout(5_000) { second.await() })
            assertEquals("One cancelled reader must not restart another reader's request", 1, calls.get())
            assertEquals("response", firstClient.execute("query Stop", JSONObject().put("id", "A")))
            assertEquals("Manual refresh after completion must still fetch fresh data", 2, calls.get())
            firstClient.execute("query Stop", JSONObject().put("id", "B"))
            assertEquals("Different stops must remain separate", 3, calls.get())
        } finally { release.countDown(); first.cancelAndJoin() }
    }

    @Test fun cancellingLastReaderCancelsRequestAndDoesNotPoisonNextFetch() = runBlocking {
        val requests = EmmaInFlightRequests()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            requests.execute("same") {
                entered.complete(Unit)
                try { CompletableDeferred<String>().await() }
                finally { cancelled.complete(Unit) }
            }
        }
        withTimeout(5_000) { entered.await() }
        job.cancelAndJoin()
        withTimeout(5_000) { cancelled.await() }
        assertEquals("fresh", requests.execute("same") { "fresh" })
    }

    @Test fun rateLimitOnVehicleFeedAppliesToOtherScreensAndManualRefresh() = runBlocking {
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(429).message("Limited")
                .header("Retry-After", "120").body("limited".toResponseBody()).build()
        }.build()
        EmmaGraphqlClient.sharedBackoff.reset()
        try {
            assertSame(EmmaGraphqlClient.sharedBackoff, EmmaFeedClient.vehicleBackoff)
            val feed = EmmaFeedClient(http)
            try { feed.get(); fail("Denied feed must fail") } catch (_: IOException) { }
            try { EmmaGraphqlClient(http).execute("query Stop"); fail("Other screens must respect denial") }
            catch (_: EmmaCooldownException) { }
            try { feed.get(force = true); fail("Refresh must respect denial") } catch (_: IOException) { }
            assertEquals(1, calls.get())
        } finally { EmmaGraphqlClient.sharedBackoff.reset() }
    }
}
