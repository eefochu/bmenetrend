package hu.app.bmenetrend.ui

import androidx.compose.ui.graphics.Color
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
    private val trainRouteDuplicate = TransitRoute("mav:s10_alt", "S10", "Komárom", emptyList(), "mav", transportMode = "RAIL")
    private val busRoute = TransitRoute("volan:762", "762", "Sóskút", emptyList(), "volan", transportMode = "BUS")

    private val stationStop = TransitStop("stop_kelenfold", "Budapest-Kelenföld", GeoPoint(47.46, 19.02), listOf(trainRoute1.id, trainRoute2.id, trainRouteDuplicate.id, busRoute.id), "mav")
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
    // Duplicate departure for S10 and duplicate for IC to verify deduplication
    private val depDuplicateS10 = Departure(
        routeId = "mav:s10_alt",
        routeNumber = "S10",
        destination = "Hegyeshalom",
        stopId = platform1.id,
        scheduledAt = Instant.now().plusSeconds(3600),
        expectedAt = Instant.now().plusSeconds(3600),
        kind = ArrivalKind.SCHEDULED,
        serviceName = "Személyvonat"
    )

    private val nearbyStation = NearbyStation(
        stop = stationStop,
        platforms = listOf(platform1, platform2),
        distanceMeters = 100.0,
        departures = listOf(dep1, dep2, depDuplicateS10),
        loading = false,
        unavailable = false
    )

    private val uiState = UiState(
        network = TransitNetwork(listOf(stationStop, platform1, platform2), listOf(trainRoute1, trainRoute2, trainRouteDuplicate, busRoute), emptyList(), Instant.now(), "test"),
        favorites = setOf(stationStop.id),
        favoriteStations = listOf(nearbyStation),
        favoritesLoading = false
    )

    @Test
    fun loadingAnotherPlatformDoesNotAddLinesToTheSavedStation() {
        val saved=stationStop.copy(routeIds=listOf(trainRoute1.id))
        val favorite=NearbyStation(saved,listOf(saved),0.0)
        val before=UiState(network=TransitNetwork(listOf(saved),listOf(trainRoute1),emptyList(),Instant.now(),"test"))
        // Loading nearby platform/board data enriches the global catalogue, not this favourite's scope.
        val otherPlatform=saved.copy(id="other-platform",name="${saved.name} (Ellenirány)",
            routeIds=listOf(trainRoute2.id,busRoute.id))
        val after=before.copy(emmaStops=listOf(otherPlatform),emmaRoutes=listOf(trainRoute2,busRoute))
        assertEquals(listOf("S10"),extractStationLines(favorite,before).trainBadges.map { it.label })
        assertEquals(extractStationLines(favorite,before),extractStationLines(favorite,after))
    }

    @Test
    fun extractStationLinesDeduplicatesBadgesAndCategorizesCorrectly() {
        val lines = extractStationLines(nearbyStation, uiState)

        // Verify deduplication: S10 appears only once even though multiple routes & departures have S10
        val s10Count = lines.trainBadges.count { it.label == "S10" }
        assertEquals(1, s10Count)

        val icCount = lines.trainBadges.count { it.label == "IC" }
        assertEquals(1, icCount)

        assertEquals(2, lines.trainBadges.size)
        assertEquals(1, lines.otherBadges.size)
        assertEquals("762", lines.otherBadges.first().label)
    }

    @Test
    fun railLineColorMatchesSpecification() {
        assertEquals(Color(0xFF008001), railLineColor("IR"))
        assertEquals(Color(0xFF008001), railLineColor("IR87"))
        assertEquals(Color(0xFFA9CD45), railLineColor("G10"))
        assertEquals(Color(0xFFA9CD45), railLineColor("G43"))
        assertEquals(Color(0xFF00AFEF), railLineColor("S10"))
        assertEquals(Color(0xFF00AFEF), railLineColor("S30"))
        assertEquals(Color(0xFFFECD28), railLineColor("Z30"))
        assertEquals(Color(0xFF171B20), railLineTextColor("Z30"))
        assertEquals(Color(0xFF171B20), railLineTextColor("S10"))
        assertEquals(Color(0xFF171B20), railLineTextColor("G10"))
        assertEquals(Color(0xFF2E5EA7), railLineColor("IC"))
        assertEquals(Color(0xFF2E5EA7), railLineColor("rjx"))
        assertEquals(Color(0xFF2E5EA7), railLineColor("Gy"))
        assertEquals(Color(0xFF2E5EA7), railLineColor("Sz"))
    }

    @Test
    fun naturalCompareSortsBadgesCorrectly() {
        val badges = listOf("rjx", "Sz", "S", "S10", "G10", "G43", "G40", "G44", "S30", "S12", "S36", "S40", "S42", "Z30", "IC", "IR", "EC", "EN", "Ex")
        val sorted = badges.sortedWith { a, b -> naturalCompare(a, b) }
        val expected = listOf("EC", "EN", "Ex", "G10", "G40", "G43", "G44", "IC", "IR", "S", "S10", "S12", "S30", "S36", "S40", "S42", "Sz", "Z30", "rjx")
        assertEquals(expected, sorted)
    }

    @Test
    fun favoritesScreenRendersDistinctBadgesAndTriggersCallbacks() {
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

        // Check line badges are displayed
        compose.onNodeWithText("S10").assertIsDisplayed()
        compose.onNodeWithText("IC").assertIsDisplayed()

        // Check other lines section is displayed
        compose.onNodeWithText(app.getString(R.string.favorites_other_lines)).assertIsDisplayed()
        compose.onNodeWithText("762").assertIsDisplayed()

        // Clicking a line badge should trigger onStopRoute
        compose.onNodeWithText("S10").performClick()
        compose.runOnIdle {
            assertEquals(stationStop.id, clickedStopId)
            assertEquals(trainRoute1.id, clickedRouteId)
        }

        // Clicking another line badge should trigger onStopRoute
        compose.onNodeWithText("IC").performClick()
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
