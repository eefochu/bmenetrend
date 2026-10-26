package hu.app.bmenetrend.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp
import org.robolectric.annotation.GraphicsMode
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.map.LineMapFocus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33],qualifiers="hu-rHU-w360dp-h800dp")
class RouteMapCardTest {
    @get:Rule val compose=createComposeRule()
    private val app get()=ApplicationProvider.getApplicationContext<android.app.Application>()
    private val route=TransitRoute("volan:hkir:762","762","Budapest–Sóskút",emptyList(),"volan",transportMode="COACH")
    private val a=TransitStop("a","Budapest, Kelenföld vasútállomás",GeoPoint(47.46,19.02),listOf(route.id),"volan")
    private val b=a.copy(id="b",name="Biatorbágy, Premier Outlet",position=GeoPoint(47.46,18.9))
    private val c=a.copy(id="c",name="Sóskút, Iskola",position=GeoPoint(47.4,18.8))
    private val out=LinePattern("out",setOf("out"),c.name,listOf(a,b,c),listOf(a.position,b.position,c.position),2,"0")
    private val inbound=LinePattern("in",setOf("in"),a.name,listOf(c,b,a),out.geometry.reversed(),2,"1")
    private fun state()=UiState(network=TransitNetwork(listOf(a,b,c),listOf(route),emptyList(),Instant.now(),"test"),
        lineDetails=mapOf(route.id to LineDetails(route,listOf(out,inbound))),loading=false)

    @Test fun selectingALineLeavesSearchAndOpensACompactCardOverTheMap() {
        var previewRoute:String?=null
        var previewDirection:String?=null
        compose.setContent { ViaBuszTheme {
            ViaBuszScreen(state(),{}, {}, {}, {}, {}, mapContent={ modifier,_,line,direction,_ ->
                SideEffect { previewRoute=line?.id; previewDirection=direction }
                Box(modifier.testTag("map")) { Text("Map stays visible") }
            })
        } }
        compose.onNodeWithText(app.getString(R.string.ui_search_short)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("762")
        compose.waitUntil(5000) { compose.onAllNodes(hasText("762") and hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("762") and hasClickAction() and !hasSetTextAction()).performClick()
        compose.onNodeWithTag("map").assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.service_interurban_bus)).assertIsDisplayed()
        compose.onNodeWithText(c.name).assertIsDisplayed()
        compose.runOnIdle { assertEquals(route.id,previewRoute); assertNull(previewDirection) }
        val close=compose.onNodeWithContentDescription(app.getString(R.string.route_close))
        assertTrue(close.fetchSemanticsNode().boundsInRoot.top > compose.onAllNodes(isRoot())[0].fetchSemanticsNode().boundsInRoot.height*.4f)
        compose.onNodeWithText(c.name).performClick()
        compose.runOnIdle { assertEquals("out",previewDirection) }
        close.performClick()
        compose.runOnIdle { assertNull(previewRoute) }
    }

