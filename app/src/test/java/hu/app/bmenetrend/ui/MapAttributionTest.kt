package hu.app.bmenetrend.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.PlannerState
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.TransitNetwork
import hu.app.bmenetrend.data.TransitStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "hu-rHU-w360dp-h640dp")
class MapAttributionTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun tapOpensAndCloseDismissesWithoutNavigatingSelection() {
        val mapInfoText = context.getString(R.string.map_info)
        val titleText = context.getString(R.string.map_credits_title)
        val closeText = context.getString(R.string.ui_close)
        var selectedItem = "route-initial"

        compose.setContent {
            ViaBuszTheme {
                Column {
                    MapAttribution()
                    Button(onClick = { selectedItem = "route-navigated" }) {
                        Text("Selection Button")
                    }
                }
            }
        }

        // Initial state: dialog not displayed
        compose.onNodeWithText(titleText).assertDoesNotExist()
        assertEquals("route-initial", selectedItem)

        // Tap MapAttribution to open dialog
        compose.onNodeWithText(mapInfoText).performClick()

        // Dialog is displayed with title and source/licence entries
        compose.onNodeWithText(titleText).assertIsDisplayed()
        compose.onNodeWithText("OpenStreetMap · ODbL 1.0").assertIsDisplayed()
        compose.onNodeWithText("OpenMapTiles · CC BY 4.0").assertIsDisplayed()
        compose.onNodeWithText("OpenFreeMap").assertIsDisplayed()
        compose.onNodeWithText("Geofabrik").assertIsDisplayed()
        compose.onNodeWithText("MapLibre Native · BSD 2-Clause").assertIsDisplayed()

        // Selection state must not have changed
        assertEquals("route-initial", selectedItem)

        // Click close on dialog
        compose.onNodeWithText(closeText).performClick()

