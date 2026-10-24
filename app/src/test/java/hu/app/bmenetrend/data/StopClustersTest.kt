package hu.app.bmenetrend.data

import org.junit.Assert.*
import org.junit.Test

class StopClustersTest {
    @Test fun distanceIsSymmetricAndPreservesNearbyPlatformSeparation() {
        val a=GeoPoint(47.5,19.0);val b=GeoPoint(47.5001,19.0)
        assertEquals(0.0,StopClusters.distanceMeters(a,a),0.0)
        assertEquals(StopClusters.distanceMeters(a,b),StopClusters.distanceMeters(b,a),.001)
        assertTrue(StopClusters.distanceMeters(a,b) in 10.0..12.0)
    }
    @Test fun sameNameAndParentDoNotMergePlatformsInSearch() {
        val a=TransitStop("a","Same station",GeoPoint(47.5,19.0),emptyList(),parentStationId="parent")
        val b=a.copy(id="b",position=GeoPoint(47.5001,19.0))
        assertEquals(setOf("a","b"),TransitSearch.indexStops(listOf(a,b)).map { it.stop.id }.toSet())
    }
}