    @Test fun openingAndClosingALineReplacesThePreviousBoardingHighlight() {
        val ui=mutableStateOf(state().copy(showNearby=false,selectedStopId=a.id,boardingSelection=BoardingSelection(route.id,"Previous",out)))
        var preview:String?=null
        compose.setContent { ViaBuszTheme {
            ViaBuszScreen(ui.value,{}, {}, {}, {}, {},onClearBoarding={ui.value=ui.value.copy(boardingSelection=null)},
                mapContent={ modifier,_,line,_,_ -> SideEffect { preview=line?.id }; Box(modifier) })
        } }
        compose.onNodeWithText(app.getString(R.string.ui_search_short)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("762")
        compose.waitUntil(5000) { compose.onAllNodes(hasText("762") and hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("762") and hasClickAction() and !hasSetTextAction()).performClick()
        compose.runOnIdle { assertEquals(route.id,preview); assertNull(ui.value.boardingSelection) }
        compose.onNodeWithContentDescription(app.getString(R.string.route_close)).performClick()
        compose.runOnIdle {
            assertNull(preview); assertNull(ui.value.boardingSelection)
            assertFalse(ui.value.showNearby)
            assertEquals(a.id,ui.value.selectedStopId)
        }
    }

    @Test fun clickingOutOfHighlightModeViaMapDismissesBrowsingRouteWithoutHidingSheet() {
        var previewRoute: String? = null
        compose.setContent { ViaBuszTheme {
            ViaBuszScreen(state(), {}, {}, {}, {}, {}, mapContent = { modifier, _, line, _, onDismiss ->
                SideEffect { previewRoute = line?.id }
                Box(modifier.testTag("map")) {
                    Text("Dismiss", Modifier.testTag("map_click").clickable { onDismiss() })
                }
            })
        } }
        compose.onNodeWithText(app.getString(R.string.ui_search_short)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("762")
        compose.waitUntil(5000) { compose.onAllNodes(hasText("762") and hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("762") and hasClickAction() and !hasSetTextAction()).performClick()
        compose.runOnIdle { assertEquals(route.id, previewRoute) }

        compose.onNodeWithTag("map_click").performClick()
        compose.runOnIdle {
            assertNull(previewRoute)
        }
        compose.onNodeWithText(app.getString(R.string.map_exit_route)).assertDoesNotExist()
    }

    @Test fun floatingCloseButtonOnMapClosesBrowsingRoute() {
        var previewRoute: String? = null
        compose.setContent { ViaBuszTheme {
            ViaBuszScreen(state(), {}, {}, {}, {}, {}, mapContent = { modifier, _, line, _, _ ->
                SideEffect { previewRoute = line?.id }
                Box(modifier.testTag("map"))
            })
        } }
        compose.onNodeWithText(app.getString(R.string.ui_search_short)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("762")
        compose.waitUntil(5000) { compose.onAllNodes(hasText("762") and hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("762") and hasClickAction() and !hasSetTextAction()).performClick()
        compose.runOnIdle { assertEquals(route.id, previewRoute) }

        val exitBtn = compose.onNodeWithText(app.getString(R.string.map_exit_route))
        exitBtn.assertIsDisplayed()
        exitBtn.performClick()
        compose.runOnIdle { assertNull(previewRoute) }
    }

    @Test fun lineMapFocusKeyChangesWithDirection() {
        val details = mapOf(route.id to LineDetails(route, listOf(out, inbound)))
        val focusOut = LineMapFocus.create(
            vehicle = null,
            boarding = BoardingSelection(route.id, out.headsign, pattern = out),
            routes = listOf(route),
            details = details,
            journey = null,
            allStops = listOf(a, b, c)
        )
        val focusIn = LineMapFocus.create(
            vehicle = null,
            boarding = BoardingSelection(route.id, inbound.headsign, pattern = inbound),
            routes = listOf(route),
            details = details,
            journey = null,
            allStops = listOf(a, b, c)
        )
        assertNotNull(focusOut)
        assertNotNull(focusIn)
        assertNotEquals(focusOut!!.key, focusIn!!.key)
        assertTrue(focusOut.key.contains("out"))
        assertTrue(focusIn.key.contains("in"))
    }

    @Test fun platformCardHasItsOwnModeProviderAndPlatformWithoutAMultipleStopsSuffix() {
        val tram=a.copy(id="tram-a",name="Example tér",providerId="bkk",providerIds=listOf("bkk"),transportMode="TRAM",platformCode="A")
        val opposite=tram.copy(id="tram-b",platformCode="B")
        val bus=a.copy(id="bus",name=tram.name,position=tram.position)
        val ui=state().copy(showNearby=false,selectedStopId=tram.id,network=state().network!!.copy(stops=listOf(tram,opposite,bus)))
        compose.setContent { ViaBuszTheme { ViaBuszScreen(ui,{}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Example tér").assertIsDisplayed()
        compose.onNodeWithText("több megálló",substring=true).assertDoesNotExist()
        compose.onNodeWithText("Volán",substring=true).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.emma_platform,"A"),substring=true).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.emma_mode_tram),substring=true).assertIsDisplayed()
    }

    @Test fun localAndCityServicesGetTheirOwnTypeInsteadOfRegionalLabels() {
        compose.setContent { ViaBuszTheme { Column {
            Text(serviceTypeLabel("viabusz",null))
            Text(serviceTypeLabel("bkk","BUS"))
            Text(serviceTypeLabel("bkk","TRAM"))
        } } }
        compose.onNodeWithText(app.getString(R.string.service_local_bus)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.service_city_bus)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.emma_mode_tram)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.service_interurban_bus)).assertDoesNotExist()
    }
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun compactCardRendersLongDirectionsWithoutClipping() {
        compose.setContent { ViaBuszTheme {
            Surface(Modifier.width(360.dp).height(350.dp).testTag("card")) {
                RouteBrowserCard(state(),route,null,{}, {_,_,_->}, {}, {}, {})
            }
        } }
        compose.onNodeWithText(c.name).assertIsDisplayed()
        compose.onNodeWithText(a.name).assertIsDisplayed()
        val file=java.io.File(System.getProperty("java.io.tmpdir"),"bmenetrend-route-card.png")
        compose.runOnIdle {
            val view=android.view.inspector.WindowInspector.getGlobalWindowViews().first()
            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
        println("Route card snapshot: ${file.absolutePath}")
    }

}
