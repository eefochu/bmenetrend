package hu.app.bmenetrend.map

/** Route stops remain visible while fitting a highlighted route; ordinary stops use the zoom gate. */
object TransitMapVisibility {
    const val STOP_MIN_ZOOM = 13f
    const val VEHICLE_MIN_ZOOM = 14f
    const val TRAIN_VEHICLE_MIN_ZOOM = VEHICLE_MIN_ZOOM
    const val OTHER_VEHICLE_MIN_ZOOM = VEHICLE_MIN_ZOOM
    const val VEHICLE_SYMBOL_MIN_ZOOM = VEHICLE_MIN_ZOOM

    fun stopMinZoom(focused: Boolean) = if (focused) 0f else STOP_MIN_ZOOM

    @Suppress("UNUSED_PARAMETER")
    fun stop(mode: String?, zoom: Double, focused: Boolean = false) = zoom >= stopMinZoom(focused)

    @Suppress("UNUSED_PARAMETER")
    fun vehicle(mode: String?, zoom: Double, focused: Boolean = false) = zoom >= VEHICLE_MIN_ZOOM
}
