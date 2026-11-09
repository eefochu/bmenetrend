package hu.app.bmenetrend

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerLeaveNowTest {
    @Test fun submissionUsesActualNowAndScheduledDepartureStillUsesChosenTime() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<Application>()
        NetworkAccessPolicy.setOffline(app, false)
        val times = java.util.Collections.synchronizedList(mutableListOf<Instant>())
        val backend = object : PlannerBackend {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                times += ready
                val route = TransitRoute("volan:bus", "762", "Bus", emptyList(), "volan", transportMode = "BUS")
                val leg = PlanLeg("BUS", ready.plusSeconds(600), ready.plusSeconds(1200), 1000.0,
                    PlanPoint(from.name, from.position), PlanPoint(to.name, to.position), route, to.name, false, false, emptyList())
                return listOf(TripItinerary("bus", leg.start, leg.end, listOf(leg)))
            }
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant) = WalkingResult(emptyMap())
        }
        val model = PlannerViewModel(app, backend) { TransitNetwork(emptyList(), emptyList(), emptyList(), Instant.now(), "fixture") }
        suspend fun awaitPlan() {
            withTimeout(10_000) {
                while (model.state.value.planning) { runCurrent(); delay(1) }
            }
            assertNull(model.state.value.error)
        }
        try {
            runCurrent()
            LocalPlanningService.providerIds.forEach { model.setLocalProvider(it, false) }
            model.edit(PlannerField.ORIGIN)
            model.choose(PlannerEndpoint("a", "Origin", "", GeoPoint(47.49,18.74)))
            model.edit(PlannerField.DESTINATION)
            model.choose(PlannerEndpoint("b", "Destination", "", GeoPoint(47.51,19.05)))
            val before = Instant.now()
            model.plan()
            val after = Instant.now()
            awaitPlan()
            val plan = model.state.value.result!!
            assertEquals(plan.requestedAt, plan.readyAt)
            assertTrue(plan.readyAt in before..after)
            assertTrue(times.isNotEmpty())
            assertTrue(times.all { it == plan.readyAt })
            assertEquals(1200L, plan.itineraries.single().totalSeconds(plan.readyAt))
            times.clear()
            val chosen = Instant.now().plusSeconds(3600)
            model.setDeparture(chosen)
            model.plan()
            awaitPlan()
            assertEquals(chosen, model.state.value.result!!.readyAt)
            assertTrue(times.all { it == chosen })
        } finally {
            model.viewModelScope.cancel()
            Dispatchers.resetMain()
        }
    }
}
