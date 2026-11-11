package hu.app.bmenetrend

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import hu.app.bmenetrend.data.EmmaGraphqlClient
import hu.app.bmenetrend.data.EmmaStopsParser
import hu.app.bmenetrend.data.EmmaStopsRepository
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.StopBoardScope
import hu.app.bmenetrend.data.TransitStop
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import hu.app.bmenetrend.ui.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Revisiting the exact stop/platform/window selection reuses a board for less than 20 seconds.
 * A different key, an explicit refresh, an expired entry and a mode change all go back to HTTP.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TransitViewModelRecentBoardsTest {

    private lateinit var app: Application
    private var activeModel: TransitViewModel? = null
    private val stopId = "emma:1:005501024_0"
    private val stop = TransitStop(stopId, "Budapest-Kelenföld", GeoPoint(47.464, 19.02), emptyList(), "mav")

    private fun emptyBkkRepository(): hu.app.bmenetrend.data.BkkWorkerRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Mock Offline")
                .body("{}".toResponseBody())
                .build()
        }.build()
        return hu.app.bmenetrend.data.BkkWorkerRepository(
            hu.app.bmenetrend.data.BkkWorkerClient(
                "https://worker.test".toHttpUrl(),
                http,
                { Instant.now() },
                hu.app.bmenetrend.data.BkkRequestBudget()
            )
        )
    }

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        try {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        } catch (_: Exception) {}
        NetworkAccessPolicy.initialize(app)
        NetworkAccessPolicy.setOffline(app, false)
        NetworkAccessPolicy.onConnectivityChanged(true)
        File(app.filesDir, "emma-stop-index-v3.json").delete()
        File(app.filesDir, "emma-stop-index-v2.json").delete()
        File(app.filesDir, "emma-stop-index-v1.json").delete()
    }

    @After
    fun teardown() {
        activeModel?.viewModelScope?.cancel()
        NetworkAccessPolicy.setOffline(app, false)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target) as T
    }

    private fun mutableState(viewModel: TransitViewModel): MutableStateFlow<UiState> = field(viewModel, "mutableState")

    private fun inbox(viewModel: TransitViewModel): LinkedHashMap<StopBoardScope.RequestKey, EmmaStopsParser.Board> =
        field(viewModel, "recentBoards")

    private fun boardLoading(viewModel: TransitViewModel): Boolean = field(viewModel, "boardLoading")

    private fun awaitUntil(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(5)
        }
        ShadowLooper.idleMainLooper()
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    @Test
    fun plannerMapDoesNotFetchHiddenDepartureBoardsAndMapTabResumesThem() = runBlocking {
        val calls = AtomicInteger()
        val fixture = JSONObject(javaClass.getResource("/rail/board.json")!!.readText())
            .getJSONObject("data").getJSONObject("stop")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONObject().put("data", JSONObject().put("board0", fixture)).toString().toResponseBody()).build()
        }.build()
        val viewModel = TransitViewModel(app).also { activeModel = it }
        try {
            val repository = TransitViewModel::class.java.getDeclaredField("stopsRepository").apply { isAccessible = true }
            repository.set(viewModel, EmmaStopsRepository(app, EmmaGraphqlClient(http)))
            val bkkField = TransitViewModel::class.java.getDeclaredField("bkkRepository").apply { isAccessible = true }
            bkkField.set(viewModel, emptyBkkRepository())
            awaitUntil { viewModel.state.value.network != null }
            viewModel.setDeparturesVisible(false)
            mutableState(viewModel).update { it.copy(emmaStops = it.emmaStops + stop) }
            viewModel.selectStopGroup(listOf(stopId))
            repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
            assertEquals("Hidden planner boards must not consume API requests", 0, calls.get())
            viewModel.setDeparturesVisible(true)
            awaitUntil { calls.get() == 1 && !boardLoading(viewModel) }
            assertEquals(1, calls.get())
        } finally {
            viewModel.viewModelScope.cancel()
            http.dispatcher.cancelAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun recentBoardIsReusedByExactKeyUntilForcedExpiredOrInvalidated() = runBlocking {
        val calls = AtomicInteger()
        val fixture = JSONObject(javaClass.getResource("/rail/board.json")!!.readText())
            .getJSONObject("data").getJSONObject("stop")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer().also { chain.request().body?.writeTo(it) }
            if (buffer.readUtf8().contains("StationBoards")) calls.incrementAndGet()
            val body = JSONObject().put("data", JSONObject().put("board0", fixture)).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        try {
            val viewModel = TransitViewModel(app).also { activeModel = it }
            val field = TransitViewModel::class.java.getDeclaredField("stopsRepository")
            field.isAccessible = true
            field.set(viewModel, EmmaStopsRepository(app, EmmaGraphqlClient(http)))
            val bkkField = TransitViewModel::class.java.getDeclaredField("bkkRepository").apply { isAccessible = true }
            bkkField.set(viewModel, emptyBkkRepository())

            awaitUntil { viewModel.state.value.network != null }
            mutableState(viewModel).update { it.copy(emmaStops = it.emmaStops + stop, windowMinutes = 120) }
            viewModel.selectStopGroup(listOf(stopId))
            awaitUntil { calls.get() == 1 && !boardLoading(viewModel) }
            assertEquals(1, calls.get())

            // Same key after visiting another window: memory hit, no HTTP.
            viewModel.setWindow(60)
            awaitUntil { calls.get() == 2 && !boardLoading(viewModel) }
            viewModel.setWindow(120)
            assertEquals("exact key revisit must not issue HTTP", 2, calls.get())
            assertTrue(inbox(viewModel).containsKey(viewModel.state.value.boardRequestKey))

            // Explicit refresh always bypasses the recent-board entry.
            viewModel.refresh()
            awaitUntil { calls.get() == 3 && !boardLoading(viewModel) }

            // TTL: age the exact entry beyond 20s and revisit via another key.
            val key = viewModel.state.value.boardRequestKey
            val cached = inbox(viewModel)[key]
            assertNotNull(cached)
            inbox(viewModel)[key] = cached!!.copy(fetchedAt = Instant.now().minusSeconds(25))
            viewModel.setWindow(30)
            awaitUntil { calls.get() == 4 && !boardLoading(viewModel) }
            viewModel.setWindow(120)
            awaitUntil { calls.get() == 5 && !boardLoading(viewModel) }

            // A mode change drops every recent board, including the 60-minute one.
            NetworkAccessPolicy.setOffline(app, true)
            // No offline pack is installed in this fixture, so the production
            // guard can restore online mode before the next looper observation.
            // Assert the cache invalidation itself rather than a transient flag.
            awaitUntil { inbox(viewModel).isEmpty() }
            assertTrue("mode change must clear recent boards", inbox(viewModel).isEmpty())
            NetworkAccessPolicy.setOffline(app, false)
            awaitUntil { !viewModel.state.value.offlineMode }
            mutableState(viewModel).update { current ->
                if (current.emmaStops.any { it.id == stopId }) current else current.copy(emmaStops = current.emmaStops + stop)
            }
            awaitUntil { !boardLoading(viewModel) }
            repeat(10) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
            val beforeInvalidationCheck = calls.get()
            viewModel.setWindow(60)
            viewModel.selectStopGroup(listOf(stopId))
            awaitUntil { calls.get() == beforeInvalidationCheck + 1 && !boardLoading(viewModel) }
        } finally {
            activeModel?.viewModelScope?.cancel()
            http.dispatcher.cancelAll()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
