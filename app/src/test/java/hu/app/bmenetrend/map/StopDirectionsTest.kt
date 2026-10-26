package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.*
import org.junit.Assert.*
import org.junit.Test

class StopDirectionsTest {
    private fun stop(id: String,lat: Double,lon: Double=19.0)=TransitStop(id,id,GeoPoint(lat,lon),emptyList(),"bkk")
    @Test fun arrowsFollowDepartureOrderForBusesTrolleysTramsAndRail() {
        val south=stop("south",47.49); val north=stop("north",47.51)
        for(mode in listOf("BUS","TROLLEYBUS","TRAM","SUBWAY","RAIL","SUBURBAN_RAILWAY")) {
            val route=TransitRoute(mode,mode,mode,listOf(RouteDirection("out","North",listOf(south.id,north.id)),
                RouteDirection("in","South",listOf(north.id,south.id))),"bkk",transportMode=mode)
            val headings=StopDirections.collect(listOf(route),listOf(south,north),emptyList())
            assertEquals(0.0,headings[south.id]!!.single(),.001)
            assertEquals(180.0,headings[north.id]!!.single(),.001)
        }
    }
    @Test fun shapeTangentWinsOverTheStraightLineToTheNextStop() {
        val a=stop("a",47.5); val b=stop("b",47.51)
        val route=TransitRoute("t","t","t",emptyList(),"bkk",transportMode="TROLLEYBUS")
        val pattern=LinePattern("p",setOf("p"),"North",listOf(a,b),listOf(a.position,GeoPoint(47.5,19.001),b.position))
        val headings=StopDirections.collect(emptyList(),listOf(a,b),listOf(LineDetails(route,listOf(pattern))))
        assertEquals(90.0,headings[a.id]!!.single(),.01)
        assertNull(headings[b.id]) // Arrival-only endpoint is not a departing direction.
    }
    @Test fun sameSourceUsedByBothDirectionsGetsTwoArrowsAndUnknownStopsGetNone() {
        val a=stop("a",47.5); val b=stop("b",47.51); val c=stop("c",47.49)
        val route=TransitRoute("bus","bus","bus",listOf(RouteDirection("n","North",listOf("a","b")),
            RouteDirection("s","South",listOf("a","c"))),"bkk")
        val headings=StopDirections.collect(listOf(route),listOf(a,b,c),emptyList())
        assertEquals(listOf(0.0,180.0),headings["a"])
        assertTrue(StopDirections.collect(emptyList(),listOf(a),emptyList()).isEmpty())
        val north=StopDirections.triangle(50.0,50.0,12.0,0.0,-1.0,1.0)
        assertEquals(31.0,north.first().y,.001)
        val east=StopDirections.triangle(50.0,50.0,12.0,1.0,0.0,1.0)
        assertEquals(69.0,east.first().x,.001)
    }
    @Test fun sharedOperatorColoursAppearTogetherInEqualSplitRingSectors() {
        val shared=stop("shared",47.5).copy(providerIds=listOf("viabusz","kanyargo","homm","volan"))
        val colors=StopMarkerColors.palette(listOf(shared))
        val sectors=StopMarkerColors.sectors(colors,50.0,50.0,12.0)
        assertEquals(4,sectors.size)
        assertEquals(colors,sectors.map { it.color })
        sectors.forEachIndexed { i,sector ->
            assertEquals(StopDirections.Point(50.0,50.0),sector.points.first())
            assertEquals(sector.points.first(),sector.points.last())
            sector.points.drop(1).dropLast(1).forEach { point ->
                assertEquals(12.0,kotlin.math.hypot(point.x-50,point.y-50),.00001)
            }
            val end=sector.points[sector.points.lastIndex-1]
            val next=sectors[(i+1)%sectors.size].points[1]
            assertEquals(end.x,next.x,.00001)
            assertEquals(end.y,next.y,.00001)
        }
        val two=StopMarkerColors.sectors(colors.take(2),50.0,50.0,12.0)
        assertEquals(50.0,two.first().points[1].x,.00001)
        assertEquals(38.0,two.first().points[1].y,.00001)
        assertEquals(62.0,two.first().points[two.first().points.lastIndex-1].y,.00001)
    }
}
