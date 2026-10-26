package hu.app.bmenetrend

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.ui.AccentColor
import hu.app.bmenetrend.ui.UiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import android.content.Context
import hu.app.bmenetrend.BuildConfig
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.*

class TransitViewModel(application: Application) : AndroidViewModel(application) {
    private val otaRepository = OtaRepository(application)
    private var otaJob: Job? = null
    private var otaDownloadJob: Job? = null
    private var otaDownloadedUrl: String? = null
    private val repository = TransitRepository(application)
    private val routeEstimator by lazy {
        ViabuszRouteEstimator(RouteRoads.decode(application.assets.open("viabusz-route-geometry.json").bufferedReader().use { it.readText() }))
    }
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
    private var boardKey: StopBoardScope.RequestKey? = null
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
        windowMinutes = preferences.getInt("window", 120).takeIf { it in DepartureTime.windows } ?: 120,
        otaChannel = UpdateChannel.fromKey(preferences.getString("ota_channel", "stable")),
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
    private var nearbyActive = false
    private var nearbyJob: Job? = null
    private var nearbyGeneration = 0
    private var mapSettledJob: Job? = null
    private var lastSettledCenter: GeoPoint? = null
    private var mapCenter = GeoPoint(47.473, 18.825)
    private var mapBounds: MapBounds? = null
    private var nearbyKey = emptyList<String>()
    private var nearbySelection = emptyList<NearbyStation>()
    private val nearbyBoards = linkedMapOf<String, EmmaStopsParser.Board>()
    private var nearbyFailed = emptySet<String>()
    private var nearbyWindow = 60
    private var nearbyLoading = false
    private var mapError: String? = null
    private var mapVisible = true
    private var favoritesVisible = false
    private var visibleFavorites = emptySet<String>()
    private var favoriteSelection = emptyList<NearbyStation>()
    private var favoriteNetworkStops: List<TransitStop>? = null
    private var favoriteEmmaStops: List<TransitStop>? = null
    private var favoriteSelectionIds = emptySet<String>()
    private var favoriteJob: Job? = null
    private var favoriteGeneration = 0
    private var favoriteKey = emptyList<String>()
    private var favoriteWindow = 0
    private var favoriteLoading = false
    private var favoriteFailures = emptySet<String>()
    private val favoriteBoards = linkedMapOf<String, EmmaStopsParser.Board>()
    private var liveError = false

    init {
        checkForUpdates(isAutoCheck = true)
    }

    /** Owned by the activity's STARTED lifecycle; all polling stops in the background. */
    suspend fun observe() = coroutineScope {
        nearbyActive = true
        if (favoritesVisible) ensureFavoriteRequest(force = true)
        launch {
            while (isActive) {
                if (favoritesVisible) ensureFavoriteRequest(force = true)
                delay(20_000)
            }
        }
        launch {
            while (isActive) {
                if (mapVisible) ensureNearbyRequest(force = true)
                delay(30_000)
            }
        }
        launch {
            while (isActive) {
                if (mapVisible || favoritesVisible) recalculate()
                delay(5_000)
            }
        }
        launch {
            while (isActive) {
                if (mapVisible) refreshEmmaData()
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
                if (mapVisible) refreshBoard()
                delay(20_000)
            }
        }
        try {
            while (isActive) {
                if (mapVisible || favoritesVisible) refreshData()
                delay(if (liveError || live?.vehicleDataAvailable == false) 30_000 else 15_000)
            }
        } finally { nearbyActive = false; boardJob?.cancel(); nearbyJob?.cancel(); favoriteJob?.cancel(); mapSettledJob?.cancel(); nearbyLoading = false; favoriteLoading = false }
    }

    fun setMapVisible(visible: Boolean) {
        if (mapVisible == visible) return
        mapVisible = visible
        if (visible) {
            recalculate()
            viewModelScope.launch { refreshEmmaData() }
            viewModelScope.launch { refreshData() }
            ensureBoardRequest(force = true)
            ensureNearbyRequest()
        } else { mapSettledJob?.cancel(); nearbyJob?.cancel(); boardJob?.cancel() }
    }

    fun setFavoritesVisible(visible: Boolean) {
        favoritesVisible = visible
        if (visible) { recalculate(); ensureFavoriteRequest() }
        else { favoriteGeneration++; favoriteJob?.cancel(); favoriteLoading = false }
    }

