package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.MapBounds
import org.junit.Assert.*
import org.junit.Test

class StationChunksTest {
    private val bounds = MapBounds(19.0401,47.4901,19.0441,47.4941)
    @Test fun preloadBorderContainsNextStationsAndReusesWindowUntilEdge() {
        val window = StationWindow.around(bounds,13.0)
        val points = listOf(GeoPoint(47.492,19.042), GeoPoint(47.496,19.046), GeoPoint(48.0,20.0))
        val index = StationChunkIndex(points) { it }
        assertEquals(points.take(2).toSet(),index.query(window).toSet())
        assertSame(window,StationWindow.next(window,MapBounds(19.0451,47.4901,19.0491,47.4941),13.0))
        val next = StationWindow.next(window,MapBounds(19.15,47.49,19.16,47.50),13.0)
        assertNotEquals(window,next)
        assertTrue(index.query(next).isEmpty())
        assertEquals(points.take(2).toSet(),index.query(window).toSet())
    }
    @Test fun chunkTiersPreserveRestoredStationZoomThresholds() {
        for (zoom in listOf(7.0, 8.99, 9.0, 11.99, 12.0, 12.99, 14.0, 17.0)) {
            val sampled = StationWindow.around(bounds, zoom).zoom
            for (mode in listOf("BUS", "RAIL", "SUBWAY", "TRAM"))
                assertEquals(TransitMapVisibility.stop(mode, zoom), TransitMapVisibility.stop(mode, sampled))
        }
    }
    @Test fun broadAndNegativeCoordinateQueriesMatchBruteForceWithoutDuplicates() {
        val points=(0 until 30_000).map { GeoPoint(-40+(it%300)*.25,-70+(it/300)*.5) }
        val index=StationChunkIndex(points) { it }
        for (b in listOf(MapBounds(-180.0,-85.0,180.0,85.0),MapBounds(-40.1,-10.2,-36.7,-7.1))) {
            val w=StationWindow.around(b,12.0)
            val expected=points.filter { StationWindow.cell(it.lon) in w.west..w.east && StationWindow.cell(it.lat) in w.south..w.north }
            val actual=index.query(w)
            assertEquals(expected.toSet(),actual.toSet())
            assertEquals(actual.size,actual.distinct().size)
        }
    }
}
