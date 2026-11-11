package hu.app.bmenetrend.map

import org.junit.Assert.*
import org.junit.Test

class VehicleZoomVisibilityTest {
    @Test fun overviewHidesAllVehiclesRegardlessOfModeOrFocusBelowZoom14() {
        val allModes = listOf("RAIL", "BUS", "COACH", "TRAM", "TROLLEYBUS", "SUBWAY")
        for (zoom in listOf(9.0, 10.0, 11.0, 12.0, 13.0, 13.99)) {
            for (mode in allModes) {
                assertFalse("Vehicle mode $mode must be hidden at zoom $zoom", TransitMapVisibility.vehicle(mode, zoom))
                assertFalse("Vehicle mode $mode must be hidden even when focused at zoom $zoom", TransitMapVisibility.vehicle(mode, zoom, focused = true))
            }
        }
    }

    @Test fun detailZoom14AndAboveShowsAllVehiclesAndZoom13ShowsStops() {
        val allModes = listOf("RAIL", "BUS", "COACH", "TRAM", "TROLLEYBUS", "SUBWAY")
        for (zoom in listOf(14.0, 14.5, 16.0)) {
            for (mode in allModes) {
                assertTrue("Vehicle mode $mode must be visible at zoom $zoom", TransitMapVisibility.vehicle(mode, zoom))
                assertTrue("Stop mode $mode must be visible at zoom $zoom", TransitMapVisibility.stop(mode, zoom))
            }
        }
        for (zoom in listOf(13.0, 13.5, 13.99)) {
            for (mode in allModes) {
                assertTrue("Stops appear one zoom level earlier at $zoom", TransitMapVisibility.stop(mode, zoom))
                assertFalse("Vehicles remain hidden at $zoom", TransitMapVisibility.vehicle(mode, zoom))
            }
        }
        for (zoom in listOf(9.0, 11.0, 12.99)) {
            for (mode in allModes) {
                assertFalse("Stops remain hidden below 13 without focus at $zoom", TransitMapVisibility.stop(mode, zoom))
                assertTrue("Focused stops remain visible below 13 at $zoom", TransitMapVisibility.stop(mode, zoom, focused = true))
            }
        }
    }

    @Test fun sharedStopMinZoom_values0Vs13() {
        assertEquals(0f, TransitMapVisibility.stopMinZoom(focused = true))
        assertEquals(13f, TransitMapVisibility.stopMinZoom(focused = false))
        assertEquals(13f, TransitMapVisibility.STOP_MIN_ZOOM)
    }

    @Test fun normalVehicleFocusGatingUnchanged_requiresZoom14RegardlessOfFocus() {
        val allModes = listOf("RAIL", "BUS", "COACH", "TRAM", "TROLLEYBUS", "SUBWAY")
        assertEquals(14f, TransitMapVisibility.VEHICLE_MIN_ZOOM)
        for (zoom in listOf(0.0, 5.0, 10.0, 12.0, 13.0, 13.99)) {
            for (mode in allModes) {
                assertFalse("Vehicle mode $mode must be hidden at zoom $zoom without focus", TransitMapVisibility.vehicle(mode, zoom, focused = false))
                assertFalse("Vehicle mode $mode must be hidden at zoom $zoom with focus", TransitMapVisibility.vehicle(mode, zoom, focused = true))
            }
        }
        for (zoom in listOf(14.0, 14.01, 15.0, 18.0)) {
            for (mode in allModes) {
                assertTrue("Vehicle mode $mode must be visible at zoom $zoom without focus", TransitMapVisibility.vehicle(mode, zoom, focused = false))
                assertTrue("Vehicle mode $mode must be visible at zoom $zoom with focus", TransitMapVisibility.vehicle(mode, zoom, focused = true))
            }
        }
    }
}
