package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.TransitStop
import org.junit.Assert.*
import org.junit.Test

class StopMarkerLayoutTest {
    private fun node(id: String,x: Double=100.0,y: Double=100.0)=
        StopMarkerLayout.Node(TransitStop(id,"Same stop",GeoPoint(47.5,19.0),emptyList(),"bkk"),x,y)
    @Test fun crowdedPlatformsStayAtTheirExactSourcePositionsAndShrinkToDots() {
        val nodes=(0..24).map { node("platform-$it") }
        val placed=StopMarkerLayout.layout(nodes,1.0)
        assertEquals(25,placed.size)
        assertTrue(placed.all { it.x==100.0 && it.y==100.0 && it.sizePx==6.0 })
        val targets=placed.map { StopHitTesting.Target(it.stop,it.x,it.y,it.sizePx/2) }
        assertEquals(nodes.map { it.stop }.toSet(),StopHitTesting.candidates(targets,100.0,100.0,1.0).toSet())
    }
    @Test fun isolatedPlatformsUseLargeRingsWhileNearbyOppositesStaySmallAndAccurate() {
        val nodes=listOf(node("a"),node("b",112.0),node("isolated",200.0))
        val placed=StopMarkerLayout.layout(nodes,1.0,24.0).associateBy { it.stop.id }
        assertEquals(100.0,placed["a"]!!.x,0.0)
        assertEquals(112.0,placed["b"]!!.x,0.0)
        assertEquals(9.0,placed["a"]!!.sizePx,0.0)
        assertEquals(9.0,placed["b"]!!.sizePx,0.0)
        assertEquals(24.0,placed["isolated"]!!.sizePx,0.0)
    }
    @Test fun ringsGrowNaturallyAsSourceCoordinatesSpreadApartWhenZooming() {
        val near=StopMarkerLayout.layout(listOf(node("a"),node("b",112.0)),1.0,22.0)
        val zoomed=StopMarkerLayout.layout(listOf(node("a"),node("b",150.0)),1.0,22.0)
        assertTrue(near.all { it.sizePx<22 })
        assertTrue(zoomed.all { it.sizePx==22.0 })
        assertEquals(listOf(100.0,150.0),zoomed.map { it.x })
    }
    @Test fun densityScalesSizingWithoutMovingMarkersAndOrderIsStable() {
        val nodes=(0..18).map { node("stop-$it",100.0+it*9,100.0) }
        val normal=StopMarkerLayout.layout(nodes,1.0)
        val scaled=StopMarkerLayout.layout(nodes.map { it.copy(x=it.x*2.75,y=it.y*2.75) },2.75)
        normal.zip(scaled).forEach { (a,b) ->
            assertEquals(a.x*2.75,b.x,.00001)
            assertEquals(a.sizePx*2.75,b.sizePx,.00001)
        }
        assertEquals(normal,StopMarkerLayout.layout(nodes.reversed(),1.0))
    }
    @Test fun tapsChooseTheNearestDisplayedDotWithoutASelectionMenu() {
        val nodes=listOf(node("a"),node("b",112.0),node("far",170.0))
        val targets=StopMarkerLayout.layout(nodes,1.0).map { StopHitTesting.Target(it.stop,it.x,it.y,it.sizePx/2) }
        assertEquals("a",StopHitTesting.candidates(targets,100.0,100.0,1.0).first().id)
        assertEquals("b",StopHitTesting.candidates(targets,112.0,100.0,1.0).first().id)
        assertTrue(StopHitTesting.candidates(targets,250.0,250.0,1.0).isEmpty())
    }
    @Test fun minimumTouchAreaScalesWithDensity() {
        val targets=listOf(StopHitTesting.Target(node("a").stop,100.0,100.0,2.0),StopHitTesting.Target(node("b").stop,140.0,100.0,2.0))
        assertEquals(1,StopHitTesting.candidates(targets,100.0,100.0,1.0).size)
        assertEquals(2,StopHitTesting.candidates(targets,100.0,100.0,2.0).size)
    }
    @Test fun tappingOverlappingOverviewDotsZoomsIn() {
        val targets=listOf(StopHitTesting.Target(node("a").stop,100.0,100.0,3.0),StopHitTesting.Target(node("b").stop,104.0,100.0,3.0))
        assertTrue(StopHitTesting.needsZoom(targets,100.0,100.0,1.0,12.0))
    }
    @Test fun tramAndRegionalBusColoursStayTransportSpecific() {
        assertEquals(0xFFFFD800.toInt(),StopMarkerLayout.color("bkk","TRAM"))
        assertEquals(0xFFE31B23.toInt(),StopMarkerLayout.color("bkk","TROLLEYBUS"))
        assertEquals(0xFFF9B900.toInt(),StopMarkerLayout.color("volan","COACH"))
    }
    @Test fun highlightedLinesRetainFixedSmallRingsAtExactCoordinates() {
        val nodes=(0..24).map { node("p$it") }
        val placed=StopMarkerLayout.layout(nodes,2.75,16.0,highlighted=true)
        assertEquals(25,placed.size)
        assertTrue(placed.all { it.x==100.0 && it.y==100.0 && it.sizePx==44.0 })
        assertTrue(StopMarkerLayout.layout(nodes,2.75,16.0).all { it.sizePx==16.5 })
    }

    @Test fun closeZoomEnlargesAndStacksCoincidentPlatformsWithBoundedNudges() {
        val nodes=listOf(node("a"),node("b"))
        val far=StopMarkerLayout.layout(nodes,1.0,22.0,zoom=14.0)
        val close=StopMarkerLayout.layout(nodes,1.0,22.0,zoom=15.0)
        assertTrue(close.all { it.sizePx>far.first().sizePx })
        assertEquals(96.0,close.first().y,0.0)
        assertEquals(104.0,close.last().y,0.0)
        assertTrue(close.all { kotlin.math.hypot(it.x-100,it.y-100)<=4 })
        assertTrue(close.last().y-close.first().y<close.first().sizePx)
        assertEquals(close,StopMarkerLayout.layout(nodes.reversed(),1.0,22.0,zoom=15.0))
        assertTrue(far.all { it.x==100.0 && it.y==100.0 && it.sizePx==6.0 })
    }
    @Test fun closeZoomNudgesScaleWithDensityAndNeverAffectHighlightedRings() {
        val nodes=listOf(node("a"),node("b",112.0))
        val close=StopMarkerLayout.layout(nodes,1.0,22.0,zoom=16.0)
        val scaled=StopMarkerLayout.layout(nodes.map { it.copy(x=it.x*3,y=it.y*3) },3.0,22.0,zoom=16.0)
        close.zip(scaled).forEach { (a,b) -> assertEquals(a.x*3,b.x,.00001);assertEquals(a.sizePx*3,b.sizePx,.00001) }
        val highlighted=StopMarkerLayout.layout(nodes,1.0,16.0,highlighted=true,zoom=16.0)
        assertEquals(listOf(100.0,112.0),highlighted.map { it.x })
        assertTrue(highlighted.all { it.sizePx==16.0 })
        assertTrue(StopMarkerLayout.layout(nodes,1.0,8.0,zoom=16.0).all { it.sizePx<=8.0 })
    }

}
