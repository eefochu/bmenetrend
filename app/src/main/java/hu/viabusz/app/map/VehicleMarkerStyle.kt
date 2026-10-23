package hu.viabusz.app.map

import hu.viabusz.app.R
import hu.viabusz.app.data.Vehicle

/** Transport mode takes priority over route colours, which vary between individual lines. */
enum class VehicleMarkerStyle {
    VOLAN_BUS, VIABUSZ_BUS, BKK_BUS, TROLLEY, TRAM, TRAIN;
    val color: Int get() = when (this) {
        VOLAN_BUS -> 0xFFF9B900.toInt()
        VIABUSZ_BUS -> 0xFF205C48.toInt()
        BKK_BUS -> 0xFF199DCF.toInt()
        TROLLEY -> 0xFFE31B23.toInt()
        TRAM -> 0xFFFFD800.toInt()
        TRAIN -> 0xFF0066AA.toInt()
    }
    val drawableRes: Int get() = when (this) {
        VOLAN_BUS -> R.drawable.vehicle_volan_bus
        VIABUSZ_BUS -> R.drawable.vehicle_viabusz_bus
        BKK_BUS -> R.drawable.vehicle_bkk_bus
        TROLLEY -> R.drawable.vehicle_trolley
        TRAM -> R.drawable.vehicle_tram
        TRAIN -> R.drawable.vehicle_train
    }
    companion object {
        fun forVehicle(vehicle: Vehicle): VehicleMarkerStyle = forTransport(vehicle.providerId, vehicle.transportMode)
        fun forTransport(providerId: String, mode: String?): VehicleMarkerStyle = when (mode) {
            "TROLLEYBUS" -> TROLLEY
            "TRAM" -> TRAM
            "RAIL", "TRAIN", "SUBWAY", "SUBURBAN_RAILWAY", "TRAMTRAIN" -> TRAIN
            else -> when (providerId) {
                "volan" -> VOLAN_BUS
                "viabusz" -> VIABUSZ_BUS
                else -> BKK_BUS
            }
        }
    }
}
