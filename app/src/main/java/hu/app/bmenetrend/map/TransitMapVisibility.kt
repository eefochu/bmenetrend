package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.RailSupport

/**
 * Zoom gates shared by both map engines. Trains and railway stations stay on the map at overview
 * zooms; other stops and vehicles appear only close in. Highlighted routes keep theirs at any zoom.
 */
object TransitMapVisibility {
    const val STOP_MIN_ZOOM = 14f
    const val RAIL_STOP_MIN_ZOOM = 9f
    const val VEHICLE_MIN_ZOOM = 15f
    const val TRAIN_VEHICLE_MIN_ZOOM = 7f
    const val OTHER_VEHICLE_MIN_ZOOM = VEHICLE_MIN_ZOOM
    /** Below this, trains use the smaller overview marker. */
    const val VEHICLE_SYMBOL_MIN_ZOOM = VEHICLE_MIN_ZOOM

    fun stopMinZoom(focused: Boolean) = if (focused) 0f else STOP_MIN_ZOOM
    fun railStopMinZoom(focused: Boolean) = if (focused) 0f else RAIL_STOP_MIN_ZOOM
    fun vehicleMinZoom(focused: Boolean) = if (focused) 0f else VEHICLE_MIN_ZOOM
    fun trainMinZoom(focused: Boolean) = if (focused) 0f else TRAIN_VEHICLE_MIN_ZOOM

    fun stop(mode: String?, zoom: Double, focused: Boolean = false) =
        zoom >= if (RailSupport.isTrain(mode)) railStopMinZoom(focused) else stopMinZoom(focused)

    fun vehicle(mode: String?, zoom: Double, focused: Boolean = false) =
        zoom >= if (RailSupport.isTrain(mode)) trainMinZoom(focused) else vehicleMinZoom(focused)

    /** Whether any stop at all can be visible: the lowest (railway) gate. */
    fun anyStop(zoom: Double, focused: Boolean = false) = zoom >= railStopMinZoom(focused)
}
