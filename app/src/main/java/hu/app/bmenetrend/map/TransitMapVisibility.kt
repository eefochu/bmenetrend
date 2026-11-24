package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.RailSupport

/**
 * Zoom gates shared by both map engines. Trains and railway stations stay on the map at overview
 * zooms; other vehicles appear together with the stops they serve, drawn smaller until
 * [VEHICLE_FULL_SIZE_ZOOM]. Highlighted routes keep theirs at any zoom and at full size.
 */
object TransitMapVisibility {
    const val STOP_MIN_ZOOM = 14f
    const val RAIL_STOP_MIN_ZOOM = 9f
    const val VEHICLE_MIN_ZOOM = STOP_MIN_ZOOM
    const val TRAIN_VEHICLE_MIN_ZOOM = 7f
    /** Below this, unfocused vehicles use the smaller overview marker. */
    const val VEHICLE_FULL_SIZE_ZOOM = 15f

    fun stopMinZoom(focused: Boolean) = if (focused) 0f else STOP_MIN_ZOOM
    fun railStopMinZoom(focused: Boolean) = if (focused) 0f else RAIL_STOP_MIN_ZOOM
    fun vehicleMinZoom(focused: Boolean) = if (focused) 0f else VEHICLE_MIN_ZOOM
    fun trainMinZoom(focused: Boolean) = if (focused) 0f else TRAIN_VEHICLE_MIN_ZOOM

    fun stop(mode: String?, zoom: Double, focused: Boolean = false) =
        zoom >= if (RailSupport.isTrain(mode)) railStopMinZoom(focused) else stopMinZoom(focused)

    fun vehicle(mode: String?, zoom: Double, focused: Boolean = false) =
        zoom >= if (RailSupport.isTrain(mode)) trainMinZoom(focused) else vehicleMinZoom(focused)

    fun vehicleOverview(zoom: Double, focused: Boolean) = !focused && zoom < VEHICLE_FULL_SIZE_ZOOM

    /** Whether any stop at all can be visible: the lowest (railway) gate. */
    fun anyStop(zoom: Double, focused: Boolean = false) = zoom >= railStopMinZoom(focused)
}