    fun setVisibleFavorites(ids: Set<String>) {
        if (ids == visibleFavorites) return
        visibleFavorites = ids
        ensureFavoriteRequest()
    }

    fun refreshFavorites() {
        ensureFavoriteRequest(force = true)
        if (!state.value.stopIndexLoaded) viewModelScope.launch { refreshStopIndex() }
    }

    private fun favoriteStations(current: UiState): List<NearbyStation> {
        if (favoriteNetworkStops !== current.network?.stops || favoriteEmmaStops !== current.emmaStops || favoriteSelectionIds != current.favorites) {
            favoriteNetworkStops = current.network?.stops
            favoriteEmmaStops = current.emmaStops
            favoriteSelectionIds = current.favorites
            favoriteSelection = FavoriteDepartures.stations(current.favorites, current.stops)
        }
        return favoriteSelection
    }

    private suspend fun fetchBoards(stops: List<TransitStop>, window: Int, cache: Map<String, EmmaStopsParser.Board>, force: Boolean,
                                    semaphore: kotlinx.coroutines.sync.Semaphore): List<Pair<String, EmmaStopsParser.Board?>> = coroutineScope {
        val ids = stops.filter { it.id.startsWith("emma:") }.map { it.id }.distinct()
        val saved = ids.mapNotNull { id -> cache[id]?.takeIf { !force && Duration.between(it.fetchedAt, Instant.now()).seconds in 0..29 }?.let { id to it } }.toMap()
        val fetched = ids.filterNot { it in saved }.chunked(6).map { batch -> async {
            semaphore.acquire()
            try { stopsRepository.boards(batch, window).toList() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (BuildConfig.DEBUG) android.util.Log.w("Emma", "Station board batch failed: ${batch.joinToString()}", error)
                batch.map { it to null }
            } finally { semaphore.release() }
        } }.awaitAll().flatten()
        saved.toList() + fetched
    }

