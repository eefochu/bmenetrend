package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.TransitStop
import org.junit.Assert.*
import org.junit.Test

class RailStationMarkersTest {
    private fun rail(id:String,lat:Double=47.48,parent:String?=null)=TransitStop(id,"Biatorbágy",GeoPoint(lat,18.833),
        listOf("mav:S10"),"mav",parentStationId=parent,transportMode="RAIL")
    @Test fun biatorbagyRailPlatformsBecomeOneMarkerButBusStopsRemainSeparate() {
        val a=rail("platform-1",parent="parent")
        val b=rail("platform-2",47.4802,parent="parent")
        val c=rail("other-feed-platform",47.4803)
        val bus=a.copy(id="bus",providerId="volan",providerIds=listOf("volan"),transportMode="BUS")
        val groups=RailStationMarkers.mergePlatforms(listOf(listOf(a),listOf(bus),listOf(b),listOf(c)))
        assertEquals(2,groups.size)
        assertEquals(setOf(a.id,b.id,c.id),groups.first().map { it.id }.toSet())
        assertEquals(listOf(bus),groups.last())
    }
    @Test fun separateRailStationsDoNotMergeEvenIfFeedNamesOrParentAreReused() {
        val a=rail("a",parent="parent")
        val b=rail("b",47.50,parent="parent")
        assertEquals(2,RailStationMarkers.mergePlatforms(listOf(listOf(a),listOf(b))).size)
    }
    @Test fun otherRoadPlatformsRemainDistinctAndSharedRailParentMerges() {
        val a=rail("a").copy(name="Kelenföld",parentStationId="kelenfold",position=GeoPoint(47.46,19.02))
        val b=a.copy(id="b",position=GeoPoint(47.4602,19.02))
        assertEquals(1,RailStationMarkers.mergePlatforms(listOf(listOf(a),listOf(b))).size)
        val busA=a.copy(transportMode="BUS",providerId="bkk",providerIds=listOf("bkk"))
        val busB=busA.copy(id="bus-b")
        assertEquals(2,RailStationMarkers.mergePlatforms(listOf(listOf(busA),listOf(busB))).size)
    }
}
