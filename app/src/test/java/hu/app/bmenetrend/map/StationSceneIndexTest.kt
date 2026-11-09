package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.*
import org.junit.Assert.*
import org.junit.Test

class StationSceneIndexTest {
    private val region = MapBounds(19.0, 47.4, 19.1, 47.6)
    private fun stop(id: String, lat: Double, lon: Double, mode: String = "BUS") =
        TransitStop(id, "Stop $id", GeoPoint(lat, lon), emptyList(), "bkk", transportMode = mode)

    @Test fun nationalRoadCatalogueDoesNotEnterCityGroupingButRailRemainsAvailable() {
        val distant = List(100_000) { stop("far$it", 46.0 + it % 1000 * .00001, 17.0 + it / 1000 * .001) }
        val local = stop("local", 47.5, 19.05)
        val rail = stop("rail", 46.3, 20.1, "RAIL")
        val index = StationSceneIndex(distant + local + rail)
        assertEquals(setOf(local.id, rail.id), index.select(region, false).map { it.id }.toSet())
        assertEquals(listOf(rail), index.select(null, false))
    }

    @Test fun cellsRetainCollisionNeighboursBeyondTheLoadedRegion() {
        val inside = stop("inside", 47.5, 19.0999)
        val neighbour = stop("neighbour", 47.5, 19.1001)
        val far = stop("far", 47.5, 19.5)
        assertEquals(setOf("inside", "neighbour"), StationSceneIndex(listOf(inside, neighbour, far))
            .select(region, false).map { it.id }.toSet())
    }

    @Test fun lineFocusRetainsTheOriginalCatalogueAndItsPlatformGrouping() {
        val all = listOf(stop("near", 47.5, 19.05), stop("far", 46.0, 17.0))
        assertSame(all, StationSceneIndex(all).select(region, true))
    }

    @Test fun stationParentStaysHiddenEvenWhenItsChildrenAreOutsideTheRegion() {
        val parent = stop("parent", 47.5, 19.05).copy(station = true)
        val child = stop("child", 47.5, 19.5).copy(parentStationId = parent.id)
        assertTrue(StationSceneIndex(listOf(parent, child)).select(region, false).isEmpty())
    }

    @Test fun filteringBeforeGroupingPreservesVisiblePlatformGroupsColoursAndOffsets() {
        val shared = stop("BKK:A", 47.5, 19.05).copy(name = "Shared")
        val volan = shared.copy(id = "hkir:A", providerId = "volan", providerIds = listOf("volan"))
        val all = listOf(shared, volan, stop("nearby", 47.5001, 19.0501),
            stop("rail", 46.3, 20.1, "RAIL"), stop("far-road", 48.1, 21.0))
        fun build(stops: List<TransitStop>): StationMarkers.Data {
            val bounds = MapBounds(stops.minOf { it.position.lon }, stops.minOf { it.position.lat },
                stops.maxOf { it.position.lon }, stops.maxOf { it.position.lat })
            val grouped = RailStationMarkers.mergePlatforms(StopMapClustering.groups(stops, bounds, 16.0))
                .map { StationGroup(stationAnchor(it), it) }
            val scene = StationScene(grouped, StationChunkIndex(grouped) { it.anchor.position },
                StopMarkerLayout.offsets(grouped.map { it.anchor }))
            return StationMarkers.build(scene, region, false, emptySet(), emptyMap(), emptyMap(), false, 0, { it.key })
        }
        assertEquals(build(all).json, build(StationSceneIndex(all).select(region, false)).json)
    }
}
