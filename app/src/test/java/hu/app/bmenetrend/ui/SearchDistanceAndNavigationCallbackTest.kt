package hu.app.bmenetrend.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.NearbyStation
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
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "hu-rHU-w360dp-h800dp")
class SearchDistanceAndNavigationCallbackTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun formatStopDistanceRespectsMeterAndKilometerThresholdsAndLocale() {
        val originalLocale = Locale.getDefault()
        try {
            // US locale (dot separator)
            Locale.setDefault(Locale.US)
            assertEquals("0 m", formatStopDistance(0.0))
            assertEquals("12 m", formatStopDistance(12.4))
            assertEquals("999 m", formatStopDistance(999.8))
            assertEquals("1.0 km", formatStopDistance(1000.0))
            assertEquals("1.5 km", formatStopDistance(1500.0))
            assertEquals("12.3 km", formatStopDistance(12345.0))

            // Hungarian locale (comma separator)
            Locale.setDefault(Locale.forLanguageTag("hu-HU"))
            assertEquals("500 m", formatStopDistance(500.0))
            assertEquals("1,0 km", formatStopDistance(1000.0))
            assertEquals("2,4 km", formatStopDistance(2380.0))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun stopSearchRowRendersFormattedDistanceOnlyWhenUserLocationIsKnown() {
        val stop = TransitStop(
            id = "stop-keleti",
            name = "Keleti pályaudvar",
            position = GeoPoint(47.5000, 19.0800),
            routeIds = emptyList(),
            providerId = "mav",
            station = true
        )
        // User is positioned at ~1.5 km distance
        val userLoc = GeoPoint(47.5135, 19.0800)
        val expectedDistance = formatStopDistance(hu.app.bmenetrend.data.StopClusters.distanceMeters(userLoc, stop.position))

        val stateWithLoc = UiState(userLocation = userLoc)
        val clickedStops = mutableListOf<String>()

        compose.setContent {
            ViaBuszTheme {
                StopSearchRow(
                    stop = stop,
                    state = stateWithLoc,
                    favorite = false,
                    onStop = { clickedStops += it }
                )
            }
        }

        // Distance text is displayed
        compose.onNodeWithText(expectedDistance).assertIsDisplayed()
        compose.onNodeWithText("Keleti pályaudvar").performClick()
        assertEquals(listOf("stop-keleti"), clickedStops)
    }

    @Test
    fun stopSearchRowOmitsDistanceWhenUserLocationIsNull() {
        val stop = TransitStop(
            id = "stop-keleti",
            name = "Keleti pályaudvar",
            position = GeoPoint(47.5000, 19.0800),
            routeIds = emptyList(),
            providerId = "mav",
            station = true
        )
        val stateWithoutLoc = UiState(userLocation = null)

        compose.setContent {
            ViaBuszTheme {
                StopSearchRow(
                    stop = stop,
                    state = stateWithoutLoc,
                    favorite = false,
                    onStop = {}
                )
            }
        }

        compose.onNodeWithText("Keleti pályaudvar").assertIsDisplayed()
        compose.onAllNodesWithText(" m", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText(" km", substring = true).assertCountEquals(0)
    }

    @Test
    fun favoritesScreenEmptyStateBrowseButtonTriggersCallback() {
        var browseCount = 0
        val emptyState = UiState(favorites = emptySet(), favoriteStations = emptyList())

        compose.setContent {
            ViaBuszTheme {
                FavoritesScreen(
                    state = emptyState,
                    onStop = {},
                    onRemove = {},
                    onRefresh = {},
                    onVisible = {},
                    onBrowse = { browseCount++ }
                )
            }
        }

        val addStopText = context.getString(R.string.design_add_stop)
        compose.onNodeWithText(addStopText).assertIsDisplayed().performClick()
        assertEquals(1, browseCount)
    }

    @Test
    fun favoritesScreenWithItemsBrowseActionTriggersCallback() {
        var browseCount = 0
        val testStop = TransitStop("stop_test", "Test Stop", GeoPoint(47.5, 19.0), emptyList(), "mav")
        val station = NearbyStation(
            stop = testStop,
            platforms = emptyList(),
            distanceMeters = 50.0,
            departures = emptyList(),
            loading = false,
            unavailable = false
        )
        val stateWithFav = UiState(
            favorites = setOf(testStop.id),
            favoriteStations = listOf(station),
            network = TransitNetwork(listOf(testStop), emptyList(), emptyList(), Instant.now(), "v")
        )

        compose.setContent {
            ViaBuszTheme {
                FavoritesScreen(
                    state = stateWithFav,
                    onStop = {},
                    onRemove = {},
                    onRefresh = {},
                    onVisible = {},
                    onBrowse = { browseCount++ }
                )
            }
        }

        val addStopText = context.getString(R.string.design_add_stop)
        compose.onNodeWithText(addStopText).assertIsDisplayed().performClick()
        assertEquals(1, browseCount)
    }

    @Test
    fun mainNavigationHidesNavigationBarWhenHideNavigationIsTrue() {
        val tab = mutableStateOf(MainTab.MAP)
        val hideNav = mutableStateOf(true)

        compose.setContent {
            ViaBuszTheme {
                MainNavigation(
                    tab = tab.value,
                    onTab = { tab.value = it },
                    map = { Box(Modifier.fillMaxSize()) { Text("Map Content") } },
                    planner = { Box(Modifier.fillMaxSize()) { Text("Planner Content") } },
                    favorites = { Box(Modifier.fillMaxSize()) { Text("Favorites Content") } },
                    hideNavigation = hideNav.value
                )
            }
        }

        val mapTabLabel = context.getString(R.string.planner_nav_map)
        val planTabLabel = context.getString(R.string.planner_nav_plan)

        // When hideNavigation is true, navigation items should not exist
        compose.onNodeWithText(mapTabLabel).assertDoesNotExist()
        compose.onNodeWithText(planTabLabel).assertDoesNotExist()
        compose.onNodeWithText("Map Content").assertIsDisplayed()

        // When hideNavigation is false, navigation items are restored
        hideNav.value = false
        compose.waitForIdle()
        compose.onNodeWithText(mapTabLabel).assertIsDisplayed()
        compose.onNodeWithText(planTabLabel).assertIsDisplayed()
    }

    @Test
    fun viaBuszScreenFiresFullscreenAndSearchOpenWhenOpenSearchRequestIncrements() {
        val searchRequest = mutableIntStateOf(0)
        val fullscreenStates = mutableListOf<Boolean>()
        val testStop = TransitStop("stop_1", "Test Stop", GeoPoint(47.5, 19.0), emptyList(), "volan")
        val state = UiState(
            network = TransitNetwork(listOf(testStop), emptyList(), emptyList(), Instant.now(), "v")
        )

        compose.setContent {
            ViaBuszTheme {
                ViaBuszScreen(
                    state = state,
                    onStopSelected = {},
                    onRefresh = {},
                    onLocate = {},
                    onWindowChanged = {},
                    onToggleFavorite = {},
                    mapContent = { modifier, _, _, _, _ -> Box(modifier) },
                    openSearchRequest = searchRequest.intValue,
                    onFullscreenChanged = { fullscreenStates += it }
                )
            }
        }

        // Initially not fullscreen (showSettings, showAbout, showUpdates, searchOpen are all false)
        assertTrue(fullscreenStates.isNotEmpty())
        assertEquals(false, fullscreenStates.last())

        // Increment search request to trigger search overlay
        searchRequest.intValue = 1
        compose.waitForIdle()

        // Fullscreen should now be reported as true
        assertEquals(true, fullscreenStates.last())
    }
}
