package hu.viabusz.app.ui

import hu.viabusz.app.data.Departure
import hu.viabusz.app.data.GeoPoint
import hu.viabusz.app.data.TransitNetwork
import hu.viabusz.app.data.Vehicle
import java.time.Instant

data class UiState(
    val network: TransitNetwork? = null,
    val selectedStopId: String? = null,
    val departures: List<Departure> = emptyList(),
    val vehicles: List<Vehicle> = emptyList(),
    val now: Instant = Instant.now(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val status: String? = null,
    val userLocation: GeoPoint? = null,
    val nearestStopId: String? = null,
    val windowMinutes: Int = 60,
    val lastUpdated: Instant? = null,
    val locationMessage: String? = null,
    val favorites: Set<String> = emptySet(),
    val liveStopMinutes: List<Int> = emptyList(),
    val cameraRequest: Int = 0,
)