    private fun ensureFavoriteRequest(force: Boolean = false) {
        if (!favoritesVisible || !nearbyActive) return
        val current = state.value
        val stations = favoriteStations(current).let { all ->
            all.filter { it.stop.id in visibleFavorites }.ifEmpty { all.take(3) }.take(8)
        }
        val window = current.windowMinutes
        val key = stations.flatMap { it.platforms }.map { it.id }.sorted() + window.toString()
        if (!force && key == favoriteKey && favoriteJob?.isCancelled != true) return
        if (favoriteWindow != window) { favoriteBoards.clear(); favoriteWindow = window }
        if (nearbyWindow == window) nearbyBoards.forEach { (id, board) ->
            if (Duration.between(board.fetchedAt, Instant.now()).seconds in 0..29) favoriteBoards[id] = board
        }
        favoriteKey = key
        val generation = ++favoriteGeneration
        favoriteJob?.cancel()
        favoriteLoading = stations.isNotEmpty()
        favoriteFailures = emptySet()
        recalculate()
        favoriteJob = viewModelScope.launch {
            val semaphore = kotlinx.coroutines.sync.Semaphore(3)
            try {
                coroutineScope {
                    stations.map { station -> async {
                        val results = fetchBoards(station.platforms, window, favoriteBoards, force, semaphore)
                        if (generation == favoriteGeneration) {
                            results.forEach { (id, result) -> if (result == null) favoriteFailures += id else favoriteBoards[id] = result }
                            while (favoriteBoards.size > 64) favoriteBoards.remove(favoriteBoards.keys.first())
                            stopRoutes = (results.flatMap { it.second?.catalogue?.routes.orEmpty() } + stopRoutes).distinctBy { it.id }
                            recalculate()
                        }
                    } }.awaitAll()
                }
            } finally {
                if (generation == favoriteGeneration) { favoriteLoading = false; recalculate() }
            }
        }
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
        mapSettledJob?.cancel()
        viewModelScope.launch { refreshData(forceNetwork = true) }
        viewModelScope.launch { refreshEmmaData(force = true) }
        ensureNearbyRequest(force = true)
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
                        current.copy(network = network, selectedStopId = selected,
                            selectedPlatformIds=current.selectedPlatformIds.takeIf { selected==current.selectedStopId }.orEmpty(),
                            nearestStopId = nearest, loading = false)
                    }
                }
                val refreshed = repository.refresh()
                val estimateNetwork = state.value.network
                live = kotlinx.coroutines.withContext(Dispatchers.Default) {
                    refreshed.copy(vehicles = routeEstimator.update(refreshed.vehicles, estimateNetwork, Instant.now()))
                }
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
            val selectedId = current.selectedStopId.takeUnless { current.showNearby }
            val cluster = if(selectedId!=null) current.selectedPlatforms else emptyList()
            val hasEmma = cluster.any { it.id.startsWith("emma:") } || selectedId?.startsWith("emma:") == true
            val validBoard = board?.takeIf { boardKey==current.boardRequestKey && it.stopId == selectedId && Duration.between(it.fetchedAt, now).seconds in 0..60 }
            val emmaDepartures = if (hasEmma) EmmaStopsParser.freshDepartures(validBoard, selectedId, now, current.windowMinutes) else emptyList()
            val localStops = if (cluster.isNotEmpty()) cluster.filterNot { it.id.startsWith("emma:") }
                else emptyList()
            val localDepartures = current.network?.let { network ->
                localStops.flatMap { localStop ->
                    repository.departures(network, snapshot, localStop.id, now, current.windowMinutes)
                }
            }.orEmpty()
            val rawDepartures = (emmaDepartures + localDepartures)
                .distinctBy { listOf(it.routeId, it.directionId, it.expectedAt) }
                .sortedBy { it.expectedAt }
            val departures = current.boardingSelection?.let { filter -> rawDepartures.filter(filter::matches) } ?: rawDepartures
            val emma = emmaLive?.takeIf {
                Duration.between(it.fetchedAt, now).seconds in 0..120
            }
            val emmaVehicles = emma?.vehicles.orEmpty().let {
                if (current.nationwideNetwork) it else EmmaParser.localVehicles(it, emmaRepository.routes)
            }
            val vehicles = (snapshot?.vehicles.orEmpty() + emmaVehicles).filter {
                Duration.between(it.observedAt, now).seconds in -30..180
            }.map { if (Duration.between(it.observedAt, now).seconds > 90) it.copy(routeEstimate = null) else it }
            val emmaMessage = when {
                emma?.messageResource == R.plurals.volan_positions -> null
                emma != null -> getApplication<Application>().getString(emma.messageResource)
                emmaFetchedAt == null -> getApplication<Application>().getString(R.string.volan_loading)
                else -> getApplication<Application>().getString(R.string.volan_unavailable)
            }
            val stop = cluster.firstOrNull { it.id == selectedId }
            val stopProviders = if (cluster.isNotEmpty()) cluster.flatMap { it.providerIds }.distinct() else stop?.providerIds.orEmpty()
            val hommOnly = stopProviders == listOf("homm")
            val networkWarning = stopProviders.mapNotNull { current.network?.providerWarnings?.get(it) }
                .distinct().joinToString("\n").takeIf { it.isNotBlank() }
            val liveWarning = stopProviders.mapNotNull { snapshot?.providerWarnings?.get(it) }
                .distinct().joinToString("\n").takeIf { it.isNotBlank() }
            val clusterStopIds = if (cluster.isNotEmpty()) cluster.map { it.id }.toSet() else setOfNotNull(selectedId)
            current.copy(
                now = now,
                favoriteStations = if (!favoritesVisible) current.favoriteStations else favoriteStations(current).map { station ->
                    val rows = station.platforms.flatMap { platform ->
                        if (platform.id.startsWith("emma:")) EmmaStopsParser.freshDepartures(favoriteBoards[platform.id], platform.id, now, current.windowMinutes)
                        else current.network?.let { repository.departures(it, snapshot, platform.id, now, current.windowMinutes) }.orEmpty()
                    }.distinctBy { listOf(it.routeId, it.directionId, it.expectedAt) }.sortedBy { it.expectedAt }
                    station.copy(departures = rows,
                        loading = favoriteLoading && (station.stop.id in visibleFavorites || visibleFavorites.isEmpty()),
                        unavailable = !favoriteLoading && station.platforms.any { it.id in favoriteFailures ||
                            it.id.startsWith("emma:") && favoriteBoards[it.id]?.let { board -> Duration.between(board.fetchedAt, now).seconds !in 0..60 } != false })
                },
                favoritesLoading = favoriteLoading || !current.stopIndexLoaded && !stopIndexFailed && current.favorites.any { it.startsWith("emma:") },
                nearbyStations = nearbySelection.mapNotNull { station ->
                    val rows = station.platforms.flatMap { platform ->
                        if (platform.id.startsWith("emma:")) EmmaStopsParser.freshDepartures(nearbyBoards[platform.id], platform.id, now, current.windowMinutes)
                        else current.network?.let { repository.departures(it, snapshot, platform.id, now, current.windowMinutes) }.orEmpty()
                    }.distinctBy { listOf(it.routeId, it.directionId, it.expectedAt) }.sortedBy { it.expectedAt }
                    if (!nearbyLoading && rows.isEmpty()) {
                        null
                    } else {
                        station.copy(departures = rows, loading = nearbyLoading,
                            unavailable = station.platforms.any { it.id in nearbyFailed || (it.id.startsWith("emma:") && nearbyBoards[it.id]?.let { board -> Duration.between(board.fetchedAt, now).seconds !in 0..60 } != false) } && !nearbyLoading)
                    }
                }.take(3),
                nearbyLoading = nearbyLoading || (!current.stopIndexLoaded && !stopIndexFailed && nearbySelection.isEmpty()),
                boardUpdatedAt = if (hasEmma) validBoard?.fetchedAt else null,
                error = null,
                departureBoardLoading = hasEmma && boardLoading,
                departureBoardUnavailable = hasEmma && !boardLoading && (boardFailure || (validBoard == null && localDepartures.isEmpty())),
                departureStatus = if (!hasEmma) null else when {
                    boardLoading && validBoard == null -> getApplication<Application>().getString(R.string.emma_board_loading)
                    boardFailure && validBoard != null -> getApplication<Application>().getString(R.string.emma_board_stale)
                    boardFailure || validBoard == null -> getApplication<Application>().getString(R.string.emma_board_unavailable)
                    validBoard.limitReached -> getApplication<Application>().getString(R.string.emma_board_limit)
                    else -> null
                },
                departures = departures,
                vehicles = vehicles,
                selectedVehicle = current.selectedVehicle?.let { selected -> vehicles.find { it.id == selected.id } ?: selected.copy(routeEstimate = null) },
                emmaRoutes = EmmaParser.routeCatalogue(vehicles.filter { it.providerId in setOf("volan", "bkk", "hev", "mav") }, (stopRoutes + lineCatalogue + emmaRepository.catalogue).distinctBy { it.id }),
                emmaStatus = emmaMessage,
                liveStopMinutes = snapshot?.arrivals.orEmpty()
                    .filter {
                        val resolved = current.network?.let { network -> SharedStops.resolve(network, it.stopId) } ?: it.stopId
                        resolved in clusterStopIds && it.isVerifiedRealtime
                    }
                    .map { max(0, it.minutes - Duration.between(snapshot!!.fetchedAt, now).toMinutes().toInt()) }
                    .filter { it in 1..current.windowMinutes }.distinct().sorted(),
                status = mapError ?: if (hasEmma && localDepartures.isEmpty()) null else networkWarning ?: if (hommOnly) null else
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
        mutableState.update { it.copy(showNearby = false, selectedVehicle = vehicle, selectedPlace = null, boardingSelection = null) }
        vehicle.routeId?.let { loadLine(it) }
        ensureVehicleJourney()
    }

    fun closeVehicle() {
        journeyGeneration++
        journeyJob?.cancel()
        journeyKey = null
        mutableState.update { it.copy(selectedVehicle = null, vehicleJourney = null, vehicleJourneyLoading = false, vehicleJourneyError = false) }
    }

    fun returnToNearby() {
        closeVehicle()
        mapSettledJob?.cancel()
        mutableState.update { it.copy(showNearby = true, boardingSelection = null,selectedPlatformIds=emptySet()) }
        ensureBoardRequest()
        ensureNearbyRequest()
    }

    fun updateMapArea(bounds: MapBounds, center: GeoPoint) {
        mapBounds = bounds
        mapCenter = center
        if (!state.value.showNearby) return
        mapSettledJob?.cancel()
        mapSettledJob = viewModelScope.launch {
            delay(1200)
            val prev = lastSettledCenter
            val dist = if (prev != null) distanceMeters(prev, center) else Double.MAX_VALUE
            if (dist > 30.0 || nearbySelection.isEmpty()) {
                lastSettledCenter = center
                ensureNearbyRequest()
            }
        }
    }

    private fun ensureNearbyRequest(force: Boolean = false) {
        if (!mapVisible || !nearbyActive || !state.value.showNearby) return
        val current = state.value
        val anchor = NearbyDepartures.anchor(mapCenter, mapBounds, current.userLocation)
        nearbySelection = NearbyDepartures.select(current.stops, anchor, mapBounds, maxStations = 6)
        mutableState.update { it.copy(nearbyAtUser = anchor == current.userLocation) }
        val window = current.windowMinutes
        val key = nearbySelection.flatMap { it.platforms }.map { it.id } + window.toString()
        if (key == nearbyKey && (nearbyJob?.isActive == true || (!force && nearbyJob?.isCancelled != true))) { recalculate(); return }
        if (nearbyWindow != window) {
            nearbyBoards.clear()
            nearbyWindow = window
        }
        nearbyKey = key
        val generation = ++nearbyGeneration
        nearbyJob?.cancel()
        nearbyLoading = true
        recalculate()
        val stations = nearbySelection.toList()
        nearbyFailed = emptySet()
        nearbyJob = viewModelScope.launch {
            delay(600)
            val semaphore = kotlinx.coroutines.sync.Semaphore(3)
            val results = coroutineScope {
                stations.map { station -> async {
                    val stationResults = fetchBoards(station.platforms, window, nearbyBoards, force, semaphore)
                    // One slow station must not hold back departures that are already available.
                    if (generation == nearbyGeneration) {
                        nearbyFailed = nearbyFailed + stationResults.filter { it.second == null }.map { it.first }
                        stationResults.forEach { (id, board) -> if (board != null) nearbyBoards[id] = board }
                        while (nearbyBoards.size > 36) nearbyBoards.remove(nearbyBoards.keys.first())
                        stopRoutes = (stationResults.flatMap { it.second?.catalogue?.routes.orEmpty() } + stopRoutes).distinctBy { it.id }
                        recalculate()
                    }
                    stationResults
                } }.awaitAll().flatten()
            }
            if (generation != nearbyGeneration) return@launch
            val enriched = results.flatMap { it.second?.catalogue?.stops.orEmpty() }.associateBy { it.id }
            mutableState.update { it.copy(emmaStops = it.emmaStops.map { stop -> enriched[stop.id] ?: stop }) }
            nearbyLoading = false
            recalculate()
        }
    }

    private fun ensureVehicleJourney(force: Boolean = false) {
        val vehicle = state.value.selectedVehicle ?: return
        val key = vehicle.id to vehicle.tripId
        if (!force && key == journeyKey) return
        journeyKey = key
        val generation = ++journeyGeneration
        journeyJob?.cancel()
        val tripId = vehicle.tripId?.takeIf { vehicle.providerId in setOf("volan", "bkk", "hev", "mav") }
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
                    selectedStopId = if (!manualStop && nearest != null) nearest else current.selectedStopId,
                    selectedPlatformIds=if(!manualStop) emptySet() else current.selectedPlatformIds)
            }
            recalculate()
            ensureBoardRequest()
            ensureNearbyRequest()
            ensureFavoriteRequest()
            val stopsSnapshot = state.value.stops
            viewModelScope.launch(Dispatchers.Default) {
                TransitSearch.indexStops(stopsSnapshot)
            }
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
        if (!mapVisible) return
        if (state.value.showNearby) { boardJob?.cancel(); boardKey = null; return }
        val key = state.value.boardRequestKey
        if (!force && key == boardKey) return
        boardKey = key
        boardJob?.cancel()
        board = null
        boardFailure = false
        val selectedId = key.stopId
        val emmaStops = if (selectedId != null) {
            state.value.selectedPlatforms.filter { it.id.startsWith("emma:") }
                .ifEmpty { if (selectedId.startsWith("emma:")) state.value.stops.filter { it.id == selectedId } else emptyList() }
        } else emptyList()
        boardLoading = emmaStops.isNotEmpty()
        recalculate()
        if (boardLoading) boardJob = viewModelScope.launch { refreshBoard(waitForCurrent = true) }
    }

    private suspend fun refreshBoard(waitForCurrent: Boolean = false) {
        if (!mapVisible || state.value.showNearby) return
        if (!waitForCurrent && boardMutex.isLocked) return
        boardMutex.withLock {
            val selection=state.value
            val requestKey=selection.boardRequestKey
            val stopId = selection.selectedStopId ?: return@withLock
            val window = selection.windowMinutes
            val allStops = selection.stops
            val cluster = selection.selectedPlatforms
            val emmaStops = EmmaBoardTargets.select(cluster.filter { it.id.startsWith("emma:") })
                .ifEmpty { if (stopId.startsWith("emma:")) allStops.filter { it.id == stopId } else emptyList() }
            if (emmaStops.isEmpty()) return@withLock
            boardLoading = true
            recalculate()
            try {
                val semaphore = kotlinx.coroutines.sync.Semaphore(3)
                val results = fetchBoards(emmaStops, window, emptyMap(), true, semaphore).mapNotNull { it.second }


                if (state.value.boardRequestKey==requestKey && !state.value.showNearby) {
                    if (results.isEmpty()) {
                        boardFailure = true
                    } else {
                        board = EmmaStopsParser.combineBoards(stopId, results)
                        boardFailure = results.size < emmaStops.size
                        val routes = results.flatMap { it.catalogue.routes }.distinctBy { it.id }
                        stopRoutes = (stopRoutes + routes).distinctBy { it.id }
                        val newStopsMap = results.flatMap { it.catalogue.stops }.associateBy { it.id }
                        mutableState.update { current ->
                            current.copy(emmaStops = current.emmaStops.map {
                                newStopsMap[it.id] ?: it
                            })
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (state.value.boardRequestKey==requestKey && !state.value.showNearby) boardFailure = true
            } finally {
                if (state.value.boardRequestKey==requestKey && !state.value.showNearby) boardLoading = false
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
        val route = state.value.findRoute(id) ?: return
        if (route.providerId !in setOf("volan", "bkk", "hev", "mav")) return
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
        val route = state.value.findRoute(routeId) ?: return
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
            delay(250)
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
        mapSettledJob?.cancel()
        lastSettledCenter = place.position
        mutableState.update { it.copy(showNearby = true, selectedPlace = place, selectedStopId = null, selectedPlatformIds=emptySet(), boardingSelection = null, placeCameraRequest = it.placeCameraRequest + 1) }
        recalculate()
        ensureBoardRequest()
        ensureNearbyRequest(force = true)
    }

    fun clearPlace() {
        if (state.value.selectedPlace == null) return
        mapSettledJob?.cancel()
        mutableState.update { it.copy(selectedPlace = null) }
        recalculate()
        ensureNearbyRequest()
    }

    fun selectStop(id: String) = selectStopGroup(listOf(id))

    /** Exact map marker members only: never expand a platform by name, parent or proximity. */
    fun selectStopGroup(ids: List<String>) {
        if(ids.isEmpty()) return
        closeVehicle()
        manualStop = true
        val network=state.value.network
        val selectedIds=ids.map { id -> network?.let { SharedStops.resolve(it,id) } ?: SharedStops.canonicalId(id) }.toSet()
        mutableState.update { it.copy(showNearby=false,selectedStopId=selectedIds.first(),selectedPlatformIds=selectedIds,
            selectedPlace=null,cameraRequest=it.cameraRequest+1,boardingSelection=null) }
        recalculate()
        ensureBoardRequest()
    }

    fun recenter() {
        manualStop = false
        mutableState.update { it.copy(cameraRequest = it.cameraRequest + 1) }
    }

    fun useNearest() {
        closeVehicle()
        manualStop = false
        mapSettledJob?.cancel()
        lastSettledCenter = state.value.userLocation
        state.value.nearestStopId?.let { id -> mutableState.update { it.copy(showNearby = false, selectedStopId = id, selectedPlatformIds=emptySet(), selectedPlace = null, cameraRequest = it.cameraRequest + 1, boardingSelection = null) } }
        recalculate()
        ensureBoardRequest()
        ensureNearbyRequest()
    }

    fun updateLocation(point: GeoPoint) {
        mutableState.update { current ->
            val nearest = nearestStop(current.stops, point)
            current.copy(userLocation = point, nearestStopId = nearest,
                selectedStopId = if (!manualStop && nearest != null) nearest else current.selectedStopId,
                selectedPlatformIds=if(!manualStop) emptySet() else current.selectedPlatformIds,
                locationMessage = null)
        }
        recalculate()
        ensureBoardRequest()
        ensureNearbyRequest()
    }

    fun locationMessage(message: String?) { mutableState.update { it.copy(locationMessage = message) } }
    fun mapMessage(message: String?) { mapError = message; recalculate() }
    fun setWindow(minutes: Int) {
        if (minutes !in DepartureTime.windows) return
        mapSettledJob?.cancel()
        preferences.edit().putInt("window", minutes).apply()
        mutableState.update { it.copy(windowMinutes = minutes) }
        recalculate()
        ensureBoardRequest()
        ensureNearbyRequest(force = true)
        ensureFavoriteRequest(force = true)
    }
    fun toggleFavorite(id: String) {
        val canonicalId = SharedStops.canonicalId(id)
        val updated = state.value.favorites.toMutableSet().apply { if (!add(canonicalId)) remove(canonicalId) }.toSet()
        preferences.edit().putStringSet("favorites", updated).apply()
        mutableState.update { it.copy(favorites = updated) }
        recalculate()
        ensureFavoriteRequest()
    }

    fun setOtaChannel(channel: UpdateChannel) {
        otaDownloadJob?.cancel()
        otaDownloadedUrl = null
        preferences.edit().putString("ota_channel", channel.key).apply()
        mutableState.update { it.copy(otaChannel = channel, otaUpdateAvailable = null, otaCheckError = null,
            otaDownloadedApk = null, otaDownloadProgress = null, showOtaDialog = false) }
        checkForUpdates(isAutoCheck = false)
    }

    fun checkForUpdates(isAutoCheck: Boolean = false) {
        val channel = state.value.otaChannel
        if (!isAutoCheck) {
            mutableState.update { it.copy(otaCheckLoading = true, otaCheckError = null) }
        }
        otaJob?.cancel()
        otaJob = viewModelScope.launch {
            try {
                val info = otaRepository.fetchUpdateInfo(channel)
                val currentVersion = BuildConfig.VERSION_NAME
                val isNew = info != null && VersionComparator.isNewer(info.version, currentVersion)
                if (info?.apkUrl != otaDownloadedUrl) otaDownloadedUrl = null
                mutableState.update { current ->
                    current.copy(
                        otaCheckLoading = false,
                        otaCheckError = null,
                        otaLastChecked = Instant.now(),
                        otaUpdateAvailable = if (isNew) info else null,
                        otaDownloadedApk = current.otaDownloadedApk.takeIf { otaDownloadedUrl != null && otaDownloadedUrl == info?.apkUrl },
                        showOtaDialog = isNew
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                mutableState.update { it.copy(otaCheckLoading = false,
                    otaCheckError = if (isAutoCheck) it.otaCheckError else e.localizedMessage ?: "Hiba a frissítések lekérésekor") }
            }
        }
    }

    fun startOtaDownloadAndInstall(context: Context, info: OtaUpdateInfo) {
        val apkUrl = info.apkUrl ?: return
        if (otaDownloadJob?.isActive == true) return
        val downloaded = state.value.otaDownloadedApk
        if (otaDownloadedUrl == apkUrl && downloaded != null && downloaded.exists()) {
            try {
                otaRepository.installApk(downloaded)
                return
            } catch (_: Exception) {}
        }
        mutableState.update { it.copy(otaDownloadProgress = 0f, otaDownloadedApk = null, otaCheckError = null) }
        otaDownloadJob = viewModelScope.launch {
            try {
                val otaDir = File(context.cacheDir, "ota_updates")
                val target = File(otaDir, "bmenetrend-${info.version}-${info.tag}.apk")
                val file = otaRepository.downloadApk(apkUrl, target) { progress ->
                    mutableState.update { it.copy(otaDownloadProgress = progress) }
                }
                otaDownloadedUrl = apkUrl
                mutableState.update { it.copy(otaDownloadProgress = null, otaDownloadedApk = file) }
                otaRepository.installApk(file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                mutableState.update { it.copy(otaDownloadProgress = null, otaCheckError = e.localizedMessage ?: "Letöltési hiba") }
            }
        }
    }

    fun dismissOtaDialog() {
        mutableState.update { it.copy(showOtaDialog = false) }
    }
}


internal fun nearestStop(network: TransitNetwork, location: GeoPoint): String? = nearestStop(network.stops, location)
internal fun nearestStop(stops: Collection<TransitStop>, location: GeoPoint): String? =
    stops.asSequence().filterNot { it.station }.minByOrNull { distanceMeters(location, it.position) }?.id

internal fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val lat = Math.toRadians(b.lat - a.lat)
    val lon = Math.toRadians(b.lon - a.lon)
    val haversine = sin(lat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(lon / 2).pow(2)
    return 6_371_000 * 2 * atan2(sqrt(haversine.coerceIn(0.0, 1.0)), sqrt((1 - haversine).coerceIn(0.0, 1.0)))
}
