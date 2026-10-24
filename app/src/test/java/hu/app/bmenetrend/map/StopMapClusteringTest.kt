package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.*
import org.junit.Assert.*
import org.junit.Test

class StopMapClusteringTest {
    private val bounds = MapBounds(18.0,47.0,20.0,48.0)
    private fun stop(id: String, name: String = "Budapest, Example tér", lon: Double = 19.0, parent: String? = null) =
        TransitStop(id,name,GeoPoint(47.5,lon),emptyList(),"bkk",parentStationId = parent)
    @Test fun overviewKeepsOppositePlatformsWithoutChangingTheirLocations() {
        val stops = listOf(stop("a"),stop("b",lon = 19.0005),stop("c",lon = 19.001))
        val groups = StopMapClustering.groups(stops,bounds,11.0)
        assertEquals(stops.map { listOf(it) },groups)
        assertEquals(19.0005,groups[1][0].position.lon,0.0)
    }
    @Test fun closeZoomRevealsEveryPlatform() {
        assertEquals(2,StopMapClustering.groups(listOf(stop("a"),stop("b")),bounds,14.5).size)
    }
    @Test fun sharedParentKeepsDifferentPlatformLabelsAndModesSeparate() {
        val stops = listOf(stop("a","Example bus",parent = "parent"),stop("b","Example tram",19.0005,"parent").copy(transportMode = "TRAM"))
        assertEquals(2,StopMapClustering.groups(stops,bounds,13.0).size)
    }
    @Test fun distantSameNameStopsAndNearbyUnrelatedStopsStaySeparate() {
        val stops = listOf(stop("a"),stop("b",lon = 19.01),stop("c","Other stop",19.0001))
        assertEquals(3,StopMapClustering.groups(stops,bounds,11.0).size)
    }
    @Test fun groupingIsStableAndCannotChainAcrossALongStreet() {
        val stops = (0..8).map { stop("p$it",lon = 19.0 + it * .002) }
        val groups = StopMapClustering.groups(stops,bounds,11.0)
        assertTrue(groups.size > 2)
        assertEquals(groups,StopMapClustering.groups(stops.reversed(),bounds,11.0))
        assertEquals(stops.size,groups.sumOf { it.size })
    }
    @Test fun missingBoundsAndOffscreenStopsDoNotProduceMarkers() {
        assertTrue(StopMapClustering.groups(listOf(stop("a")),null,11.0).isEmpty())
        assertTrue(StopMapClustering.groups(listOf(stop("a",lon = 30.0)),bounds,11.0).isEmpty())
    }
}
