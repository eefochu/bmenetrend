package hu.app.bmenetrend

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import hu.app.bmenetrend.ui.UiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteLinesRecoveryTest {
    private fun field(model: TransitViewModel, name: String, value: Any) {
        TransitViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(model, value)
    }
    @Test fun failedFirstLoadRecoversAutomaticallyWithoutReloadingSuccessfulFavorites() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<Application>()
        try { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) } catch (_: IllegalStateException) {}
        NetworkAccessPolicy.initialize(app)
        NetworkAccessPolicy.setOffline(app, false)
        NetworkAccessPolicy.onConnectivityChanged(true)
        File(app.filesDir, "emma-station-lines-v1").deleteRecursively()
        val counts = ConcurrentHashMap<String, AtomicInteger>()
        val failAll = AtomicBoolean(false)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer().also { chain.request().body!!.writeTo(it) }
            val id = JSONObject(buffer.readUtf8()).getJSONObject("variables").getString("id")
            val count = counts.getOrPut(id) { AtomicInteger() }.incrementAndGet()
            if (failAll.get() || id.endsWith("broken") && count == 1) throw IOException("temporary connection loss")
            val body = """{"data":{"stop":{"gtfsId":"$id","name":"$id","lat":47.48,"lon":18.83,"routes":[{"gtfsId":"hkir:recovery-line","shortName":"762","longName":"Local bus","mode":"BUS"}]}}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        }.build()
        val model = TransitViewModel(app)
        try {
            field(model,"stopsRepository", EmmaStopsRepository(app, EmmaGraphqlClient(http, EmmaRequestBackoff())))
            runCurrent()
            val stops = listOf("healthy", "broken").map { TransitStop("emma:hkir:recovery-$it", it,
                GeoPoint(47.48,18.83), emptyList(), "volan") }
            @Suppress("UNCHECKED_CAST")
            val mutable = TransitViewModel::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
                .get(model) as MutableStateFlow<UiState>
            mutable.value = mutable.value.copy(emmaStops=stops, favorites=stops.map { it.id }.toSet(), stopIndexLoaded=true)
            field(model,"nearbyActive",true)
            model.setFavoritesVisible(true)
            fun awaitResult(condition: () -> Boolean) {
                val deadline = System.nanoTime() + 5_000_000_000L
                while (!condition() && System.nanoTime() < deadline) { runCurrent(); Thread.sleep(5) }
                assertTrue("Expected favourite state was not published", condition())
            }
            awaitResult { !model.state.value.favoritesLoading && model.state.value.favoriteStations.any { it.unavailable } }
            assertEquals(1, counts["hkir:recovery-healthy"]?.get())
            assertEquals(1, counts["hkir:recovery-broken"]?.get())
            advanceTimeBy(15_001)
            awaitResult { model.state.value.favoriteStations.size == 2 && model.state.value.favoriteStations.none { it.unavailable || it.stop.routeIds.isEmpty() } }
            assertEquals(2, counts["hkir:recovery-broken"]?.get())
            assertEquals("The successful station must not be fetched again",1,counts["hkir:recovery-healthy"]?.get())
            failAll.set(true)
            model.refreshFavorites()
            awaitResult { !model.state.value.favoritesLoading && model.state.value.favoriteStations.all { it.unavailable } }
            assertTrue("A refresh failure must preserve the known line badges",
                model.state.value.favoriteStations.all { it.stop.routeIds.isNotEmpty() })
            val failedCounts = counts.mapValues { it.value.get() }
            advanceTimeBy(14_999)
            runCurrent()
            assertEquals("Retry must be paced", failedCounts, counts.mapValues { it.value.get() })
            model.setFavoritesVisible(false)
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals("Leaving favourites cancels scheduled recovery", failedCounts, counts.mapValues { it.value.get() })

            val restarted = TransitViewModel(app)
            try {
                field(restarted,"stopsRepository", EmmaStopsRepository(app, EmmaGraphqlClient(http, EmmaRequestBackoff())))
                runCurrent()
                @Suppress("UNCHECKED_CAST")
                val restartedState = TransitViewModel::class.java.getDeclaredField("mutableState").apply { isAccessible = true }
                    .get(restarted) as MutableStateFlow<UiState>
                restartedState.value = restartedState.value.copy(emmaStops=stops, favorites=stops.map { it.id }.toSet(), stopIndexLoaded=true)
                field(restarted,"nearbyActive",true)
                restarted.setFavoritesVisible(true)
                awaitResult { !restarted.state.value.favoritesLoading && restarted.state.value.favoriteStations.size == 2 &&
                    restarted.state.value.favoriteStations.all { it.stop.routeIds.isNotEmpty() && !it.unavailable } }
                assertEquals("A fresh saved line list must not wait for the failing network",failedCounts,counts.mapValues { it.value.get() })
            } finally { restarted.viewModelScope.cancel() }

        } finally {
            model.viewModelScope.cancel()
            http.dispatcher.cancelAll(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll()
            Dispatchers.resetMain()
        }
    }
}
