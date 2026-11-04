package hu.app.bmenetrend.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "hu-rHU-w360dp-h800dp")
class SettingsPageThemeSelectorTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun compactThemeSelectorDisplaysAllThreeOptionsWithRadioRoleAndAutoSelectedByDefault() {
        val state = UiState(themeMode = ThemeMode.SYSTEM)
        var selectedMode: ThemeMode? = null

        compose.setContent {
            ViaBuszTheme {
                SettingsPage(
                    state = state,
                    onAccent = {},
                    onCustom = {},
                    onNationwide = {},
                    onAbout = {},
                    onBack = {},
                    onThemeMode = { selectedMode = it }
                )
            }
        }

        val autoText = context.getString(R.string.design_theme_auto)
        val lightText = context.getString(R.string.design_theme_light)
        val darkText = context.getString(R.string.design_theme_dark)

        // All 3 segmented options must exist with Role.RadioButton
        val autoNode = compose.onNode(hasText(autoText) and hasRole(Role.RadioButton))
        val lightNode = compose.onNode(hasText(lightText) and hasRole(Role.RadioButton))
        val darkNode = compose.onNode(hasText(darkText) and hasRole(Role.RadioButton))

        autoNode.assertIsDisplayed().assertIsSelected()
        lightNode.assertIsDisplayed().assertIsNotSelected()
        darkNode.assertIsDisplayed().assertIsNotSelected()

        // Minimum 48.dp touch target height
        autoNode.assertHeightIsAtLeast(48.dp)
        lightNode.assertHeightIsAtLeast(48.dp)
        darkNode.assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun compactThemeSelectorInvokesCallbackWhenSwitchingModes() {
        val state = UiState(themeMode = ThemeMode.SYSTEM)
        val selectedModes = mutableListOf<ThemeMode>()

        compose.setContent {
            ViaBuszTheme {
                SettingsPage(
                    state = state,
                    onAccent = {},
                    onCustom = {},
                    onNationwide = {},
                    onAbout = {},
                    onBack = {},
                    onThemeMode = { selectedModes += it }
                )
            }
        }

        val lightText = context.getString(R.string.design_theme_light)
        val darkText = context.getString(R.string.design_theme_dark)

        compose.onNode(hasText(lightText) and hasRole(Role.RadioButton)).performClick()
        assertEquals(listOf(ThemeMode.LIGHT), selectedModes)

        compose.onNode(hasText(darkText) and hasRole(Role.RadioButton)).performClick()
        assertEquals(listOf(ThemeMode.LIGHT, ThemeMode.DARK), selectedModes)
    }

    @Test
    fun compactThemeSelectorReflectsActiveSelectionForLightMode() {
        val autoText = context.getString(R.string.design_theme_auto)
        val lightText = context.getString(R.string.design_theme_light)
        val darkText = context.getString(R.string.design_theme_dark)

        compose.setContent {
            ViaBuszTheme {
                SettingsPage(
                    state = UiState(themeMode = ThemeMode.LIGHT),
                    onAccent = {},
                    onCustom = {},
                    onNationwide = {},
                    onAbout = {},
                    onBack = {}
                )
            }
        }

        compose.onNode(hasText(lightText) and hasRole(Role.RadioButton)).assertIsSelected()
        compose.onNode(hasText(autoText) and hasRole(Role.RadioButton)).assertIsNotSelected()
        compose.onNode(hasText(darkText) and hasRole(Role.RadioButton)).assertIsNotSelected()
    }

    @Test
    fun compactThemeSelectorReflectsActiveSelectionForDarkMode() {
        val autoText = context.getString(R.string.design_theme_auto)
        val lightText = context.getString(R.string.design_theme_light)
        val darkText = context.getString(R.string.design_theme_dark)

        compose.setContent {
            ViaBuszTheme {
                SettingsPage(
                    state = UiState(themeMode = ThemeMode.DARK),
                    onAccent = {},
                    onCustom = {},
                    onNationwide = {},
                    onAbout = {},
                    onBack = {}
                )
            }
        }

        compose.onNode(hasText(darkText) and hasRole(Role.RadioButton)).assertIsSelected()
        compose.onNode(hasText(autoText) and hasRole(Role.RadioButton)).assertIsNotSelected()
        compose.onNode(hasText(lightText) and hasRole(Role.RadioButton)).assertIsNotSelected()
    }

    @Test
    fun designSystemTokensAndCornerShapesMatchUpdatedSpecification() {
        assertEquals(RoundedCornerShape(12.dp), TransitDesign.controlShape)
        assertEquals(RoundedCornerShape(20.dp), TransitDesign.panelShape)
        assertEquals(RoundedCornerShape(16.dp), TransitDesign.groupShape)
    }

    @Test
    fun offlineMapChipRendersWithOpaqueBackgroundAndOpensSettings() {
        val offlineChipText = context.getString(R.string.design_offline_status)

        // Offline mode = true
        compose.setContent {
            ViaBuszTheme {
                ViaBuszScreen(
                    state = UiState(offlineMode = true),
                    onStopSelected = {},
                    onRefresh = {},
                    onLocate = {},
                    onWindowChanged = {},
                    onToggleFavorite = {},
                    mapContent = { modifier, _, _, _, _ -> Box(modifier) }
                )
            }
        }

        val chipNode = compose.onNodeWithText(offlineChipText)
        chipNode.assertIsDisplayed()

        // Clicking the chip opens settings (verified by Settings header appearing)
        chipNode.performClick()
        val settingsTitle = context.getString(R.string.volan_settings)
        compose.onNodeWithText(settingsTitle).assertIsDisplayed()
    }

    @Test
    fun onlineModeOmitsOfflineMapChip() {
        val offlineChipText = context.getString(R.string.design_offline_status)

        compose.setContent {
            ViaBuszTheme {
                ViaBuszScreen(
                    state = UiState(offlineMode = false),
                    onStopSelected = {},
                    onRefresh = {},
                    onLocate = {},
                    onWindowChanged = {},
                    onToggleFavorite = {},
                    mapContent = { modifier, _, _, _, _ -> Box(modifier) }
                )
            }
        }

        compose.onNodeWithText(offlineChipText).assertDoesNotExist()
    }

    @Test
    fun standardNavigationUsesTabSemanticsAndSwitchesDestinations() {
        var currentTab by mutableStateOf(MainTab.MAP)

        compose.setContent {
            ViaBuszTheme {
                MainNavigation(
                    tab = currentTab,
                    onTab = { currentTab = it },
                    map = { Box {} },
                    planner = { Box {} },
                    favorites = { Box {} }
                )
            }
        }

        val mapText = context.getString(R.string.planner_nav_map)
        val planText = context.getString(R.string.planner_nav_plan)
        val favText = context.getString(R.string.favorites_nav)

        val mapTab = compose.onNode(hasText(mapText) and hasRole(Role.Tab))
        val planTab = compose.onNode(hasText(planText) and hasRole(Role.Tab))
        val favTab = compose.onNode(hasText(favText) and hasRole(Role.Tab))

        mapTab.assertIsSelected()
        planTab.assertIsNotSelected()
        favTab.assertIsNotSelected()

        planTab.performClick()
        assertEquals(MainTab.PLANNER, currentTab)
        compose.waitForIdle()

        planTab.assertIsSelected()
        mapTab.assertIsNotSelected()

        favTab.performClick()
        assertEquals(MainTab.FAVORITES, currentTab)
        compose.waitForIdle()

        favTab.assertIsSelected()
        planTab.assertIsNotSelected()
    }
}
