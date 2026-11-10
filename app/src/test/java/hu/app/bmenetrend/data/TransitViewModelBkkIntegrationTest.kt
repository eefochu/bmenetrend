package hu.app.bmenetrend.data

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.TransitViewModel
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import hu.app.bmenetrend.ui.UiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TransitViewModelBkkIntegrationTest {

    private lateinit var app: Application
    private val models = mutableListOf<TransitViewModel>()
    private val now = Instant.parse("2026-11-10T12:00:00Z")

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
    fun cleanup() {
        models.forEach { it.viewModelScope.cancel() }
        NetworkAccessPolicy.setOffline(app, false)
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutableState(viewModel: TransitViewModel): MutableStateFlow<UiState> {
        val field = TransitViewModel::class.java.getDeclaredField("mutableState")
        field.isAccessible = true
        return field.get(viewModel) as MutableStateFlow<UiState>
    }

    private fun injectBkkRepository(viewModel: TransitViewModel, repo: BkkWorkerRepository) {
        val field = TransitViewModel::class.java.getDeclaredField("bkkRepository")
        field.isAccessible = true
        field.set(viewModel, repo)
    }

    private fun injectStopsRepository(viewModel: TransitViewModel, repo: EmmaStopsRepository) {
        val field = TransitViewModel::class.java.getDeclaredField("stopsRepository")
        field.isAccessible = true
        field.set(viewModel, repo)
    }

    private fun awaitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
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
    fun bkkBoardFetchedForBkkPlatform() = runBlocking {
        val bkkStop = TransitStop("emma:BKK:F01020", "Deák Ferenc tér M", GeoPoint(47.497, 19.054), emptyList(), "bkk")
        val bkkCalls = AtomicInteger(0)

        val bkkClient = OkHttpClient.Builder().addInterceptor { chain ->
            bkkCalls.incrementAndGet()
            val callNow = Instant.now()
            val json = JSONObject().put("status", "OK").put("data", JSONObject()
                .put("references", JSONObject()
                    .put("routes", JSONObject().put("BKK_B0070", JSONObject().put("id", "BKK_B0070").put("type", "BUS").put("shortName", "7")))
                    .put("trips", JSONObject().put("BKK_TRIP1", JSONObject().put("routeId", "BKK_B0070").put("tripHeadsign", "Albertfalva")))
                    .put("stops", JSONObject().put("BKK_F01020", JSONObject().put("platformCode", "1")))
                )
                .put("entry", JSONObject()
                    .put("stopId", "BKK_F01020")
                    .put("stopTimes", JSONArray().put(JSONObject()
                        .put("stopId", "BKK_F01020")
                        .put("tripId", "BKK_TRIP1")
                        .put("departureTime", callNow.epochSecond + 180)
                        .put("pickupAllowed", true)))
                )
            ).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody()).build()
        }.build()

        val emmaClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"station":null}}""".toResponseBody()).build()
        }.build()

        val viewModel = TransitViewModel(app).also { models += it }
        val bkkRepo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), bkkClient, { Instant.now() }, BkkRequestBudget()))
        val emmaRepo = EmmaStopsRepository(app, EmmaGraphqlClient(emmaClient))

        injectBkkRepository(viewModel, bkkRepo)
        injectStopsRepository(viewModel, emmaRepo)

        awaitUntil { viewModel.state.value.network != null }
        viewModel.setDeparturesVisible(false)
        mutableState(viewModel).update { it.copy(emmaStops = it.emmaStops + bkkStop) }
        viewModel.selectStopGroup(listOf(bkkStop.id))
        viewModel.setDeparturesVisible(true)

        awaitUntil { bkkCalls.get() >= 1 && viewModel.state.value.departures.isNotEmpty() }

        val departures = viewModel.state.value.departures
        assertFalse("Departures should be loaded from BKK worker", departures.isEmpty())
        assertEquals("7", departures[0].routeNumber)
        assertTrue(bkkCalls.get() >= 1)
    }

    @Test
    fun offlineModeCancelsWorkerJobsAndClearsVehicles() = runBlocking {
        val viewModel = TransitViewModel(app).also { models += it }

        // Enable offline mode
        NetworkAccessPolicy.setOffline(app, true)
        ShadowLooper.idleMainLooper()

        assertTrue(viewModel.state.value.offlineMode)
        assertTrue(viewModel.state.value.vehicles.isEmpty())
        assertNull(viewModel.state.value.selectedVehicle)

        // Update map area during offline mode -> ensures no crash and offline state stays intact
        viewModel.updateMapArea(MapBounds(19.04, 47.48, 19.06, 47.50), GeoPoint(47.49, 19.05))
        ShadowLooper.idleMainLooper()
        assertTrue(viewModel.state.value.offlineMode)
    }
}
