package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.RailSupport

/** Rail remains useful at regional zooms; marker sizing is shared with every other mode. */
object TransitMapVisibility {
    const val TRAIN_VEHICLE_MIN_ZOOM = 7f
    const val OTHER_VEHICLE_MIN_ZOOM = 11.5f
    const val VEHICLE_SYMBOL_MIN_ZOOM = 12f
    fun stop(mode: String?, zoom: Double, focused: Boolean = false) = focused || zoom >= if (RailSupport.isTrain(mode)) 9.0 else 12.0
    fun vehicle(mode: String?, zoom: Double, focused: Boolean = false) = focused || zoom >= if (RailSupport.isTrain(mode)) TRAIN_VEHICLE_MIN_ZOOM else OTHER_VEHICLE_MIN_ZOOM
}
