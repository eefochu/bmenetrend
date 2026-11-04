package hu.app.bmenetrend.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],qualifiers="hu-rHU-w320dp-h800dp")
class ExpressiveNavigationTest {
    @get:Rule val compose=createComposeRule()
    @Test fun largeTextNavigationRetainsAllDestinationsAndAnnouncesSelection() {
        var tab by mutableStateOf(MainTab.MAP)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f,1.8f)) {
                ViaBuszTheme { MainNavigation(tab,{tab=it},map={Box {}},planner={Box {}},favorites={Box {}}) }
            }
        }
        compose.onNodeWithContentDescription("Kedvencek").assertHasClickAction().performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Tervezés").assertHasClickAction().performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Térkép").assertHasClickAction().performClick().assertIsSelected()
    }

    @Test fun phoneRetainsBottomNavigationOnPlannerWithActualMainActivityContract() {
        var tab by mutableStateOf(MainTab.MAP)
        compose.setContent {
            ViaBuszTheme {
                // Actual MainActivity contract: hideBottomBarOnPlanner defaults to false
                MainNavigation(
                    tab = tab,
                    onTab = { tab = it },
                    map = { Box {} },
                    planner = { Box {} },
                    favorites = { Box {} }
                )
            }
        }
        // Initially on MAP: bottom navigation is present
        compose.onNodeWithText("Térkép").assertIsDisplayed()
        compose.onNodeWithText("Tervezés").assertIsDisplayed()

        // Switch to PLANNER: bottom navigation bar remains visible
        tab = MainTab.PLANNER
        compose.waitForIdle()
        compose.onNodeWithText("Térkép").assertIsDisplayed()
        compose.onNodeWithText("Tervezés").assertIsDisplayed()
        compose.onNodeWithText("Kedvencek").assertIsDisplayed()
    }

    @Test fun phoneHidesBottomNavigationWhenPlannerActiveOnlyIfExplicitlyRequested() {
        var tab by mutableStateOf(MainTab.MAP)
        compose.setContent {
            ViaBuszTheme {
                MainNavigation(
                    tab = tab,
                    onTab = { tab = it },
                    map = { Box {} },
                    planner = { Box {} },
                    favorites = { Box {} },
                    hideBottomBarOnPlanner = true
                )
            }
        }
        // Initially on MAP: bottom navigation is present
        compose.onNodeWithText("Térkép").assertIsDisplayed()
        compose.onNodeWithText("Tervezés").assertIsDisplayed()

        // Switch to PLANNER: bottom navigation bar is hidden
        tab = MainTab.PLANNER
        compose.waitForIdle()
        compose.onNodeWithText("Térkép").assertDoesNotExist()
        compose.onNodeWithText("Tervezés").assertDoesNotExist()

        // Switch to FAVORITES: bottom navigation bar returns
        tab = MainTab.FAVORITES
        compose.waitForIdle()
        compose.onNodeWithText("Térkép").assertIsDisplayed()
        compose.onNodeWithText("Kedvencek").assertIsDisplayed()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],qualifiers="hu-rHU-w1200dp-h800dp")
class TabletNavigationRailTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wideTabletNavigationRailStaysVisibleOnPlannerTab() {
        var tab by mutableStateOf(MainTab.PLANNER)
        compose.setContent {
            ViaBuszTheme {
                MainNavigation(
                    tab = tab,
                    onTab = { tab = it },
                    map = { Box {} },
                    planner = { Box {} },
                    favorites = { Box {} },
                    hideBottomBarOnPlanner = true
                )
            }
        }
        // Even when hideBottomBarOnPlanner=true, wide tablet rail displays all destinations
        compose.onNodeWithText("Térkép").assertIsDisplayed()
        compose.onNodeWithText("Tervezés").assertIsDisplayed()
        compose.onNodeWithText("Kedvencek").assertIsDisplayed()
    }
}
