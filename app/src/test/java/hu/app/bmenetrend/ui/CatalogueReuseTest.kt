package hu.app.bmenetrend.ui

import hu.app.bmenetrend.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CatalogueReuseTest {
    private fun stop(id: String, name: String = id) = TransitStop(id, name, GeoPoint(47.5, 19.0), emptyList())
    @Test fun stateCopiesReuseCatalogueAndPreserveNationalIdPrecedence() {
        val local = stop("shared", "local")
        val national = stop("shared", "national")
        val state = UiState(network = TransitNetwork(listOf(local), emptyList(), emptyList(), Instant.EPOCH, "test"), emmaStops = listOf(national))
        val copy = state.copy(now = Instant.EPOCH.plusSeconds(10), cameraRequest = 2)
        assertSame(state.stops, copy.stops)
        assertSame(state.stopsById, copy.stopsById)
        assertEquals(listOf(local, national), copy.stops)
        assertSame(national, copy.findStop("shared"))
    }
    @Test fun replacingSourcesInvalidatesOnlyChangedCatalogueAndLeavesOldSnapshotsValid() {
        val first = stop("first")
        val route = TransitRoute("r", "1", "Route", emptyList(), "bkk")
        val state = UiState(emmaStops = listOf(first), emmaRoutes = listOf(route))
        val oldMap = state.stopsById
        val replacement = state.copy(emmaStops = listOf(stop("second")))
        assertSame(state.routesById, replacement.routesById)
        assertNull(replacement.findStop("first"))
        assertNotNull(replacement.findStop("second"))
        assertSame(first, oldMap["first"])
        assertSame(first, state.findStop("first"))
    }
    @Test fun mapTapNearestMatchesOrderedCandidatesIncludingTiesAndDuplicates() {
        val random = kotlin.random.Random(12)
        repeat(50) {
            val targets = List(200) { i -> hu.app.bmenetrend.map.StopHitTesting.Target(stop("${i % 99}"), random.nextDouble(-100.0, 100.0), random.nextDouble(-100.0, 100.0), 9.0) }
            val nearest = hu.app.bmenetrend.map.StopHitTesting.nearest(targets, 0.0, 0.0, 1.0)
            assertEquals(hu.app.bmenetrend.map.StopHitTesting.candidates(targets, 0.0, 0.0, 1.0).firstOrNull(), nearest?.stop)
        }
        val ties = listOf("z", "a").map { hu.app.bmenetrend.map.StopHitTesting.Target(stop(it), 2.0, 0.0, 9.0) }
        assertEquals("a", hu.app.bmenetrend.map.StopHitTesting.nearest(ties, 0.0, 0.0, 1.0)?.stop?.id)
    }
}
