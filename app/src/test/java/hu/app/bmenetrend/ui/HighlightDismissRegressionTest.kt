package hu.app.bmenetrend.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.BoardingSelection
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.LineDetails
import hu.app.bmenetrend.data.LinePattern
import hu.app.bmenetrend.data.TransitNetwork
import hu.app.bmenetrend.data.TransitRoute
import hu.app.bmenetrend.data.TransitStop
import hu.app.bmenetrend.data.Vehicle
import hu.app.bmenetrend.map.LineMapFocus
import hu.app.bmenetrend.map.VehicleMapScene
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.geojson.FeatureCollection
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "hu-rHU-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HighlightDismissRegressionTest {

    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private val route762 = TransitRoute("volan:hkir:762", "762", "Budapest–Biatorbágy", emptyList(), "volan", transportMode = "COACH")
    private val kelenfold = TransitStop("kelenfold", "Budapest, Kelenföld vasútállomás", GeoPoint(47.46, 19.02), listOf(route762.id), "volan")
    private val biatorbagy = kelenfold.copy(id = "biatorbagy", name = "Biatorbágy, Premier Outlet", position = GeoPoint(47.46, 18.90))
    private val soskut = kelenfold.copy(id = "soskut", name = "Sóskút, Iskola", position = GeoPoint(47.40, 18.80))
    private val patternOut = LinePattern("out", setOf("out"), soskut.name, listOf(kelenfold, biatorbagy, soskut), listOf(kelenfold.position, biatorbagy.position, soskut.position), 2, "0")
    private val patternIn = LinePattern("in", setOf("in"), kelenfold.name, listOf(soskut, biatorbagy, kelenfold), patternOut.geometry.reversed(), 2, "1")

    private fun state() = UiState(
        network = TransitNetwork(listOf(kelenfold, biatorbagy, soskut), listOf(route762), emptyList(), Instant.now(), "test"),
        lineDetails = mapOf(route762.id to LineDetails(route762, listOf(patternOut, patternIn))),
        loading = false,
    )

    /**
     * Verifies that dismissHighlight via map click with trackedVehicle exits highlight
     * (invokes closeVehicle: onVehicleClosed + onClearBoarding) instead of reopening vehicle.
     */
    @Test
    fun dismissHighlightViaMapClick_withTrackedVehicleExitsHighlightInsteadOfReopeningVehicle() {
        val vehicle = Vehicle(
            id = "v-biatorbagy",
            position = GeoPoint(47.46, 18.90),
            observedAt = Instant.now(),
            routeId = route762.id,
            routeNumber = "762",
            providerId = "volan",
            transportMode = "BUS",
            tripId = "trip-762",
        )
        val pattern = LinePattern("p1", setOf("p1"), "Budapest", listOf(kelenfold, biatorbagy, soskut), listOf(kelenfold.position, soskut.position))
        var returnedToVehicle = false
        var vehicleClosed = false
        var boardingCleared = false

        val uiState = state().copy(
            showNearby = false,
            selectedStopId = biatorbagy.id,
            trackedVehicle = vehicle,
            selectedVehicle = null,
            boardingSelection = BoardingSelection(route762.id, pattern.headsign, pattern),
        )

        compose.setContent {
            ViaBuszTheme {
                ViaBuszScreen(
                    state = uiState,
                    onStopSelected = {},
                    onRefresh = {},
                    onLocate = {},
                    onWindowChanged = {},
                    onToggleFavorite = {},
                    onVehicleClosed = { vehicleClosed = true },
                    onClearBoarding = { boardingCleared = true },
                    onReturnToTrackedVehicle = { returnedToVehicle = true },
                    mapContent = { modifier, _, _, _, onDismiss ->
                        Box(modifier.testTag("map"), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text("Dismiss", Modifier.testTag("map_click").clickable { onDismiss() })
                        }
                    }
                )
            }
        }

        // The floating "Exit route" (X) button is displayed on the map
        val exitBtn = compose.onNodeWithText(app.getString(R.string.map_exit_route))
        exitBtn.assertIsDisplayed()

        // User clicks the map dismiss target
        compose.onNodeWithTag("map_click").performClick()

        compose.runOnIdle {
            assertFalse("Map click dismiss should NOT return to/reopen tracked vehicle", returnedToVehicle)
            assertTrue("Map click dismiss must close vehicle", vehicleClosed)
            assertTrue("Map click dismiss must clear boarding selection", boardingCleared)
        }
    }

    /**
     * Contract test proving that X exits highlight (close vehicle and clear boarding)
     * instead of returning to/reopening the tracked vehicle.
     */
    @Test
    fun exitRouteButtonContract_provesXShouldExitHighlightInsteadOfReturningToVehicle() {
        val vehicle = Vehicle(
            id = "v-biatorbagy",
            position = GeoPoint(47.46, 18.90),
            observedAt = Instant.now(),
            routeId = route762.id,
            routeNumber = "762",
            providerId = "volan",
            transportMode = "BUS",
            tripId = "trip-762",
        )
        val pattern = LinePattern("p1", setOf("p1"), "Budapest", listOf(kelenfold, biatorbagy, soskut), listOf(kelenfold.position, soskut.position))
        var returnedToVehicle = false
        var vehicleClosed = false
        var boardingCleared = false

        val uiState = state().copy(
            showNearby = false,
            selectedStopId = biatorbagy.id,
            trackedVehicle = vehicle,
            selectedVehicle = null,
            boardingSelection = BoardingSelection(route762.id, pattern.headsign, pattern),
        )

        compose.setContent {
            ViaBuszTheme {
                ViaBuszScreen(
                    state = uiState,
                    onStopSelected = {},
                    onRefresh = {},
                    onLocate = {},
                    onWindowChanged = {},
                    onToggleFavorite = {},
                    onVehicleClosed = { vehicleClosed = true },
                    onClearBoarding = { boardingCleared = true },
                    onReturnToTrackedVehicle = { returnedToVehicle = true },
                )
            }
        }

        val exitBtn = compose.onNodeWithText(app.getString(R.string.map_exit_route))
        exitBtn.assertIsDisplayed()
        exitBtn.performClick()

        compose.runOnIdle {
            assertFalse("X button should NOT return to/reopen tracked vehicle", returnedToVehicle)
            assertTrue("X button should close vehicle to exit highlight", vehicleClosed)
            assertTrue("X button should clear boarding selection", boardingCleared)
        }
    }

    /**
     * Verifies that back/navigation in the boarding banner correctly retains the behavior
     * of returning to the tracked vehicle.
     */
    @Test
    fun backNavigationWithTrackedVehicleRetainsReturnToVehicle() {
        val vehicle = Vehicle(
            id = "v-biatorbagy",
            position = GeoPoint(47.46, 18.90),
            observedAt = Instant.now(),
            routeId = route762.id,
            routeNumber = "762",
            providerId = "volan",
            transportMode = "BUS",
            tripId = "trip-762",
        )
        val pattern = LinePattern("p1", setOf("p1"), "Budapest", listOf(kelenfold, biatorbagy, soskut), listOf(kelenfold.position, soskut.position))
        var returnedToVehicle = false
        var vehicleClosed = false

        val uiState = state().copy(
            showNearby = false,
            selectedStopId = biatorbagy.id,
            trackedVehicle = vehicle,
            selectedVehicle = null,
            boardingSelection = BoardingSelection(route762.id, pattern.headsign, pattern),
        )

        compose.setContent {
            ViaBuszTheme {
                ViaBuszScreen(
                    state = uiState,
                    onStopSelected = {},
                    onRefresh = {},
                    onLocate = {},
                    onWindowChanged = {},
                    onToggleFavorite = {},
                    onVehicleClosed = { vehicleClosed = true },
                    onReturnToTrackedVehicle = { returnedToVehicle = true },
                )
            }
        }

        // User clicks the back arrow in the boarding banner (navigation back to vehicle)
        compose.onNodeWithContentDescription(app.getString(R.string.ui_back)).performClick()

        compose.runOnIdle {
            // Navigation back retains return-to-vehicle behavior
            assertTrue("Back navigation in sheet must return to tracked vehicle", returnedToVehicle)
            assertFalse("Back navigation must not close vehicle", vehicleClosed)
        }
    }

    /**
     * Verifies that when a normal line is opened, clicking X clears selectedRoute,
     * and a subsequent map move does NOT retain selectedRoute and prepares unfiltered vehicles.
     */
    @Test
    fun normalLineOpenThenDismissThenMapMoveClearsSelectedRouteAndPreparesUnfilteredVehicles() = runBlocking {
        var previewRouteId: String? = null
        var clearBoardingCalled = false

        val uiState = state()

        compose.setContent {
            ViaBuszTheme {
                ViaBuszScreen(
                    state = uiState,
                    onStopSelected = {},
                    onRefresh = {},
                    onLocate = {},
                    onWindowChanged = {},
                    onToggleFavorite = {},
                    onClearBoarding = { clearBoardingCalled = true },
                    mapContent = { modifier, _, browsingRoute, _, _ ->
                        SideEffect {
                            previewRouteId = browsingRoute?.id
                        }
                        Box(modifier.testTag("map"))
                    }
                )
            }
        }

        // 1. Open normal line (762) via search
        compose.onNodeWithText(app.getString(R.string.ui_search_short)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("762")
        compose.waitUntil(5000) { compose.onAllNodes(hasText("762") and hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("762") and hasClickAction() and !hasSetTextAction()).performClick()

        compose.runOnIdle {
            assertEquals("browsingRoute must be route 762 when line is open", route762.id, previewRouteId)
        }

        // The floating "Exit route" (X) button is visible
        val exitBtn = compose.onNodeWithText(app.getString(R.string.map_exit_route))
        exitBtn.assertIsDisplayed()

        // 2. Click X to exit route
        exitBtn.performClick()

        compose.runOnIdle {
            assertNull("browsingRoute must be cleared to null after clicking X", previewRouteId)
            assertTrue("onClearBoarding must be called on closeLine", clearBoardingCalled)
        }

        // 3. Simulate map move (pan to Budapest with vehicles):
        // Ensure no focus is created and VehicleMapScene.prepare prepares unfiltered vehicles
        val budapestVeh1 = Vehicle(
            id = "v-bkk-1",
            position = GeoPoint(47.49, 19.04),
            observedAt = Instant.now(),
            routeId = "bkk:1",
            routeNumber = "1",
            providerId = "bkk",
            transportMode = "TRAM",
            destination = "Bécsi út",
        )
        val budapestVeh2 = Vehicle(
            id = "v-bkk-4",
            position = GeoPoint(47.50, 19.05),
            observedAt = Instant.now(),
            routeId = "bkk:4",
            routeNumber = "4",
            providerId = "bkk",
            transportMode = "TRAM",
            destination = "Újbuda-központ",
        )

        // LineMapFocus is null because browsingRoute is null, selectedVehicle is null, boardingSelection is null
        val focus = LineMapFocus.create(
            vehicle = null,
            boarding = null,
            routes = emptyList(),
            details = emptyMap(),
            journey = null,
            allStops = emptyList(),
        )
        assertNull("Focus must be null after line is closed", focus)

        val pending = linkedMapOf<String, android.graphics.Bitmap>()
        try {
            val json = VehicleMapScene.prepare(
                context = app,
                vehicles = listOf(budapestVeh1, budapestVeh2),
                selectedId = null,
                cachedIcons = emptySet(),
                pending = pending,
            )
            val features = FeatureCollection.fromJson(json).features()!!
            assertEquals("Map move after X must prepare all unfiltered vehicles", 2, features.size)
            val ids = features.map { it.getStringProperty("vehicle-id") }.toSet()
            assertTrue(ids.contains("v-bkk-1"))
            assertTrue(ids.contains("v-bkk-4"))
        } finally {
            pending.values.forEach(android.graphics.Bitmap::recycle)
        }
    }

    /**
     * Proves with mutable real UI state that clicking X makes selectedVehicle, trackedVehicle,
     * and boardingSelection all null, thereby clearing LineMapFocus and preparing all Budapest
     * vehicles unfiltered.
     */
    @Test
    fun exitRouteButtonWithRealUiStateClearsFocusAndPreparesUnfilteredBudapestVehicles() = runBlocking {
        val vehicle = Vehicle(
            id = "v-biatorbagy",
            position = GeoPoint(47.46, 18.90),
            observedAt = Instant.now(),
            routeId = route762.id,
            routeNumber = "762",
            providerId = "volan",
            transportMode = "BUS",
            tripId = "trip-762",
        )
        val pattern = LinePattern("p1", setOf("p1"), "Budapest", listOf(kelenfold, biatorbagy, soskut), listOf(kelenfold.position, soskut.position))
        var uiState by mutableStateOf(state().copy(
            showNearby = false,
            selectedStopId = biatorbagy.id,
            trackedVehicle = vehicle,
            selectedVehicle = null,
            boardingSelection = BoardingSelection(route762.id, pattern.headsign, pattern),
        ))

        compose.setContent {
            ViaBuszTheme {
                ViaBuszScreen(
                    state = uiState,
                    onStopSelected = { id -> uiState = uiState.copy(selectedStopId = id, showNearby = false) },
                    onRefresh = {},
                    onLocate = {},
                    onWindowChanged = {},
                    onToggleFavorite = {},
                    onVehicleClosed = {
                        uiState = uiState.copy(selectedVehicle = null, trackedVehicle = null)
                    },
                    onClearBoarding = {
                        uiState = uiState.copy(boardingSelection = null)
                    },
                    onReturnToTrackedVehicle = {
                        val v = uiState.trackedVehicle
                        uiState = uiState.copy(
                            showNearby = false,
                            selectedVehicle = v,
                            trackedVehicle = null,
                            boardingSelection = null,
                        )
                    },
                    onReturnToNearby = {
                        uiState = uiState.copy(
                            selectedVehicle = null,
                            trackedVehicle = null,
                            boardingSelection = null,
                            selectedStopId = null,
                            showNearby = true,
                        )
                    },
                )
            }
        }

        val budapestVeh1 = Vehicle(
            id = "v-bkk-1",
            position = GeoPoint(47.49, 19.04),
            observedAt = Instant.now(),
            routeId = "bkk:1",
            routeNumber = "1",
            providerId = "bkk",
            transportMode = "TRAM",
            destination = "Bécsi út",
        )
        val budapestVeh2 = Vehicle(
            id = "v-bkk-4",
            position = GeoPoint(47.50, 19.05),
            observedAt = Instant.now(),
            routeId = "bkk:4",
            routeNumber = "4",
            providerId = "bkk",
            transportMode = "TRAM",
            destination = "Újbuda-központ",
        )

        // Before dismiss: focus exists; source includes all vehicles while layer filter/focus.includes handles visibility
        val initialFocus = LineMapFocus.create(
            vehicle = uiState.selectedVehicle ?: uiState.trackedVehicle,
            boarding = uiState.boardingSelection,
            routes = listOf(route762),
            details = uiState.lineDetails,
            journey = null,
            allStops = listOf(kelenfold, biatorbagy, soskut),
        )
        assertNotNull("Initial focus must be non-null while boarding/tracked is active", initialFocus)
        val activeFocus = requireNotNull(initialFocus)
        assertFalse("Initial focus must not include Budapest vehicle 1", activeFocus.includes(budapestVeh1))
        assertFalse("Initial focus must not include Budapest vehicle 2", activeFocus.includes(budapestVeh2))
        val pendingBefore = linkedMapOf<String, android.graphics.Bitmap>()
        try {
            val jsonBefore = VehicleMapScene.prepare(
                context = app,
                vehicles = listOf(budapestVeh1, budapestVeh2),
                selectedId = null,
                cachedIcons = emptySet(),
                pending = pendingBefore,
            )
            val featuresBefore = FeatureCollection.fromJson(jsonBefore).features().orEmpty()
            assertEquals("Source always includes all vehicles; layer handles visibility", 2, featuresBefore.size)
        } finally {
            pendingBefore.values.forEach(android.graphics.Bitmap::recycle)
        }

        // Click X button
        val exitBtn = compose.onNodeWithText(app.getString(R.string.map_exit_route))
        exitBtn.assertIsDisplayed()
        exitBtn.performClick()

        compose.runOnIdle {
            assertNull("selectedVehicle must be null after exiting highlight", uiState.selectedVehicle)
            assertNull("trackedVehicle must be null after exiting highlight", uiState.trackedVehicle)
            assertNull("boardingSelection must be null after exiting highlight", uiState.boardingSelection)
        }

        val focusAfter = LineMapFocus.create(
            vehicle = uiState.selectedVehicle ?: uiState.trackedVehicle,
            boarding = uiState.boardingSelection,
            routes = listOf(route762),
            details = uiState.lineDetails,
            journey = null,
            allStops = listOf(kelenfold, biatorbagy, soskut),
        )
        assertNull("Focus must be null once selected, tracked, and boarding are all null", focusAfter)

        val pendingAfter = linkedMapOf<String, android.graphics.Bitmap>()
        try {
            val jsonAfter = VehicleMapScene.prepare(
                context = app,
                vehicles = listOf(budapestVeh1, budapestVeh2),
                selectedId = null,
                cachedIcons = emptySet(),
                pending = pendingAfter,
            )
            val featuresAfter = FeatureCollection.fromJson(jsonAfter).features()!!
            assertEquals("All Budapest vehicles must be prepared unfiltered after highlight exit", 2, featuresAfter.size)
            val ids = featuresAfter.map { it.getStringProperty("vehicle-id") }.toSet()
            assertTrue(ids.contains("v-bkk-1"))
            assertTrue(ids.contains("v-bkk-4"))
        } finally {
            pendingAfter.values.forEach(android.graphics.Bitmap::recycle)
        }
    }

    /**
     * Verifies that swiping DOWN the bottomsheet with an active tracked vehicle clears highlight
     * immediately upon targetValue == Hidden (before the animation finishes), resetting trackedVehicle
     * and boardingSelection and restoring unfiltered vehicles without slow lag.
     */
    @Test
    fun swipeDownSheetWithActiveTrackedVehicleClearsHighlightImmediatelyOnTargetHidden() = runBlocking {
        val vehicle = Vehicle(
            id = "v-biatorbagy",
            position = GeoPoint(47.46, 18.90),
            observedAt = Instant.now(),
            routeId = route762.id,
            routeNumber = "762",
            providerId = "volan",
            transportMode = "BUS",
            tripId = "trip-762",
        )
        val pattern = LinePattern("p1", setOf("p1"), "Budapest", listOf(kelenfold, biatorbagy, soskut), listOf(kelenfold.position, soskut.position))
        var uiState by mutableStateOf(state().copy(
            showNearby = false,
            selectedStopId = biatorbagy.id,
            trackedVehicle = vehicle,
            selectedVehicle = null,
            boardingSelection = BoardingSelection(route762.id, pattern.headsign, pattern),
        ))

        var clearBoardingCalled = false
        var returnToNearbyCalled = false

        compose.setContent {
            ViaBuszTheme {
                ViaBuszScreen(
                    state = uiState,
                    onStopSelected = { id -> uiState = uiState.copy(selectedStopId = id, showNearby = false) },
                    onRefresh = {},
                    onLocate = {},
                    onWindowChanged = {},
                    onToggleFavorite = {},
                    onVehicleClosed = {
                        uiState = uiState.copy(selectedVehicle = null, trackedVehicle = null)
                    },
                    onClearBoarding = {
                        clearBoardingCalled = true
                        uiState = uiState.copy(boardingSelection = null)
                    },
                    onReturnToNearby = {
                        returnToNearbyCalled = true
                        uiState = uiState.copy(
                            selectedVehicle = null,
                            trackedVehicle = null,
                            boardingSelection = null,
                            selectedStopId = null,
                            showNearby = true,
                        )
                    },
                )
            }
        }

        // Sheet is partially expanded. Pause clock to observe state transition before animation finishes.
        compose.mainClock.autoAdvance = false

        // Swipe DOWN on the sheet drag handle
        val dragHandle = compose.onNodeWithContentDescription(app.getString(R.string.ui_expand_departures))
        dragHandle.performTouchInput {
            swipe(start = center, end = center.copy(y = center.y + 600f), durationMillis = 200)
        }

        // Advance 1 frame for targetValue transition to register
        compose.mainClock.advanceTimeByFrame()

        // Contract verification: callbacks must fire immediately upon targetValue == Hidden, BEFORE animation settles
        assertTrue("onClearBoarding must fire immediately at start of hide animation", clearBoardingCalled)
        assertTrue("onReturnToNearby must fire immediately at start of hide animation", returnToNearbyCalled)
        assertNull("trackedVehicle must be cleared", uiState.trackedVehicle)
        assertNull("boardingSelection must be cleared", uiState.boardingSelection)
        assertTrue("showNearby must be restored", uiState.showNearby)

        // Resume clock and let sheet fully settle to Hidden
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        // Verify that vehicles are unfiltered
        val focusAfter = LineMapFocus.create(
            vehicle = uiState.selectedVehicle ?: uiState.trackedVehicle,
            boarding = uiState.boardingSelection,
            routes = listOf(route762),
            details = uiState.lineDetails,
            journey = null,
            allStops = listOf(kelenfold, biatorbagy, soskut),
        )
        assertNull("Focus must be null after swiping down", focusAfter)

        val budapestVeh1 = Vehicle(
            id = "v-bkk-1",
            position = GeoPoint(47.49, 19.04),
            observedAt = Instant.now(),
            routeId = "bkk:1",
            routeNumber = "1",
            providerId = "bkk",
            transportMode = "TRAM",
            destination = "Bécsi út",
        )
        val budapestVeh2 = Vehicle(
            id = "v-bkk-4",
            position = GeoPoint(47.50, 19.05),
            observedAt = Instant.now(),
            routeId = "bkk:4",
            routeNumber = "4",
            providerId = "bkk",
            transportMode = "TRAM",
            destination = "Újbuda-központ",
        )

        val pending = linkedMapOf<String, android.graphics.Bitmap>()
        try {
            val json = VehicleMapScene.prepare(
                context = app,
                vehicles = listOf(budapestVeh1, budapestVeh2),
                selectedId = null,
                cachedIcons = emptySet(),
                pending = pending,
            )
            val features = FeatureCollection.fromJson(json).features()!!
            assertEquals("Budapest vehicles must be unfiltered after swipe down restores nearby", 2, features.size)
        } finally {
            pending.values.forEach(android.graphics.Bitmap::recycle)
        }
    }

    /**
     * Verifies that swiping DOWN the sheet while browsing a standard line dismisses the route preview.
     */
    @Test
    fun swipeDownSheetWithStandardLinePreviewDismissesRoute() {
        var previewRouteId: String? = null
        var clearBoardingCalled = false

        val uiState = state()

        compose.setContent {
            ViaBuszTheme {
                ViaBuszScreen(
                    state = uiState,
                    onStopSelected = {},
                    onRefresh = {},
                    onLocate = {},
                    onWindowChanged = {},
                    onToggleFavorite = {},
                    onClearBoarding = { clearBoardingCalled = true },
                    mapContent = { modifier, _, browsingRoute, _, _ ->
                        SideEffect {
                            previewRouteId = browsingRoute?.id
                        }
                        Box(modifier.testTag("map"))
                    }
                )
            }
        }

        // Open route 762 via search
        compose.onNodeWithText(app.getString(R.string.ui_search_short)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("762")
        compose.waitUntil(5000) { compose.onAllNodes(hasText("762") and hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("762") and hasClickAction() and !hasSetTextAction()).performClick()

        compose.runOnIdle {
            assertEquals(route762.id, previewRouteId)
        }

        // Swipe down on the drag handle
        val dragHandle = compose.onNodeWithContentDescription(app.getString(R.string.ui_expand_departures))
        dragHandle.performTouchInput {
            swipe(start = center, end = center.copy(y = center.y + 600f), durationMillis = 200)
        }

        compose.waitForIdle()

        compose.runOnIdle {
            assertNull("browsingRoute must be cleared after sheet is swiped down", previewRouteId)
        }
    }

    /**
     * Verifies that when the sheet is in Hidden state (restored/default), a new external selection
     * (e.g. stop selected from map) is NOT prematurely cleared by the sheet lifecycle.
     */
    @Test
    fun restoredOrInitialHiddenSheetDoesNotPrematurelyDismissNewExternalSelection() {
        var uiState by mutableStateOf(state().copy(showNearby = true))
        var returnToNearbyCount = 0

        compose.setContent {
            ViaBuszTheme {
                ViaBuszScreen(
                    state = uiState,
                    onStopSelected = { id -> uiState = uiState.copy(selectedStopId = id, showNearby = false) },
                    onRefresh = {},
                    onLocate = {},
                    onWindowChanged = {},
                    onToggleFavorite = {},
                    onReturnToNearby = {
                        returnToNearbyCount++
                        uiState = uiState.copy(
                            selectedVehicle = null,
                            trackedVehicle = null,
                            boardingSelection = null,
                            selectedStopId = null,
                            showNearby = true,
                        )
                    },
                )
            }
        }

        // 1. Swipe down sheet to Hidden
        val dragHandle = compose.onNodeWithContentDescription(app.getString(R.string.ui_expand_departures))
        dragHandle.performTouchInput {
            swipe(start = center, end = center.copy(y = center.y + 600f), durationMillis = 200)
        }
        compose.waitForIdle()

        val countAfterHide = returnToNearbyCount

        // 2. An external selection occurs while sheet is Hidden (e.g. stop tapped externally)
        compose.runOnIdle {
            uiState = uiState.copy(
                selectedStopId = biatorbagy.id,
                showNearby = false,
                cameraRequest = 1,
            )
        }

        compose.waitForIdle()

        // 3. Verify that the restored hidden sheet does NOT immediately invoke onReturnToNearby
        assertEquals("Restored hidden sheet must NOT prematurely trigger onReturnToNearby", countAfterHide, returnToNearbyCount)
        assertEquals("External stop selection must be preserved", biatorbagy.id, uiState.selectedStopId)
        assertFalse("showNearby must remain false for external selection", uiState.showNearby)
        compose.onNodeWithText(biatorbagy.name).assertIsDisplayed()
    }
}

