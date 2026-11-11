package hu.app.bmenetrend.data

import hu.app.bmenetrend.offline.NetworkAccessPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class BkkWorkerClientTest {

    private var currentTime = Instant.parse("2026-11-10T12:00:00Z")
    private val clock: () -> Instant = { currentTime }

    private val baseUrl = "https://bkk-worker.example.com".toHttpUrl()

    private fun okResponse(body: String = """{"status":"OK","data":{"list":[]}}""") = body

    @Test
    fun endpointPathAndSortedQueryParams() = runBlocking {
        var requestedUrl: String? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedUrl = chain.request().url.toString()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(okResponse().toResponseBody())
                .build()
        }.build()

        val workerClient = BkkWorkerClient(baseUrl, client, clock, BkkRequestBudget())
        workerClient.get("vehicles-for-location", mapOf("lon" to "19.05", "lat" to "47.49", "radius" to "500"))

        assertNotNull(requestedUrl)
        // Checks /api/vehicles-for-location.json and query parameter ordering (lat, lon, radius)
        assertEquals("https://bkk-worker.example.com/api/vehicles-for-location.json?lat=47.49&lon=19.05&radius=500", requestedUrl)
    }

    @Test
    fun inFlightDeduplicationSharesNetworkCall() = runBlocking {
        val networkCalls = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            networkCalls.incrementAndGet()
            Thread.sleep(50) // simulate network latency
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(okResponse().toResponseBody())
                .build()
        }.build()

        val workerClient = BkkWorkerClient(baseUrl, client, clock, BkkRequestBudget())
        val deferreds = (1..5).map {
            async {
                workerClient.get("stops-for-location", mapOf("lat" to "47.49", "lon" to "19.05"))
            }
        }
        val results = deferreds.awaitAll()

        assertEquals(5, results.size)
        // All concurrent requests for same URL must share a single network call
        assertEquals(1, networkCalls.get())
    }

    @Test
    fun responseCachingHonorsTtlPerEndpoint() = runBlocking {
        val networkCalls = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            networkCalls.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(okResponse("""{"status":"OK","call":${networkCalls.get()}}""").toResponseBody())
                .build()
        }.build()

        val workerClient = BkkWorkerClient(baseUrl, client, clock, BkkRequestBudget())

        // 1. vehicles-for-location TTL is 5 seconds
        val first = workerClient.get("vehicles-for-location", mapOf("lat" to "47.49", "lon" to "19.05"))
        assertEquals(1, networkCalls.get())

        // 2 seconds later -> cached, no network call
        currentTime = currentTime.plusSeconds(2)
        val cached = workerClient.get("vehicles-for-location", mapOf("lat" to "47.49", "lon" to "19.05"))
        assertEquals(first, cached)
        assertEquals(1, networkCalls.get())

        // 6 seconds later (total 8s > 5s TTL) -> cache expired, new network call
        currentTime = currentTime.plusSeconds(6)
        val refreshed = workerClient.get("vehicles-for-location", mapOf("lat" to "47.49", "lon" to "19.05"))
        assertNotEquals(first, refreshed)
        assertEquals(2, networkCalls.get())
    }

    @Test
    fun planTripTtlIs5Seconds() = runBlocking {
        val networkCalls = AtomicInteger(0)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            networkCalls.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(okResponse().toResponseBody())
                .build()
        }.build()

        val workerClient = BkkWorkerClient(baseUrl, client, clock, BkkRequestBudget())
        workerClient.get("plan-trip", mapOf("from" to "A", "to" to "B"))
        assertEquals(1, networkCalls.get())

        // 4 seconds later (within 5s TTL) -> cached
        currentTime = currentTime.plusSeconds(4)
        workerClient.get("plan-trip", mapOf("from" to "A", "to" to "B"))
        assertEquals(1, networkCalls.get())

        // 6 seconds later (exceeded 5s TTL) -> refreshed
        currentTime = currentTime.plusSeconds(2)
        workerClient.get("plan-trip", mapOf("from" to "A", "to" to "B"))
        assertEquals(2, networkCalls.get())
    }

    @Test
    fun nonOkEnvelopeThrows502() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"status":"NOT_FOUND","data":null}""".toResponseBody())
                .build()
        }.build()

        val workerClient = BkkWorkerClient(baseUrl, client, clock, BkkRequestBudget())
        try {
            workerClient.get("search", mapOf("query" to "test"))
            fail("Expected BkkWorkerException")
        } catch (e: BkkWorkerException) {
            assertEquals(502, e.status)
            assertTrue(e.message?.contains("BKK proxy returned an error") == true)
        }
    }

    @Test
    fun httpErrorThrowsBkkWorkerException() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Server Error")
                .body("Internal Error".toResponseBody())
                .build()
        }.build()

        val workerClient = BkkWorkerClient(baseUrl, client, clock, BkkRequestBudget())
        try {
            workerClient.get("search", mapOf("query" to "test"))
            fail("Expected BkkWorkerException")
        } catch (e: BkkWorkerException) {
            assertEquals(500, e.status)
        }
    }

    @Test
    fun rateLimit429WithRetryAfterHeaderUpdatesBudget() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("Too Many Requests")
                .header("Retry-After", "10")
                .body("Rate limited".toResponseBody())
                .build()
        }.build()

        val budget = BkkRequestBudget()
        val workerClient = BkkWorkerClient(baseUrl, client, clock, budget)

        try {
            workerClient.get("search", mapOf("query" to "test"))
            fail("Expected 429 exception")
        } catch (e: BkkWorkerException) {
            assertEquals(429, e.status)
            assertEquals(currentTime.plusSeconds(10), e.retryAt)
        }

        // Subsequent call before 10s must be rejected by budget immediately without hitting network
        try {
            workerClient.get("search", mapOf("query" to "test2"))
            fail("Expected immediate 429 from budget")
        } catch (e: BkkWorkerException) {
            assertEquals(429, e.status)
        }

        // Advancing clock past retryAfter allows requests again
        currentTime = currentTime.plusSeconds(11)
        val successClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(okResponse().toResponseBody()).build()
        }.build()
        val retryWorker = BkkWorkerClient(baseUrl, successClient, clock, budget)
        val result = retryWorker.get("search", mapOf("query" to "test2"))
        assertTrue(result.contains("status"))
    }

    @Test
    fun budgetEnforcesPlannerLimit() = runBlocking {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(okResponse().toResponseBody()).build()
        }.build()

        val budget = BkkRequestBudget()
        val workerClient = BkkWorkerClient(baseUrl, client, clock, budget)

        // Planner limit is 8 requests per minute
        repeat(8) { i ->
            workerClient.get("plan-trip", mapOf("from" to "A$i", "to" to "B$i"))
        }

        // 9th request must be rejected by the budget with 429
        try {
            workerClient.get("plan-trip", mapOf("from" to "A9", "to" to "B9"))
            fail("Expected 429 limit on 9th plan-trip")
        } catch (e: BkkWorkerException) {
            assertEquals(429, e.status)
        }
    }
}
