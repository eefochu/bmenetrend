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
}
