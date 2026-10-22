package hu.viabusz.app.map

import hu.viabusz.app.data.GeoPoint
import hu.viabusz.app.data.TransitStop
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class StopMarkerLayoutTest {
    private fun node(id: String, x: Double = 100.0, y: Double = 100.0) =
        StopMarkerLayout.Node(TransitStop(id, "Same stop", GeoPoint(47.5, 19.0), emptyList(), "bkk"), x, y)

    @Test fun everyPlatformStaysIndependentEvenWithIdenticalNamesAndCoordinates() {
        val nodes = (0..24).map { node("platform-$it") }
        val placed = StopMarkerLayout.layout(nodes, 1.0)
        assertEquals(25, placed.size)
        assertEquals(nodes.map { it.stop }.toSet(), placed.map { it.stop }.toSet())
        placed.forEachIndexed { i, a -> placed.drop(i + 1).forEach { b ->
            assertTrue(hypot(a.x - b.x, a.y - b.y) >= (a.sizePx + b.sizePx) / 2)
        } }
        assertTrue(placed.all { it.sizePx == 22.0 })
    }

    @Test fun isolatedPlatformsRemainExactlyAtTheirSourceProjection() {
        val nodes = listOf(node("a", 30.0, 80.0), node("b", 300.0, 400.0))
        val placed = StopMarkerLayout.layout(nodes, 2.0)
        placed.forEachIndexed { i, p ->
            assertEquals(nodes[i].x, p.x, 0.0); assertEquals(nodes[i].y, p.y, 0.0)
            assertEquals(44.0, p.sizePx, 0.0)
        }
    }

    @Test fun nearbyPlatformsKeepNormalSizeAtEachZoomAndRemainSeparatelyTappable() {
        val nodes = listOf(node("a"), node("b", x = 112.0))
        listOf(16.0, 22.0).forEach { normalSize ->
            val placed = StopMarkerLayout.layout(nodes, 1.0, normalSize)
            assertTrue(placed.all { it.sizePx == normalSize })
            assertEquals(nodes.map { it.stop }, placed.map { it.stop })
            assertEquals(nodes.first().x, placed.first().x, 0.0)
            assertTrue(hypot(placed[0].x - placed[1].x, placed[0].y - placed[1].y) >= normalSize)
        }
    }

    @Test fun denseLayoutIsStableRegardlessOfFeedOrder() {
        val nodes = (0..18).map { node("stop-$it", x = 100.0 + it % 3, y = 100.0 + it / 3) }
        assertEquals(StopMarkerLayout.layout(nodes, 2.0), StopMarkerLayout.layout(nodes.reversed(), 2.0))
    }

    @Test fun tramStopsAreYellowAndTrolleyStopsAreRed() {
        assertEquals(0xFFFFD800.toInt(), StopMarkerLayout.color("bkk", "TRAM"))
        assertEquals(0xFFE31B23.toInt(), StopMarkerLayout.color("bkk", "TROLLEYBUS"))
        assertEquals(0xFF199DCF.toInt(), StopMarkerLayout.color("bkk", "BUS"))
        assertEquals(0xFFF9B900.toInt(), StopMarkerLayout.color("volan", "COACH"))
    }
}
