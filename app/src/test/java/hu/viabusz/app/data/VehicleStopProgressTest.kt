package hu.viabusz.app.data

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class VehicleStopProgressTest {
    private val now = Instant.parse("2026-10-22T20:00:00Z")
    private val a = TransitStop("emma:BKK:A", "A", GeoPoint(47.46, 19.0), emptyList(), "bkk")
    private val b = a.copy(id = "emma:BKK:B", name = "B", position = GeoPoint(47.46, 19.01))
    private val journey = VehicleJourney("BKK:trip", "1", "A–B", "B", listOf(a, b))
    private fun vehicle() = Vehicle("v", GeoPoint(47.46, 19.005), now, providerId = "bkk", tripId = "BKK:trip",
        speedKmh = 20.0, nextStopId = b.id, nextStopName = b.name, nextStopPoint = b.position, nextStopPosition = 1)
    @Test fun reportsSourceNextAndPreviousStopInTripOrder() {
        val progress = VehicleStopProgress.resolve(vehicle(), journey, now)!!
        assertEquals(a, progress.previous)
        assertEquals(b, progress.next)
        assertNull(progress.at)
    }
    @Test fun onlyMarksAtStopWhenSlowAndWithinGpsThreshold() {
        assertEquals(b, VehicleStopProgress.resolve(vehicle().copy(position = b.position, speedKmh = 0.0), journey, now)!!.at)
        assertNull(VehicleStopProgress.resolve(vehicle().copy(position = b.position, speedKmh = 40.0), journey, now)!!.at)
        assertEquals(a, VehicleStopProgress.resolve(vehicle().copy(position = a.position, speedKmh = 0.0), journey, now)!!.at)
    }
    @Test fun stalePositionsAndUnidentifiedLoopOccurrencesDoNotInventSegments() {
        assertNull(VehicleStopProgress.resolve(vehicle().copy(observedAt = now.minusSeconds(181)), journey, now))
        val loop = journey.copy(stops = listOf(a, b, a, b))
        val progress = VehicleStopProgress.resolve(vehicle().copy(nextStopPosition = null), loop, now)!!
        assertNull(progress.previous)
        assertNull(progress.nextIndex)
    }
    @Test fun wrongTripOrMissingJourneyCanStillShowSourceNextStopButNeverGuessPrevious() {
        assertNull(VehicleStopProgress.resolve(vehicle(), journey.copy(tripId = "different"), now)!!.previous)
        assertEquals(b.name, VehicleStopProgress.resolve(vehicle(), null, now)!!.next!!.name)
    }
}
