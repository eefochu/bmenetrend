package hu.app.bmenetrend

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerWarmPlanTest {

    private class CountingFakeBackend : PlannerBackend {
        val routeCalls = AtomicInteger(0)

        override suspend fun routes(
            from: PlannerEndpoint,
            to: PlannerEndpoint,
            ready: Instant,
            busesOnly: Boolean
        ): List<TripItinerary> {
            routeCalls.incrementAndGet()
            val route = TransitRoute("volan:bus", "762", "Bus", emptyList(), "volan", transportMode = "BUS")
            val leg = PlanLeg(
                "BUS",
                ready.plusSeconds(3600),
                ready.plusSeconds(4200),
                1000.0,
                PlanPoint(from.name, from.position),
                PlanPoint(to.name, to.position),
                route,
                to.name,
                false,
                false,
                emptyList()
            )
            return listOf(TripItinerary("bus", leg.start, leg.end, listOf(leg)))
        }

        override suspend fun plainRoutes(
            from: PlannerEndpoint,
            to: PlannerEndpoint,
            ready: Instant,
            busesOnly: Boolean
        ): List<TripItinerary> = routes(from, to, ready, busesOnly)

        override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult =
            WalkingResult(emptyMap())
    }

    private val origin = PlannerEndpoint("orig", "Origin Stop", "", GeoPoint(47.49, 18.74))
    private val destination = PlannerEndpoint("dest", "Destination Stop", "", GeoPoint(47.51, 19.05))

    private lateinit var app: Application

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext<Application>()
        NetworkAccessPolicy.setOffline(app, false)
        NetworkAccessPolicy.onConnectivityChanged(true)
    }

    @After
    fun teardown() {
        NetworkAccessPolicy.setOffline(app, false)
        NetworkAccessPolicy.onConnectivityChanged(true)
    }

    private fun TestScope.createModel(backend: CountingFakeBackend): PlannerViewModel {
        val model = PlannerViewModel(app, backend) {
            TransitNetwork(emptyList(), emptyList(), emptyList(), Instant.now(), "fixture")
        }
        runCurrent()
        LocalPlanningService.providerIds.forEach { model.setLocalProvider(it, false) }
        runCurrent()
        return model
    }

    private fun TestScope.awaitPrefetch(backend: CountingFakeBackend, timeoutNanos: Long = 5_000_000_000L) {
        val deadline = System.nanoTime() + timeoutNanos
        while (backend.routeCalls.get() < 2 && System.nanoTime() < deadline) {
            runCurrent()
            Thread.sleep(5)
        }
        assertTrue("Prefetch did not invoke backend routes within deadline", backend.routeCalls.get() >= 2)
        runCurrent()
        Thread.sleep(20)
        runCurrent()
    }

    private fun TestScope.awaitPlan(model: PlannerViewModel, timeoutNanos: Long = 5_000_000_000L) {
        val deadline = System.nanoTime() + timeoutNanos
        while (model.state.value.planning && System.nanoTime() < deadline) {
            runCurrent()
            Thread.sleep(5)
        }
        assertFalse("Planner did not finish within deadline", model.state.value.planning)
        assertNull("Planner returned error: ${model.state.value.error}", model.state.value.error)
    }

    /**
     * Requirement 1a:
     * Choosing origin+destination, advancing past the 250 ms debounce until the prefetch completes,
     * then plan() -> result shown and NO additional backend route calls after the prefetch.
     */
    @Test
    fun prefetchReusedWhenEndpointsUnchangedAndFeasible() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val backend = CountingFakeBackend()
        val model = createModel(backend)
        try {
            model.edit(PlannerField.ORIGIN)
            model.choose(origin)
            runCurrent()

            model.edit(PlannerField.DESTINATION)
            model.choose(destination)
            runCurrent()

            // Advance past the 250 ms debounce
            advanceTimeBy(300)
            runCurrent()

            // Await prefetch completion
            awaitPrefetch(backend)
            val callsAfterPrefetch = backend.routeCalls.get()
            assertTrue("Prefetch should have executed route queries", callsAfterPrefetch > 0)

            // Submit search via plan()
            model.plan()
            awaitPlan(model)

            // Result must be present and no extra route calls should have been made
            assertNotNull("Plan result should be shown", model.state.value.result)
            assertEquals(1, model.state.value.result?.itineraries?.size)
            assertEquals(
                "No additional backend route calls should occur when reusing warm plan",
                callsAfterPrefetch,
                backend.routeCalls.get()
            )
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    /**
     * Requirement 1b:
     * plan(force = true) after a completed prefetch -> backend is called again.
     */
    @Test
    fun forcePlanBypassesWarmPlan() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val backend = CountingFakeBackend()
        val model = createModel(backend)
        try {
            model.edit(PlannerField.ORIGIN)
            model.choose(origin)
            runCurrent()

            model.edit(PlannerField.DESTINATION)
            model.choose(destination)
            runCurrent()

            // Advance past the 250 ms debounce
            advanceTimeBy(300)
            runCurrent()

            awaitPrefetch(backend)
            val callsAfterPrefetch = backend.routeCalls.get()
            assertTrue("Prefetch should have executed route queries", callsAfterPrefetch > 0)

            // Force plan should invalidate warm plan and query backend fresh
            model.plan(force = true)
            awaitPlan(model)

            assertNotNull("Plan result should be shown", model.state.value.result)
            assertTrue(
                "Backend routes should be called again on forced plan",
                backend.routeCalls.get() > callsAfterPrefetch
            )
            assertEquals(
                "Backend routes should have been called for both bus and coach queries",
                callsAfterPrefetch + 2,
                backend.routeCalls.get()
            )
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }

    /**
     * Requirement 1c:
     * With a fixed departure (setDeparture to a future instant) and a completed prefetch,
     * advancing the clock more than 90 s before plan() -> fresh backend calls (the prefetch is stale).
     *
     * Only do this if the VM's clock (Instant.now()) can be controlled in the existing test style;
     * if it cannot be controlled, skip (c) and say why.
     */
    @Ignore("Skipped because PlannerViewModel invokes static Instant.now() directly in warmOnlinePlan and plan, which cannot be controlled via testScheduler or existing test infrastructure without production code modifications.")
    @Test
    fun stalePrefetchPast90SecondsTriggersFreshPlan() {
        // Skipped: See explanation above.
    }

    /**
     * Requirement 1d:
     * edit(PlannerField.DESTINATION) before the debounce fires -> no prefetch backend calls at all.
     */
    @Test
    fun editBeforeDebounceCancelsPrefetch() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val backend = CountingFakeBackend()
        val model = createModel(backend)
        try {
            model.edit(PlannerField.ORIGIN)
            model.choose(origin)
            runCurrent()

            model.edit(PlannerField.DESTINATION)
            model.choose(destination)
            runCurrent()

            // Advance by only 100 ms (before the 250 ms debounce triggers)
            advanceTimeBy(100)
            runCurrent()
            assertEquals("No backend calls before debounce fires", 0, backend.routeCalls.get())

            // Editing a field cancels warmupJob
            model.edit(PlannerField.DESTINATION)
            runCurrent()

            // Advance well past the 250 ms debounce window
            advanceTimeBy(500)
            runCurrent()
            Thread.sleep(50)
            runCurrent()

            assertEquals("Cancelling warmup before debounce fires must result in 0 backend route calls", 0, backend.routeCalls.get())
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
