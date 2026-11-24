package hu.app.bmenetrend.map

import org.junit.Assert.*
import org.junit.Test

class VehicleZoomVisibilityTest {
    @Test fun overviewHidesUnfocusedVehiclesBelowZoom14_whileFocusedVisibleAtOverview() {
        val allModes = listOf("BUS", "COACH", "TRAM", "TROLLEYBUS", "SUBWAY")
        for (zoom in listOf(0.0, 5.0, 9.0, 10.0, 11.0, 12.0, 13.0, 13.99)) {
            for (mode in allModes) {
                assertFalse("Unfocused vehicle mode $mode must be hidden at zoom $zoom", TransitMapVisibility.vehicle(mode, zoom, focused = false))
                assertTrue("Focused vehicle mode $mode must be visible at overview zoom $zoom", TransitMapVisibility.vehicle(mode, zoom, focused = true))
            }
        }
    }

    @Test fun detailZoom14AndAboveShowsAllVehiclesAndStops() {
        val allModes = listOf("BUS", "COACH", "TRAM", "TROLLEYBUS", "SUBWAY")
        for (zoom in listOf(14.0, 14.5, 14.99, 15.0, 15.5, 16.0, 17.0)) {
            for (mode in allModes) {
                assertTrue("Vehicle mode $mode must be visible at zoom $zoom", TransitMapVisibility.vehicle(mode, zoom))
                assertTrue("Stop mode $mode must be visible at zoom $zoom", TransitMapVisibility.stop(mode, zoom))
            }
        }
        for (zoom in listOf(9.0, 11.0, 13.0, 13.99)) {
            for (mode in allModes) {
                assertFalse("Stops remain hidden below 14 without focus at $zoom", TransitMapVisibility.stop(mode, zoom))
                assertTrue("Focused stops remain visible below 14 at $zoom", TransitMapVisibility.stop(mode, zoom, focused = true))
                assertFalse("Vehicles remain hidden below 14 without focus at $zoom", TransitMapVisibility.vehicle(mode, zoom, focused = false))
                assertTrue("Focused vehicles remain visible below 14 at $zoom", TransitMapVisibility.vehicle(mode, zoom, focused = true))
            }
        }
    }

    @Test fun sharedStopMinZoom_values0Vs14() {
        assertEquals(0f, TransitMapVisibility.stopMinZoom(focused = true))
        assertEquals(14f, TransitMapVisibility.stopMinZoom(focused = false))
        assertEquals(14f, TransitMapVisibility.STOP_MIN_ZOOM)
    }

    @Test fun sharedVehicleMinZoom_values0Vs14() {
        assertEquals(0f, TransitMapVisibility.vehicleMinZoom(focused = true))
        assertEquals(14f, TransitMapVisibility.vehicleMinZoom(focused = false))
        assertEquals(14f, TransitMapVisibility.VEHICLE_MIN_ZOOM)
        assertEquals(15f, TransitMapVisibility.VEHICLE_FULL_SIZE_ZOOM)
    }

    @Test fun vehicleZoomGating_unfocusedRequiresZoom14_focusedVisibleFromZoom0() {
        val allModes = listOf("BUS", "COACH", "TRAM", "TROLLEYBUS", "SUBWAY")
        assertEquals(14f, TransitMapVisibility.VEHICLE_MIN_ZOOM)
        for (zoom in listOf(0.0, 5.0, 10.0, 12.0, 13.0, 13.99)) {
            for (mode in allModes) {
                assertFalse("Vehicle mode $mode must be hidden at zoom $zoom without focus", TransitMapVisibility.vehicle(mode, zoom, focused = false))
                assertTrue("Vehicle mode $mode must be visible at zoom $zoom with focus", TransitMapVisibility.vehicle(mode, zoom, focused = true))
            }
        }
        for (zoom in listOf(14.0, 14.5, 14.99, 15.0, 15.01, 16.0, 18.0)) {
            for (mode in allModes) {
                assertTrue("Vehicle mode $mode must be visible at zoom $zoom without focus", TransitMapVisibility.vehicle(mode, zoom, focused = false))
                assertTrue("Vehicle mode $mode must be visible at zoom $zoom with focus", TransitMapVisibility.vehicle(mode, zoom, focused = true))
            }
        }
    }

