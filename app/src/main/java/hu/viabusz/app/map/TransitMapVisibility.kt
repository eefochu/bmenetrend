package hu.viabusz.app.map

import hu.viabusz.app.data.RailSupport

/** Rail remains useful at regional zooms; marker sizing is shared with every other mode. */
object TransitMapVisibility {
    fun stop(mode: String?, zoom: Double, focused: Boolean = false) = focused || zoom >= if (RailSupport.isTrain(mode)) 9.0 else 12.0
    fun vehicle(mode: String?, zoom: Double, focused: Boolean = false) = focused || zoom >= if (RailSupport.isTrain(mode)) 7.0 else 9.0
}
