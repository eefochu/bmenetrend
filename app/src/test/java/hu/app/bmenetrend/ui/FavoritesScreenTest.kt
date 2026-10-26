package hu.app.bmenetrend.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "hu-rHU-w360dp-h800dp")
class FavoritesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val trainRoute1 = TransitRoute("mav:s10", "S10", "Győr", emptyList(), "mav", transportMode = "RAIL")
    private val trainRoute2 = TransitRoute("mav:ic123", "IC", "Szombathely", emptyList(), "mav", transportMode = "RAIL")
    private val busRoute = TransitRoute("volan:762", "762", "Sóskút", emptyList(), "volan", transportMode = "BUS")

    private val stationStop = TransitStop("stop_kelenfold", "Budapest-Kelenföld", GeoPoint(47.46, 19.02), listOf(trainRoute1.id, trainRoute2.id, busRoute.id), "mav")
    private val platform1 = TransitStop("plat_1", "1. vágány", GeoPoint(47.46, 19.02), listOf(trainRoute1.id), "mav")
    private val platform2 = TransitStop("plat_2", "2. vágány", GeoPoint(47.46, 19.02), listOf(trainRoute2.id), "mav")

    private val dep1 = Departure(
        routeId = "mav:s10",
        routeNumber = "S10",
        destination = "Győr",
        stopId = platform1.id,
        scheduledAt = Instant.now().plusSeconds(600),
        expectedAt = Instant.now().plusSeconds(600),
        kind = ArrivalKind.SCHEDULED,
        serviceName = "Személyvonat"
    )
    private val dep2 = Departure(
        routeId = "mav:ic123",
        routeNumber = "IC",
        destination = "Szombathely",
        stopId = platform2.id,
        scheduledAt = Instant.now().plusSeconds(1800),
        expectedAt = Instant.now().plusSeconds(1800),
        kind = ArrivalKind.SCHEDULED,
        serviceName = "BAKONY InterCity"
    )

    private val nearbyStation = NearbyStation(
        stop = stationStop,
        platforms = listOf(platform1, platform2),
        distanceMeters = 100.0,
        departures = listOf(dep1, dep2),
        loading = false,
        unavailable = false
    )

    private val uiState = UiState(
        network = TransitNetwork(listOf(stationStop, platform1, platform2), listOf(trainRoute1, trainRoute2, busRoute), emptyList(), Instant.now(), "test"),
        favorites = setOf(stationStop.id),
        favoriteStations = listOf(nearbyStation),
        favoritesLoading = false
    )

    @Test
    fun extractStationLinesCategorizesTrainAndOtherRoutesCorrectly() {
        val lines = extractStationLines(nearbyStation, uiState)

        assertTrue(lines.trainTypes.contains("Személyvonat"))
        assertTrue(lines.trainTypes.contains("InterCity"))
        assertEquals(2, lines.trainRoutes.size)
        assertEquals(1, lines.otherRoutes.size)
        assertEquals("762", lines.otherRoutes.first().number)
    }

    @Test
    fun favoritesScreenRendersTrainTypesAndLineBadgesAndTriggersCallbacks() {
        var clickedStopId: String? = null
        var clickedRouteId: String? = null
        var allDeparturesStopId: String? = null

        compose.setContent {
            ViaBuszTheme {
                FavoritesScreen(
                    state = uiState,
                    onStop = { allDeparturesStopId = it },
                    onStopRoute = { stop, route ->
                        clickedStopId = stop
                        clickedRouteId = route
                    },
                    onRemove = {},
                    onRefresh = {},
                    onWindow = {},
                    onVisible = {}
                )
            }
        }

        // Check station name header is displayed
        compose.onNodeWithText("Budapest-Kelenföld").assertIsDisplayed()

        // Check train types are displayed
        compose.onNodeWithText(app.getString(R.string.favorites_train_types)).assertIsDisplayed()
        compose.onNodeWithText("Személyvonat").assertIsDisplayed()
        compose.onNodeWithText("InterCity").assertIsDisplayed()

        // Check train lines section is displayed with line badges
        compose.onNodeWithText(app.getString(R.string.favorites_train_lines)).assertIsDisplayed()
        compose.onNodeWithText("S10").assertIsDisplayed()

        // Check other lines section is displayed
        compose.onNodeWithText(app.getString(R.string.favorites_other_lines)).assertIsDisplayed()
        compose.onNodeWithText("762").assertIsDisplayed()

        // Clicking a line badge should trigger onStopRoute
        compose.onNodeWithText("S10").performClick()
        compose.runOnIdle {
            assertEquals(stationStop.id, clickedStopId)
            assertEquals(trainRoute1.id, clickedRouteId)
        }

        // Clicking a train type chip should also trigger onStopRoute for the matching route
        compose.onNodeWithText("InterCity").performClick()
        compose.runOnIdle {
            assertEquals(stationStop.id, clickedStopId)
            assertEquals(trainRoute2.id, clickedRouteId)
        }

        // Clicking "Összes indulás" should trigger onStop
        compose.onNodeWithText(app.getString(R.string.favorites_all_departures)).performClick()
        compose.runOnIdle {
            assertEquals(stationStop.id, allDeparturesStopId)
        }
    }
}
