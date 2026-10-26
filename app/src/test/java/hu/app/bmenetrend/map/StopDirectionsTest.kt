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
    @Test fun sharedOperatorColoursFadeContinuouslyAndReturnToTheFirstColour() {
        val green=StopMarkerLayout.color("viabusz",null); val yellow=StopMarkerLayout.color("volan",null)
        val palette=listOf(green,yellow)
        assertEquals(green,StopMarkerColors.at(palette,0.0))
        assertEquals(yellow,StopMarkerColors.at(palette,.5))
        assertEquals(green,StopMarkerColors.at(palette,1.0))
        val middle=StopMarkerColors.at(palette,.25)
        assertNotEquals(green,middle); assertNotEquals(yellow,middle)
        assertEquals(StopMarkerColors.at(palette,.000001),StopMarkerColors.at(palette,.999999))
        val shared=stop("shared",47.5).copy(providerIds=listOf("viabusz","kanyargo","homm","volan"))
        assertEquals(4,StopMarkerColors.palette(listOf(shared)).size)
        assertEquals(green,StopMarkerColors.at(listOf(green),.83))
    }
}
