package hu.viabusz.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import hu.viabusz.app.data.*
import hu.viabusz.app.ui.AccentColor
import hu.viabusz.app.ui.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.*

class TransitViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TransitRepository(application)
    private val preferences = application.getSharedPreferences("viabusz", 0)
    private val emmaRepository = EmmaRepository(application)
    private val stopsRepository = EmmaStopsRepository(application)
    private val linesRepository = EmmaLinesRepository(application)
    private val placesRepository = PlacesRepository(application)
    private var placeSearchJob: Job? = null
    private var placeSearchGeneration = 0
    private var lineCatalogue = emptyList<TransitRoute>()
    private var lineCatalogueFailed = false
    private val lineLoadedAt = mutableMapOf<String, Instant>()
    private val journeyClient = EmmaGraphqlClient()
    private var journeyJob: Job? = null
    private var journeyKey: Pair<String, String?>? = null
    private var journeyGeneration = 0
    private var lineJob: Job? = null
    private var lineGeneration = 0
    private val boardMutex = Mutex()
    private var board: EmmaStopsParser.Board? = null
    private var boardJob: Job? = null
    private var boardKey: Pair<String?, Int>? = null
    private var boardLoading = false
    private var boardFailure = false
    private var stopIndexFailed = false
    private var stopRoutes = emptyList<TransitRoute>()
    private val initialNetwork = repository.bundledNetwork()
    private val mutableState = MutableStateFlow(UiState(
        network = initialNetwork,
        emmaRoutes = emmaRepository.catalogue,
        selectedStopId = initialNetwork.stops.firstOrNull { it.id == "1" }?.id ?: initialNetwork.stops.firstOrNull()?.id,
        favorites = preferences.getStringSet("favorites", emptySet())?.map(SharedStops::canonicalId)?.toSet().orEmpty(),
        customAccentArgb = preferences.getInt("custom_accent_argb", 0xFF6750A4.toInt()),
        accentColor = AccentColor.fromKey(preferences.getString("accent_color", "system")),
        nationwideNetwork = preferences.getBoolean("emma_nationwide", true),
        windowMinutes = preferences.getInt("window", 60).takeIf { it in listOf(30, 60, 120) } ?: 60,
    ))
    val state: StateFlow<UiState> = mutableState
    private val refreshMutex = Mutex()
    private val emmaMutex = Mutex()
    private var live: LiveSnapshot? = null
    private var emmaLive: EmmaRepository.Result? = null
    private var emmaFetchedAt: Instant? = null
    private var manualStop = false
    private var loadedDate: LocalDate? = null
    private var lastNetworkAttempt: Instant? = null
    private val budapest = ZoneId.of("Europe/Budapest")
    private var mapError: String? = null
    private var liveError = false

    /** Owned by the activity's STARTED lifecycle; all polling stops in the background. */
    suspend fun observe() = coroutineScope {
        launch {
            while (isActive) {
                recalculate()
                delay(1_000)
            }
        }
        launch {
            while (isActive) {
                refreshEmmaData()
                delay(5_000)
            }
        }
        launch {
            while (isActive) {
                refreshLineCatalogue()
                delay(if (lineCatalogueFailed) 60_000 else 3_600_000)
            }
        }
        launch {
            while (isActive) {
                refreshStopIndex()
                delay(if (stopIndexFailed) 60_000 else 3_600_000)
            }
        }
        launch {
            while (isActive) {
                refreshBoard()
                delay(20_000)
            }
        }
        try {
            while (isActive) {
                refreshData()
                delay(if (liveError || live?.vehicleDataAvailable == false) 30_000 else 15_000)
            }
        } finally { boardJob?.cancel() }
    }

    // Resource strings stored in state must be refreshed after the system language changes.
    fun onLocaleChanged() {
        loadedDate = null
        lastNetworkAttempt = null
        mapError = null
        liveError = false
        live = null
        emmaLive = null
        emmaFetchedAt = null
        mutableState.update { it.copy(error = null, status = null, locationMessage = null) }
    }

    fun refresh() {
        viewModelScope.launch { refreshData(forceNetwork = true) }
        viewModelScope.launch { refreshEmmaData(force = true) }
        ensureBoardRequest(force = true)
        ensureVehicleJourney(force = true)
        if (!state.value.stopIndexLoaded) viewModelScope.launch { refreshStopIndex() }
    }

    private suspend fun refreshEmmaData(force: Boolean = false) {
        if (emmaMutex.isLocked) return
        emmaMutex.withLock {
            val result = emmaRepository.refresh(force)
            emmaLive = result
            emmaFetchedAt = result.fetchedAt
            recalculate()
        }
    }

    private suspend fun refreshData(forceNetwork: Boolean = false) {
        if (refreshMutex.isLocked) return
        refreshMutex.withLock {
            mutableState.update { it.copy(refreshing = true) }
            try {
                val today = LocalDate.now(budapest)
                val now = Instant.now()
                val retryNetwork = lastNetworkAttempt?.let { Duration.between(it, now).seconds >= 60 } ?: true
                if (forceNetwork || (retryNetwork && (state.value.network == null || loadedDate != today))) {
                    lastNetworkAttempt = now
                    val network = repository.loadNetwork(force = forceNetwork)
                    loadedDate = if (network.warning == null) today else null
                    mutableState.update { current ->
                        val availableStops = network.stops + current.emmaStops
                        val nearest = current.userLocation?.let { location -> nearestStop(availableStops, location) }
                        val selected = when {
                            !manualStop && nearest != null -> nearest
                            availableStops.any { it.id == current.selectedStopId } -> current.selectedStopId
                            else -> network.stops.firstOrNull { it.name.contains("Vasút", ignoreCase = true) }?.id ?: network.stops.firstOrNull()?.id
                        }
                        current.copy(network = network, selectedStopId = selected, nearestStopId = nearest, loading = false)
                    }
                }
                live = repository.refresh()
                liveError = false
                mutableState.update { it.copy(error = null, loading = false,
                    lastUpdated = if (live?.vehicleDataAvailable == true) live?.fetchedAt else it.lastUpdated) }
                ensureBoardRequest()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                liveError = true
                mutableState.update { it.copy(loading = false) }
            } finally {
                mutableState.update { it.copy(refreshing = false) }
                recalculate()
            }
        }
    }

    private fun recalculate() {
        val now = Instant.now()
        val snapshot = live?.takeIf { Duration.between(it.fetchedAt, now).seconds in 0..45 }
        mutableState.update { current ->
            val isEmmaStop = current.selectedStopId?.startsWith("emma:") == true
            val validBoard = board?.takeIf { it.stopId == current.selectedStopId && Duration.between(it.fetchedAt, now).seconds in 0..60 }
            val departures = if (isEmmaStop) EmmaStopsParser.freshDepartures(board, current.selectedStopId, now, current.windowMinutes)
                else current.network?.let { network -> current.selectedStopId?.let { stop ->
                    repository.departures(network, snapshot, stop, now, current.windowMinutes)
                }}.orEmpty()
            val emma = emmaLive?.takeIf {
                Duration.between(it.fetchedAt, now).seconds in 0..120
            }
            val emmaVehicles = emma?.vehicles.orEmpty().let {
                if (current.nationwideNetwork) it else EmmaParser.localVehicles(it, emmaRepository.routes)
            }
            val vehicles = (snapshot?.vehicles.orEmpty() + emmaVehicles).filter {
                Duration.between(it.observedAt, now).seconds in -30..180
            }
            val emmaMessage = when {
                emma?.messageResource == R.plurals.volan_positions -> null
                emma != null -> getApplication<Application>().getString(emma.messageResource)
                emmaFetchedAt == null -> getApplication<Application>().getString(R.string.volan_loading)
                else -> getApplication<Application>().getString(R.string.volan_unavailable)
            }
            val stop = current.stops.firstOrNull { it.id == current.selectedStopId }
            val stopProviders = stop?.providerIds.orEmpty()
            val hommOnly = stopProviders == listOf("homm")
            val networkWarning = stopProviders.mapNotNull { current.network?.providerWarnings?.get(it) }
                .distinct().joinToString("\n").takeIf { it.isNotBlank() }
            val liveWarning = stopProviders.mapNotNull { snapshot?.providerWarnings?.get(it) }
                .distinct().joinToString("\n").takeIf { it.isNotBlank() }
            current.copy(
                now = now,
                boardUpdatedAt = if (isEmmaStop) validBoard?.fetchedAt else null,
                error = null,
                departureBoardLoading = isEmmaStop && boardLoading,
                departureBoardUnavailable = isEmmaStop && !boardLoading && (boardFailure || validBoard == null),
                departureStatus = if (!isEmmaStop) null else when {
                    boardLoading && validBoard == null -> getApplication<Application>().getString(R.string.emma_board_loading)
                    boardFailure && validBoard != null -> getApplication<Application>().getString(R.string.emma_board_stale)
                    boardFailure || validBoard == null -> getApplication<Application>().getString(R.string.emma_board_unavailable)
                    validBoard.limitReached -> getApplication<Application>().getString(R.string.emma_board_limit)
                    else -> null
                },
                departures = current.boardingSelection?.let { filter -> departures.filter(filter::matches) } ?: departures,
                vehicles = vehicles,
                selectedVehicle = current.selectedVehicle?.let { selected -> vehicles.find { it.id == selected.id } ?: selected },
                emmaRoutes = EmmaParser.routeCatalogue(vehicles.filter { it.providerId in setOf("volan", "bkk") }, (stopRoutes + lineCatalogue + emmaRepository.catalogue).distinctBy { it.id }),
                emmaStatus = emmaMessage,
                // Route-less ETA signals must never be attached to a guessed bus route.
                liveStopMinutes = snapshot?.arrivals.orEmpty()
                    .filter { current.network?.let { network -> SharedStops.resolve(network, it.stopId) } == current.selectedStopId && it.isVerifiedRealtime }
                    .map { max(0, it.minutes - Duration.between(snapshot!!.fetchedAt, now).toMinutes().toInt()) }
                    .filter { it in 1..current.windowMinutes }.distinct().sorted(),
                status = mapError ?: if (isEmmaStop) null else networkWarning ?: if (hommOnly) null else
                    if (liveError && stopProviders.any { it in setOf("viabusz", "kanyargo") })
                        getApplication<Application>().getString(R.string.data_live_unavailable, stopProviders.filter { it in setOf("viabusz", "kanyargo") }
                            .joinToString(" / ") { if (it == "viabusz") "ViaBusz" else "KanyarGO" })
                    else liveWarning ?: if (vehicles.isNotEmpty())
                    getApplication<Application>().getString(R.string.engine_live_positions)
                else getApplication<Application>().getString(R.string.engine_no_live_positions),
            )
        }
        ensureVehicleJourney()
    }

    fun selectVehicle(id: String) {
        val vehicle = state.value.vehicles.find { it.id == id } ?: return
        mutableState.update { it.copy(selectedVehicle = vehicle, selectedPlace = null) }
        ensureVehicleJourney()
    }

    fun closeVehicle() {
        journeyGeneration++
        journeyJob?.cancel()
        journeyKey = null
        mutableState.update { it.copy(selectedVehicle = null, vehicleJourney = null, vehicleJourneyLoading = false, vehicleJourneyError = false) }
    }

    private fun ensureVehicleJourney(force: Boolean = false) {
        val vehicle = state.value.selectedVehicle ?: return
        val key = vehicle.id to vehicle.tripId
        if (!force && key == journeyKey) return
        journeyKey = key
        val generation = ++journeyGeneration
        journeyJob?.cancel()
        val tripId = vehicle.tripId?.takeIf { vehicle.providerId in setOf("volan", "bkk") }
        mutableState.update { it.copy(vehicleJourney = null, vehicleJourneyError = false, vehicleJourneyLoading = tripId != null) }
        if (tripId == null) return
        journeyJob = viewModelScope.launch {
            try {
                val text = journeyClient.execute(EmmaJourneyParser.QUERY, org.json.JSONObject().put("id", tripId))
                val journey = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { EmmaJourneyParser.parse(text, tripId) }
                if (journeyGeneration == generation && journeyKey == key) mutableState.update { current ->
                    val stops = current.emmaStops.associateBy { it.id }.toMutableMap()
                    journey.stops.forEach { stop -> if (stop.id !in stops) stops[stop.id] = stop }
                    current.copy(vehicleJourney = journey, emmaStops = stops.values.toList())
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (journeyGeneration == generation && journeyKey == key) mutableState.update { it.copy(vehicleJourneyError = true) }
            } finally {
                if (journeyGeneration == generation && journeyKey == key) mutableState.update { it.copy(vehicleJourneyLoading = false) }
            }
        }
    }

    fun setCustomAccentColor(argb: Int) {
        val opaque = argb or 0xFF000000.toInt()
        if (state.value.accentColor == AccentColor.CUSTOM && state.value.customAccentArgb == opaque) return
        preferences.edit().putInt("custom_accent_argb", opaque).putString("accent_color", AccentColor.CUSTOM.key).apply()
        mutableState.update { it.copy(accentColor = AccentColor.CUSTOM, customAccentArgb = opaque) }
    }

    fun setAccentColor(accent: AccentColor) {
        preferences.edit().putString("accent_color", accent.key).apply()
        mutableState.update { it.copy(accentColor = accent) }
    }

    fun setNationwideNetwork(enabled: Boolean) {
        if (state.value.nationwideNetwork == enabled) return
        preferences.edit().putBoolean("emma_nationwide", enabled).apply()
        mutableState.update { it.copy(nationwideNetwork = enabled) }
        recalculate()
    }

    private suspend fun refreshStopIndex() {
        try {
            val index = stopsRepository.index()
            stopIndexFailed = index.stale
            mutableState.update { current ->
                val updated = index.catalogue.stops.associateBy { it.id }.toMutableMap()
                current.emmaStops.filter { it.routeIds.isNotEmpty() }.forEach { updated[it.id] = it }
                val allStops = current.network?.stops.orEmpty() + updated.values
                val nearest = current.userLocation?.let { nearestStop(allStops, it) }
                current.copy(emmaStops = updated.values.toList(), stopIndexLoaded = true,
                    stopStatus = if (index.stale) getApplication<Application>().getString(R.string.emma_stops_saved) else null,
                    nearestStopId = nearest,
                    selectedStopId = if (!manualStop && nearest != null) nearest else current.selectedStopId)
            }
            recalculate()
            ensureBoardRequest()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            stopIndexFailed = true
            mutableState.update { it.copy(stopStatus = getApplication<Application>().getString(R.string.emma_stops_unavailable)) }
        }
    }

    fun updateMapZoom(zoom: Double) {
        mutableState.update { if (kotlin.math.abs(it.mapZoom - zoom) < 0.01) it else it.copy(mapZoom = zoom) }
    }

    private fun ensureBoardRequest(force: Boolean = false) {
        val key = state.value.selectedStopId to state.value.windowMinutes
        if (!force && key == boardKey) return
        boardKey = key
        boardJob?.cancel()
        board = null
        boardFailure = false
        boardLoading = key.first?.startsWith("emma:") == true
        recalculate()
        if (boardLoading) boardJob = viewModelScope.launch { refreshBoard(waitForCurrent = true) }
    }

    private suspend fun refreshBoard(waitForCurrent: Boolean = false) {
        if (!waitForCurrent && boardMutex.isLocked) return
        boardMutex.withLock {
            val stopId = state.value.selectedStopId?.takeIf { it.startsWith("emma:") } ?: return@withLock
            val window = state.value.windowMinutes
            boardLoading = true
            recalculate()
            try {
                val result = stopsRepository.board(stopId, window)
                if (state.value.selectedStopId == stopId && state.value.windowMinutes == window) {
                    board = result
                    boardFailure = false
                    val stop = result.catalogue.stops.single()
                    stopRoutes = (stopRoutes + result.catalogue.routes).distinctBy { it.id }
                    mutableState.update { current -> current.copy(emmaStops = current.emmaStops.map {
                        if (it.id == stop.id) stop else it
                    }) }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (state.value.selectedStopId == stopId && state.value.windowMinutes == window) boardFailure = true
            } finally {
                if (state.value.selectedStopId == stopId && state.value.windowMinutes == window) boardLoading = false
                recalculate()
            }
        }
    }

    private suspend fun refreshLineCatalogue() {
        try {
            val result = linesRepository.get()
            lineCatalogue = result.routes
            lineCatalogueFailed = result.stale
            recalculate()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { lineCatalogueFailed = true }
    }

    fun selectJourneyBoardingStop(id: String) {
        val vehicle = state.value.selectedVehicle
        val journey = state.value.vehicleJourney
        if (vehicle?.routeId == null || journey == null || journey.patternCode == null || journey.tripId != vehicle.tripId || journey.stops.none { it.id == id }) {
            selectStop(id); return
        }
        val pattern = LinePattern(journey.patternCode, setOf(journey.patternCode), journey.destination ?: vehicle.destination.orEmpty(), journey.stops, journey.geometry)
        selectStop(id)
        mutableState.update { it.copy(boardingSelection = BoardingSelection(vehicle.routeId, pattern.headsign, pattern)) }
        recalculate()
    }

    fun loadLine(id: String, force: Boolean = false) {
        if (!force && (state.value.loadingLineId == id || lineLoadedAt[id]?.let { Duration.between(it, Instant.now()).seconds in 0..599 } == true)) return
        val route = state.value.routes.find { it.id == id } ?: return
        if (route.providerId !in setOf("volan", "bkk")) return
        val generation = ++lineGeneration
        lineJob?.cancel()
        mutableState.update { it.copy(loadingLineId = id, lineErrorId = null) }
        lineJob = viewModelScope.launch {
            try {
                val text = journeyClient.execute(EmmaRouteParser.QUERY, org.json.JSONObject().put("id", id.substringAfter(":"))
                    .put("date", LocalDate.now(budapest).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)))
                val details = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { EmmaRouteParser.parse(text, id) }
                if (lineGeneration == generation) {
                    lineLoadedAt[id] = Instant.now()
                    stopRoutes = (listOf(details.route.copy(name = if (details.route.name == details.route.number) route.name else details.route.name)) + stopRoutes.filterNot { it.id == id })
                    mutableState.update { current ->
                        val stops = current.emmaStops.associateBy { it.id }.toMutableMap()
                        details.patterns.flatMap { it.stops }.forEach { stop -> if (stop.id !in stops) stops[stop.id] = stop }
                        current.copy(lineDetails = current.lineDetails + (id to details), emmaStops = stops.values.toList())
                    }
                    recalculate()
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (lineGeneration == generation) mutableState.update { it.copy(lineErrorId = id) }
            } finally {
                if (lineGeneration == generation) mutableState.update { it.copy(loadingLineId = null) }
            }
        }
    }

    fun selectBoardingStop(routeId: String, directionId: String, stopId: String) {
        val route = state.value.routes.find { it.id == routeId } ?: return
        val pattern = state.value.lineDetails[routeId]?.boardingPattern(directionId, stopId)
        val local = route.directions.find { it.id == directionId }
        if (pattern == null && local == null) return
        if (stopId !in (pattern?.stops?.map { it.id } ?: local!!.stopIds)) return
        selectStop(stopId)
        mutableState.update { it.copy(boardingSelection = BoardingSelection(routeId, pattern?.headsign ?: local!!.name,
            pattern = pattern, localDirectionId = local?.id)) }
        recalculate()
    }

    fun clearBoardingSelection() { mutableState.update { it.copy(boardingSelection = null) }; recalculate() }
    fun showWholeRoute() { mutableState.update { it.copy(fitRouteRequest = it.fitRouteRequest + 1) } }

    fun searchPlaces(query: String, enabled: Boolean) {
        val generation = ++placeSearchGeneration
        placeSearchJob?.cancel()
        val shouldSearch = enabled && query.trim().length >= 2
        mutableState.update { it.copy(placeQuery = query, placeResults = emptyList(), placesLoading = shouldSearch, placesUnavailable = false) }
        if (!shouldSearch) return
        placeSearchJob = viewModelScope.launch {
            delay(500)
            try {
                val places = placesRepository.search(query)
                if (generation == placeSearchGeneration) mutableState.update { it.copy(placeResults = places, placesLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == placeSearchGeneration) mutableState.update { it.copy(placesLoading = false, placesUnavailable = true) }
            }
        }
    }

    fun selectPlace(place: PlaceResult) {
        closeVehicle()
        manualStop = true
        mutableState.update { it.copy(selectedPlace = place, selectedStopId = null, boardingSelection = null, placeCameraRequest = it.placeCameraRequest + 1) }
        recalculate()
        ensureBoardRequest()
    }

    fun clearPlace() { mutableState.update { it.copy(selectedPlace = null) } }

    fun selectStop(id: String) {
        mutableState.update { it.copy(boardingSelection = null, selectedPlace = null) }
        closeVehicle()
        manualStop = true
        val selectedId = state.value.network?.let { SharedStops.resolve(it, id) } ?: SharedStops.canonicalId(id)
        mutableState.update { it.copy(selectedStopId = selectedId, cameraRequest = it.cameraRequest + 1) }
        recalculate()
        ensureBoardRequest()
    }

    fun recenter() {
        if (state.value.selectedStopId != null) {
            mutableState.update { it.copy(cameraRequest = it.cameraRequest + 1) }
        }
    }

    fun useNearest() {
        mutableState.update { it.copy(boardingSelection = null, selectedPlace = null) }
        closeVehicle()
        manualStop = false
        state.value.nearestStopId?.let { id -> mutableState.update { it.copy(selectedStopId = id, cameraRequest = it.cameraRequest + 1) } }
        recalculate()
        ensureBoardRequest()
    }

    fun updateLocation(point: GeoPoint) {
        mutableState.update { current ->
            val nearest = nearestStop(current.stops, point)
            current.copy(userLocation = point, nearestStopId = nearest,
                selectedStopId = if (!manualStop && nearest != null) nearest else current.selectedStopId,
                locationMessage = null)
        }
        recalculate()
        ensureBoardRequest()
    }

    fun locationMessage(message: String?) { mutableState.update { it.copy(locationMessage = message) } }
    fun mapMessage(message: String?) { mapError = message; recalculate() }
    fun setWindow(minutes: Int) {
        if (minutes !in listOf(30, 60, 120)) return
        preferences.edit().putInt("window", minutes).apply()
        mutableState.update { it.copy(windowMinutes = minutes) }
        recalculate()
        ensureBoardRequest()
    }
    fun toggleFavorite(id: String) {
        val canonicalId = SharedStops.canonicalId(id)
        val updated = state.value.favorites.toMutableSet().apply { if (!add(canonicalId)) remove(canonicalId) }.toSet()
        preferences.edit().putStringSet("favorites", updated).apply()
        mutableState.update { it.copy(favorites = updated) }
    }
}

internal fun nearestStop(network: TransitNetwork, location: GeoPoint): String? = nearestStop(network.stops, location)
internal fun nearestStop(stops: Collection<TransitStop>, location: GeoPoint): String? =
    stops.minByOrNull { distanceMeters(location, it.position) }?.id

internal fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val lat = Math.toRadians(b.lat - a.lat)
    val lon = Math.toRadians(b.lon - a.lon)
    val haversine = sin(lat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(lon / 2).pow(2)
    return 6_371_000 * 2 * atan2(sqrt(haversine.coerceIn(0.0, 1.0)), sqrt((1 - haversine).coerceIn(0.0, 1.0)))
}
