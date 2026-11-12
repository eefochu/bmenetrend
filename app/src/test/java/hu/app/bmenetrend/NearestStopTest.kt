package hu.app.bmenetrend

import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.TransitStop
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class NearestStopTest {
    private val location = GeoPoint(47.5, 19.05)
    private fun stop(id: String, point: GeoPoint, station: Boolean = false) =
        TransitStop(id, id, point, emptyList(), station = station)

    @Test fun closestNonStationWins() {
        val stops = listOf(stop("far", GeoPoint(47.51, 19.05)), stop("station", location, true),
            stop("near", GeoPoint(47.501, 19.05)))
        assertEquals("near", nearestStop(stops, location))
        assertNull(nearestStop(stops.filter { it.station }, location))
    }

    @Test fun emptyInputReturnsNull() {
        assertNull(nearestStop(emptyList(), location))
    }

    @Test fun agreesWithHaversineAroundBudapest() {
        val random = Random(48271)
        val stops = List(500) { i -> stop("$i", GeoPoint(
            47.5 + random.nextDouble(-0.1, 0.1), 19.05 + random.nextDouble(-0.15, 0.15))) }
        repeat(30) {
            val query = GeoPoint(47.5 + random.nextDouble(-0.05, 0.05),
                19.05 + random.nextDouble(-0.08, 0.08))
            assertEquals(stops.minByOrNull { distanceMeters(it.position, query) }!!.id,
                nearestStop(stops, query))
        }
    }
}
