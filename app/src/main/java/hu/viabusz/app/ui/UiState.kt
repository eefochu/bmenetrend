package hu.viabusz.app.ui

import hu.viabusz.app.data.Departure
import hu.viabusz.app.data.GeoPoint
import hu.viabusz.app.data.TransitNetwork
import hu.viabusz.app.data.TransitRoute
import hu.viabusz.app.data.TransitStop
import hu.viabusz.app.data.VehicleJourney
import hu.viabusz.app.data.Vehicle
import java.time.Instant

data class UiState(
    val network: TransitNetwork? = null,
    val selectedStopId: String? = null,
    val departures: List<Departure> = emptyList(),
    val vehicles: List<Vehicle> = emptyList(),
    val selectedVehicle: Vehicle? = null,
    val vehicleJourney: VehicleJourney? = null,
    val vehicleJourneyLoading: Boolean = false,
    val vehicleJourneyError: Boolean = false,
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
    val emmaStops: List<TransitStop> = emptyList(),
    val stopIndexLoaded: Boolean = false,
    val stopStatus: String? = null,
    val departureStatus: String? = null,
    val departureBoardLoading: Boolean = false,
    val departureBoardUnavailable: Boolean = false,
    val mapZoom: Double = 13.2,
    val boardUpdatedAt: Instant? = null,
    val emmaRoutes: List<TransitRoute> = emptyList(),
    val nationwideNetwork: Boolean = true,
    val accentColor: AccentColor = AccentColor.SYSTEM,
    val customAccentArgb: Int = 0xFF6750A4.toInt(),
    val emmaStatus: String? = null,
    val cameraRequest: Int = 0,
) {
    val stops: List<TransitStop> get() = network?.stops.orEmpty() + emmaStops
    val routes: List<TransitRoute> get() = network?.routes.orEmpty() + emmaRoutes
}
