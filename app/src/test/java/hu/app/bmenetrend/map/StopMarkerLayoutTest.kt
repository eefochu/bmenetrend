package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.TransitStop
import org.junit.Assert.*
import org.junit.Test

class StopMarkerLayoutTest {
    private fun node(id: String,x: Double=100.0,y: Double=100.0)=
        StopMarkerLayout.Node(TransitStop(id,"Same stop",GeoPoint(47.5,19.0),emptyList(),"bkk"),x,y)
    @Test fun overlappingPlatformsKeepTheirRealCoordinatesAndAllRemainSelectable() {
        val nodes=(0..24).map { node("platform-$it") }
        val placed=StopMarkerLayout.layout(nodes,1.0)
        assertEquals(25,placed.size)
        assertTrue(placed.all { it.x==100.0 && it.y==100.0 && it.sizePx==22.0 })
        val targets=placed.map { StopHitTesting.Target(it.stop,it.x,it.y,it.sizePx/2) }
        assertEquals(nodes.map { it.stop }.toSet(),StopHitTesting.candidates(targets,100.0,100.0,1.0).toSet())
    }
    @Test fun nearbyMarkersNeverMoveAwayFromTheRouteToAvoidACollision() {
        val nodes=listOf(node("a"),node("b",112.0))
        for(size in listOf(16.0,22.0,24.0)) {
            val placed=StopMarkerLayout.layout(nodes,2.0,size)
            assertEquals(listOf(100.0,112.0),placed.map { it.x })
            assertTrue(placed.all { it.sizePx==size*2 })
        }
    }
    @Test fun tapsIncludeAllOverlapsButNeverADistantPlatform() {
        val nodes=listOf(node("a"),node("b",112.0),node("far",170.0))
        val targets=StopMarkerLayout.layout(nodes,1.0).map { StopHitTesting.Target(it.stop,it.x,it.y,it.sizePx/2) }
        assertEquals(listOf("a","b"),StopHitTesting.candidates(targets,100.0,100.0,1.0).map { it.id })
        assertTrue(StopHitTesting.candidates(targets,250.0,250.0,1.0).isEmpty())
    }
    @Test fun minimumTouchAreaScalesWithDensityAndRetainsDistinctPlatformIds() {
        val a=node("a").stop; val b=node("b").stop
        val targets=listOf(StopHitTesting.Target(a,100.0,100.0,2.0),StopHitTesting.Target(b,140.0,100.0,2.0))
        assertEquals(1,StopHitTesting.candidates(targets,100.0,100.0,1.0).size)
        assertEquals(2,StopHitTesting.candidates(targets,100.0,100.0,2.0).size)
    }
    @Test fun layoutAndPickerAreStableAcrossFeedReordering() {
        val nodes=(0..18).map { node("stop-$it",100.0+it%3,100.0+it/3) }
        assertEquals(StopMarkerLayout.layout(nodes,2.0),StopMarkerLayout.layout(nodes.reversed(),2.0))
    }
    @Test fun tramAndRegionalBusColoursStayTransportSpecific() {
        assertEquals(0xFFFFD800.toInt(),StopMarkerLayout.color("bkk","TRAM"))
        assertEquals(0xFFE31B23.toInt(),StopMarkerLayout.color("bkk","TROLLEYBUS"))
        assertEquals(0xFFF9B900.toInt(),StopMarkerLayout.color("volan","COACH"))
    }
    @Test fun closeZoomSeparatesCoincidentPlatformsAndEveryDotSelectsDirectly() {
        val nodes=(0..24).map { node("platform-$it",180.0,260.0) }
        for(density in listOf(1.0,2.75)) {
            val scaled=nodes.map { it.copy(x=it.x*density,y=it.y*density) }
            val placed=StopMarkerLayout.layout(scaled,density,24.0,16.0)
            for((index,a) in placed.withIndex()) for(b in placed.drop(index+1)) {
                assertTrue(kotlin.math.hypot(a.x-b.x,a.y-b.y)>=42*density-.001)
            }
            val targets=placed.map { StopHitTesting.Target(it.stop,it.x,it.y,it.sizePx/2) }
            placed.forEach { point ->
                assertEquals(point.stop,StopHitTesting.candidates(targets,point.x,point.y,density).first())
                assertFalse(StopHitTesting.needsZoom(targets,point.x,point.y,density,16.0))
            }
            assertEquals(placed,StopMarkerLayout.layout(scaled.reversed(),density,24.0,16.0))
        }
    }
    @Test fun closeZoomAvoidsCoveringBusesAndKeepsDotsInsideTheVisibleMap() {
        val nodes=(0..15).map { node("p$it",35.0,125.0) }
        val bounds=StopMarkerLayout.Bounds(0.0,96.0,360.0,450.0)
        val placed=StopMarkerLayout.layout(nodes,1.0,24.0,16.0,listOf(StopMarkerLayout.Obstacle(35.0,125.0,21.0)),bounds)
        placed.forEach {
            assertTrue(bounds.contains(it.x,it.y,it.sizePx/2))
            assertTrue(kotlin.math.hypot(it.x-35,it.y-125)>=51)
        }
    }
    @Test fun tappingOverlappingOverviewRingsZoomsInButNearbyDistinctDotsSelectDirectly() {
        val targets=listOf(StopHitTesting.Target(node("a").stop,100.0,100.0,8.0),StopHitTesting.Target(node("b").stop,110.0,100.0,8.0))
        assertTrue(StopHitTesting.needsZoom(targets,100.0,100.0,1.0,12.0))
        assertFalse(StopHitTesting.needsZoom(targets,100.0,100.0,1.0,16.0))
        assertFalse(StopHitTesting.needsZoom(targets.mapIndexed { i,t -> t.copy(x=100.0+i*40) },100.0,100.0,1.0,12.0))
    }

}
