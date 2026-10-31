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
import hu.app.bmenetrend.data.UpdateChannel
import hu.app.bmenetrend.data.OtaUpdateInfo
import hu.app.bmenetrend.data.OfflineStationMetadata
import java.io.File
import java.time.Instant

@Immutable
data class UiState(
    val offlineMode: Boolean = hu.app.bmenetrend.offline.NetworkAccessPolicy.offline.value,
    val offlinePack: hu.app.bmenetrend.offline.OfflinePackState = hu.app.bmenetrend.offline.OfflinePackState(),
    val showNearby: Boolean = true,
    val nearbyStations: List<hu.app.bmenetrend.data.NearbyStation> = emptyList(),
    val nearbyAtUser: Boolean = false,
    val nearbyLoading: Boolean = true,
    val network: TransitNetwork? = null,
    val selectedStopId: String? = null,
    val selectedPlatformIds: Set<String> = emptySet(),
    val departures: List<Departure> = emptyList(),
    val vehicles: List<Vehicle> = emptyList(),
    val selectedVehicle: Vehicle? = null,
    val trackedVehicle: Vehicle? = null,
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
    val lineCatalogueLoading: Boolean = false,
    val lineCatalogueStatus: String? = null,
    val nationwideNetwork: Boolean = true,
    val accentColor: AccentColor = AccentColor.SYSTEM,
    val customAccentArgb: Int = 0xFF6750A4.toInt(),
    val emmaStatus: String? = null,
    val cameraRequest: Int = 0,
    val locationCameraRequest: Int = 0,
    val otaChannel: UpdateChannel = UpdateChannel.STABLE,
    val otaUpdateAvailable: OtaUpdateInfo? = null,
    val otaCheckLoading: Boolean = false,
    val otaCheckError: String? = null,
    val otaLastChecked: Instant? = null,
    val otaDownloadProgress: Float? = null,
    val otaDownloadedApk: File? = null,
    val showOtaDialog: Boolean = false,
    val downloadedStationIds: Set<String> = emptySet(),
    val downloadingStationIds: Set<String> = emptySet(),
    val offlineStationTimetables: Map<String, OfflineStationMetadata> = emptyMap(),
    val isOfflineTimetableActive: Boolean = false,
) {
    private val stopCatalogue by lazy(LazyThreadSafetyMode.NONE) {
        TransitCatalogueIndexes.stops(network?.stops.orEmpty(), emmaStops)
    }
    private val routeCatalogue by lazy(LazyThreadSafetyMode.NONE) {
        TransitCatalogueIndexes.routes(network?.routes.orEmpty(), emmaRoutes)
    }
    val stops: List<TransitStop> get() = stopCatalogue.values

    val selectedPlatforms: List<TransitStop> by lazy(LazyThreadSafetyMode.NONE) {
        hu.app.bmenetrend.data.StopBoardScope.platforms(selectedStopId,selectedPlatformIds,stops)
    }
    val boardRequestKey: hu.app.bmenetrend.data.StopBoardScope.RequestKey get() =
        hu.app.bmenetrend.data.StopBoardScope.requestKey(selectedStopId,selectedPlatformIds,windowMinutes)

    val routes: List<TransitRoute> get() = routeCatalogue.values
    val routesById: Map<String, TransitRoute> get() = routeCatalogue.byId
    val stopsById: Map<String, TransitStop> get() = stopCatalogue.byId

    fun findRoute(id: String?): TransitRoute? = if (id != null) routesById[id] else null
    fun findStop(id: String?): TransitStop? = if (id != null) stopsById[id] else null
}
