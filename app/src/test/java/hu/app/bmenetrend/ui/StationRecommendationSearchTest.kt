package hu.app.bmenetrend.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.PlannerState
import hu.app.bmenetrend.PlannerField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import hu.app.bmenetrend.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "hu-rHU-w400dp-h850dp-night")
class StationRecommendationSearchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recommendationAndLiteralStationAndPlaceRemainSelectableAndQueryRefreshes() {
        val query = mutableStateOf("kelen")
        val selectedStops = mutableListOf<String>()
        val selectedPlaces = mutableListOf<PlaceResult>()
        val stop = TransitStop("kelen", "Kelenföld vasútállomás", GeoPoint(47.46, 19.02), emptyList(), "mav", transportMode = "RAIL", station = true)
        val place = PlaceResult("place", "Kelenföld Cafe", "Kelenföld", GeoPoint(47.46, 19.02))
        compose.setContent { ViaBuszTheme {
            CategorizedSearch(UiState(emmaStops = listOf(stop), stopIndexLoaded = true, placeQuery = query.value, placeResults = listOf(place)), query.value,
                SearchCategory.ALL, {}, {}, { selectedStops += it }, {}, { selectedPlaces += it }, { _, _ -> },
                onQueryChanged = { query.value = it }, buildStopIndex = { local, national -> TransitSearch.indexStops(local, national) })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Kelenföld", substring = true).fetchSemanticsNodes().isNotEmpty() }
        val recommended = ApplicationProvider.getApplicationContext<Context>().getString(R.string.search_recommended)
        compose.onNodeWithText(recommended, substring = true).assertExists()
        compose.onAllNodesWithText("Kelenföld vasútállomás", substring = true).assertCountEquals(2).onFirst().performClick()
        assertEquals(listOf("kelen"), selectedStops)
        compose.onNodeWithText("Kelenföld Cafe").assertExists().performClick()
        assertEquals(listOf(place), selectedPlaces)
        query.value = "unrelated"
        compose.waitForIdle()
        compose.onNodeWithText(recommended, substring = true).assertDoesNotExist()
    }
    @Test
    @Config(qualifiers = "hu-rHU-w360dp-h640dp")
    fun plannerRecommendationKeepsEndpointIdentityAndSearchCanBeClearedAtLargeFont() {
        val endpoint = PlannerEndpoint("kelen", "Kelenföld vasútállomás", "", GeoPoint(47.46, 19.02),
            isStation = true, providerIds = listOf("mav"), transportMode = "RAIL")
        val query = mutableStateOf("kelen")
        val selected = mutableListOf<PlannerEndpoint>()
        compose.setContent { ViaBuszTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                PlannerSearch(PlannerState(editing = PlannerField.DESTINATION, query = query.value,
                    recommendation = endpoint.takeIf { query.value.isNotEmpty() }),
                    onQuery = { query.value = it }, onChoose = { selected += it }, onBack = {}, onCurrent = {})
            }
        } }
        compose.onNodeWithText(endpoint.name).assertIsDisplayed().performClick()
        assertTrue(selected.single() === endpoint)
        val clear = ApplicationProvider.getApplicationContext<Context>().getString(R.string.ui_clear_search)
        compose.onNodeWithContentDescription(clear).performClick()
        assertEquals("", query.value)
        compose.onNodeWithText(endpoint.name).assertDoesNotExist()
    }

}
