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
            ViaBuszScreen(state(),{}, {}, {}, {}, {}, mapContent={ modifier,_,line,direction ->
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

    @Test fun overlappingPlatformsRemainReachableInThePickerIncludingABusAboveThem() {
        val stops=(1..25).map { a.copy(id="p$it",name="Vasútállomás",platformCode=it.toString()) }
        var chosen:String?=null
        val vehicle=Vehicle("bus",a.position,Instant.now(),routeId=route.id,providerId="volan",routeNumber="762",destination=c.name)
        compose.setContent { ViaBuszTheme { MapStopPicker(stops,vehicle,{chosen=it},{},{}) } }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(app.getString(R.string.emma_platform,"25"),substring=true))
        compose.onNodeWithText(app.getString(R.string.emma_platform,"25"),substring=true).performClick()
        compose.runOnIdle { assertEquals("p25",chosen) }
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
