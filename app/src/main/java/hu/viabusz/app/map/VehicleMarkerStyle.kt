package hu.viabusz.app.map

import hu.viabusz.app.R
import hu.viabusz.app.data.Vehicle

/** Transport mode takes priority over route colours, which vary between individual lines. */
enum class VehicleMarkerStyle {
    VOLAN_BUS, BKK_BUS, TROLLEY, TRAM, TRAIN;
    val drawableRes: Int get() = when (this) {
        VOLAN_BUS -> R.drawable.vehicle_volan_bus
        BKK_BUS -> R.drawable.vehicle_bkk_bus
        TROLLEY -> R.drawable.vehicle_trolley
        TRAM -> R.drawable.vehicle_tram
        TRAIN -> R.drawable.vehicle_train
    }
    companion object {
        fun forVehicle(vehicle: Vehicle): VehicleMarkerStyle = when (vehicle.transportMode) {
            "TROLLEYBUS" -> TROLLEY
            "TRAM" -> TRAM
            "RAIL", "TRAIN", "SUBWAY", "SUBURBAN_RAILWAY", "TRAMTRAIN" -> TRAIN
            else -> if (vehicle.providerId == "volan") VOLAN_BUS else BKK_BUS
        }
    }
}
