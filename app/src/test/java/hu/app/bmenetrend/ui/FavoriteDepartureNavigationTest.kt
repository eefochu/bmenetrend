package hu.app.bmenetrend.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "hu-rHU-w360dp-h800dp")
class FavoriteDepartureNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val stop = TransitStop("saved", "Saved station", GeoPoint(47.4, 19.0), emptyList())
    private val tab = mutableStateOf(MainTab.MAP)
    private val state = mutableStateOf(UiState(
        network = TransitNetwork(listOf(stop), emptyList(), emptyList(), Instant.now(), "test"),
        favorites = setOf(stop.id), favoriteStations = listOf(NearbyStation(stop, listOf(stop), 0.0)),
        stopIndexLoaded = true, nearbyLoading = false, loading = false,
    ))
    private var returnsToNearby = 0

    private fun showApp() {
        compose.setContent { ViaBuszTheme {
            MainNavigation(tab.value, { tab.value = it }, map = {
                ViaBuszScreen(state.value, {}, {}, {}, {}, {},
                    onReturnToNearby = {
                        returnsToNearby++
                        state.value = state.value.copy(showNearby = true, selectedStopId = null)
                    }, mapContent = { modifier, _, _, _, _ -> Box(modifier) })
            }, planner = {}, favorites = {
                FavoritesScreen(state.value, onStop = { id ->
                    state.value = state.value.copy(showNearby = false, selectedStopId = id,
                        selectedPlatformIds = setOf(id), boardingSelection = null,
                        cameraRequest = state.value.cameraRequest + 1)
                    tab.value = MainTab.MAP
                }, onRemove = {}, onRefresh = {}, onVisible = {})
            })
        } }
    }

    private fun selectFavoriteAndCheckBoard() {
        compose.onNode(hasText(app.getString(R.string.favorites_nav)) and hasClickAction()).performClick()
        compose.onNodeWithTag("favorite:${stop.id}").performClick()
        compose.waitForIdle()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText(stop.name).assertIsDisplayed()
        compose.onNodeWithContentDescription(app.getString(R.string.ui_hide_departures)).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(stop.id, state.value.selectedStopId)
            assertEquals(0, returnsToNearby)
        }
    }

    @Test fun favoriteReplacesRestoredSearchWithDepartures() {
        showApp()
        compose.onNodeWithText(app.getString(R.string.ui_search_short)).performClick()
        compose.onNode(hasSetTextAction()).assertExists()
        selectFavoriteAndCheckBoard()
    }

    @Test fun favoriteReopensHiddenSheetWithoutClearingTheSelectedStop() {
        showApp()
        compose.onNodeWithContentDescription(app.getString(R.string.ui_hide_departures)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(app.getString(R.string.ui_show_departures)).assertIsDisplayed()
        selectFavoriteAndCheckBoard()
    }
    @Test fun returningWithoutSelectingAFavoritePreservesTheSearch() {
        state.value = state.value.copy(showNearby = false, selectedStopId = stop.id, cameraRequest = 1)
        showApp()
        compose.waitForIdle()
        compose.onNodeWithText(app.getString(R.string.ui_search_short)).performClick()
        compose.onNode(hasSetTextAction()).assertExists()
        compose.onNode(hasText(app.getString(R.string.favorites_nav)) and hasClickAction()).performClick()
        compose.onNode(hasText(app.getString(R.string.planner_nav_map)) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertExists()
        compose.runOnIdle { assertEquals(0, returnsToNearby) }
    }

}
