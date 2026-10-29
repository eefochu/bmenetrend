package hu.app.bmenetrend

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import hu.app.bmenetrend.ui.PlannerScreen
import hu.app.bmenetrend.ui.ViaBuszTheme
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "hu-rHU-w360dp-h800dp")
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerLocationRegressionTest {
    @get:Rule val compose = createComposeRule()

    private val dummyBackend = object : PlannerBackend {
        override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> = emptyList()
        override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant) = WalkingResult(emptyMap())
    }

    private lateinit var app: Application

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        NetworkAccessPolicy.setOffline(app, false)
    }

    @After
    fun cleanup() {
        NetworkAccessPolicy.setOffline(app, false)
        Dispatchers.resetMain()
    }

    private fun createModel(): PlannerViewModel {
        return PlannerViewModel(app, dummyBackend) {
            TransitNetwork(emptyList(), emptyList(), emptyList(), Instant.now(), "fixture")
        }
    }

    @Test
    fun defaultStateRetainsUseLocationTrueAndNullOrigin() {
        val defaultState = PlannerState()
        assertTrue("Default PlannerState.useLocation must be true", defaultState.useLocation)
        assertNull("Default PlannerState.origin must be null until location arrives", defaultState.origin)

        val model = createModel()
        assertTrue("PlannerViewModel initial useLocation must be true", model.state.value.useLocation)
        assertNull("PlannerViewModel initial origin must be null", model.state.value.origin)
        assertNull("PlannerViewModel initial destination must be null", model.state.value.destination)
    }

    @Test
    fun initialPlannerShowsOnlyOneDestinationFieldWithoutOriginOrControls() {
        val model = createModel()
        compose.setContent {
            ViaBuszTheme {
                PlannerScreen(model) {}
            }
        }

        val expectedPlaceholder = app.getString(R.string.planner_subtitle)
        val expectedToLabel = app.getString(R.string.planner_to)

        assertEquals("Hova szeretnél menni?", expectedPlaceholder)
        assertEquals("Hová?", expectedToLabel)

        // Only ONE destination field is displayed with placeholder and label
        compose.onAllNodesWithText(expectedPlaceholder).assertCountEquals(1)
        compose.onAllNodesWithText(expectedToLabel).assertCountEquals(1)

        // No origin field ("Honnan?" / "Jelenlegi helyzetem")
        compose.onAllNodesWithText(app.getString(R.string.planner_from)).assertCountEquals(0)
        compose.onAllNodesWithText(app.getString(R.string.planner_current)).assertCountEquals(0)

        // No full form header ("Utazástervezés")
        compose.onAllNodesWithText(app.getString(R.string.planner_title)).assertCountEquals(0)

        // No departure control, swap button, or find button
        compose.onAllNodesWithText(app.getString(R.string.planner_find)).assertCountEquals(0)
        compose.onAllNodesWithText(app.getString(R.string.planner_departure)).assertCountEquals(0)
        compose.onAllNodesWithText(app.getString(R.string.planner_leave_now)).assertCountEquals(0)
        compose.onAllNodesWithContentDescription(app.getString(R.string.planner_swap)).assertCountEquals(0)

        // Tapping the destination field triggers destination search
        compose.onNodeWithText(expectedPlaceholder).performClick()
        assertEquals(PlannerField.DESTINATION, model.state.value.editing)
    }

    @Test
    fun choosingDestinationOpensFullPlannerFormWithDefaultCurrentLocationOrigin() {
        val model = createModel()
        compose.setContent {
            ViaBuszTheme {
                PlannerScreen(model) {}
            }
        }

        // Tap initial field to open search
        val placeholder = app.getString(R.string.planner_subtitle)
        compose.onNodeWithText(placeholder).performClick()
        assertEquals(PlannerField.DESTINATION, model.state.value.editing)

        // Choose destination
        val selectedDest = PlannerEndpoint("dest:deak", "Deák Ferenc tér M", "Budapest", GeoPoint(47.4980, 19.0540))
        model.choose(selectedDest)

        assertNull("Choosing destination must close search", model.state.value.editing)
        assertEquals(selectedDest, model.state.value.destination)

        // Full planner form is now shown:
        // Header title is present without duplicate subtitle
        compose.onNodeWithText(app.getString(R.string.planner_title)).assertIsDisplayed()
        compose.onAllNodesWithText(placeholder).assertCountEquals(0)

        // Origin field appears with "Honnan?" and defaults to "Jelenlegi helyzetem"
        compose.onNodeWithText(app.getString(R.string.planner_from)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.planner_current)).assertIsDisplayed()

        // Destination field shows the chosen destination
        compose.onNodeWithText(app.getString(R.string.planner_to)).assertIsDisplayed()
        compose.onNodeWithText("Deák Ferenc tér M").assertIsDisplayed()

        // Departure control, swap button, and Plan button are now present
        compose.onNodeWithText(app.getString(R.string.planner_departure)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.planner_leave_now)).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.planner_swap)).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(app.getString(R.string.planner_find)))
        compose.onNodeWithText(app.getString(R.string.planner_find)).assertIsEnabled()
    }

    @Test
    fun selectedDestinationCanBeEditedAndManualOriginAndSwapWorkInFullForm() {
        val model = createModel()
        val dest1 = PlannerEndpoint("dest:deak", "Deák Ferenc tér M", "Budapest", GeoPoint(47.4980, 19.0540))
        model.edit(PlannerField.DESTINATION)
        model.choose(dest1)

        compose.setContent {
            ViaBuszTheme {
                PlannerScreen(model) {}
            }
        }

        // Click destination to re-edit
        compose.onNodeWithText("Deák Ferenc tér M").performClick()
        assertEquals(PlannerField.DESTINATION, model.state.value.editing)

        // Select new destination
        val dest2 = PlannerEndpoint("dest:szell", "Széll Kálmán tér M", "Budapest", GeoPoint(47.5070, 19.0245))
        model.choose(dest2)
        compose.onNodeWithText("Széll Kálmán tér M").assertIsDisplayed()
        compose.onAllNodesWithText("Deák Ferenc tér M").assertCountEquals(0)

        // Select manual origin
        val manualOrigin = PlannerEndpoint("stop:kelenfold", "Kelenföld vasútállomás M", "Budapest", GeoPoint(47.4635, 19.0232))
        compose.onNodeWithText(app.getString(R.string.planner_current)).performClick()
        assertEquals(PlannerField.ORIGIN, model.state.value.editing)
        model.choose(manualOrigin)

        compose.onNodeWithText("Kelenföld vasútállomás M").assertIsDisplayed()
        compose.onAllNodesWithText(app.getString(R.string.planner_current)).assertCountEquals(0)
        assertFalse(model.state.value.useLocation)

        // Swap origin and destination
        compose.onNodeWithContentDescription(app.getString(R.string.planner_swap)).performClick()
        assertEquals(dest2, model.state.value.origin)
        assertEquals(manualOrigin, model.state.value.destination)
        compose.onNodeWithText("Széll Kálmán tér M").assertIsDisplayed()
        compose.onNodeWithText("Kelenföld vasútállomás M").assertIsDisplayed()

        // Subsequent GPS update does not overwrite swapped origin
        model.updateLocation(GeoPoint(47.5100, 18.8600))
        assertEquals(dest2, model.state.value.origin)
    }

    @Test
    fun gpsLateArrivalAndSubsequentPositionsUpdateOriginWhenUseLocationIsTrue() {
        val model = createModel()
        assertTrue(model.state.value.useLocation)
        assertNull(model.state.value.origin)

        // Late GPS arrival
        val firstGps = GeoPoint(47.4721, 18.8245)
        model.updateLocation(firstGps)

        val origin1 = model.state.value.origin
        assertNotNull("Origin should be set on late GPS arrival", origin1)
        assertEquals("current", origin1?.id)
        assertEquals(firstGps, origin1?.position)
        assertTrue(model.state.value.useLocation)

        // Subsequent position update refreshes origin
        val secondGps = GeoPoint(47.4812, 18.8310)
        model.updateLocation(secondGps)

        val origin2 = model.state.value.origin
        assertNotNull(origin2)
        assertEquals(secondGps, origin2?.position)
        assertTrue(model.state.value.useLocation)
    }

    @Test
    fun manualOriginIsNotReplacedByGpsUpdatesOrAfterSwap() {
        val model = createModel()
        val manualOrigin = PlannerEndpoint("stop:kelenfold", "Kelenföld vasútállomás M", "Budapest", GeoPoint(47.4635, 19.0232))
        model.edit(PlannerField.ORIGIN)
        model.choose(manualOrigin)

        assertFalse("Choosing manual origin must clear useLocation", model.state.value.useLocation)
        assertEquals(manualOrigin, model.state.value.origin)

        // GPS updates should NOT overwrite manual origin
        model.updateLocation(GeoPoint(47.4900, 18.8500))
        assertEquals("Manual origin must not be replaced by GPS update", manualOrigin, model.state.value.origin)
        assertFalse(model.state.value.useLocation)

        // Swapping origin and destination
        val manualDest = PlannerEndpoint("stop:szell", "Széll Kálmán tér M", "Budapest", GeoPoint(47.5070, 19.0245))
        model.edit(PlannerField.DESTINATION)
        model.choose(manualDest)
        model.swap()

        assertEquals(manualDest, model.state.value.origin)
        assertEquals(manualOrigin, model.state.value.destination)
        assertFalse("Swapping must keep useLocation false", model.state.value.useLocation)

        // Subsequent GPS update still does NOT replace swapped origin
        model.updateLocation(GeoPoint(47.5100, 18.8600))
        assertEquals("Swapped origin must not be replaced by GPS update", manualDest, model.state.value.origin)
    }

    @Test
    fun planButtonWithUseLocationAndMissingGpsRequestsPermissionAndSetsLocationErrorWhichGpsClears() {
        val model = createModel()
        var permissionRequested = false

        val destination = PlannerEndpoint("dest:moricz", "Móricz Zsigmond körtér M", "Budapest", GeoPoint(47.4740, 19.0460))
        model.edit(PlannerField.DESTINATION)
        model.choose(destination)

        compose.setContent {
            ViaBuszTheme {
                PlannerScreen(model, onRequestLocation = { permissionRequested = true })
            }
        }

        // Plan button is enabled because destination is present and useLocation is true
        val planButton = compose.onNodeWithText(app.getString(R.string.planner_find))
        planButton.assertIsEnabled()

        // Clicking plan button triggers onRequestLocation and model.plan()
        planButton.performClick()
        assertTrue("Location permission request callback must be called when origin is missing", permissionRequested)
        assertEquals("Missing origin with useLocation must set LOCATION error", PlannerError.LOCATION, model.state.value.error)

        // Error banner is displayed
        val expectedErrorText = app.getString(R.string.planner_location_error)
        compose.onNodeWithText(expectedErrorText).assertIsDisplayed()

        // When GPS arrives, updateLocation resolves origin and clears the error
        val arrivingGps = GeoPoint(47.4700, 19.0400)
        model.updateLocation(arrivingGps)

        assertNull("GPS arrival must clear LOCATION error", model.state.value.error)
        assertEquals(arrivingGps, model.state.value.origin?.position)

        // Error banner should be gone
        compose.onAllNodesWithText(expectedErrorText).assertCountEquals(0)
    }

    @Test
    fun useCurrentLocationResetsManualSelectionAndReEnablesLocationTracking() {
        val model = createModel()
        val manualStop = PlannerEndpoint("stop:moricz", "Móricz Zsigmond körtér M", "", GeoPoint(47.4740, 19.0460))
        model.edit(PlannerField.ORIGIN)
        model.choose(manualStop)
        assertFalse(model.state.value.useLocation)

        // User chooses to use current location when GPS has not arrived yet
        model.useCurrentLocation()
        assertTrue("useCurrentLocation must reset useLocation to true", model.state.value.useLocation)
        assertNull("origin should be null if location was null", model.state.value.origin)
        assertEquals("Should set LOCATION error when location is not yet available", PlannerError.LOCATION, model.state.value.error)

        // Now GPS arrives
        val pos = GeoPoint(47.4750, 19.0450)
        model.updateLocation(pos)
        assertEquals(pos, model.state.value.origin?.position)
        assertNull("updateLocation must clear error", model.state.value.error)
    }
}
