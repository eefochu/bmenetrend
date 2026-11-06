package hu.app.bmenetrend.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "hu-rHU-w320dp-h800dp")
class MavNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ticketsTabIsPresentAndCanBeSelected() {
        var tab by mutableStateOf(MainTab.MAP)
        compose.setContent {
            ViaBuszTheme {
                MainNavigation(
                    tab = tab,
                    onTab = { tab = it },
                    map = { Text("Térkép nézet") },
                    planner = { Text("Tervezés nézet") },
                    favorites = { Text("Kedvencek nézet") },
                    tickets = { Text("MÁV jegyek és bérletek nézet") }
                )
            }
        }

        // Check tickets destination exists
        compose.onNodeWithText("Jegyek").assertIsDisplayed()

        // Click tickets tab
        compose.onNodeWithText("Jegyek").performClick()
        compose.waitForIdle()

        assertEquals(MainTab.TICKETS, tab)
        compose.onNodeWithText("MÁV jegyek és bérletek nézet").assertIsDisplayed()
    }
}
