package hu.app.bmenetrend.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class EmmaServingLinesCacheTest {
    @Test fun recentMemoryEntriesSurviveEvictionAndDiskHitsBecomeMemoryHits() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        NetworkAccessPolicy.setOffline(context, false)
        val directory = File(context.filesDir, "emma-station-lines-v1")
        directory.deleteRecursively()
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val buffer = Buffer().also { chain.request().body!!.writeTo(it) }
            val sourceId = JSONObject(buffer.readUtf8()).getJSONObject("variables").getString("id")
            val row = JSONObject().put("gtfsId", sourceId).put("name", sourceId)
                .put("lat", 47.46).put("lon", 19.03).put("routes", JSONArray())
            val body = JSONObject().put("data", JSONObject().put("stop", row)).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val graphql = EmmaGraphqlClient(http, EmmaRequestBackoff())
        fun id(number: Int) = "emma:hkir:cache-$number"
        fun file(number: Int) = File(directory, Base64.getUrlEncoder().withoutPadding()
            .encodeToString(id(number).toByteArray(Charsets.UTF_8)) + ".json")
        try {
            val repository = EmmaStopsRepository(context, graphql)
            repeat(64) { repository.servingLines(id(it)) }
            repository.servingLines(id(0)) // Recently accessed, so insertion 64 must evict 1.
            repository.servingLines(id(64))
            assertEquals(65, calls.get())
            file(0).writeText("invalid disk cache")
            file(1).writeText("invalid disk cache")
            assertEquals(id(0), repository.servingLines(id(0)).catalogue.stops.single().id)
            assertEquals(65, calls.get())
            repository.servingLines(id(1))
            assertEquals(66, calls.get())

            val coldRepository = EmmaStopsRepository(context, graphql)
            coldRepository.servingLines(id(2))
            file(2).writeText("invalid disk cache")
            assertEquals(id(2), coldRepository.servingLines(id(2)).catalogue.stops.single().id)
            assertEquals(66, calls.get())
        } finally {
            directory.deleteRecursively()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
