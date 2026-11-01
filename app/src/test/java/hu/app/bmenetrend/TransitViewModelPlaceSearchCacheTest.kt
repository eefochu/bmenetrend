package hu.app.bmenetrend

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.EmmaGraphqlClient
import hu.app.bmenetrend.data.EmmaStopsRepository
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.PlaceResult
import hu.app.bmenetrend.data.PlaceSearch
import hu.app.bmenetrend.data.PlacesRepository
import hu.app.bmenetrend.data.TransitStop
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A cached geocoder hit feeds search-as-you-type immediately; the station search runs in
 * parallel and must never hold the place suggestions hostage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TransitViewModelPlaceSearchCacheTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val app = ApplicationProvider.getApplicationContext<Application>()
        try {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        } catch (_: Exception) {}
        NetworkAccessPolicy.initialize(app)
        NetworkAccessPolicy.setOffline(app, false)
    }

    @After
    fun teardown() {
        try {
            NetworkAccessPolicy.setOffline(ApplicationProvider.getApplicationContext<Application>(), false)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun setField(target: Any, name: String, value: Any) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun seedPlaces(repository: PlacesRepository, query: String, places: List<PlaceResult>) {
        val field = PlacesRepository::class.java.getDeclaredField("memoryCache")
        field.isAccessible = true
        (field.get(repository) as MutableMap<String, List<PlaceResult>>)[PlaceSearch.query(query)] = places
    }

    private val places = listOf(
        PlaceResult("node:1", "Keleti pályaudvar", "Budapest", GeoPoint(47.500, 19.083))
    )

    @Test
    fun cachedPlacesPublishWhileStationSearchIsBlocked() = runTest(dispatcher.scheduler) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val searchEntered = CountDownLatch(1)
        val searchRelease = CountDownLatch(1)
        val searchCalls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer().also { chain.request().body?.writeTo(it) }
            val body = buffer.readUtf8()
            if (body.contains("FindStops")) {
                searchCalls.incrementAndGet()
                searchEntered.countDown()
                searchRelease.await(10, TimeUnit.SECONDS)
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONObject().put("data", JSONObject().put("stops", JSONArray())).toString().toResponseBody())
                .build()
        }.build()
        try {
            val viewModel = TransitViewModel(app)
            setField(viewModel, "stopsRepository", EmmaStopsRepository(app, EmmaGraphqlClient(http)))
            val placesRepository = PlacesRepository(app)
            seedPlaces(placesRepository, "Keleti", places)
            setField(viewModel, "placesRepository", placesRepository)

            // Let startup publish the bundled network and settle the initial offline emission
            // (which legitimately clears any earlier search publication).
            val startupDeadline = System.currentTimeMillis() + 20_000
            while (viewModel.state.value.network == null && System.currentTimeMillis() < startupDeadline) {
                runCurrent()
                Thread.sleep(2)
            }
            repeat(5) { runCurrent(); Thread.sleep(2) }

            viewModel.searchPlaces("Keleti", true)
            // A memory hit must be published synchronously, before debounce or any HTTP.
            assertEquals(places, viewModel.state.value.placeResults)
            assertFalse("cached place hit must not show a spinner", viewModel.state.value.placesLoading)

            advanceTimeBy(251)
            runCurrent()
            // The station query is now parked inside the interceptor; places still land.
            assertEquals(places, viewModel.state.value.placeResults)
            assertFalse(viewModel.state.value.placesLoading)
            assertTrue("station search should have started", searchEntered.await(5, TimeUnit.SECONDS))
            assertEquals(1, searchCalls.get())

            searchRelease.countDown()
            runCurrent()
            assertEquals(places, viewModel.state.value.placeResults)
        } finally {
            searchRelease.countDown()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
