package hu.app.bmenetrend

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.ui.AccentColor
import hu.app.bmenetrend.ui.UiState
import hu.app.bmenetrend.offline.*
import kotlinx.coroutines.withContext
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
    val offlinePacks = OfflinePackRepository(application, viewModelScope)
    private val offlineData = OfflineTransitData(offlinePacks.store)
    private var offlineBoardJob: Job? = null
    private var offlineLoadedId: String? = null
    private var onlineLineCatalogue = emptyList<TransitRoute>()
    private var onlineNetwork: TransitNetwork? = null
    private var onlineCatalogue: Pair<List<TransitStop>, List<TransitRoute>>? = null
    private val otaRepository = OtaRepository(application)
    private var otaJob: Job? = null
    private var otaDownloadJob: Job? = null
    private var otaDownloadedUrl: String? = null
    private val repository = TransitRepository(application)
    private val stationTimetableRepository = StationTimetableRepository(application)
    private data class SavedBoardKey(
        val request: StopBoardScope.RequestKey,
        val platforms: List<String>,
        val revision: Int,
        val timeBucket: Long,
    )
    private var savedBoardJob: Job? = null
    private var savedBoardKey: SavedBoardKey? = null
    private var savedBoardResult: Pair<SavedBoardKey, OfflineBoardResult?>? = null
    private var savedBoardRevision = 0
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
        lineCatalogueLoading = true,
        selectedStopId = initialNetwork.stops.firstOrNull { it.id == "1" }?.id ?: initialNetwork.stops.firstOrNull()?.id,
        favorites = preferences.getStringSet("favorites", emptySet())?.map(SharedStops::canonicalId)?.toSet().orEmpty(),
        customAccentArgb = preferences.getInt("custom_accent_argb", 0xFF6750A4.toInt()),
        accentColor = AccentColor.fromKey(preferences.getString("accent_color", "system")),
        nationwideNetwork = preferences.getBoolean("emma_nationwide", true),
        windowMinutes = preferences.getInt("window", 120).takeIf { it in DepartureTime.windows } ?: 120,
        otaChannel = UpdateChannel.fromKey(preferences.getString("ota_channel", "stable")),
        downloadedStationIds = stationTimetableRepository.downloadedStopIds(),
        offlineStationTimetables = stationTimetableRepository.allMetadata(),
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
    private var mapStopsJob: Job? = null
    private var mapStopsGeneration = 0
    private var loadedMapBounds: MapBounds? = null
    private val loadedMapBoundsList = mutableListOf<MapBounds>()
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
    private var favoriteLoading = false
    private var favoriteFailures = emptySet<String>()
    private val favoriteLines = linkedMapOf<String, EmmaStopsRepository.Lines>()
    private var liveError = false

    init {
        viewModelScope.launch {
            stationTimetableRepository.initialize()
            savedBoardRevision++
            mutableState.update { it.copy(downloadedStationIds = stationTimetableRepository.downloadedStopIds(),
                offlineStationTimetables = stationTimetableRepository.allMetadata()) }
            recalculate()
        }
        viewModelScope.launch {
            offlinePacks.state.collect { pack ->
                mutableState.update { it.copy(offlinePack = pack) }
                if (state.value.offlineMode && pack.loaded && pack.metadata?.id != null && pack.metadata.id != offlineLoadedId) loadOfflineCatalogue()
            }
        }
        viewModelScope.launch {
            NetworkAccessPolicy.offline.collect { enabled -> applyOfflineMode(enabled) }
        }
        if (!NetworkAccessPolicy.offline.value) checkForUpdates(isAutoCheck = true)
        viewModelScope.launch(Dispatchers.Default) {
            TransitSearch.indexStops(initialNetwork.stops, emptyList())
        }
    }

    /** Owned by the activity's STARTED lifecycle; all polling stops in the background. */
    suspend fun observe() = coroutineScope {
        nearbyActive = true
        if (favoritesVisible) ensureFavoriteRequest()
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
                refreshStopIndex()
                delay(if (stopIndexFailed) 60_000 else 3_600_000)
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
        } else { mapSettledJob?.cancel(); nearbyJob?.cancel(); boardJob?.cancel(); mapStopsJob?.cancel(); mapStopsGeneration++ }
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
        if (!state.value.stopIndexLoaded) viewModelScope.launch { refreshStopIndex() }
        ensureFavoriteRequest(force = true)
    }

    private fun favoriteStations(current: UiState): List<NearbyStation> {
        if (favoriteNetworkStops !== current.network?.stops || favoriteEmmaStops !== current.emmaStops || favoriteSelectionIds != current.favorites) {
            favoriteNetworkStops = current.network?.stops
            favoriteEmmaStops = current.emmaStops
            favoriteSelectionIds = current.favorites
            favoriteSelection = FavoriteDepartures.stations(current.favorites, current.stops)
        }
        return favoriteSelection.map { station ->
            val catalogued=favoriteLines[station.stop.id]?.catalogue?.stops?.singleOrNull()
            if(catalogued==null) station else station.copy(stop=catalogued,platforms=listOf(catalogued))
        }
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
        if (NetworkAccessPolicy.offline.value) { recalculateOffline(); return }
        if (!favoritesVisible || !nearbyActive) return
        val current = state.value
        val stations = favoriteStations(current).let { all ->
            all.filter { it.stop.id in visibleFavorites }.ifEmpty { all.take(3) }.take(8)
        }
        val key = stations.map { it.stop.id }.sorted()+LocalDate.now(budapest).toString()
        if (!force && key == favoriteKey && favoriteJob?.isCancelled != true) return
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
                        val id=station.stop.id
                        if(id.startsWith("emma:")) {
                            semaphore.acquire()
                            try {
                                val result=stopsRepository.servingLines(id,force)
                                if(generation==favoriteGeneration) {
                                    favoriteLines[id]=result
                                    while(favoriteLines.size>64) favoriteLines.remove(favoriteLines.keys.first())
                                    if(result.stale) favoriteFailures+=id
                                    stopRoutes=(result.catalogue.routes+stopRoutes).distinctBy { it.id }
                                    recalculate()
                                }
                            } catch(cancelled: CancellationException) { throw cancelled
                            } catch(_: Exception) { if(generation==favoriteGeneration) { favoriteFailures+=id;recalculate() } }
                            finally { semaphore.release() }
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
        if (!state.value.stopIndexLoaded) viewModelScope.launch { refreshStopIndex() }
        viewModelScope.launch { refreshData(forceNetwork = true) }
        viewModelScope.launch { refreshEmmaData(force = true) }
        ensureNearbyRequest(force = true)
        ensureBoardRequest(force = true)
        ensureVehicleJourney(force = true)
        mapBounds?.let { ensureMapStops(it) }
    }

    private suspend fun refreshEmmaData(force: Boolean = false) {
        if (NetworkAccessPolicy.offline.value) return
        if (emmaMutex.isLocked) return
        emmaMutex.withLock {
            val generation = NetworkAccessPolicy.generation
            val result = emmaRepository.refresh(force)
            if (generation != NetworkAccessPolicy.generation) return@withLock
            emmaLive = result
            emmaFetchedAt = result.fetchedAt
            recalculate()
        }
    }

    private suspend fun refreshData(forceNetwork: Boolean = false) {
        if (NetworkAccessPolicy.offline.value) return
        if (refreshMutex.isLocked) return
        refreshMutex.withLock {
            mutableState.update { it.copy(refreshing = true) }
            try {
                val today = LocalDate.now(budapest)
                val now = Instant.now()
                val retryNetwork = lastNetworkAttempt?.let { Duration.between(it, now).seconds >= 60 } ?: true
                if (forceNetwork || (retryNetwork && (state.value.network == null || loadedDate != today))) {
                    lastNetworkAttempt = now
                    val generation = NetworkAccessPolicy.generation
                    val network = repository.loadNetwork(force = forceNetwork)
                    if (generation != NetworkAccessPolicy.generation) return@withLock
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

    /** Request saved fallback data without doing file reads/decompression during a UI refresh. */
    private fun savedStationBoard(current: UiState, now: Instant): OfflineBoardResult? {
        val selected = current.selectedStopId.takeUnless { current.showNearby }
        val platforms = if (selected != null) current.selectedPlatforms.map { it.id } else emptyList()
        if (selected == null || (selected !in current.downloadedStationIds && platforms.none { it in current.downloadedStationIds })) {
            savedBoardJob?.cancel()
            savedBoardKey = null
            savedBoardResult = null
            return null
        }
        val key = SavedBoardKey(current.boardRequestKey, platforms, savedBoardRevision, now.epochSecond / 5)
        if (key != savedBoardKey) {
            savedBoardJob?.cancel()
            savedBoardKey = key
            savedBoardJob = viewModelScope.launch {
                val result = try { stationTimetableRepository.getOfflineBoard(selected, platforms, now, current.windowMinutes) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (savedBoardKey == key && !NetworkAccessPolicy.offline.value && state.value.boardRequestKey == key.request) {
                    savedBoardResult = key to result
                    recalculate()
                }
            }
        }
        return savedBoardResult?.takeIf { (previous, _) ->
            previous.request == key.request && previous.platforms == key.platforms && previous.revision == key.revision &&
                key.timeBucket - previous.timeBucket in 0..1
        }?.second
    }

    private fun recalculate() {
        if (NetworkAccessPolicy.offline.value) { recalculateOffline(); return }
        val now = Instant.now()
        val savedBoard = savedStationBoard(state.value, now)
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

            val offlineBoard = if (selectedId != null && (validBoard == null || boardFailure) && emmaDepartures.isEmpty()) {
                savedBoard?.takeIf { savedBoardResult?.first?.request == current.boardRequestKey }
            } else null
            val isOfflineActive = offlineBoard != null && (validBoard == null || boardFailure)
            if (offlineBoard != null) {
                stopRoutes = (stopRoutes + offlineBoard.routes).distinctBy { it.id }
            }
            val effectiveEmmaDepartures = if (isOfflineActive) offlineBoard!!.departures else emmaDepartures
            val rawDepartures = (effectiveEmmaDepartures + localDepartures)
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
                    station.copy(loading=favoriteLoading && (station.stop.id in visibleFavorites || visibleFavorites.isEmpty()),
                        unavailable=station.stop.id in favoriteFailures || !favoriteLoading && station.stop.id.startsWith("emma:") && station.stop.id !in favoriteLines)
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
                departureBoardUnavailable = hasEmma && !boardLoading && !isOfflineActive && (boardFailure || (validBoard == null && localDepartures.isEmpty())),
                departureStatus = if (isOfflineActive) {
                    getApplication<Application>().getString(R.string.ui_offline_timetable_active)
                } else if (!hasEmma) null else when {
                    boardLoading && validBoard == null -> getApplication<Application>().getString(R.string.emma_board_loading)
                    boardFailure && validBoard != null -> getApplication<Application>().getString(R.string.emma_board_stale)
                    boardFailure || validBoard == null -> getApplication<Application>().getString(R.string.emma_board_unavailable)
                    validBoard.limitReached -> getApplication<Application>().getString(R.string.emma_board_limit)
                    else -> null
                },
                isOfflineTimetableActive = isOfflineActive,
                departures = departures,
                vehicles = vehicles,
                selectedVehicle = current.selectedVehicle?.let { selected -> vehicles.find { it.id == selected.id } ?: selected.copy(routeEstimate = null) },
                trackedVehicle = current.trackedVehicle?.let { tracked -> vehicles.find { it.id == tracked.id } ?: tracked.copy(routeEstimate = null) },
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
        manualStop = false
        mutableState.update { it.copy(showNearby = false, selectedVehicle = vehicle, trackedVehicle = null, selectedStopId = null, selectedPlatformIds = emptySet(), selectedPlace = null, boardingSelection = null) }
        vehicle.routeId?.let { loadLine(it) }
        ensureVehicleJourney()
    }

    fun closeVehicle() {
        journeyGeneration++
        journeyJob?.cancel()
        journeyKey = null
        mutableState.update { it.copy(selectedVehicle = null, trackedVehicle = null, vehicleJourney = null, vehicleJourneyLoading = false, vehicleJourneyError = false) }
    }

    fun returnToTrackedVehicle() {
        val vehicle = state.value.trackedVehicle ?: return
        manualStop = false
        mutableState.update {
            it.copy(
                showNearby = false,
                selectedVehicle = vehicle,
                trackedVehicle = null,
                selectedStopId = null,
                selectedPlatformIds = emptySet(),
                boardingSelection = null,
                fitRouteRequest = it.fitRouteRequest + 1
            )
        }
        recalculate()
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
        ensureMapStops(bounds)
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

    private fun ensureMapStops(bounds: MapBounds) {
        if (!mapVisible || NetworkAccessPolicy.offline.value || state.value.stopIndexLoaded) return
        // Do not query when zoomed far out where individual stops are not visible anyway
        if (bounds.north - bounds.south !in 0.0..0.85 || bounds.east - bounds.west !in 0.0..1.2) return
        val alreadyCovered = loadedMapBoundsList.any { covered ->
            bounds.south >= covered.south && bounds.north <= covered.north &&
            bounds.west >= covered.west && bounds.east <= covered.east
        }
        if (alreadyCovered) return

        mapStopsJob?.cancel()
        val generation = ++mapStopsGeneration
        val networkGeneration = NetworkAccessPolicy.generation
        val latMargin = (bounds.north - bounds.south) * .2
        val lonMargin = (bounds.east - bounds.west) * .2
        val area = MapBounds(
            (bounds.west - lonMargin).coerceAtLeast(16.1),
            (bounds.south - latMargin).coerceAtLeast(45.7),
            (bounds.east + lonMargin).coerceAtMost(22.9),
            (bounds.north + latMargin).coerceAtMost(48.6)
        )
        if (area.west >= area.east || area.south >= area.north) return

        mapStopsJob = viewModelScope.launch {
            try {
                val catalogue = stopsRepository.mapArea(area)
                if (generation != mapStopsGeneration || networkGeneration != NetworkAccessPolicy.generation || state.value.stopIndexLoaded) return@launch
                if (loadedMapBoundsList.size >= 16) {
                    loadedMapBoundsList.removeAt(0)
                }
                loadedMapBoundsList.add(area)
                loadedMapBounds = area
                mutableState.update { current ->
                    val merged = current.emmaStops.associateBy { it.id }.toMutableMap()
                    catalogue.stops.forEach { stop ->
                        if (merged[stop.id]?.routeIds.isNullOrEmpty()) merged[stop.id] = stop
                    }
                    current.copy(emmaStops = merged.values.toList())
                }
                recalculate()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
            }
        }
    }

    private fun ensureNearbyRequest(force: Boolean = false) {
        if (NetworkAccessPolicy.offline.value) { recalculateOffline(); return }
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
        if (NetworkAccessPolicy.offline.value) return
        val vehicle = state.value.selectedVehicle ?: state.value.trackedVehicle ?: return
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
                val journey = kotlinx.coroutines.withContext(Dispatchers.Default) { EmmaJourneyParser.parse(text, tripId) }
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


    fun setOfflineMode(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (!enabled) {
                NetworkAccessPolicy.setOffline(getApplication(), false)
                return@launch
            }
            val pack = offlinePacks.state.value
            if (pack.loaded && !pack.busy && pack.metadata != null) {
                NetworkAccessPolicy.setOffline(getApplication(), true)
            }
        }
    }
    fun downloadOfflinePack(wifiOnly: Boolean) { offlinePacks.download(wifiOnly) }
    fun setAutomaticOfflineUpdates(enabled: Boolean) { offlinePacks.setAutomaticUpdates(enabled) }
    fun cancelOfflineDownload() { offlinePacks.cancel() }
    fun deleteOfflinePack() { viewModelScope.launch { offlinePacks.delete() } }
    fun importOfflineFeed(uri: android.net.Uri) {
        viewModelScope.launch {
            try { offlinePacks.importFeed(uri) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(offlinePack = it.offlinePack.copy(phase = OfflinePackPhase.FAILED, error = "import")) } }
        }
    }
    private suspend fun applyOfflineMode(enabled: Boolean) {
        savedBoardJob?.cancel()
        savedBoardKey = null
        savedBoardResult = null
        mapStopsGeneration++; mapStopsJob?.cancel(); loadedMapBounds = null; loadedMapBoundsList.clear()
        placeSearchGeneration++; lineGeneration++; journeyGeneration++; nearbyGeneration++; favoriteGeneration++
        placeSearchJob?.cancel(); lineJob?.cancel(); journeyJob?.cancel(); nearbyJob?.cancel(); favoriteJob?.cancel(); boardJob?.cancel()
        otaJob?.cancel(); otaDownloadJob?.cancel()
        board = null; boardKey = null; nearbyBoards.clear(); favoriteLines.clear()
        boardLoading = false; nearbyLoading = false; favoriteLoading = false
        live = null; emmaLive = null
        mutableState.update { it.copy(offlineMode = enabled, vehicles = emptyList(), selectedVehicle = null, trackedVehicle = null,
            vehicleJourney = null, vehicleJourneyLoading = false, loadingLineId = null, placesLoading = false, placeResults = emptyList(),
            departureBoardLoading = false, lineDetails = emptyMap(), boardingSelection = null, otaCheckLoading = false, otaDownloadProgress = null, showOtaDialog = false) }
        if (enabled) {
            onlineLineCatalogue = lineCatalogue
            onlineNetwork = state.value.network
            onlineCatalogue = state.value.emmaStops to state.value.emmaRoutes
            loadOfflineCatalogue()
        } else {
            offlineBoardJob?.cancel()
            offlineLoadedId = null
            lineCatalogue = onlineLineCatalogue
            stopRoutes = emptyList()
            onlineCatalogue?.let { saved -> mutableState.update { it.copy(network = onlineNetwork ?: initialNetwork, emmaStops = saved.first, emmaRoutes = saved.second) } }
            recalculate()
            refresh()
            mapBounds?.let { ensureMapStops(it) }
            viewModelScope.launch { refreshLineCatalogue() }
            viewModelScope.launch { refreshStopIndex() }
        }
    }
    private suspend fun loadOfflineCatalogue() {
        val generation = NetworkAccessPolicy.generation
        val catalogue = withContext(Dispatchers.IO) { runCatching { offlineData.catalogue() }.getOrNull() }
        if (catalogue == null) {
            withContext(Dispatchers.IO) { NetworkAccessPolicy.setOffline(getApplication(), false) }
            mutableState.update { it.copy(offlinePack = it.offlinePack.copy(phase = OfflinePackPhase.FAILED, error = "import")) }
            return
        }
        if (!NetworkAccessPolicy.offline.value || generation != NetworkAccessPolicy.generation) return
        offlineLoadedId = offlinePacks.state.value.metadata?.id
        lineCatalogue = catalogue.second
        mutableState.update { it.copy(network = TransitNetwork(emptyList(), emptyList(), emptyList(), Instant.now(), "offline"), emmaStops = catalogue.first, emmaRoutes = catalogue.second, stopIndexLoaded = true, stopStatus = null,
            lineCatalogueLoading = false, lineCatalogueStatus = null, loading = false, refreshing = false) }
        recalculateOffline()
    }
    private fun recalculateOffline() {
        if (!NetworkAccessPolicy.offline.value || offlineBoardJob?.isActive == true) return
        val current = state.value
        val generation = NetworkAccessPolicy.generation
        val key = current.boardRequestKey
        offlineBoardJob = viewModelScope.launch {
            val now = Instant.now()
            val expired = current.offlinePack.metadata?.validUntil?.let { now.atZone(budapest).toLocalDate() > it } == true
            val offlineStatus = getApplication<Application>().getString(if (expired) R.string.offline_expired else R.string.offline_scheduled)
            val all = current.emmaStops
            val anchor = NearbyDepartures.anchor(mapCenter, mapBounds, current.userLocation)
            val nearby = NearbyDepartures.select(all, anchor, mapBounds)
            val favorites = FavoriteDepartures.stations(current.favorites, all)
            val result = withContext(Dispatchers.IO) {
                fun rows(ids: List<String>) = runCatching { offlineData.board(ids, now, current.windowMinutes) }.getOrDefault(emptyList())
                Triple(rows(current.selectedPlatforms.map { it.id }), nearby.map { it.copy(departures = rows(it.platforms.map { p -> p.id })) },
                    favorites.map { it.copy(departures = rows(it.platforms.map { p -> p.id })) })
            }
            if (generation == NetworkAccessPolicy.generation && key == state.value.boardRequestKey) mutableState.update {
                it.copy(now = now, departures = result.first.let { rows -> it.boardingSelection?.let { selection -> rows.filter(selection::matches) } ?: rows },
                    nearbyStations = result.second, favoriteStations = result.third, nearbyLoading = false, favoritesLoading = false,
                    departureBoardLoading = false, departureBoardUnavailable = false, vehicles = emptyList(), liveStopMinutes = emptyList(),
                    departureStatus = offlineStatus, status = offlineStatus,
                    isOfflineTimetableActive = true, emmaStatus = getApplication<Application>().getString(R.string.offline_live_unavailable),
                    boardUpdatedAt = null, loading = false, refreshing = false)
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
        if (NetworkAccessPolicy.offline.value) return
        var published: EmmaStopsRepository.Index? = null
        fun publish(index: EmmaStopsRepository.Index) {
            if (published?.catalogue === index.catalogue && published?.stale == index.stale) return
            published = index
            publishStopIndex(index)
        }
        try {
            publish(stopsRepository.index(onCached = ::publish))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            stopIndexFailed = true
            mutableState.update { it.copy(stopStatus = getApplication<Application>().getString(R.string.emma_stops_unavailable)) }
        }
    }

    private fun publishStopIndex(index: EmmaStopsRepository.Index) {
        if (NetworkAccessPolicy.offline.value) return
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
        val snapshot = state.value
        viewModelScope.launch(Dispatchers.Default) {
            TransitSearch.indexStops(snapshot.network?.stops.orEmpty(), snapshot.emmaStops)
        }
    }

    fun updateMapZoom(zoom: Double) {
        mutableState.update { if (kotlin.math.abs(it.mapZoom - zoom) < 0.01) it else it.copy(mapZoom = zoom) }
    }

    private fun ensureBoardRequest(force: Boolean = false) {
        if (NetworkAccessPolicy.offline.value) { recalculateOffline(); return }
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
        if (NetworkAccessPolicy.offline.value) { recalculateOffline(); return }
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
        if (NetworkAccessPolicy.offline.value) return
        fun publish(result: EmmaLinesRepository.Catalogue) {
            if (NetworkAccessPolicy.offline.value) return
            lineCatalogue = result.routes
            lineCatalogueFailed = result.stale
            mutableState.update { it.copy(lineCatalogueLoading = false,
                lineCatalogueStatus = if (result.stale) getApplication<Application>().getString(R.string.emma_lines_saved) else null) }
            recalculate()
        }
        try {
            publish(linesRepository.get(onCached = ::publish))
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) {
            lineCatalogueFailed = true
            mutableState.update { it.copy(lineCatalogueLoading = false,
                lineCatalogueStatus = getApplication<Application>().getString(R.string.emma_lines_unavailable)) }
        }
    }

    fun selectJourneyBoardingStop(id: String) {
        val vehicle = state.value.selectedVehicle ?: state.value.trackedVehicle
        val journey = state.value.vehicleJourney
        if (vehicle?.routeId == null || journey == null || journey.patternCode == null || journey.tripId != vehicle.tripId || journey.stops.none { it.id == id }) {
            selectStop(id); return
        }
        val pattern = LinePattern(journey.patternCode, setOf(journey.patternCode), journey.destination ?: vehicle.destination.orEmpty(), journey.stops, journey.geometry)
        manualStop = true
        val network = state.value.network
        val selectedIds = setOf(network?.let { SharedStops.resolve(it, id) } ?: SharedStops.canonicalId(id))
        mutableState.update {
            it.copy(
                showNearby = false,
                selectedStopId = selectedIds.first(),
                selectedPlatformIds = selectedIds,
                selectedPlace = null,
                cameraRequest = it.cameraRequest + 1,
                trackedVehicle = vehicle,
                selectedVehicle = null,
                boardingSelection = BoardingSelection(vehicle.routeId, pattern.headsign, pattern)
            )
        }
        recalculate()
        ensureBoardRequest()
    }

    fun loadLine(id: String, force: Boolean = false) {
        if (NetworkAccessPolicy.offline.value) {
            lineJob?.cancel()
            lineJob = viewModelScope.launch {
                val generation = NetworkAccessPolicy.generation
                mutableState.update { it.copy(loadingLineId = id) }
                val details = withContext(Dispatchers.IO) { runCatching { offlineData.line(id) }.getOrNull() }
                if (generation == NetworkAccessPolicy.generation) mutableState.update { it.copy(loadingLineId = null, lineErrorId = if (details == null) id else null, lineDetails = if (details == null) it.lineDetails else it.lineDetails + (id to details)) }
            }
            return
        }
        if (!force && (state.value.loadingLineId == id || lineLoadedAt[id]?.let { Duration.between(it, Instant.now()).seconds in 0..599 } == true)) return
        val route = state.value.findRoute(id)
        val provider = route?.providerId ?: if (id.startsWith("emma:")) "mav" else null ?: return
        if (provider !in setOf("volan", "bkk", "hev", "mav")) return
        val generation = ++lineGeneration
        lineJob?.cancel()
        mutableState.update { it.copy(loadingLineId = id, lineErrorId = null) }
        lineJob = viewModelScope.launch {
            try {
                val text = journeyClient.execute(EmmaRouteParser.QUERY, org.json.JSONObject().put("id", id.substringAfter(":"))
                    .put("date", LocalDate.now(budapest).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)))
                val details = kotlinx.coroutines.withContext(Dispatchers.Default) { EmmaRouteParser.parse(text, id) }
                if (lineGeneration == generation) {
                    lineLoadedAt[id] = Instant.now()
                    stopRoutes = (listOf(details.route.copy(name = if (details.route.name == details.route.number) (route?.name ?: details.route.name) else details.route.name)) + stopRoutes.filterNot { it.id == id })
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
        val shouldSearch = enabled && !NetworkAccessPolicy.offline.value && query.trim().length >= 2
        mutableState.update { it.copy(placeQuery = query, placeResults = emptyList(), placesLoading = shouldSearch, placesUnavailable = false) }
        if (!shouldSearch) return
        placeSearchJob = viewModelScope.launch {
            delay(250)
            try {
                val placesAsync = async { placesRepository.search(query) }
                val stationsAsync = async {
                    runCatching { stopsRepository.search(query).stops }.getOrDefault(emptyList())
                }
                val stations = stationsAsync.await()
                if (generation != placeSearchGeneration) return@launch
                if (stations.isNotEmpty()) {
                    mutableState.update { current ->
                        val merged = current.emmaStops.associateBy { it.id }.toMutableMap()
                        stations.forEach { stop ->
                            if (merged[stop.id]?.routeIds.isNullOrEmpty()) merged[stop.id] = stop
                        }
                        current.copy(emmaStops = merged.values.toList())
                    }
                    recalculate()
                }
                val places = placesAsync.await()
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
    }
    fun toggleFavorite(id: String) {
        val canonicalId = SharedStops.canonicalId(id)
        val updated = state.value.favorites.toMutableSet().apply { if (!add(canonicalId)) remove(canonicalId) }.toSet()
        preferences.edit().putStringSet("favorites", updated).apply()
        mutableState.update { it.copy(favorites = updated) }
        recalculate()
        ensureFavoriteRequest()
    }

    fun downloadStationTimetable(stopId: String) {
        if (NetworkAccessPolicy.offline.value) return
        viewModelScope.launch {
            val current = state.value
            val stop = current.stops.find { it.id == stopId } ?: return@launch
            val cluster = StopClusters.clusterFor(stop, current.stops).ifEmpty { listOf(stop) }
            val clusterIds = cluster.map { it.id }.toSet() + stopId
            mutableState.update { it.copy(downloadingStationIds = it.downloadingStationIds + clusterIds) }
            try {
                val meta = stationTimetableRepository.downloadStationTimetable(
                    station = stop,
                    platforms = cluster,
                    network = current.network,
                    now = Instant.now(),
                )
                savedBoardRevision++
                mutableState.update { s ->
                    s.copy(
                        downloadingStationIds = s.downloadingStationIds - clusterIds,
                        downloadedStationIds = stationTimetableRepository.downloadedStopIds(),
                        offlineStationTimetables = stationTimetableRepository.allMetadata(),
                        status = getApplication<Application>().getString(
                            R.string.ui_timetable_download_success,
                            StationTimetableRepository.formatFileSize(meta.fileSizeBytes),
                            meta.departureCount,
                        ),
                    )
                }
                recalculate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                mutableState.update { s ->
                    s.copy(
                        downloadingStationIds = s.downloadingStationIds - clusterIds,
                        status = getApplication<Application>().getString(R.string.ui_timetable_download_failed),
                    )
                }
            }
        }
    }

    fun deleteStationTimetable(stopId: String) {
        viewModelScope.launch {
            stationTimetableRepository.deleteStationTimetable(stopId)
            savedBoardRevision++
            mutableState.update { s ->
                s.copy(
                    downloadedStationIds = stationTimetableRepository.downloadedStopIds(),
                    offlineStationTimetables = stationTimetableRepository.allMetadata(),
                )
            }
            recalculate()
        }
    }

    fun clearAllStationTimetables() {
        viewModelScope.launch {
            stationTimetableRepository.clearAll()
            savedBoardRevision++
            mutableState.update { s ->
                s.copy(
                    downloadedStationIds = emptySet(),
                    offlineStationTimetables = emptyMap(),
                )
            }
            recalculate()
        }
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
        if (NetworkAccessPolicy.offline.value) return
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
        if (NetworkAccessPolicy.offline.value) return
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
