package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.TransitStop
import org.junit.Assert.*
import org.junit.Test

class MapTapResolverTest {
    private fun vehicle(x: Double = 0.0, radius: Double = 10.0) =
        MapTapResolver.VehicleTarget("bus", x, 0.0, radius)
    private fun stop(id: String = "stop", x: Double = 0.0, radius: Double = 10.0) =
        StopHitTesting.Target(TransitStop(id, id, GeoPoint(47.5, 19.0), emptyList(), "bkk"), x, 0.0, radius)
    private fun resolve(x: Double, vehicles: List<MapTapResolver.VehicleTarget> = emptyList(),
                        stops: List<StopHitTesting.Target> = emptyList(), density: Double = 1.0) =
        MapTapResolver.resolve(x, 0.0, density, vehicles, stops)

    @Test fun tapOnVehicleBesideStopPicksVehicle() {
        assertEquals(MapTapResolver.VehicleHit("bus"), resolve(0.0, listOf(vehicle()), listOf(stop(x = 18.0))))
    }

    @Test fun tapOnStopBesideVehiclePicksStop() {
        val target = stop(x = 18.0)
        assertEquals(MapTapResolver.StopHit(target), resolve(18.0, listOf(vehicle()), listOf(target)))
    }

    @Test fun overlappingCirclesUseRelativeCentrality() {
        val target = stop(radius = 20.0)
        assertEquals(MapTapResolver.StopHit(target), resolve(5.0, listOf(vehicle()), listOf(target)))
        assertEquals(MapTapResolver.VehicleHit("bus"), resolve(5.0, listOf(vehicle(radius = 20.0)), listOf(stop())))
    }

    @Test fun exactCentreTiePicksVehicle() {
        assertEquals(MapTapResolver.VehicleHit("bus"), resolve(0.0, listOf(vehicle()), listOf(stop())))
    }

    @Test fun justOutsideSmallVehiclePicksItOverFartherStop() {
        assertEquals(MapTapResolver.VehicleHit("bus"), resolve(8.0, listOf(vehicle(radius = 4.0)), listOf(stop(x = 25.0, radius = 5.0))))
    }

    @Test fun beyondEveryReachReturnsNull() {
        assertNull(resolve(100.0, listOf(vehicle()), listOf(stop(x = 30.0))))
    }

    @Test fun minimumReachIncludesBoundaryForTinyMarkers() {
        assertEquals(MapTapResolver.VehicleHit("bus"), resolve(22.0, listOf(vehicle(radius = 1.0))))
        assertNull(resolve(22.001, listOf(vehicle(radius = 1.0))))
        val target = stop(radius = 1.0)
        assertEquals(MapTapResolver.StopHit(target), resolve(22.0, stops = listOf(target)))
    }

    @Test fun densityScalesMinimumReach() {
        assertNull(resolve(60.0, listOf(vehicle(radius = 1.0)), density = 1.0))
        assertEquals(MapTapResolver.VehicleHit("bus"), resolve(60.0, listOf(vehicle(radius = 1.0)), density = 3.0))
        assertNull(resolve(66.001, listOf(vehicle(radius = 1.0)), density = 3.0))
    }

    @Test fun densityScalesEdgeSlop() {
        assertNull(resolve(70.0, listOf(vehicle(radius = 50.0)), density = 1.0))
        assertEquals(MapTapResolver.VehicleHit("bus"), resolve(70.0, listOf(vehicle(radius = 50.0)), density = 3.0))
    }

    @Test fun equalDistanceStopsUseIdRegardlessOfInputOrder() {
        val a = stop("a", -5.0)
        val b = stop("b", 5.0)
        for (targets in listOf(listOf(b, a), listOf(a, b))) {
            assertEquals(MapTapResolver.StopHit(a), resolve(0.0, stops = targets))
            val tinyTargets = targets.map { it.copy(radius = 1.0) }
            assertEquals(MapTapResolver.StopHit(a.copy(radius = 1.0)), resolve(0.0, stops = tinyTargets))
        }
    }

    @Test fun invalidDensityThrows() {
        for (density in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { resolve(0.0, density = density) }
        }
    }

    @Test fun unequalDirectScoresDoNotUseVehicleTiePriority() {
        val target = stop(x = 4.999995)
        assertEquals(MapTapResolver.StopHit(target), resolve(0.0, listOf(vehicle(x = 5.0)), listOf(target)))
    }
}
