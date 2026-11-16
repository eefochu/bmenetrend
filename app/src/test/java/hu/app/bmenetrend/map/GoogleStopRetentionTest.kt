package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.TransitStop
import org.junit.Assert.*
import org.junit.Test

class GoogleStopRetentionTest {
    private fun stop(id: String) = GoogleMarkerSpec("stop:$id", GeoPoint(47.47, 18.82), "icon",
        stops = listOf(TransitStop(id = id, name = id, position = GeoPoint(47.47, 18.82), routeIds = emptyList(), providerId = "bkk")))

    @Test fun overviewDoesNotDiscardUploadedStopsAndReturningDetailReusesTheirIds() {
        val stops = listOf(stop("a"), stop("b"))
        val overview = GoogleScene(emptyList(), null)
        val kept = GoogleStopRetention.keep(stops, null, overview)
        assertEquals(stops.map { it.id }.toSet(), kept)
        val retained = stops.filter { it.id in kept }
        assertTrue(retained.all { original -> stops.any { it === original } })
        assertTrue(GoogleStopRetention.keep(retained, null, GoogleScene(stops, null)).isEmpty())
    }

    @Test fun cacheIsBoundedAndNeverCarriesStopsAcrossRouteFocus() {
        val many = (0..GoogleStopRetention.LIMIT + 30).map { stop(it.toString()) }
        val overview = GoogleScene(emptyList(), null)
        val kept = GoogleStopRetention.keep(many, null, overview)
        assertEquals(GoogleStopRetention.LIMIT, kept.size)
        assertTrue(many.last().id in kept)
        assertFalse(many.first().id in kept)
        assertTrue(GoogleStopRetention.keep(many, "route", overview).isEmpty())
        assertTrue(GoogleStopRetention.keep(many, null, overview.copy(focusKey = "route")).isEmpty())
        assertTrue(GoogleStopRetention.keep(many, null, overview.copy(showStops = false)).isEmpty())
        val pin = GoogleMarkerSpec("place", GeoPoint(47.47, 18.82), "icon")
        assertTrue(GoogleStopRetention.keep(listOf(pin), null, overview).isEmpty())
    }
}
