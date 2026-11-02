package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.RailSupport

/** City overview prioritizes the metro network. Platforms and live vehicles need closer zoom. */
object TransitMapVisibility {
    const val RAIL_VEHICLE_MIN_ZOOM = 13.5f
    const val ROAD_VEHICLE_MIN_ZOOM = 14f
    const val VEHICLE_SYMBOL_MIN_ZOOM = 15f
    fun stop(mode: String?, zoom: Double, focused: Boolean = false) = focused || zoom >= if (RailSupport.isTrain(mode)) 11.5 else 14.0
    fun vehicle(mode: String?, zoom: Double, focused: Boolean = false) = focused || zoom >=
        if (RailSupport.isTrain(mode)) RAIL_VEHICLE_MIN_ZOOM else ROAD_VEHICLE_MIN_ZOOM
}
