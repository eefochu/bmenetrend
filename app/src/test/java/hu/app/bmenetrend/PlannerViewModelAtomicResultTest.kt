package hu.app.bmenetrend

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerViewModelAtomicResultTest {
    private lateinit var app: Application

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        NetworkAccessPolicy.setOffline(app, false)
        NetworkAccessPolicy.onConnectivityChanged(true)
    }

    @After fun cleanup() {
        NetworkAccessPolicy.setOffline(app, false)
        NetworkAccessPolicy.onConnectivityChanged(true)
        Dispatchers.resetMain()
    }

    @Test
    fun resultsNeverBecomeProvisional_andPracticalPairPublishedAtomicallyWithPlanningFalse() = runBlocking {
        val origin = PlannerEndpoint("stop:origin", "Origin Stop", "", GeoPoint(47.460, 18.946))
        val destination = PlannerEndpoint("stop:dest", "Destination Stop", "", GeoPoint(47.470, 18.956))

        val backend = object : PlannerBackend {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                return if (!busesOnly) {
                    delay(50)
                    val fastLeg = PlanLeg(
                        mode = "BUS",
                        start = ready.plusSeconds(300),
                        end = ready.plusSeconds(900),
                        distance = 3000.0,
                        from = PlanPoint(origin.name, origin.position),
                        to = PlanPoint(destination.name, destination.position),
                        route = TransitRoute("route:1", "1", "1", emptyList(), "volan", transportMode = "BUS"),
                        headsign = "Fast Direct",
                        realtime = false,
                        stayOnBoard = false,
                        stops = emptyList(),
                        source = DataSource.EMMA
                    )
                    listOf(TripItinerary("fast-direct", fastLeg.start, fastLeg.end, listOf(fastLeg)))
                } else {
                    delay(500)
                    val slowLeg = PlanLeg(
                        mode = "BUS",
                        start = ready.plusSeconds(600),
                        end = ready.plusSeconds(1200),
                        distance = 3000.0,
                        from = PlanPoint(origin.name, origin.position),
                        to = PlanPoint(destination.name, destination.position),
                        route = TransitRoute("route:2", "2", "2", emptyList(), "viabusz", transportMode = "BUS"),
                        headsign = "Slow Late Candidate",
                        realtime = false,
                        stayOnBoard = false,
                        stops = emptyList(),
                        source = DataSource.EMMA
                    )
                    listOf(TripItinerary("slow-late", slowLeg.start, slowLeg.end, listOf(slowLeg)))
                }
            }
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
                delay(400)
                return WalkingResult(emptyMap())
            }
            override fun clearRouteCache() {}
        }

        val model = PlannerViewModel(app, backend) {
            delay(400)
            TransitNetwork(emptyList(), emptyList(), emptyList(), Instant.now(), "fixture")
        }

        model.edit(PlannerField.ORIGIN)
        model.choose(origin)
        model.edit(PlannerField.DESTINATION)
        model.choose(destination)

        val observedStates = Collections.synchronizedList(mutableListOf<PlannerState>())
        val collectJob = launch {
            model.state.collect { observedStates += it }
        }

        // Trigger planning
        model.plan()

        // Await plan completion
        withTimeout(10_000) {
            while (model.state.value.planning) {
                delay(20)
            }
        }

        // Verify: while planning was in progress, results NEVER became provisional or partial
        for (state in observedStates) {
            if (state.planning) {
                assertNull("Results must never be published while planning = true (no provisional results)", state.result)
            }
        }

        // Search must have completed with a stable recommendation and backup and planning = false
        val completedState = model.state.value
        assertFalse("Planning must be false upon completion", completedState.planning)
        assertNotNull("Result must be present", completedState.result)
        assertEquals("Must publish the completed recommendation and backup", 2, completedState.result?.itineraries?.size)
        assertEquals("fast-direct", completedState.result?.itineraries?.first()?.id)

        val publishedItineraries = completedState.result!!.itineraries

        // Wait for slow candidate / local network / time advance to finish
        delay(1000)

        // Result must remain identical: no late background mutation or replacement
        val finalState = model.state.value
        assertFalse("Planning must remain false", finalState.planning)
        assertEquals("Result must remain identical after time advance and late candidate arrival",
            publishedItineraries, finalState.result?.itineraries)
        assertEquals("Itinerary count must remain 2", 2, finalState.result?.itineraries?.size)

        collectJob.cancel()
    }
}
