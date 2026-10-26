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
}
