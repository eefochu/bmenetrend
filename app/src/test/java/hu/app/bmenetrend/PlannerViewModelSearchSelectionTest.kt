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

    @Test fun searchRetainsSourceStopIdsOnConsolidatedStopResults(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        NetworkAccessPolicy.setOffline(app, true)

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
        val model = ViewModelProvider(viewModelStore, factory)[PlannerViewModel::class.java]

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

        model.updateStops(emptyList(), listOf(platform1, platform2))
        delay(500)

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
    }
}
