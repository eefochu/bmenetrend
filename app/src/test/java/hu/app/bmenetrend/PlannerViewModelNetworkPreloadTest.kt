package hu.app.bmenetrend

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerViewModelNetworkPreloadTest {
    private lateinit var app: Application
    private val models = mutableListOf<PlannerViewModel>()
    private val routeCalls = AtomicInteger()

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        NetworkAccessPolicy.setOffline(app, false)
        NetworkAccessPolicy.onConnectivityChanged(true)
    }

    @After fun cleanup() {
        models.forEach { it.viewModelScope.cancel() }
        NetworkAccessPolicy.setOffline(app, false)
        NetworkAccessPolicy.onConnectivityChanged(true)
        Dispatchers.resetMain()
    }

    @Test fun constructionStartsNetworkLoadBeforeAnyPlanRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val model = createModel {
            started.complete(Unit)
            release.await()
            network()
        }

        try {
            withTimeout(5_000) { started.await() }
            assertEquals(0, routeCalls.get())
            assertFalse(model.state.value.planning)
            assertNull(model.state.value.result)
        } finally {
            release.complete(Unit)
        }
    }

    @Test fun consecutivePlansOnTheSameDayReuseThePreloadedNetwork() = runBlocking {
        val loads = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        val model = createModel {
            loads.incrementAndGet()
            started.complete(Unit)
            network()
        }
        withTimeout(5_000) { started.await() }
        selectEndpoints(model)

        planAndAwaitDirectResult(model)
        val firstPlanCalls = routeCalls.get()
        planAndAwaitDirectResult(model)

        assertTrue("The second submission must execute a fresh search", routeCalls.get() > firstPlanCalls)
        assertEquals("Both searches must reuse the startup load", 1, loads.get())
    }

    @Test fun failedPreloadIsRetriedByTheNextPlanRequest() = runBlocking {
        val loads = AtomicInteger()
        val firstLoad = CompletableDeferred<Job>()
        val retriedLoad = CompletableDeferred<Job>()
        val model = createModel(beforeDirectResult = { retriedLoad.await().join() }) {
            if (loads.incrementAndGet() == 1) {
                firstLoad.complete(currentCoroutineContext().job)
                error("Timetable fixture unavailable")
            }
            retriedLoad.complete(currentCoroutineContext().job)
            network()
        }
        // Wait for the Deferred to finish failing, rather than racing IO completion.
        withTimeout(5_000) { firstLoad.await().join() }
        assertEquals(1, loads.get())
        selectEndpoints(model)

        planAndAwaitDirectResult(model)

        assertEquals("The failed startup Deferred must be replaced", 2, loads.get())
        planAndAwaitDirectResult(model)
        assertEquals("A successful retry must also be shared", 2, loads.get())
    }

    @Test fun directResultStillReturnsWhenTheRetriedNetworkLoadAlsoFails() = runBlocking {
        val loads = AtomicInteger()
        val firstLoad = CompletableDeferred<Job>()
        val retriedLoad = CompletableDeferred<Job>()
        val model = createModel(beforeDirectResult = { retriedLoad.await().join() }) {
            if (loads.incrementAndGet() == 1) firstLoad.complete(currentCoroutineContext().job)
            else retriedLoad.complete(currentCoroutineContext().job)
            error("Timetable fixture unavailable")
        }
        withTimeout(5_000) { firstLoad.await().join() }
        selectEndpoints(model)

        planAndAwaitDirectResult(model)

        assertEquals("The search must attempt to reload after the startup failure", 2, loads.get())
    }

    private fun createModel(
        beforeDirectResult: suspend () -> Unit = {},
        loadNetwork: suspend () -> TransitNetwork,
    ): PlannerViewModel {
        val backend = object : PlannerBackend {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                routeCalls.incrementAndGet()
                beforeDirectResult()
                val route = TransitRoute("volan:fixture", "762", "Bus", emptyList(), "volan", transportMode = "BUS")
                val leg = PlanLeg("BUS", ready.plusSeconds(300), ready.plusSeconds(900), 3000.0,
                    PlanPoint(from.name, from.position), PlanPoint(to.name, to.position), route,
                    to.name, false, false, emptyList())
                return listOf(TripItinerary("direct", leg.start, leg.end, listOf(leg)))
            }

            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant) = WalkingResult(emptyMap())
        }
        return PlannerViewModel(app, backend, loadNetwork).also { models += it }
    }

    private fun selectEndpoints(model: PlannerViewModel) {
        model.setDeparture(Instant.now().plusSeconds(3600))
        model.edit(PlannerField.ORIGIN)
        model.choose(PlannerEndpoint("a", "Origin", "", GeoPoint(47.49, 18.74)))
        model.edit(PlannerField.DESTINATION)
        model.choose(PlannerEndpoint("b", "Destination", "", GeoPoint(47.51, 19.05)))
    }

    private suspend fun planAndAwaitDirectResult(model: PlannerViewModel) {
        model.plan(force = true)
        // IO and the hybrid planner's Default dispatcher use real time, not the Main
        // test scheduler. Bound the wait without advancing virtual debounce timers.
        withTimeout(5_000) {
            while (model.state.value.planning) delay(5)
        }
        assertNull(model.state.value.error)
        assertEquals(listOf("direct"), model.state.value.result?.itineraries?.map { it.id })
    }

    private fun network() = TransitNetwork(emptyList(), emptyList(), emptyList(), Instant.now(), "fixture")
}
