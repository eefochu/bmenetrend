package hu.app.bmenetrend.ui

import androidx.compose.runtime.Immutable
import hu.app.bmenetrend.data.Departure
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.TransitNetwork
import hu.app.bmenetrend.data.TransitRoute
import hu.app.bmenetrend.data.TransitStop
import hu.app.bmenetrend.data.LineDetails
import hu.app.bmenetrend.data.BoardingSelection
import hu.app.bmenetrend.data.VehicleJourney
import hu.app.bmenetrend.data.Vehicle
import hu.app.bmenetrend.data.PlaceResult
import java.time.Instant

@Immutable
data class UiState(
    val showNearby: Boolean = true,
    val nearbyStations: List<hu.app.bmenetrend.data.NearbyStation> = emptyList(),
    val nearbyAtUser: Boolean = false,
    val nearbyLoading: Boolean = true,
    val network: TransitNetwork? = null,
    val selectedStopId: String? = null,
    val departures: List<Departure> = emptyList(),
    val vehicles: List<Vehicle> = emptyList(),
    val selectedVehicle: Vehicle? = null,
    val selectedPlace: PlaceResult? = null,
    val placeCameraRequest: Int = 0,
    val placeResults: List<PlaceResult> = emptyList(),
    val placeQuery: String = "",
    val placesLoading: Boolean = false,
    val placesUnavailable: Boolean = false,
    val lineDetails: Map<String, LineDetails> = emptyMap(),
    val loadingLineId: String? = null,
    val lineErrorId: String? = null,
    val boardingSelection: BoardingSelection? = null,
    val fitRouteRequest: Int = 0,
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
    val favoriteStations: List<hu.app.bmenetrend.data.NearbyStation> = emptyList(),
    val favoritesLoading: Boolean = false,
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
    val locationCameraRequest: Int = 0,
) {
    val stops: List<TransitStop> by lazy(LazyThreadSafetyMode.NONE) {
        val netStops = network?.stops
        if (netStops.isNullOrEmpty()) emmaStops
        else if (emmaStops.isEmpty()) netStops
        else netStops + emmaStops
    }

    val routes: List<TransitRoute> by lazy(LazyThreadSafetyMode.NONE) {
        val netRoutes = network?.routes
        if (netRoutes.isNullOrEmpty()) emmaRoutes
        else if (emmaRoutes.isEmpty()) netRoutes
        else netRoutes + emmaRoutes
    }

    val routesById: Map<String, TransitRoute> by lazy(LazyThreadSafetyMode.NONE) {
        val map = HashMap<String, TransitRoute>((network?.routes?.size ?: 0) + emmaRoutes.size)
        network?.routes?.forEach { map[it.id] = it }
        emmaRoutes.forEach { map[it.id] = it }
        map
    }

    val stopsById: Map<String, TransitStop> by lazy(LazyThreadSafetyMode.NONE) {
        val map = HashMap<String, TransitStop>((network?.stops?.size ?: 0) + emmaStops.size)
        network?.stops?.forEach { map[it.id] = it }
        emmaStops.forEach { map[it.id] = it }
        map
    }

    fun findRoute(id: String?): TransitRoute? = if (id != null) routesById[id] else null
    fun findStop(id: String?): TransitStop? = if (id != null) stopsById[id] else null
}
