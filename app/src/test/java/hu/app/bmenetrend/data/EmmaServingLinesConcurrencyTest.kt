package hu.app.bmenetrend.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serving-line cache fills are striped: a slow station holds only its own stripe's mutex.
 * These tests pin two behaviours of that design:
 *  - requests for stops in different stripes reach the HTTP layer concurrently,
 *  - concurrent cold requests for the same stop stay single-flight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class EmmaServingLinesConcurrencyTest {

    private fun stripe(id: String) = (id.hashCode() and Int.MAX_VALUE) % 64

    private fun mockClient(entered: CountDownLatch, release: CountDownLatch, calls: AtomicInteger) =
        OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            val buffer = Buffer().also { chain.request().body!!.writeTo(it) }
            val sourceId = JSONObject(buffer.readUtf8()).getJSONObject("variables").getString("id")
            val row = JSONObject().put("gtfsId", sourceId).put("name", sourceId)
                .put("lat", 47.46).put("lon", 19.03).put("routes", JSONArray())
            val body = JSONObject().put("data", JSONObject().put("stop", row)).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()

    @Test
    fun unrelatedStopsInDifferentStripesReachHttpConcurrently() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        NetworkAccessPolicy.setOffline(context, false)
        val directory = File(context.filesDir, "emma-station-lines-v1").apply { deleteRecursively() }
        val first = "emma:hkir:stripe-independent-a"
        val second = generateSequence(0) { it + 1 }
            .map { "emma:hkir:stripe-independent-b$it" }
            .first { stripe(it) != stripe(first) }
        assertNotEquals("fixture stops must use different stripes", stripe(first), stripe(second))

        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val http = mockClient(entered, release, calls)
        try {
            val repository = EmmaStopsRepository(context, EmmaGraphqlClient(http))
            val one = async(Dispatchers.IO) { repository.servingLines(first) }
            val two = async(Dispatchers.IO) { repository.servingLines(second) }
            try {
                assertTrue("both non-colliding stripes must reach HTTP before either is released",
                    entered.await(5, TimeUnit.SECONDS))
                assertEquals(2, calls.get())
            } finally {
                release.countDown()
            }
            assertEquals(first, one.await().catalogue.stops.single().id)
            assertEquals(second, two.await().catalogue.stops.single().id)
            assertEquals(2, calls.get())
        } finally {
            directory.deleteRecursively()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun concurrentColdRequestsForTheSameStopIssueOneHttpRequest() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        NetworkAccessPolicy.setOffline(context, false)
        val directory = File(context.filesDir, "emma-station-lines-v1").apply { deleteRecursively() }
        val id = "emma:hkir:stripe-single-flight"
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val http = mockClient(entered, release, calls)
        try {
            val repository = EmmaStopsRepository(context, EmmaGraphqlClient(http))
            val first = async(Dispatchers.IO) { repository.servingLines(id) }
            assertTrue("first request must reach HTTP", entered.await(5, TimeUnit.SECONDS))
            val second = async(Dispatchers.IO) { repository.servingLines(id) }
            release.countDown()
            val a = first.await()
            val b = second.await()
            assertEquals("same-station cold fill must be single-flight", 1, calls.get())
            assertEquals(a.fetchedAt, b.fetchedAt)
            assertEquals(a.catalogue.stops.single().id, b.catalogue.stops.single().id)
        } finally {
            release.countDown()
            directory.deleteRecursively()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
