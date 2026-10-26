package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.TransitStop
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class StopMarkerLayoutTest {
    private fun stop(id: String,lat: Double=47.5,lon: Double=19.0)=
        TransitStop(id,"Same stop",GeoPoint(lat,lon),emptyList(),"bkk")

    @Test fun closePlatformsAllKeepTheFullRingSizeInsteadOfShrinkingToDots() {
        val stops=(0..12).map { stop("platform-$it") }
        assertEquals(13,StopMarkerLayout.offsets(stops).size)
        for(zoom in listOf(15.0,15.5,16.0,18.0,19.0)) assertEquals(22.0,StopMarkerLayout.diameterDp(zoom),0.0)
    }
    @Test fun coincidentPlatformsReceiveDistinctBoundedOffsetsAndRemainDirectlyClickable() {
        val stops=(0..5).map { stop("p$it") }
        val offsets=StopMarkerLayout.offsets(stops)
        assertEquals(6,offsets.values.toSet().size)
        assertTrue(offsets.values.all { hypot(it.x,it.y)<=24.000001 })
        val targets=stops.map { stop -> offsets.getValue(stop.id).let { StopHitTesting.Target(stop,100+it.x,100+it.y,11.0) } }
        targets.forEach { target ->
            assertEquals(target.stop.id,StopHitTesting.candidates(targets,target.x,target.y,1.0).first().id)
        }
    }
    @Test fun panningFilteringOrSelectingAMarkerCannotAllocateAnotherOffset() {
        val stops=listOf(stop("a"),stop("b"),stop("c",lat=47.50015))
        val world=StopMarkerLayout.offsets(stops)
        val firstViewport=stops.take(2).associate { it.id to world.getValue(it.id) }
        val secondViewport=stops.drop(1).associate { it.id to world.getValue(it.id) }
        assertEquals(firstViewport.getValue("b"),secondViewport.getValue("b"))
        assertEquals(world,StopMarkerLayout.offsets(stops.reversed()))
        assertEquals(world,StopMarkerLayout.offsets(stops.map { it.copy(name="Selected platform") }))
        assertEquals(world,StopMarkerLayout.offsets(stops+stop("remote",lat=47.0,lon=18.0)).filterKeys { it!="remote" })
    }
    @Test fun newlyLoadedStopsCannotPushExistingMarkersAround() {
        val stops=listOf(stop("b"),stop("c"))
        val original=StopMarkerLayout.offsets(stops)
        val updated=StopMarkerLayout.offsets(stops+stop("a"),original)
        assertEquals(original.getValue("b"),updated.getValue("b"))
        assertEquals(original.getValue("c"),updated.getValue("c"))
        assertFalse(updated.getValue("a") in original.values)
        assertEquals(updated,StopMarkerLayout.offsets(stops+stop("a"),updated))
    }
    @Test fun isolatedStopsStayAtTheirActualLocationsAndKeepTheirSourceData() {
        val stops=listOf(stop("a"),stop("b",lat=47.51))
        val offsets=StopMarkerLayout.offsets(stops)
        assertTrue(offsets.values.all { it==StopMarkerLayout.Offset() })
        assertEquals(GeoPoint(47.5,19.0),stops.first().position)
    }
    @Test fun closeZoomMakesFixedScreenDisplacementMoreAccurateWithoutChangingTheOffset() {
        val offset=StopMarkerLayout.offsets(listOf(stop("a"),stop("b"))).getValue("b")
        val metersPerDp16=cos(Math.toRadians(47.5))*40075016.686/(512*2.0.pow(16))
        val meters16=hypot(offset.x,offset.y)*metersPerDp16
        val meters17=hypot(offset.x,offset.y)*metersPerDp16/2
        assertTrue(meters16<20)
        assertEquals(meters16/2,meters17,.00001)
        assertEquals(22.0,StopMarkerLayout.diameterDp(16.0),0.0)
        assertEquals(22.0,StopMarkerLayout.diameterDp(17.0),0.0)
    }
    @Test fun overviewMarkersAreUniformAndSizeChangesContinuouslyAcrossZoomBoundaries() {
        assertEquals(8.0,StopMarkerLayout.diameterDp(12.0),0.0)
        assertEquals(12.0,StopMarkerLayout.diameterDp(13.0),0.0)
        assertEquals(17.0,StopMarkerLayout.diameterDp(14.0),0.0)
        StopMarkerLayout.sizeStops.forEach { (zoom,_) ->
            assertTrue(abs(StopMarkerLayout.diameterDp(zoom-.00001)-StopMarkerLayout.diameterDp(zoom+.00001))<.001)
        }
    }
    @Test fun highlightedLinesRetainFixedSmallRingsAtEveryZoom() {
        for(zoom in listOf(9.0,12.5,15.0,18.0)) assertEquals(16.0,StopMarkerLayout.diameterDp(zoom,true),0.0)
    }
    @Test fun tappingOverlappingOverviewDotsZoomsInWhileCloseRingsSelectDirectly() {
        val targets=listOf(StopHitTesting.Target(stop("a"),100.0,100.0,3.0),StopHitTesting.Target(stop("b"),104.0,100.0,3.0))
        assertTrue(StopHitTesting.needsZoom(targets,100.0,100.0,1.0,12.0))
        assertFalse(StopHitTesting.needsZoom(targets,100.0,100.0,1.0,16.0))
    }
    @Test fun minimumTouchAreaScalesWithDensity() {
        val targets=listOf(StopHitTesting.Target(stop("a"),100.0,100.0,2.0),StopHitTesting.Target(stop("b"),140.0,100.0,2.0))
        assertEquals(1,StopHitTesting.candidates(targets,100.0,100.0,1.0).size)
        assertEquals(2,StopHitTesting.candidates(targets,100.0,100.0,2.0).size)
    }
    @Test fun tramAndRegionalBusColoursStayTransportSpecific() {
        assertEquals(0xFFFFD800.toInt(),StopMarkerLayout.color("bkk","TRAM"))
        assertEquals(0xFFE31B23.toInt(),StopMarkerLayout.color("bkk","TROLLEYBUS"))
        assertEquals(0xFFF9B900.toInt(),StopMarkerLayout.color("volan","COACH"))
    }
}
