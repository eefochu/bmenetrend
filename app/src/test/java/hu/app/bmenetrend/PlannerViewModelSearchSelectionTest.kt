package hu.app.bmenetrend

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerViewModelSearchSelectionTest {
    @OptIn(DelicateCoroutinesApi::class)
    private val mainThreadSurrogate = newSingleThreadContext("UI thread")
    private lateinit var viewModelStore: ViewModelStore

    @Before fun setup() {
        Dispatchers.setMain(mainThreadSurrogate)
        viewModelStore = ViewModelStore()
        val app = ApplicationProvider.getApplicationContext<Application>()
        NetworkAccessPolicy.setOffline(app, false)
    }

    @After fun teardown() {
        try {
            if (::viewModelStore.isInitialized) {
                viewModelStore.clear()
            }
        } finally {
            try {
                val app = ApplicationProvider.getApplicationContext<Application>()
                NetworkAccessPolicy.setOffline(app, false)
            } finally {
                Dispatchers.resetMain()
                mainThreadSurrogate.close()
            }
        }
    }

    private fun createModel(app: Application): PlannerViewModel {
        val dummyBackend = object : PlannerBackend {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> = emptyList()
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant) = WalkingResult(emptyMap())
        }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return PlannerViewModel(app, dummyBackend) {
                    TransitNetwork(emptyList(), emptyList(), emptyList(), Instant.now(), "fixture")
                } as T
            }
        }
        return ViewModelProvider(viewModelStore, factory)[PlannerViewModel::class.java]
    }

    @Test fun searchRetainsSourceStopIdsOnConsolidatedStopResults(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        NetworkAccessPolicy.setOffline(app, true)
        val model = createModel(app)

        withTimeout(3_000) {
            while (!model.state.value.offline) delay(20)
        }

        val platform1 = TransitStop(
            id = "emma:hkir:773538_0",
            name = "Kelenföld vasútállomás M",
            position = GeoPoint(47.4635, 19.0232),
            routeIds = emptyList(),
            providerId = "volan",
            sourceStopIds = listOf("emma:hkir:773538_0"),
            station = false,
        )
        val platform2 = TransitStop(
            id = "emma:BKK:007897",
            name = "Kelenföld vasútállomás M",
            position = GeoPoint(47.4640, 19.0230),
            routeIds = emptyList(),
            providerId = "bkk",
            sourceStopIds = listOf("emma:BKK:007897"),
            station = false,
        )
        val parentStation = TransitStop(
            id = "emma:hkir:station_773538",
            name = "Kelenföld vasútállomás M",
            position = GeoPoint(47.4635, 19.0232),
            routeIds = emptyList(),
            providerId = "volan",
            sourceStopIds = listOf("emma:hkir:station_773538"),
            station = true,
        )

        model.updateStops(emptyList(), listOf(platform1, platform2, parentStation))
        delay(300)

        model.edit(PlannerField.DESTINATION)
        model.search("Kelenföld")

        withTimeout(5_000) {
            while (model.state.value.suggestions.isEmpty()) {
                delay(50)
            }
        }

        val suggestions = model.state.value.suggestions
        assertTrue("Suggestions should not be empty", suggestions.isNotEmpty())
        val kelenfold = suggestions.first { it.name.contains("Kelenföld") }
        assertTrue(
            "PlannerEndpoint must retain all clustered sourceStopIds via PlannerEndpoint.fromStop, found: ${kelenfold.sourceStopIds}",
            kelenfold.sourceStopIds.contains("emma:hkir:773538_0") && kelenfold.sourceStopIds.contains("emma:BKK:007897")
        )
        assertFalse("Parent station records must not become planner search results", suggestions.any { it.id == parentStation.id })
    }

    @Test fun localResultsArriveBeforeRemoteDebounce(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val model = createModel(app)
        val local = TransitStop("local-unique", "Kelenföld Hub", GeoPoint(47.46, 19.02), emptyList(), "bkk")

        model.updateStops(listOf(local))
        delay(200)

        model.edit(PlannerField.ORIGIN)
        val startTime = System.currentTimeMillis()
        model.search("Kelenföld")

        // Local station results must appear well before the 400ms geocoder debounce
        withTimeout(250) {
            while (model.state.value.suggestions.isEmpty()) delay(10)
        }
        val elapsed = System.currentTimeMillis() - startTime
        assertTrue("Local results should arrive in <250ms, took ${elapsed}ms", elapsed < 250)
        assertEquals("local-unique", model.state.value.suggestions.first().id)
        assertTrue("Searching flag should remain true while remote geocoder is debouncing", model.state.value.searching)
    }

    @Test fun offlineLocalSearchReturnsImmediately(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        NetworkAccessPolicy.setOffline(app, true)
        val model = createModel(app)

        withTimeout(2_000) {
            while (!model.state.value.offline) delay(20)
        }

        val offlineStop = TransitStop("offline-stop", "Kelenföld Offline", GeoPoint(47.46, 19.02), emptyList(), "volan", station = false)
        model.updateStops(emptyList(), listOf(offlineStop))
        delay(200)

        model.edit(PlannerField.ORIGIN)
        model.search("Kelenföld")

        // Offline mode finishes search immediately without waiting for remote 400ms debounce
        withTimeout(200) {
            while (model.state.value.searching || model.state.value.suggestions.isEmpty()) delay(10)
        }
        assertEquals("offline-stop", model.state.value.suggestions.first().id)
        assertFalse("Searching flag should be false immediately in offline mode", model.state.value.searching)
    }

    @Test fun cancellationOfNewerQueryPreventsStalePublication(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val model = createModel(app)
        val alpha = TransitStop("alpha", "Alpha Station", GeoPoint(47.46, 19.02), emptyList(), "bkk")
        val beta = TransitStop("beta", "Beta Station", GeoPoint(47.47, 19.02), emptyList(), "bkk")

        model.updateStops(listOf(alpha, beta))
        delay(200)

        model.edit(PlannerField.DESTINATION)
        // Fire search for Alpha, then immediately override with search for Beta
        model.search("Alpha")
        model.search("Beta")

        withTimeout(1_000) {
            while (model.state.value.suggestions.isEmpty()) delay(20)
        }
        // Suggestions must be Beta, never stale Alpha
        assertEquals(listOf("beta"), model.state.value.suggestions.map { it.id })
        assertEquals("Beta", model.state.value.query)

        // Wait beyond 400ms to ensure no delayed Alpha callback overwrote results
        delay(500)
        assertEquals(listOf("beta"), model.state.value.suggestions.map { it.id })
    }

    @Test fun repeatedIdenticalCataloguePreservesReadiness(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val model = createModel(app)
        val catalogue = listOf(TransitStop("alpha", "Alpha Terminal", GeoPoint(47.46, 19.02), emptyList(), "bkk"))

        model.updateStops(catalogue)
        delay(200)

        model.edit(PlannerField.DESTINATION)
        model.search("Alpha")
        withTimeout(1_000) {
            while (model.state.value.suggestions.isEmpty()) delay(20)
        }
        assertEquals("alpha", model.state.value.suggestions.single().id)

        // Same list instance identity (===) must preserve readiness immediately without clearing suggestions
        model.updateStops(catalogue)
        assertEquals("alpha", model.state.value.suggestions.single().id)
    }

    @Test fun changedCatalogueUpdatesActiveSearch(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val model = createModel(app)
        val initialList = listOf(TransitStop("alpha", "Terminal Station", GeoPoint(47.46, 19.02), emptyList(), "bkk"))

        model.updateStops(initialList)
        delay(200)

        model.edit(PlannerField.DESTINATION)
        model.search("Terminal")
        withTimeout(1_000) {
            while (model.state.value.suggestions.isEmpty()) delay(20)
        }
        assertEquals(listOf("alpha"), model.state.value.suggestions.map { it.id })

        // Provide a new catalogue list instance with a different stop matching the query
        val updatedList = listOf(TransitStop("beta", "Terminal Station", GeoPoint(47.47, 19.02), emptyList(), "bkk"))
        model.updateStops(updatedList)

        // Background indexing runs and automatically re-triggers the active search query
        withTimeout(2_000) {
            while (model.state.value.suggestions.map { it.id } != listOf("beta")) delay(20)
        }
        assertEquals(listOf("beta"), model.state.value.suggestions.map { it.id })
    }
}
