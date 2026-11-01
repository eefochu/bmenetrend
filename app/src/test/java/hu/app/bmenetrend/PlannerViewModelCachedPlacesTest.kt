package hu.app.bmenetrend

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.EmmaGraphqlClient
import hu.app.bmenetrend.data.EmmaStopsRepository
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.PlanPoint
import hu.app.bmenetrend.data.PlaceResult
import hu.app.bmenetrend.data.PlaceSearch
import hu.app.bmenetrend.data.PlacesRepository
import hu.app.bmenetrend.data.PlannerBackend
import hu.app.bmenetrend.data.PlannerEndpoint
import hu.app.bmenetrend.data.TransitNetwork
import hu.app.bmenetrend.data.TripItinerary
import hu.app.bmenetrend.data.WalkingResult
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
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Planner place suggestions come from the geocoder cache and appear below local stations
 * without waiting for the remote station search.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlannerViewModelCachedPlacesTest {

    private val dispatcher = StandardTestDispatcher()
    private val places = listOf(
        PlaceResult("node:1", "Keleti pályaudvar", "Budapest", GeoPoint(47.500, 19.083))
    )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        val app = ApplicationProvider.getApplicationContext<Application>()
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
    private fun seedPlaces(repository: PlacesRepository, query: String, values: List<PlaceResult>) {
        val field = PlacesRepository::class.java.getDeclaredField("memoryCache")
        field.isAccessible = true
        (field.get(repository) as MutableMap<String, List<PlaceResult>>)[PlaceSearch.query(query)] = values
    }

    @Test
    fun cachedPlaceSuggestionsAppearBeforeStationDebounceAndStationHttp() = runTest(dispatcher.scheduler) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer().also { chain.request().body?.writeTo(it) }
            if (buffer.readUtf8().contains("FindStops")) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONObject().put("data", JSONObject().put("stops", JSONArray())).toString().toResponseBody())
                .build()
        }.build()
        try {
            val backend = object : PlannerBackend {
                override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean) = emptyList<TripItinerary>()
                override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant) = WalkingResult(emptyMap())
            }
            val model = PlannerViewModel(app, backend) {
                TransitNetwork(emptyList(), emptyList(), emptyList(), Instant.now(), "fixture")
            }
            setField(model, "stopsRepository", EmmaStopsRepository(app, EmmaGraphqlClient(http)))
            val placesRepository = PlacesRepository(app)
            seedPlaces(placesRepository, "Keleti", places)
            assertEquals(places, placesRepository.cached("Keleti"))
            setField(model, "places", placesRepository)

            // Let the constructor's initial offline-state emission settle before searching.
            repeat(20) { runCurrent(); Thread.sleep(2) }

            model.edit(PlannerField.DESTINATION)
            model.search("Keleti")

            // No virtual time is advanced: the cached place must already be listed.
            val deadline = System.currentTimeMillis() + 5_000
            while (model.state.value.suggestions.none { it.name == "Keleti pályaudvar" } && System.currentTimeMillis() < deadline) {
                runCurrent()
                Thread.sleep(2)
            }
            assertTrue("cached place suggestion must appear before debounce/station search",
                model.state.value.suggestions.any { it.name == "Keleti pályaudvar" })
            assertTrue("search must still be running after only local+cached results", model.state.value.searching)

            advanceTimeBy(301)
            runCurrent()
            assertTrue("remote station search should now be parked in HTTP", entered.await(5, TimeUnit.SECONDS))
            assertTrue("cached place stays visible while stations are blocked",
                model.state.value.suggestions.any { it.name == "Keleti pályaudvar" })
            assertFalse(model.state.value.searchUnavailable)
        } finally {
            release.countDown()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }
}