        // Dialog is dismissed and selection remains untouched
        compose.onNodeWithText(titleText).assertDoesNotExist()
        assertEquals("route-initial", selectedItem)
    }

    @Test
    fun settingsEntryReachableViaScrollAndOpensDialog() {
        val creditsTitle = context.getString(R.string.map_credits_title)
        val creditsData = context.getString(R.string.map_credits_data)
        val closeText = context.getString(R.string.ui_close)

        compose.setContent {
            ViaBuszTheme {
                SettingsPage(
                    state = UiState(),
                    onAccent = {},
                    onCustom = {},
                    onNationwide = {},
                    onAbout = {},
                    onBack = {}
                )
            }
        }

        // Scroll to map credits button in SettingsPage
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(creditsTitle))
        compose.onNodeWithText(creditsTitle).assertIsDisplayed()

        // Click the settings button to open dialog
        compose.onNodeWithText(creditsTitle).performClick()

        // MapCreditsDialog is now displayed
        compose.onNodeWithText(creditsData).assertIsDisplayed()
        compose.onNodeWithText("OpenStreetMap · ODbL 1.0").assertIsDisplayed()

        // Dismiss dialog via close button
        compose.onNodeWithText(closeText).performClick()
        compose.onNodeWithText(creditsData).assertDoesNotExist()
    }

    @Test
    fun realViaBuszScreenFakeMapCreditsLocatedBelowSearchbarAndSelectable() {
        val searchHint = context.getString(R.string.ui_search_short)
        val mapInfoText = context.getString(R.string.map_info)
        val creditsTitle = context.getString(R.string.map_credits_title)
        val closeText = context.getString(R.string.ui_close)

        var mapClicks = 0
        var stopSelected: String? = null
        val testStop = TransitStop("stop-kelenfold", "Kelenföld", GeoPoint(47.46, 19.02), emptyList(), "volan")
        val state = UiState(
            selectedStopId = "stop-initial",
            network = TransitNetwork(listOf(testStop), emptyList(), emptyList(), Instant.now(), "test")
        )

        compose.setContent {
            ViaBuszTheme {
                ViaBuszScreen(
                    state = state,
                    onStopSelected = { stopSelected = it },
                    onRefresh = {},
                    onLocate = {},
                    onWindowChanged = {},
                    onToggleFavorite = {},
                    mapContent = { modifier, _, _, _, _ ->
                        Box(modifier.testTag("fake-map").clickable { mapClicks++ })
                    }
                )
            }
        }

        // Verify Search bar and Credits are displayed
        val searchNode = compose.onNodeWithText(searchHint)
        searchNode.assertIsDisplayed()
        val creditsNode = compose.onNodeWithText(mapInfoText)
        creditsNode.assertIsDisplayed()

        val searchBounds = searchNode.fetchSemanticsNode().boundsInRoot
        val creditsBounds = creditsNode.fetchSemanticsNode().boundsInRoot

        // Verify map credits is located below the search bar
        assertTrue(
            "Credits top (${creditsBounds.top}) must be at or below searchbar bottom (${searchBounds.bottom})",
            creditsBounds.top >= searchBounds.bottom
        )

        // Select the credits item
        creditsNode.performClick()

        // Verify dialog opens and interaction does not alter selection or click map
        compose.onNodeWithText(creditsTitle).assertIsDisplayed()
        assertEquals(null, stopSelected)
        assertEquals(0, mapClicks)

        // Dismiss the dialog
        compose.onNodeWithText(closeText).performClick()
        compose.onNodeWithText(creditsTitle).assertDoesNotExist()
        assertEquals(null, stopSelected)
        assertEquals(0, mapClicks)
    }

    @Test
    fun plannerMapOverlayPhonePositionsAttributionBelowFormAndAboveMap() {
        val mapInfoText = context.getString(R.string.map_info)
        compose.setContent {
            ViaBuszTheme {
                PlannerMapOverlay(
                    state = PlannerState(offline = false),
                    onOrigin = {},
                    onDestination = {},
                    onSwap = {},
                    onDeparture = {},
                    onFind = {},
                    onClose = {},
                    mapContent = { modifier -> Box(modifier.testTag("planner-test-map")) }
                )
            }
        }

        val cardBounds = compose.onNodeWithTag("planner-controls-card").fetchSemanticsNode().boundsInRoot
        val attrBounds = compose.onNodeWithText(mapInfoText).fetchSemanticsNode().boundsInRoot
        val mapBounds = compose.onNodeWithTag("planner-test-map").fetchSemanticsNode().boundsInRoot

        assertTrue("Card bottom (${cardBounds.bottom}) <= Attr top (${attrBounds.top})", cardBounds.bottom <= attrBounds.top)
        assertTrue("Attr bottom (${attrBounds.bottom}) <= Map top (${mapBounds.top})", attrBounds.bottom <= mapBounds.top)
    }

    @Test
    @Config(qualifiers = "hu-rHU-w1200dp-h800dp")
    fun plannerMapOverlayTabletPositionsAttributionBelowControlsAndLeftOfMap() {
        val mapInfoText = context.getString(R.string.map_info)
        compose.setContent {
            ViaBuszTheme {
                PlannerMapOverlay(
                    state = PlannerState(offline = false),
                    onOrigin = {},
                    onDestination = {},
                    onSwap = {},
                    onDeparture = {},
                    onFind = {},
                    onClose = {},
                    mapContent = { modifier -> Box(modifier.testTag("planner-test-map")) }
                )
            }
        }

        val cardBounds = compose.onNodeWithTag("planner-controls-card").fetchSemanticsNode().boundsInRoot
        val attrBounds = compose.onNodeWithText(mapInfoText).fetchSemanticsNode().boundsInRoot
        val mapBounds = compose.onNodeWithTag("planner-test-map").fetchSemanticsNode().boundsInRoot

        assertTrue("Tablet card right (${cardBounds.right}) <= map left (${mapBounds.left})", cardBounds.right <= mapBounds.left)
        assertTrue("Tablet attr right (${attrBounds.right}) <= map left (${mapBounds.left})", attrBounds.right <= mapBounds.left)
        assertTrue("Tablet attr top (${attrBounds.top}) >= card bottom (${cardBounds.bottom})", attrBounds.top >= cardBounds.bottom)
    }
}
