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
    private fun vehicle(id: String = "v", routeId: String? = route.id, provider: String = "bkk", mode: String = "TRAM", destination: String? = null) =
        Vehicle(id, a.position, Instant.EPOCH, routeId = routeId, providerId = provider, routeNumber = "1", transportMode = mode, tripId = "trip", destination = destination)
    private fun focus(details: LineDetails = LineDetails(route, listOf(outbound,inbound))) =
        LineMapFocus.create(vehicle(destination = "North"), null, listOf(route), mapOf(route.id to details), null, listOf(a,b,c))!!

    @Test fun selectedVehicleShowsOnlyMatchingDirectionStopsAndPath() {
        val focusNorth = focus()
        assertEquals(listOf(outbound.geometry), focusNorth.paths)
        assertEquals(setOf(a.id, b.id), focusNorth.stops.map { it.id }.toSet())
        assertFalse(focusNorth.stops.any { it.id == c.id })
        assertTrue(c in focusNorth.contextStops)
        assertEquals(listOf(outbound.geometry,inbound.geometry),focusNorth.displayPaths)
        assertEquals(setOf(a,b,c),focusNorth.displayStops.toSet())

        val focusSouth = LineMapFocus.create(vehicle(destination = "South"), null, listOf(route), mapOf(route.id to LineDetails(route, listOf(outbound, inbound))), null, listOf(a,b,c))!!
        assertEquals(listOf(inbound.geometry), focusSouth.paths)
        assertEquals(setOf(c.id, a.id), focusSouth.stops.map { it.id }.toSet())
        assertFalse(focusSouth.stops.any { it.id == b.id })
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
        assertEquals(listOf(inbound.geometry), focus.paths)
        assertEquals(setOf(c.id, a.id), focus.stops.map { it.id }.toSet())
        assertFalse(focus.stops.any { it.id == b.id })
        val wrongTrip = LineMapFocus.create(vehicle(), null, listOf(route), emptyMap(), journey.copy(tripId = "other"), emptyList())!!
        assertTrue(wrongTrip.paths.isEmpty())
    }

    @Test fun selectedBoardingDirectionHighlightsOnlyItsOwnStopsAndPath() {
        val unrelated = stop("unrelated",47.7).copy(routeIds = listOf("other"))
        val focus = LineMapFocus.create(null, BoardingSelection(route.id,"North",outbound), listOf(route),
            mapOf(route.id to LineDetails(route,listOf(outbound,inbound))), null, listOf(unrelated))!!
        assertEquals(listOf(outbound.geometry),focus.paths)
        assertEquals(outbound.stops,focus.stops)
        assertFalse(focus.stops.contains(unrelated))
        assertTrue(c in focus.contextStops)
        assertEquals(listOf(outbound.geometry,inbound.geometry),focus.displayPaths)
        assertFalse(unrelated in focus.displayStops)
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

    @Test fun lineOverviewStillShowsBothDirectionsUntilOneIsChosen() {
        val focus=LineMapFocus.create(null,BoardingSelection(route.id,"Line overview"),listOf(route),
            mapOf(route.id to LineDetails(route,listOf(outbound,inbound))),null,listOf(a,b,c))!!
        assertEquals(2,focus.paths.size)
        assertEquals(setOf(a,b,c),focus.stops.toSet())
    }

    @Test fun routeStopIdsAddContextStopsAndSelectedStopsAreDeduplicatedFromContext() {
        val selected = stop("selected", 47.4)
        val routeOnly = stop("route-only", 47.8).copy(routeIds = emptyList())
        val routeWithStops = route.copy(directions = listOf(RouteDirection("d", "North", listOf(selected.id, routeOnly.id))))
        val pattern = LinePattern("p", setOf("p"), "North", listOf(selected), listOf(selected.position, GeoPoint(47.41, 19.0)), 1, "0")
        val focus = LineMapFocus.create(
            vehicle = vehicle(destination = "North"),
            boarding = null,
            routes = listOf(routeWithStops),
            details = mapOf(route.id to LineDetails(routeWithStops, listOf(pattern))),
            journey = null,
            allStops = listOf(selected, routeOnly),
        )!!
        assertTrue(focus.stops.any { it.id == selected.id })
        assertTrue(focus.contextStops.any { it.id == routeOnly.id })
        assertFalse(focus.contextStops.any { it.id == selected.id })
    }

    @Test fun lineFocusParityWithLargeIrrelevantCatalogue() {
        val routeWithDirections = route.copy(
            directions = listOf(
                RouteDirection("out", "North", listOf(a.id, b.id)),
                RouteDirection("in", "South", listOf(c.id, a.id))
            )
        )
        val smallStops = listOf(a, b, c)

        // 10,000 irrelevant stops simulating nationwide national catalogue
        val irrelevantStops = (1..10_000).map { i ->
            TransitStop(
                id = "irrelevant-$i",
                name = "Irrelevant Stop $i",
                position = GeoPoint(46.0 + (i % 100) * 0.01, 18.0 + (i / 100) * 0.01),
                routeIds = listOf("volan:other-route-$i"),
                providerId = "volan",
            )
        }
        val largeStops = smallStops + irrelevantStops

        val details = mapOf(route.id to LineDetails(routeWithDirections, listOf(outbound, inbound)))

        val focusSmall = LineMapFocus.create(
            vehicle = vehicle(destination = "North"),
            boarding = null,
            routes = listOf(routeWithDirections),
            details = details,
            journey = null,
            allStops = smallStops,
        )!!

        val focusLarge = LineMapFocus.create(
            vehicle = vehicle(destination = "North"),
            boarding = null,
            routes = listOf(routeWithDirections),
            details = details,
            journey = null,
            allStops = largeStops,
        )!!

        // Full parity checks
        assertEquals("Focus stops must match exactly", focusSmall.stops.map { it.id }, focusLarge.stops.map { it.id })
        assertEquals("Focus context stops must match exactly", focusSmall.contextStops.map { it.id }, focusLarge.contextStops.map { it.id })
        assertEquals("Paths must match", focusSmall.paths, focusLarge.paths)
        assertEquals("Display paths must match", focusSmall.displayPaths, focusLarge.displayPaths)
        assertEquals("Focus key must match", focusSmall.key, focusLarge.key)

        // Verify none of the 10,000 irrelevant stops leaked into any stop list
        val largeStopIds = focusLarge.displayStops.map { it.id }.toSet()
        assertFalse(largeStopIds.any { it.startsWith("irrelevant-") })
    }
}
