package hu.app.bmenetrend.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import hu.app.bmenetrend.data.PlannerEndpoint
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.PlannerField
import hu.app.bmenetrend.PlannerState
import hu.app.bmenetrend.R
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "hu-rHU-w1200dp-h800dp")
class PlannerMapOverlayTabletTest {
    @get:Rule val compose = createComposeRule()

    @Test fun plannerUsesFullPaneAndCardControlsWork() {
        var tab = MainTab.PLANNER
        val state = mutableStateOf(PlannerState())
        var origins = 0; var destinations = 0; var swaps = 0; var departures = 0; var finds = 0
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.setContent {
            ViaBuszTheme {
                MainNavigation(tab, { tab = it }, map = { Box(Modifier.fillMaxSize()) },
                    planner = {
                        Box(Modifier.fillMaxSize().testTag("planner-test-map"))
                        PlannerMapOverlay(state.value, { origins++ }, { destinations++ }, { swaps++ },
                            { departures++ }, { finds++ }, {})
                    }, favorites = { Box {} })
            }
        }
        val pxPerDp = context.resources.displayMetrics.density
        val mapWidth = compose.onNodeWithTag("planner-test-map").fetchSemanticsNode().boundsInRoot.width / pxPerDp
        val cardWidth = compose.onNodeWithTag("planner-controls-card").fetchSemanticsNode().boundsInRoot.width / pxPerDp
        assertTrue("planner pane width was $mapWidth dp", mapWidth > 760f)
        assertTrue("planner card width was $cardWidth dp", cardWidth <= 400f)
        compose.onNodeWithContentDescription("Bezárás").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.planner_from)).performClick()
        compose.onNodeWithText(context.getString(R.string.planner_to)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.planner_swap)).assertIsNotEnabled()
        compose.runOnIdle {
            state.value = PlannerState(destination = PlannerEndpoint("keleti", "Keleti pályaudvar", "", GeoPoint(47.5, 19.08)))
        }
        compose.onNodeWithContentDescription(context.getString(R.string.planner_swap)).assertIsEnabled().performClick()
        compose.onNodeWithText(context.getString(R.string.planner_leave_now)).performClick()
        compose.onNodeWithText(context.getString(R.string.planner_find)).performClick()
        assertEquals("origin=$origins destination=$destinations swap=$swaps", 1, origins)
        assertEquals(1, destinations); assertEquals(1, swaps)
        assertEquals(1, departures); assertEquals(1, finds)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "hu-rHU-w360dp-h640dp")
class PlannerMapOverlayPhoneTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeFontFormScrollsAndKeepsFindReachable() {
        var destinationEdits = 0; var finds = 0
        compose.setContent {
            ViaBuszTheme {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                    PlannerMapOverlay(PlannerState(), {}, { destinationEdits++ }, {}, {}, { finds++ }, {})
                }
            }
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.onNodeWithText(context.getString(R.string.planner_find)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.planner_to)).performClick()
        assertEquals(1, finds); assertEquals(1, destinationEdits)
        compose.onNodeWithText(context.getString(R.string.planner_settings)).assertDoesNotExist()
    }
}
