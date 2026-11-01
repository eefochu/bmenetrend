package hu.app.bmenetrend

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import hu.app.bmenetrend.ui.PlannerScreen
import hu.app.bmenetrend.ui.ViaBuszTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * An explicit origin/destination search while online must publish the national result as
 * soon as the backend answers, without waiting for slow local-timetable verification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "hu-rHU-w360dp-h800dp")
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerOnlinePartialResultsTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var app: Application

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        NetworkAccessPolicy.setOffline(app, false)
    }

    @After fun cleanup() {
        NetworkAccessPolicy.setOffline(app, false)
        Dispatchers.resetMain()
    }

    @Test
    fun explicitOnlinePlanShowsTheNationalResultWhileLocalVerificationIsStillRunning() {
        val ready = Instant.now().plusSeconds(10 * 60)
        val origin = PlannerEndpoint("stop:origin", "Budaörs", "", GeoPoint(47.460, 18.946))
        val destination = PlannerEndpoint("stop:destination", "Technikum", "", GeoPoint(47.47959, 18.8091914))
        val leg = PlanLeg("COACH", ready, ready.plusSeconds(20 * 60), 15_000.0,
            PlanPoint(origin.name, origin.position), PlanPoint(destination.name, destination.position),
            TransitRoute("volan:test", "762", "Volán", emptyList(), "volan", transportMode = "COACH"),
            destination.name, false, false, emptyList())
        val direct = TripItinerary("fast-national", ready, ready.plusSeconds(20 * 60), listOf(leg))

        var endpointCalls = 0
        val backend = object : PlannerBackend {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                if (!busesOnly && from.position == origin.position && to.position == destination.position) {
                    endpointCalls++
                    return listOf(direct)
                }
                return emptyList()
            }
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant) =
                WalkingResult(emptyMap())
        }

        // Local timetable verification is deliberately stalled: the shared network load
        // never finishes until the assertion below has already observed usable results.
        val localVerification = CompletableDeferred<Unit>()
        val model = PlannerViewModel(app, backend) {
            localVerification.await()
            TransitNetwork(emptyList(), emptyList(), emptyList(), ready, "fixture")
        }
        model.edit(PlannerField.ORIGIN)
        model.choose(origin)
        model.edit(PlannerField.DESTINATION)
        model.choose(destination)
        assertFalse("Test must run online", model.state.value.offline)

        compose.setContent { ViaBuszTheme { PlannerScreen(model) {} } }
        compose.onNodeWithText(app.getString(R.string.planner_find)).performClick()

        try {
            compose.waitUntil(10_000) { model.state.value.result?.itineraries?.isNotEmpty() == true }
            assertTrue("Explicit endpoints must reach the online backend", endpointCalls > 0)
            assertTrue("The planner is still waiting for local verification, not finished",
                model.state.value.planning)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("762"))
            compose.onNodeWithText("762").assertIsDisplayed()
            model.selectItinerary("fast-national")
        } finally {
            localVerification.complete(Unit)
        }

        compose.waitUntil(10_000) { !model.state.value.planning }
        assertNull(model.state.value.error)
        assertNotNull(model.state.value.result?.itineraries?.firstOrNull { it.id == "fast-national" })
        assertEquals("fast-national", model.state.value.selectedId)
    }
}
