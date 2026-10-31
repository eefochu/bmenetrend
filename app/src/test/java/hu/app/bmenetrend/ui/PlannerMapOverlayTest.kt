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
import hu.app.bmenetrend.data.LocalPlanningService
import hu.app.bmenetrend.PlannerField
import hu.app.bmenetrend.PlannerState
import hu.app.bmenetrend.R
import androidx.compose.ui.test.assertIsOn
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
                        PlannerMapOverlay(
                            state = state.value,
                            onOrigin = { origins++ },
                            onDestination = { destinations++ },
                            onSwap = { swaps++ },
                            onDeparture = { departures++ },
                            onFind = { finds++ },
                            onClose = {},
                            mapContent = { modifier ->
                                Box(modifier.testTag("planner-test-map"))
                            }
                        )
                    }, favorites = { Box {} })
            }
        }
        val pxPerDp = context.resources.displayMetrics.density
        val mapNode = compose.onNodeWithTag("planner-test-map").fetchSemanticsNode()
        val cardNode = compose.onNodeWithTag("planner-controls-card").fetchSemanticsNode()
        val mapBounds = mapNode.boundsInRoot
        val cardBounds = cardNode.boundsInRoot
        val mapWidth = mapBounds.width / pxPerDp
        val cardWidth = cardBounds.width / pxPerDp
        assertTrue("planner map width was $mapWidth dp", mapWidth >= 700f)
        assertTrue("planner card width was $cardWidth dp", cardWidth <= 384f)
        assertTrue("tablet card right (${cardBounds.right}) must not exceed map left (${mapBounds.left})", cardBounds.right <= mapBounds.left)

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

    @Test fun tabletBoundsNeverOverlapMapWithCard() {
        compose.setContent {
            ViaBuszTheme {
                PlannerMapOverlay(
                    state = PlannerState(),
                    onOrigin = {}, onDestination = {}, onSwap = {},
                    onDeparture = {}, onFind = {}, onClose = {},
                    mapContent = { modifier -> Box(modifier.testTag("planner-test-map")) }
                )
            }
        }
        val cardBounds = compose.onNodeWithTag("planner-controls-card").fetchSemanticsNode().boundsInRoot
        val mapBounds = compose.onNodeWithTag("planner-test-map").fetchSemanticsNode().boundsInRoot
        assertTrue("Tablet card right (${cardBounds.right}) must not exceed map left (${mapBounds.left})", cardBounds.right <= mapBounds.left)
        assertTrue("Card width must be positive", cardBounds.width > 0f)
        assertTrue("Map width must be positive", mapBounds.width > 0f)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "hu-rHU-w360dp-h640dp")
class PlannerMapOverlayPhoneTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeFontFormScrollsAndKeepsFindReachable() {
        var destinationEdits = 0; var finds = 0
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.setContent {
            ViaBuszTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                    PlannerMapOverlay(
                        state = PlannerState(),
                        onOrigin = {},
                        onDestination = { destinationEdits++ },
                        onSwap = {},
                        onDeparture = {},
                        onFind = { finds++ },
                        onClose = {},
                        mapContent = { modifier -> Box(modifier.testTag("planner-test-map")) }
                    )
                }
            }
        }
        // Controls card has scroll capability
        compose.onNode(hasScrollAction()).assertIsDisplayed()

        // CTA button is displayed and reachable outside the scrollable card
        compose.onNodeWithText(context.getString(R.string.planner_find)).assertIsDisplayed().performClick()
        compose.onNodeWithText(context.getString(R.string.planner_to)).performClick()
        assertEquals(1, finds); assertEquals(1, destinationEdits)
        compose.onNodeWithText(context.getString(R.string.planner_settings)).assertDoesNotExist()
    }

    @Test fun phoneActualBoundsNeverOverlapMapWithCardOrCta() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.setContent {
            ViaBuszTheme {
                PlannerMapOverlay(
                    state = PlannerState(),
                    onOrigin = {}, onDestination = {}, onSwap = {},
                    onDeparture = {}, onFind = {},
                    mapContent = { modifier -> Box(modifier.testTag("planner-test-map")) }
                )
            }
        }
        val cardBounds = compose.onNodeWithTag("planner-controls-card").fetchSemanticsNode().boundsInRoot
        val mapBounds = compose.onNodeWithTag("planner-test-map").fetchSemanticsNode().boundsInRoot
        val ctaBounds = compose.onNodeWithText(context.getString(R.string.planner_find)).fetchSemanticsNode().boundsInRoot
        assertTrue("Card bottom (${cardBounds.bottom}) must not exceed map top (${mapBounds.top})", cardBounds.bottom <= mapBounds.top)
        assertTrue("Map bottom (${mapBounds.bottom}) must not exceed CTA top (${ctaBounds.top})", mapBounds.bottom <= ctaBounds.top)
        assertTrue("Map must have positive height", mapBounds.height > 0f)
    }

    @Test fun servicesDialogShowsDistinctOperatorsDefaultCheckedAndInvokesCallbackOnce() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val providerCallbacks = mutableListOf<Pair<String, Boolean>>()
        val state = mutableStateOf(PlannerState())
        compose.setContent {
            ViaBuszTheme {
                PlannerMapOverlay(
                    state = state.value,
                    onOrigin = {}, onDestination = {}, onSwap = {},
                    onDeparture = {}, onFind = {},
                    onLocalProvider = { id, enabled ->
                        providerCallbacks += id to enabled
                    },
                    mapContent = { modifier -> Box(modifier.testTag("planner-test-map")) }
                )
            }
        }
        // Open local services dialog
        compose.onNodeWithTag("planner-local-services").assertIsDisplayed().performClick()

        // Dialog title
        compose.onNodeWithText(context.getString(R.string.planner_local_services)).assertIsDisplayed()

        // Distinct operator names displayed and all default checked
        val services = LocalPlanningService.entries
        assertEquals(3, services.size)
        val distinctNames = services.map { it.displayName }.distinct()
        assertEquals(3, distinctNames.size)

        for (service in services) {
            val node = compose.onNodeWithText(service.displayName)
            node.assertIsDisplayed()
            node.assertIsOn()
        }

        // Toggle one provider (ViaBusz)
        compose.onNodeWithText(LocalPlanningService.VIABUSZ.displayName).performClick()
        assertEquals(1, providerCallbacks.size)
        assertEquals(LocalPlanningService.VIABUSZ.providerId to false, providerCallbacks.first())

        // Done button dismisses dialog
        compose.onNodeWithText(context.getString(R.string.planner_local_services_done)).performClick()
        compose.onNodeWithText(context.getString(R.string.planner_local_services)).assertDoesNotExist()
    }

    @Test fun searchSheetRendersStationResultsWithSharedOperatorArtwork() {
        val endpoints = listOf(
            PlannerEndpoint("homm:38", "Budaörs, lakótelep", "HOMM", GeoPoint(47.46, 18.96), isStation = true, providerIds = listOf("homm"), transportMode = "BUS"),
            PlannerEndpoint("kanyargo:1", "Piliscsaba vasútállomás", "KanyarGO", GeoPoint(47.63, 18.83), isStation = true, providerIds = listOf("kanyargo"), transportMode = "BUS"),
            PlannerEndpoint("viabusz:11", "Biatorbágy, vasútállomás", "ViaBusz", GeoPoint(47.47, 18.82), isStation = true, providerIds = listOf("viabusz"), transportMode = "BUS"),
            PlannerEndpoint("mav:station", "Keleti pályaudvar", "MÁV", GeoPoint(47.50, 19.08), isStation = true, providerIds = listOf("mav"), transportMode = "RAIL")
        )
        val state = PlannerState(
            editing = PlannerField.DESTINATION,
            query = "állomás",
            suggestions = endpoints,
        )
        var chosen: PlannerEndpoint? = null
        compose.setContent {
            ViaBuszTheme {
                PlannerSearch(
                    state = state,
                    onQuery = {},
                    onChoose = { chosen = it },
                    onBack = {},
                    onCurrent = {},
                )
            }
        }
        compose.onNodeWithText("Budaörs, lakótelep").assertIsDisplayed()
        compose.onNodeWithText("Piliscsaba vasútállomás").assertIsDisplayed()
        compose.onNodeWithText("Biatorbágy, vasútállomás").assertIsDisplayed()
        compose.onNodeWithText("Keleti pályaudvar").assertIsDisplayed().performClick()
        assertEquals("mav:station", chosen?.id)
    }
}
