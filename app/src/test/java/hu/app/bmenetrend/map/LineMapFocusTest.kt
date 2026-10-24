package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LineMapFocusTest {
    private val route = TransitRoute("bkk:BKK:1", "1", "Tram 1", emptyList(), "bkk", transportMode = "TRAM")
    private fun stop(id: String, lat: Double) = TransitStop(id, id, GeoPoint(lat, 19.0), listOf(route.id), "bkk")
    private val a = stop("a", 47.4)
    private val b = stop("b", 47.5)
    private val c = stop("c", 47.6)
    private val outbound = LinePattern("out", setOf("out"), "North", listOf(a,b), listOf(a.position,b.position), 4, "0")
    private val inbound = LinePattern("in", setOf("in"), "South", listOf(c,a), listOf(c.position,a.position), 4, "1")
    private fun vehicle(id: String = "v", routeId: String? = route.id, provider: String = "bkk", mode: String = "TRAM") =
        Vehicle(id, a.position, Instant.EPOCH, routeId = routeId, providerId = provider, routeNumber = "1", transportMode = mode, tripId = "trip")
    private fun focus(details: LineDetails = LineDetails(route, listOf(outbound,inbound))) =
        LineMapFocus.create(vehicle(), null, listOf(route), mapOf(route.id to details), null, listOf(a,b,c))!!

    @Test fun bothDirectionsRemainSeparatePathsAndPlatformsAreIncluded() {
        val focus = focus()
        assertEquals(listOf(outbound.geometry,inbound.geometry), focus.paths)
        assertEquals(setOf(a.id,b.id,c.id), focus.stops.map { it.id }.toSet())
        assertEquals(2, focus.paths.size) // No invented connector from the first path to the second.
    }
    @Test fun vehiclesAreMatchedByLineIdentityAcrossDirectionsNotByColourOrNumber() {
        val focus = focus()
        assertTrue(focus.includes(vehicle("opposite")))
        assertFalse(focus.includes(vehicle("other-line", "bkk:BKK:2")))
        assertFalse(focus.includes(vehicle("volan", "volan:hkir:1", "volan", "BUS")))
        assertFalse(focus.includes(vehicle("unknown", null)))
    }
    @Test fun selectedTripSurvivesMissingCatalogueAndInactivePatternFiltering() {
        val journey = VehicleJourney("trip", "1", null, "South", listOf(c,a), inbound.geometry)
        val details = LineDetails(route, listOf(outbound, inbound.copy(serviceCount = 0)))
        val focus = LineMapFocus.create(vehicle(), null, listOf(route), mapOf(route.id to details), journey, emptyList())!!
        assertEquals(listOf(outbound.geometry,inbound.geometry), focus.paths)
        assertTrue(focus.stops.contains(c))
        val wrongTrip = LineMapFocus.create(vehicle(), null, listOf(route), emptyMap(), journey.copy(tripId = "other"), emptyList())!!
        assertTrue(wrongTrip.paths.isEmpty())
    }
    @Test fun boardingSelectionStillShowsBothDirectionsWithoutLeakingUnrelatedStops() {
        val unrelated = stop("unrelated",47.7).copy(routeIds = listOf("other"))
        val focus = LineMapFocus.create(null, BoardingSelection(route.id,"North",outbound), listOf(route),
            mapOf(route.id to LineDetails(route,listOf(outbound,inbound))), null, listOf(unrelated))!!
        assertEquals(2,focus.paths.size)
        assertFalse(focus.stops.contains(unrelated))
    }
    @Test fun missingRouteIdentityDoesNotShowAllUnknownVehicles() {
        val selected = vehicle(routeId = null).copy(routeNumber = null)
        val focus = LineMapFocus.create(selected,null,emptyList(),emptyMap(),null,emptyList())!!
        assertTrue(focus.includes(selected))
        assertFalse(focus.includes(selected.copy(id = "other")))
    }
    @Test fun noSelectionMeansNoFocus() {
        assertNull(LineMapFocus.create(null,null,emptyList(),emptyMap(),null,emptyList()))
    }
}
