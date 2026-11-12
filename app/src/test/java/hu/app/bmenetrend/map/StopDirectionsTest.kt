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
        // Arrival-only endpoint shows the general way vehicles arrive into the station
        assertEquals(356.1,headings[b.id]!!.single(),.5)
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
    @Test fun multiDirectionStopsReduceToRepresentativeCorridorHeadings() {
        val northGroup = listOf(325.0, 2.0, 52.0)
        val northGeneral = StopDirections.generalDirections(northGroup)
        assertEquals(1, northGeneral.size)
        assertTrue(northGeneral.single() in 0.0..10.0 || northGeneral.single() > 350.0)

        val corridor = listOf(28.0, 35.0, 210.0, 225.0)
        val corridorGeneral = StopDirections.generalDirections(corridor)
        assertEquals(2, corridorGeneral.size)
        assertTrue(StopDirections.difference(corridorGeneral[0], 31.5) < 5.0)
        assertTrue(StopDirections.difference(corridorGeneral[1], 217.5) < 5.0)
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
    @Test fun mixedBkkTramAndBusPlusVolanSplitColoursAndDeduplication() {
        val tram1 = TransitRoute("t1", "4", "4", emptyList(), "bkk", transportMode = "TRAM")
        val tram2 = TransitRoute("t2", "6", "6", emptyList(), "bkk", transportMode = "TRAM")
        val bus1 = TransitRoute("b1", "7", "7", emptyList(), "bkk", transportMode = "BUS")
        val bus2 = TransitRoute("b2", "8", "8", emptyList(), "bkk", transportMode = "BUS")
        val volanBus = TransitRoute("v1", "762", "762", emptyList(), "volan", transportMode = "BUS")
        val routes = listOf(tram1, tram2, bus1, bus2, volanBus).associateBy { it.id }

        val stopAll = stop("mixed", 47.5).copy(
            routeIds = listOf("t1", "t2", "b1", "b2", "v1"),
            providerIds = listOf("bkk", "volan")
        )
        val expected = listOf(0xFF199DCF.toInt(), 0xFFF9B900.toInt(), 0xFFFFD800.toInt()).sorted()
        assertEquals(expected, StopMarkerColors.palette(listOf(stopAll), routes))

        val stopUncovered = stop("uncovered", 47.5).copy(
            routeIds = listOf("t1", "b1"),
            providerIds = listOf("bkk", "volan")
        )
        assertEquals(expected, StopMarkerColors.palette(listOf(stopUncovered), routes))
    }
    @Test fun metroPerLineAndHevColoursWithUnknownRouteFallback() {
        val m1 = TransitRoute("m1", "M1", "M1", emptyList(), "bkk", transportMode = "SUBWAY")
        val m2 = TransitRoute("m2", "2", "M2", emptyList(), "bkk", transportMode = "SUBWAY")
        val m3 = TransitRoute("m3", "M3", "M3", emptyList(), "bkk", transportMode = "SUBWAY")
        val m4 = TransitRoute("m4", "4", "M4", emptyList(), "bkk", transportMode = "SUBWAY")
        val mUnknown = TransitRoute("mX", "M9", "M9", emptyList(), "bkk", transportMode = "SUBWAY")
        val hev5 = TransitRoute("h5", "H5", "H5", emptyList(), "bkk", transportMode = "SUBURBAN_RAILWAY")
        val hev6Explicit = TransitRoute("h6", "H6", "H6", emptyList(), "hev", transportMode = "SUBURBAN_RAILWAY", color = "824B00")
        val suburbanUnknown = TransitRoute("sub", "S30", "S30", emptyList(), "mav", transportMode = "SUBURBAN_RAILWAY")
        val routes = listOf(m1, m2, m3, m4, mUnknown, hev5, hev6Explicit, suburbanUnknown).associateBy { it.id }

        assertEquals(listOf(0xFFFFD800.toInt()), StopMarkerColors.palette(listOf(stop("s1", 47.5).copy(routeIds = listOf("m1"))), routes))
        assertEquals(listOf(0xFFE41B13.toInt()), StopMarkerColors.palette(listOf(stop("s2", 47.5).copy(routeIds = listOf("m2"))), routes))
        assertEquals(listOf(0xFF075DA4.toInt()), StopMarkerColors.palette(listOf(stop("s3", 47.5).copy(routeIds = listOf("m3"))), routes))
        assertEquals(listOf(0xFF46A43B.toInt()), StopMarkerColors.palette(listOf(stop("s4", 47.5).copy(routeIds = listOf("m4"))), routes))
        assertEquals(listOf(0xFF0066AA.toInt()), StopMarkerColors.palette(listOf(stop("sX", 47.5).copy(routeIds = listOf("mX"))), routes))

        assertEquals(listOf(0xFF7B116B.toInt()), StopMarkerColors.palette(listOf(stop("sh5", 47.5).copy(routeIds = listOf("h5"))), routes))
        assertEquals(listOf(0xFF824B00.toInt()), StopMarkerColors.palette(listOf(stop("sh6", 47.5).copy(providerId = "hev", providerIds = listOf("hev"), routeIds = listOf("h6"))), routes))
        assertEquals(listOf(0xFF267D44.toInt()), StopMarkerColors.palette(listOf(stop("sub", 47.5).copy(providerId = "mav", providerIds = listOf("mav"), routeIds = listOf("sub"))), routes))

        val unmapped = stop("unmapped", 47.5).copy(routeIds = listOf("missing"), providerIds = listOf("volan"), transportMode = "BUS")
        assertEquals(listOf(0xFFF9B900.toInt()), StopMarkerColors.palette(listOf(unmapped), routes))
    }

    @Test
    fun withPlatformDirections_authoritativeBkkHeadingOverridesInferredOnSharedIdAndHandlesFallbacks() {
        val derived = mapOf(
            "emma:BKK:F1" to listOf(45.0),
            "emma:BKK:F2" to listOf(270.0),
            "emma:BKK:F4" to listOf(120.0)
        )
        val platformDirections = mapOf(
            "emma:BKK:F1" to 180.0,
            "emma:BKK:F3" to 90.0,
            "emma:BKK:F4" to Double.NaN
        )
        val merged = StopDirections.withPlatformDirections(derived, platformDirections)

        // Authoritative BKK heading overrides inferred on shared ID
        assertEquals(listOf(180.0), merged["emma:BKK:F1"])

        // Unknown keeps fallback
        assertEquals(listOf(270.0), merged["emma:BKK:F2"])

        // Valid metadata adds arrows even when no route geometry
        assertEquals(listOf(90.0), merged["emma:BKK:F3"])

        // Invalid metadata cannot erase fallback
        assertEquals(listOf(120.0), merged["emma:BKK:F4"])
    }

    @Test
    fun stationMarkers_directions_actualOutputNonEmptyForBkkPlatforms() {
        val bkkStop = TransitStop("emma:BKK:F01020", "Deák Ferenc tér", GeoPoint(47.497, 19.054), emptyList(), "bkk")
        val platformDirections = mapOf("emma:BKK:F01020" to 182.0)
        val bearings = StopDirections.withPlatformDirections(emptyMap(), platformDirections)

        val directions = StationMarkers.directions(listOf(bkkStop), bearings)
        assertFalse("StationMarkers.directions must be non-empty for BKK platforms with direction metadata", directions.isEmpty())
        assertEquals(listOf(180), directions)
    }
}
