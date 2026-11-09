package hu.app.bmenetrend.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class EmmaStopsRepositoryRetryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        NetworkAccessPolicy.setOffline(context, false)
    }

    @After
    fun tearDown() {
        NetworkAccessPolicy.setOffline(context, false)
    }

    private fun mockBatchResponseBody(stopId: String = "emma:hkir:platform1"): String {
        val sourceId = EmmaStopsParser.sourceId(stopId)
        val stopObj = JSONObject()
            .put("gtfsId", sourceId)
            .put("name", "Kelenföld")
            .put("lat", 47.46)
            .put("lon", 19.03)
            .put("locationType", "STOP")
            .put("vehicleMode", "COACH")
            .put("routes", JSONArray())
            .put("stoptimesWithoutPatterns", JSONArray())
        return JSONObject().put("data", JSONObject().put("board0", stopObj)).toString()
    }

    @Test
    fun boards_retriesOnceOnTransportIOExceptionAndSucceeds() = runBlocking {
        val attempts = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val attempt = attempts.incrementAndGet()
            if (attempt == 1) {
                throw IOException("Simulated dropped socket")
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(mockBatchResponseBody().toResponseBody())
                .build()
        }.build()

        try {
            val backoff = EmmaRequestBackoff()
            val graphql = EmmaGraphqlClient(http, backoff)
            val repository = EmmaStopsRepository(context, graphql)

            val result = repository.boards(listOf("emma:hkir:platform1"), 30)

            assertEquals(2, attempts.get())
            assertNotNull(result["emma:hkir:platform1"])
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun boards_doesNotRetryOnHttp503AndPropagatesException() {
        val attempts = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            attempts.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(503)
                .message("Service Unavailable")
                .body("Service Unavailable".toResponseBody())
                .build()
        }.build()

        try {
            val backoff = EmmaRequestBackoff()
            val graphql = EmmaGraphqlClient(http, backoff)
            val repository = EmmaStopsRepository(context, graphql)

            val exception = assertThrows(EmmaHttpException::class.java) {
                runBlocking { repository.boards(listOf("emma:hkir:platform1"), 30) }
            }
            assertEquals(503, exception.status)
            assertEquals(1, attempts.get())
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun boards_failsAfterExactlyTwoAttemptsWhenBothFailWithIOException() {
        val attempts = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            attempts.incrementAndGet()
            throw IOException("Socket timeout")
        }.build()

        try {
            val backoff = EmmaRequestBackoff()
            val graphql = EmmaGraphqlClient(http, backoff)
            val repository = EmmaStopsRepository(context, graphql)

            val exception = assertThrows(IOException::class.java) {
                runBlocking { repository.boards(listOf("emma:hkir:platform1"), 30) }
            }
            assertEquals("Socket timeout", exception.message)
            assertEquals(2, attempts.get())
        } finally {
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
