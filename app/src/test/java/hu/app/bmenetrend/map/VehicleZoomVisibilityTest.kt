package hu.app.bmenetrend.map

import org.junit.Assert.*
import org.junit.Test

class VehicleZoomVisibilityTest {
    @Test fun wideBudapestOverviewShowsTrainsButHidesOtherVehicles() {
        for (zoom in listOf(9.0,10.0,11.0,11.49)) {
            assertTrue("Trains remain visible at $zoom",TransitMapVisibility.vehicle("RAIL",zoom))
            for (mode in listOf("BUS","COACH","TRAM","TROLLEYBUS","SUBWAY"))
                assertFalse("$mode must not clutter the wide overview at $zoom",TransitMapVisibility.vehicle(mode,zoom))
        }
    }
    @Test fun closerZoomRestoresOtherVehiclesAndExplicitFocusStillWorks() {
        for (mode in listOf("BUS","TRAM","TROLLEYBUS","SUBWAY")) {
            assertTrue(TransitMapVisibility.vehicle(mode,11.5))
            assertTrue(TransitMapVisibility.vehicle(mode,12.0))
            assertTrue(TransitMapVisibility.vehicle(mode,9.0,focused=true))
        }
    }
}
