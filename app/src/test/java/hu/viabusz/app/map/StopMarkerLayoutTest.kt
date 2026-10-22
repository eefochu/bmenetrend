package hu.viabusz.app.map

import hu.viabusz.app.data.GeoPoint
import hu.viabusz.app.data.TransitStop
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class StopMarkerLayoutTest {
    private fun node(id: String, name: String = "Budapest, Interchange M", lat: Double = 47.5,
                     x: Double = 100.0, y: Double = 100.0, parent: String? = null) =
        StopMarkerLayout.Node(TransitStop(id, name, GeoPoint(lat, 19.0), emptyList(), parentStationId = parent), x, y)

    @Test fun colocatedPlatformsStayDistinctAndSeparatelyReachable() {
        val nodes = listOf(node("bus"), node("tram", name = "Interchange"), node("metro"))
        val groups = StopMarkerLayout.layout(nodes, 1.0)
        assertEquals(1, groups.size)
        val places = groups.single().members
        assertEquals(nodes.map { it.stop.id }.toSet(), places.map { it.stop.id }.toSet())
        places.forEachIndexed { i, a -> places.drop(i + 1).forEach { b -> assertTrue(hypot(a.x - b.x, a.y - b.y) >= 38) } }
        assertEquals(nodes.map { it.stop }.toSet(), places.map { it.stop }.toSet())
    }

    @Test fun screenOverlapAloneDoesNotInventAnInterchange() {
        val groups = StopMarkerLayout.layout(listOf(node("a", name = "First", lat = 47.5), node("b", name = "Second", lat = 47.501)), 1.0)
        assertEquals(2, groups.size)
        val (a, b) = groups.flatMap { it.members }
        assertTrue(hypot(a.x - b.x, a.y - b.y) >= 38)
    }

    @Test fun matchingNamesElsewhereAndDistantPlatformsRemainIndependent() {
        val groups = StopMarkerLayout.layout(listOf(node("a"), node("b", lat = 47.51), node("c", x = 500.0)), 1.0)
        assertEquals(3, groups.size)
    }

    @Test fun crowdedInterchangesNeverHideEachOtherAndLayoutIsInputOrderIndependent() {
        val nodes = (0..31).map { node("stop-$it") } + node("other", name = "Other", lat = 47.501)
        val first = StopMarkerLayout.layout(nodes, 2.0)
        assertEquals(first, StopMarkerLayout.layout(nodes.reversed(), 2.0))
        val places = first.flatMap { it.members }
        places.forEachIndexed { i, a -> places.drop(i + 1).forEach { b -> assertTrue(hypot(a.x - b.x, a.y - b.y) >= 76 - .01) } }
        assertEquals(33, places.size)
    }

    @Test fun unoccupiedSingleStopKeepsItsOriginalPosition() {
        val placed = StopMarkerLayout.layout(listOf(node("one", x = 53.0, y = 78.0)), 1.0).single().members.single()
        assertEquals(53.0, placed.x, 0.0)
        assertEquals(78.0, placed.y, 0.0)
    }
}