    @Test fun transitMapVisibilityZoomGatesAndOverview() {
        // Bus: visible at 14.0, hidden at 13.9 unfocused
        assertTrue("Bus must be visible at zoom 14.0 unfocused", TransitMapVisibility.vehicle("BUS", 14.0, focused = false))
        assertFalse("Bus must be hidden at zoom 13.9 unfocused", TransitMapVisibility.vehicle("BUS", 13.9, focused = false))

        // Rail: visible from 7.0 unfocused, hidden below 7.0 unfocused
        assertTrue("Rail must be visible at zoom 7.0 unfocused", TransitMapVisibility.vehicle("TRAIN", 7.0, focused = false))
        assertTrue("Rail must be visible at zoom 7.0 (RAIL) unfocused", TransitMapVisibility.vehicle("RAIL", 7.0, focused = false))
        assertFalse("Rail must be hidden at zoom 6.9 unfocused", TransitMapVisibility.vehicle("TRAIN", 6.9, focused = false))

        // vehicleOverview: true at 14.5 unfocused, false at 15.0 and false when focused
        assertTrue("vehicleOverview must be true at 14.5 unfocused", TransitMapVisibility.vehicleOverview(14.5, focused = false))
        assertFalse("vehicleOverview must be false at 15.0 unfocused", TransitMapVisibility.vehicleOverview(15.0, focused = false))
        assertFalse("vehicleOverview must be false at 14.5 when focused", TransitMapVisibility.vehicleOverview(14.5, focused = true))
        assertFalse("vehicleOverview must be false at 15.0 when focused", TransitMapVisibility.vehicleOverview(15.0, focused = true))
    }

    @Test fun modeAwareBoundariesAndFocusOverrides() {
        for (mode in listOf("RAIL", "TRAIN", "TRAMTRAIN", "BUS", "TRAM", "COACH", "TROLLEYBUS", "SUBWAY", null)) {
            val rail = mode in listOf("RAIL", "TRAIN", "TRAMTRAIN")
            for (zoom in listOf(0.0, 6.99, 7.0, 8.99, 9.0, 13.99, 14.0, 14.99, 15.0)) {
                assertEquals("$mode stop at $zoom", zoom >= if (rail) 9.0 else 14.0, TransitMapVisibility.stop(mode, zoom))
                assertEquals("$mode vehicle at $zoom", zoom >= if (rail) 7.0 else TransitMapVisibility.VEHICLE_MIN_ZOOM.toDouble(), TransitMapVisibility.vehicle(mode, zoom))
                assertTrue(TransitMapVisibility.stop(mode, zoom, true))
                assertTrue(TransitMapVisibility.vehicle(mode, zoom, true))
                assertEquals(zoom >= 9.0, TransitMapVisibility.anyStop(zoom))
                assertTrue(TransitMapVisibility.anyStop(zoom, true))
            }
        }
        assertEquals(9f, TransitMapVisibility.railStopMinZoom(false))
        assertEquals(7f, TransitMapVisibility.trainMinZoom(false))
        assertEquals(0f, TransitMapVisibility.railStopMinZoom(true))
        assertEquals(0f, TransitMapVisibility.trainMinZoom(true))
    }

    @Test fun overviewMarkerDimensionsAndArrowScales() {
        for ((selected, diameter) in listOf(false to 26f, true to 30f)) {
            assertEquals(diameter, VehicleMarkerLayout.diameterDp(selected, false), 0.0001f)
            assertEquals(diameter * 0.7f, VehicleMarkerLayout.diameterDp(selected, true), 0.0001f)
            assertEquals(diameter / 18f, VehicleMarkerLayout.arrowScale(selected, false), 0.0001f)
            assertEquals(diameter * 0.7f / 18f, VehicleMarkerLayout.arrowScale(selected, true), 0.0001f)
        }
    }
}
