package hu.app.bmenetrend

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.TransitNetwork
import hu.app.bmenetrend.data.TransitRoute
import hu.app.bmenetrend.data.TransitStop
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import hu.app.bmenetrend.ui.UiState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.Instant

/**
 * [TransitViewModel] now loads the bundled network and the bundled EMMA route catalogue on
 * `Dispatchers.IO` after construction so the first frame can paint immediately.
 *
 * These tests guard the publication rules of that asynchronous startup:
 *  - construction must not block,
 *  - the bundled data is eventually published,
 *  - a network/offline catalogue that was already published is never overwritten,
 *  - a user selection made before the load finishes is preserved,
 *  - cancelling the ViewModel scope drops a late startup result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TransitViewModelStartupTest {

    private lateinit var app: Application

    private val initialNetworkField = TransitViewModel::class.java
        .getDeclaredField("initialNetwork").apply { isAccessible = true }
    private val bundledCatalogueField = TransitViewModel::class.java
        .getDeclaredField("bundledCatalogue").apply { isAccessible = true }

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        try {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        } catch (_: Exception) {}
        NetworkAccessPolicy.initialize(app)
        NetworkAccessPolicy.setOffline(app, false)
    }

    private fun mutableState(viewModel: TransitViewModel): MutableStateFlow<UiState> {
        val field = TransitViewModel::class.java.getDeclaredField("mutableState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(viewModel) as MutableStateFlow<UiState>
    }

    private fun sampleStop(id: String) = TransitStop(
        id = id,
        name = "Stop $id",
        position = GeoPoint(47.49, 19.05),
        routeIds = listOf("bkk:1"),
        providerId = "bkk",
        station = true,
    )

    private fun sampleNetwork(source: String) = TransitNetwork(
        stops = listOf(sampleStop("$source:stop")),
        routes = listOf(TransitRoute("$source:route", "1", "Route", emptyList(), "bkk")),
        trips = emptyList(),
        fetchedAt = Instant.now(),
        source = source,
    )

    /** Runs queued main-thread work (including coroutine resumptions) until [condition] holds. */
    private fun awaitUntil(timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(5)
        }
        ShadowLooper.idleMainLooper()
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    private fun awaitStartup(viewModel: TransitViewModel) {
        awaitUntil { initialNetworkField.get(viewModel) != null && bundledCatalogueField.get(viewModel) != null }
    }

    @Test
    fun constructionReturnsImmediatelyAndPublishesBundledDataOnCompletion() {
        val viewModel = TransitViewModel(app)

        // Bundled parsing must not happen on the constructor thread.
        assertNull("network must not be published synchronously", viewModel.state.value.network)
        assertTrue(viewModel.state.value.loading)

        awaitStartup(viewModel)
        awaitUntil { viewModel.state.value.network != null }

        val network = viewModel.state.value.network
        assertNotNull("bundled network must be published", network)
        assertTrue("bundled network must contain stops", network!!.stops.isNotEmpty())
        assertFalse("loading flag must be cleared after publishing", viewModel.state.value.loading)
        assertNotNull("a default stop must be selected", viewModel.state.value.selectedStopId)
        assertTrue(
            "the default selection must belong to the bundled network",
            viewModel.state.value.selectedStopId in network.stops.map { it.id }
        )
    }

    @Test
    fun lateStartupDoesNotOverwriteAnAlreadyPublishedNetwork() {
        val viewModel = TransitViewModel(app)
        val state = mutableState(viewModel)
        val published = sampleNetwork("already-published")
        state.value = state.value.copy(network = published, selectedStopId = "already-published:stop")

        awaitStartup(viewModel)

        assertSame("an already published network must be kept", published, state.value.network)
        assertEquals("already-published:stop", state.value.selectedStopId)
    }

    @Test
    fun lateStartupDoesNotOverwritePublishedOfflineCatalogue() {
        val viewModel = TransitViewModel(app)
        val state = mutableState(viewModel)
        val offline = sampleNetwork("offline")
        state.value = state.value.copy(
            offlineMode = true,
            network = offline,
            selectedStopId = "offline:stop",
            loading = false,
        )

        awaitStartup(viewModel)

        assertSame("an offline catalogue must not be replaced by bundled data", offline, state.value.network)
        assertEquals("offline:stop", state.value.selectedStopId)
    }

    @Test
    fun userSelectionBeforeStartupCompletesIsPreserved() {
        val viewModel = TransitViewModel(app)

        viewModel.selectStop("emma:BKK:user-selected")
        val selected = viewModel.state.value.selectedStopId
        assertNotNull("selection must be applied synchronously", selected)
        assertNull("bundled network is still loading", viewModel.state.value.network)

        awaitStartup(viewModel)
        awaitUntil { viewModel.state.value.network != null }

        assertEquals("the user's selection must survive the async startup", selected, viewModel.state.value.selectedStopId)
        assertTrue(viewModel.state.value.network!!.stops.isNotEmpty())
    }

    @Test
    fun cancellingTheViewModelScopeDropsALateStartupResult() {
        val viewModel = TransitViewModel(app)
        viewModel.viewModelScope.cancel()

        // Give the in-flight IO parse plenty of time to complete; its result must be discarded.
        repeat(100) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }

        assertNull("cancelled startup must not publish the bundled network", viewModel.state.value.network)
        assertNull("cancelled startup must not retain the bundled network", initialNetworkField.get(viewModel))
        assertTrue("loading stays as-is when startup is cancelled", viewModel.state.value.loading)
    }
}
