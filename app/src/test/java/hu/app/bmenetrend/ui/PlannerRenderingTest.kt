package hu.app.bmenetrend.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.PlannerField
import hu.app.bmenetrend.PlannerViewModel
import hu.app.bmenetrend.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Before
import org.junit.After
import hu.app.bmenetrend.data.*
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "hu-rHU-w360dp-h800dp")
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlannerRenderingTest {
    @get:Rule val compose = createComposeRule()
    @Before fun setupMain() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun resetMain() { Dispatchers.resetMain() }

    private fun plan(): TripPlan {
        val text = javaClass.getResource("/planner/volan-weekend.json")!!.readText()
        val row = JSONObject(text).getJSONObject("data").getJSONObject("plan").getJSONArray("itineraries").getJSONObject(0)
        val ready = Instant.ofEpochMilli(row.getLong("startTime")).minusSeconds(60)
        return TripPlanner.parse(text, PlannerEndpoint("a","Kamaraerdő","",GeoPoint(47.4,18.9)),
            PlannerEndpoint("b","Technikum","",GeoPoint(47.48,18.81)),ready,ready)
    }
    @Test fun saturdayBusResultCardRendersWithHungarianResources() {
        val plan = plan()
        compose.setContent {
            ViaBuszTheme {
                Column(Modifier.fillMaxSize()) {
                    ItineraryCard(plan.itineraries.single(),true,plan.readyAt) {}
                }
            }
        }
        compose.onAllNodesWithText("762").assertCountEquals(1)
    }
    @Test fun openingSaturdayJourneyRendersItsTimeline() {
        val plan = plan()
        compose.setContent { ViaBuszTheme { JourneyDetail(plan,plan.itineraries.single(),plan.readyAt) {} } }
        compose.onAllNodesWithText("Az út lépései").assertCountEquals(1)
    }
    @Test fun trainMetroAndLocalAlternativesCanReplaceEachOtherOnAndroid11() {
        val text = javaClass.getResource("/planner/biatorbagy-keleti.json")!!.readText()
        val row = JSONObject(text).getJSONObject("data").getJSONObject("plan").getJSONArray("itineraries").getJSONObject(0)
        val ready = Instant.ofEpochMilli(row.getLong("startTime")).minusSeconds(120)
        val sample = plan()
        val train = TripPlanner.parse(text,sample.origin,sample.destination,ready,ready).itineraries.first()
        val shown = mutableStateOf(train)
        compose.setContent { ViaBuszTheme { ItineraryCard(shown.value,true,ready) {} } }
        compose.onAllNodesWithText("S10").assertCountEquals(1)
        compose.runOnIdle {
            val original = sample.itineraries.single()
            shown.value = original.copy(legs=original.legs.map { leg -> if(leg.walking) leg else leg.copy(
                route=leg.route!!.copy(number="288",providerId="homm",transportMode="BUS"),mode="BUS") })
        }
        compose.onAllNodesWithText("288", useUnmergedTree = true).assertCountEquals(2)
    }
    @Test fun pressingRouteSearchPublishesProgressAndRendersResultsWithoutCrashing() {
        val sample = plan()
        val backend = object : PlannerBackend {
            override suspend fun routes(from:PlannerEndpoint,to:PlannerEndpoint,ready:Instant,busesOnly:Boolean):List<TripItinerary> {
                delay(if(busesOnly) 100 else 20)
                val offset = java.time.Duration.between(sample.readyAt,ready)
                return (if(busesOnly) 4..7 else 0..3).map { index ->
                    val trip=sample.itineraries.single()
                    trip.copy(id="option-$index",start=trip.start.plus(offset),end=trip.end.plus(offset),legs=trip.legs.mapIndexed { legIndex, leg ->
                        leg.copy(start=leg.start.plus(offset),end=leg.end.plus(offset),route=leg.route?.let { route ->
                            if(legIndex==0) route.copy(id="bkk:$index",number="287-$index",
                                providerId=if(index>=4) "homm" else "bkk",transportMode=if(index>=4) null else "BUS") else route
                        })
                    })
                }
            }
            override suspend fun walking(pairs:List<Pair<PlanPoint,PlanPoint>>,ready:Instant) = WalkingResult(emptyMap())
        }
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val model = PlannerViewModel(app,backend) { error("local source unavailable") }
        model.edit(PlannerField.ORIGIN); model.choose(sample.origin)
        model.edit(PlannerField.DESTINATION); model.choose(sample.destination)
        compose.setContent { ViaBuszTheme { PlannerScreen(model) {} } }
        compose.onNodeWithText(app.getString(R.string.planner_find)).performClick()
        compose.waitUntil(10_000) { !model.state.value.planning }
        org.junit.Assert.assertTrue(model.state.value.toString(),model.state.value.result?.itineraries?.isNotEmpty() == true)
        org.junit.Assert.assertEquals(3,model.state.value.result!!.itineraries.size)
        for(index in 0..2) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("287-$index"))
            compose.onNodeWithText("287-$index").assertIsDisplayed()
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(app.getString(R.string.planner_source)))
        compose.onNodeWithText(app.getString(R.string.planner_source)).assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("287-0"))
        compose.onAllNodesWithText(app.getString(R.string.planner_view_steps))[0].performClick()
        compose.onAllNodesWithText(app.getString(R.string.planner_steps)).assertCountEquals(1)
    }
    @Test fun settingsCanScrollToTheBottomOnAndroid11() {
        val app=ApplicationProvider.getApplicationContext<android.app.Application>()
        compose.setContent { ViaBuszTheme { SettingsPage(UiState(),{}, {}, {}, {}, {}) } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(app.getString(R.string.ota_about_title)))
        compose.onNodeWithText(app.getString(R.string.ota_about_title)).assertIsDisplayed()
    }
    @Test fun crashReportCanBeInspectedAndCopiedWithoutNetworkOrDevicePermissions() {
        val app=ApplicationProvider.getApplicationContext<android.app.Application>()
        var dismissed=false
        compose.setContent { ViaBuszTheme { CrashReportDialog("IllegalStateException: test report") { dismissed=true } } }
        compose.onNodeWithText(app.getString(R.string.crash_report_copy)).performClick()
        org.junit.Assert.assertTrue(dismissed)
        org.junit.Assert.assertTrue(app.getSystemService(android.content.ClipboardManager::class.java).primaryClip!!
            .getItemAt(0).text.toString().contains("test report"))
    }
    @Test fun estimatedNumberHasASeparateLabelAndConfirmedNumbersDoNot() {
        val vehicle = Vehicle("local",GeoPoint(47.47,18.82),Instant.now(),
            routeEstimate=VehicleRouteEstimate("11","1P"))
        val shown=mutableStateOf(vehicle)
        compose.setContent { ViaBuszTheme { VehicleServiceBadge(shown.value) } }
        val app=ApplicationProvider.getApplicationContext<android.app.Application>()
        compose.onNodeWithText("1P").assertIsDisplayed()
        val label=compose.onNodeWithText(app.getString(R.string.vehicle_route_estimated))
        label.assertIsDisplayed()
        org.junit.Assert.assertTrue(label.fetchSemanticsNode().boundsInRoot.left >=
            compose.onNodeWithText("1P").fetchSemanticsNode().boundsInRoot.right)
        compose.runOnIdle { shown.value=vehicle.copy(routeNumber="2") }
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onAllNodesWithText(app.getString(R.string.vehicle_route_estimated)).assertCountEquals(0)
    }

    @Test fun anExpiredDirectionIdDoesNotSilentlySelectTheFirstOppositePattern() {
        val route=TransitRoute("bkk:1","1","A–B",emptyList(),"bkk",transportMode="BUS")
        val stops=listOf(TransitStop("a","A",GeoPoint(47.4,19.0),listOf(route.id)),
            TransitStop("b","B",GeoPoint(47.5,19.0),listOf(route.id)))
        val pattern=LinePattern("new",setOf("new"),"B",stops,emptyList())
        val state=UiState(lineDetails=mapOf(route.id to LineDetails(route,listOf(pattern))))
        val app=ApplicationProvider.getApplicationContext<android.app.Application>()
        compose.setContent { ViaBuszTheme { EmmaLineBrowser(state,route,"expired",{}, {_,_,_->}, {}) } }
        compose.onNodeWithText(app.getString(R.string.line_from,"A")).assertIsDisplayed()
    }

}
