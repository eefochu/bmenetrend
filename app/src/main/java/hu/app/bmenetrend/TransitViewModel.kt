package hu.app.bmenetrend

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.ui.AccentColor
import hu.app.bmenetrend.ui.ThemeMode
import hu.app.bmenetrend.ui.UiState
import hu.app.bmenetrend.ui.TransitCatalogueIndexes
import hu.app.bmenetrend.ui.RemoteStationResults
import hu.app.bmenetrend.ui.RemoteVehicleResults
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
    private var offlineBoardPending = false
    private var offlineLoadedId: String? = null
    private var onlineLineCatalogue = emptyList<TransitRoute>()
    private var onlineNetwork: TransitNetwork? = null
    private var onlineStopIndexLoaded = false
    private var onlineCatalogue: Pair<List<TransitStop>, List<TransitRoute>>? = null
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
    // BKK-first data through the keyless Worker; direct EMMA remains the fallback and covers
    // Volán, MÁV and HÉV, and anything outside the Worker's query limits.
    private val bkkRepository = BkkWorkerRepository.shared
    private var bkkVehicles: BkkWorkerRepository.VehicleSnapshot? = null
    private var bkkVehicleJob: Job? = null
    private var bkkVehicleRequest: Pair<MapBounds?, String?>? = null
    private var bkkSnapshotRequest: Pair<MapBounds?, String?>? = null
    private var vehicleFeed = LiveVehicleFeed()
    // One lane: the feed's memo is single-threaded, and an older merge can never overwrite a newer one.
    private val vehicleLane = Dispatchers.Default.limitedParallelism(1)
    private var vehicleJob: Job? = null
    private var liveRouteVehicles = emptyList<Vehicle>()
    private var vehicleInputs: Pair<LiveVehicleFeed.Inputs, Instant>? = null
    private var publishingVehicles = false
    /** Stops known only from the Worker; EMMA/index records (the saved-item identities and names) replace them. */
    private val workerOnlyStopIds = HashSet<String>()
    private var journeyJob: Job? = null
    private var departureTripJob: Job? = null
    private var departureTripId: String? = null
    private var journeyKey: Pair<String, String?>? = null
    private var journeyGeneration = 0
    private var lineJob: Job? = null
    private var lineGeneration = 0
    private val boardMutex = Mutex()
    private var board: EmmaStopsParser.Board? = null
    private val recentBoards = object : LinkedHashMap<StopBoardScope.RequestKey, EmmaStopsParser.Board>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StopBoardScope.RequestKey, EmmaStopsParser.Board>?): Boolean = size > 8
    }
    private var boardJob: Job? = null
    private var boardKey: StopBoardScope.RequestKey? = null
    private var boardLoading = false
    private var boardFailure = false
    private var stopIndexFailed = false
    private var stopRoutes = emptyList<TransitRoute>()
    private var stopRoutesIndexed: List<TransitRoute>? = null
    private var stopRoutesById = emptyMap<String, TransitRoute>()
    /** Keeps [stopRoutes] identity when nothing new arrives, so the live route catalogue is not rebuilt. */
    private fun mergeStopRoutes(incoming: List<TransitRoute>, preferIncoming: Boolean = true) {
        if (stopRoutesIndexed !== stopRoutes) { stopRoutesById = stopRoutes.associateBy { it.id }; stopRoutesIndexed = stopRoutes }
        stopRoutes = CatalogueMerge.mergeRoutes(stopRoutes, incoming, preferIncoming, stopRoutesById)
    }
    private var initialNetwork: TransitNetwork? = null
    private var bundledEmmaRoutes = emptyList<EmmaParser.Route>()
    private var bundledCatalogue = emptyList<TransitRoute>()
    private val liveRouteCatalogue = LiveRouteCatalogue()
    private val mutableState = MutableStateFlow(UiState(
        network = initialNetwork,
        lineCatalogueLoading = true,
        favorites = preferences.getStringSet("favorites", emptySet())?.map(SharedStops::canonicalId)?.toSet().orEmpty(),
        customAccentArgb = preferences.getInt("custom_accent_argb", 0xFF6750A4.toInt()),
        accentColor = AccentColor.fromKey(preferences.getString("accent_color", "system")),
        themeMode = ThemeMode.fromKey(preferences.getString("theme_mode", "system")),
        mapStyle = hu.app.bmenetrend.ui.MapStyle.fromKey(preferences.getString("map_style", "street")),
        nationwideNetwork = preferences.getBoolean("emma_nationwide", true),
        windowMinutes = preferences.getInt("window", 120).takeIf { it in DepartureTime.windows } ?: 120,
        otaChannel = UpdateChannel.fromKey(preferences.getString("ota_channel", "stable")),
        downloadedStationIds = stationTimetableRepository.downloadedStopIds(),
        offlineStationTimetables = stationTimetableRepository.allMetadata(),
    ))
    val state: StateFlow<UiState> = mutableState
    private val ota = OtaUpdates(OtaRepository(application), preferences, viewModelScope, mutableState)
    // Positions change every few seconds; outside UiState so a tick redraws only the map, not every screen.
    private val mutableVehicles = MutableStateFlow<List<Vehicle>>(emptyList())
    val vehicles: StateFlow<List<Vehicle>> = mutableVehicles
    /**
     * Every GPS fix, for the map's position dot. Fixes arrive about once a second; only movement that
     * matters to stops and boards reaches [UiState.userLocation], which recomposes the whole screen.
     */
    private val mutableLivePosition = MutableStateFlow<GeoPoint?>(null)
    val livePosition: StateFlow<GeoPoint?> = mutableLivePosition
    /** Compass direction the phone faces, degrees clockwise from north; null without a usable sensor. */
    private val mutableHeading = MutableStateFlow<Float?>(null)
    val heading: StateFlow<Float?> = mutableHeading
    private var lastPublishedFixAt = 0L
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
    private var pendingMapBounds: MapBounds? = null
    private var loadedMapBounds: MapBounds? = null
    private val loadedMapBoundsList = mutableListOf<MapBounds>()
    private data class StopDirectionArea(val area: MapBounds, val fetchedAt: Instant, val directions: Map<String, Double>)
    private val stopDirectionAreas = ArrayDeque<StopDirectionArea>()
    private var stopDirectionsJob: Job? = null
    private var stopDirectionsRequest: MapBounds? = null
    private var stopDirectionsGeneration = 0
    private var nearbyKey = emptyList<String>()
    private var nearbySelection = emptyList<NearbyStation>()
    private val nearbyBoards = linkedMapOf<String, EmmaStopsParser.Board>()
    private var nearbyFailed = emptySet<String>()
    private var nearbyWindow = 60
    private var nearbyLoading = false
    private var mapError: String? = null
    private var mapVisible = true
    private var departuresVisible = true
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
        // Paint the shell and accept input before reading/parsing bundled timetables.
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                Triple(repository.bundledNetwork(), emmaRepository.routes, emmaRepository.catalogue)
            }
            initialNetwork = prepared.first
            bundledEmmaRoutes = prepared.second
            bundledCatalogue = prepared.third
            mutableState.update { current ->
                if (current.offlineMode || current.network != null) current
                else current.copy(network = prepared.first, loading = false,
                    selectedStopId = current.selectedStopId ?: prepared.first.stops.firstOrNull { it.id == "1" }?.id
                        ?: prepared.first.stops.firstOrNull()?.id)
            }
            recalculate()
            ensureBoardRequest()
            ensureNearbyRequest()
            ensureFavoriteRequest()
            val snapshot = state.value
            withContext(Dispatchers.Default) { TransitSearch.indexStops(snapshot.network?.stops.orEmpty(), snapshot.emmaStops) }
        }
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
                if (state.value.offlineMode && pack.loaded && pack.metadata?.id != offlineLoadedId) loadOfflineCatalogue()
            }
        }
        viewModelScope.launch {
            NetworkAccessPolicy.offline.collect { enabled -> applyOfflineMode(enabled) }
        }
        viewModelScope.launch {
            NetworkAccessPolicy.internetAvailable.collect { available ->
                mutableState.update { it.copy(internetAvailable = available) }
            }
        }
        // The first live refresh needs the ViaBusz road geometry; parse it before that, off the main thread.
        viewModelScope.launch(Dispatchers.IO) { routeEstimator }
        if (!NetworkAccessPolicy.offline.value) checkForUpdates(isAutoCheck = true)
    }

    /** Owned by the activity's STARTED lifecycle; all polling stops in the background. */
    suspend fun observe() = coroutineScope {
        nearbyActive = true
        if (favoritesVisible) ensureFavoriteRequest()
        launch {
            while (isActive) {
                if (mapVisible && departuresVisible) ensureNearbyRequest(force = true)
                delay(30_000)
            }
        }
        launch {
            while (isActive) {
                if (mapVisible || favoritesVisible) recalculateWhenIdle()
                delay(5_000)
            }
        }
        launch {
            while (isActive) {
                if (mapVisible) refreshEmmaData()
                // BKK is the live source wherever its area query covers the view; EMMA then only fills gaps
                // (other operators, beyond the view), so the national feed is polled half as often.
                delay(if (bkkCoversView()) EMMA_BACKGROUND_POLL_MS else EMMA_POLL_MS)
            }
        }
        launch {
            // The Worker caches vehicles for 5 s; polling faster only adds requests.
            while (isActive) {
                if (mapVisible) ensureBkkVehicles(force = true)
                delay(BKK_VEHICLE_POLL_MS)
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
            ensureBkkVehicles()
            mapBounds?.let(::ensureStopDirections)
            viewModelScope.launch { refreshEmmaData() }
            viewModelScope.launch { refreshData() }
            ensureBoardRequest(force = true)
            ensureNearbyRequest()
        } else { mapSettledJob?.cancel(); nearbyJob?.cancel(); boardJob?.cancel(); mapStopsJob?.cancel(); bkkVehicleJob?.cancel(); mapStopsGeneration++; stopDirectionsJob?.cancel(); stopDirectionsGeneration++ }
    }

    /** The planner uses the map/vehicles, but has no departure panel to keep polling. */
    fun setDeparturesVisible(visible: Boolean) {
        if (departuresVisible == visible) return
        departuresVisible = visible
        if (!visible) {
            boardJob?.cancel()
            nearbyJob?.cancel()
        } else {
            boardKey = null
            ensureBoardRequest()
            ensureNearbyRequest()
        }
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
            favoriteSelection = if (current.favorites.isEmpty()) emptyList()
                else FavoriteDepartures.stations(current.favorites, current.stopsById)
        }
        return favoriteSelection.map { station ->
            val catalogued=favoriteLines[station.stop.id]?.catalogue?.stops?.singleOrNull()
            val sources=setOfNotNull(favoriteLines[station.stop.id]?.source)
            if(catalogued==null) station.copy(sources=sources) else station.copy(stop=catalogued,platforms=listOf(catalogued),sources=sources)
        }
    }

    private suspend fun fetchBoards(stops: List<TransitStop>, window: Int, cache: Map<String, EmmaStopsParser.Board>, force: Boolean,
                                    semaphore: kotlinx.coroutines.sync.Semaphore): List<Pair<String, EmmaStopsParser.Board?>> = coroutineScope {
        val ids = stops.filter { it.id.startsWith("emma:") }.map { it.id }.distinct()
        val saved = ids.mapNotNull { id -> cache[id]?.takeIf { !force && Duration.between(it.fetchedAt, Instant.now()).seconds in 0..29 }?.let { id to it } }.toMap()
        val missing = stops.filter { it.id in ids && it.id !in saved }.distinctBy { it.id }
        // BKK platforms go to the Worker first, six per request; other platforms go to EMMA at the
        // same time, so a slow EMMA batch never holds back a BKK board or the other way around.
        // FUTÁR serves at most two hours, and knows rural Volán stops (Kecskéd, Császár…) without
        // their timetables, so longer Volán windows go straight to EMMA.
        val bkkTargets = missing.filter { BkkWorkerParser.workerStopId(it) != null && !(it.providerId == "volan" && window > BKK_MAX_WINDOW_MINUTES) }
        val bkkIds = bkkTargets.mapTo(HashSet()) { it.id }
        suspend fun emma(batch: List<String>): List<Pair<String, EmmaStopsParser.Board?>> {
            semaphore.acquire()
            return try { stopsRepository.boards(batch, window).toList() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (BuildConfig.DEBUG) android.util.Log.w("Emma", "Station board batch failed: ${batch.joinToString()}", error)
                batch.map { it to null }
            } finally { semaphore.release() }
        }
        val bkk = async<Map<String, EmmaStopsParser.Board?>> {
            if (bkkTargets.isEmpty()) emptyMap() else try { bkkRepository.boards(bkkTargets, window) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (BuildConfig.DEBUG) android.util.Log.w("BkkWorker", "Board failed", error); emptyMap() }
        }
        val fetched = missing.map { it.id }.filterNot { it in bkkIds }.chunked(6).map { batch -> async { emma(batch) } }
        val bkkBoards = bkk.await()
        // Platforms BKK could not answer (failed stop, failed batch, budget) fall back to EMMA. An empty
        // BKK board is final for BKK's own stops, but not for Volán: outside Budapest FUTÁR returns
        // the stop with no departures even when buses run.
        val retry = bkkTargets.filter { stop -> bkkBoards[stop.id].let { it == null || stop.providerId == "volan" && it.departures.isEmpty() } }
            .mapTo(HashSet()) { it.id }
        val fallback = retry.toList().chunked(6).map { batch -> async { emma(batch) } }
        saved.toList() + bkkBoards.mapNotNull { (id, board) -> board?.takeIf { id !in retry }?.let { id to it } } + fetched.awaitAll().flatten() + fallback.awaitAll().flatten()
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
                    // BKK platforms: serving lines from the Worker's batched boards (six stops per
                    // request). Any stop BKK could not answer uses EMMA's daily-cached lines.
                    val bkk = stations.map { it.stop }.filter { BkkWorkerParser.workerStopId(it) != null }.let { stops ->
                        if (stops.isEmpty()) emptyMap() else try { bkkRepository.servingLines(stops) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { if (BuildConfig.DEBUG) android.util.Log.w("BkkWorker", "Favourite lines failed", error); emptyMap() }
                    }
                    stations.map { station -> async {
                        val id=station.stop.id
                        if(id.startsWith("emma:")) {
                            semaphore.acquire()
                            try {
                                val result=bkk[id] ?: stopsRepository.servingLines(id,force).copy(source=DataSource.EMMA)
                                if(generation==favoriteGeneration) {
                                    favoriteLines[id]=result
                                    while(favoriteLines.size>64) favoriteLines.remove(favoriteLines.keys.first())
                                    if(result.stale) favoriteFailures+=id
                                    mergeStopRoutes(result.catalogue.routes)
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
        bkkVehicles = null
        bkkSnapshotRequest = null
        mutableState.update { it.copy(error = null, status = null, locationMessage = null) }
    }

    fun refresh() {
        mapSettledJob?.cancel()
        if (!state.value.stopIndexLoaded) viewModelScope.launch { refreshStopIndex() }
        viewModelScope.launch { refreshData(forceNetwork = true) }
        viewModelScope.launch { refreshEmmaData(force = true) }
        ensureBkkVehicles(force = true)
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
            recalculateWhenIdle()
        }
    }

    private suspend fun refreshData(forceNetwork: Boolean = false) {
        if (NetworkAccessPolicy.offline.value) return
        if (refreshMutex.isLocked) return
        refreshMutex.withLock {
            // Only a user-requested refresh shows the spinner; the 15 s background poll stays invisible
            // instead of re-emitting the whole UI state twice per poll.
            if (forceNetwork) mutableState.update { it.copy(refreshing = true) }
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
                recalculateWhenIdle()
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

    /**
     * Every UiState emission recomposes the screen. Many recalculations change nothing but the clock;
     * those keep the current state, so a clock-only emission happens at most every [NOW_REFRESH].
     * Countdowns are whole minutes; a quarter-minute of clock lag is invisible next to live prediction noise.
     */
    private fun settle(current: UiState, next: UiState, now: Instant): UiState =
        if (Duration.between(current.now, now) < NOW_REFRESH && next.copy(now = current.now) == current) current else next

    private fun recalculate() {
        if (NetworkAccessPolicy.offline.value) { recalculateOffline(); return }
        val now = Instant.now()
        val savedBoard = savedStationBoard(state.value, now)
        val snapshot = live?.takeIf { Duration.between(it.fetchedAt, now).seconds in 0..45 }
        val vehicles = mutableVehicles.value
        mutableState.update { current ->
            val selectedId = current.selectedStopId.takeUnless { current.showNearby }
            val cluster = if(selectedId!=null) current.selectedPlatforms else emptyList()
            val hasEmma = cluster.any { it.id.startsWith("emma:") } || selectedId?.startsWith("emma:") == true
            val validBoard = board?.takeIf { boardKey==current.boardRequestKey && it.stopId == selectedId && Duration.between(it.fetchedAt, now).seconds in 0..EmmaStopsParser.STALE_BOARD_SECONDS }
            // Predictions older than a minute are still far better than an empty panel; they are labelled.
            val boardStale = validBoard != null && Duration.between(validBoard.fetchedAt, now).seconds > EmmaStopsParser.FRESH_BOARD_SECONDS
            val emmaDepartures = if (hasEmma) EmmaStopsParser.freshDepartures(validBoard, selectedId, now, current.windowMinutes, EmmaStopsParser.STALE_BOARD_SECONDS) else emptyList()
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
                mergeStopRoutes(offlineBoard.routes, preferIncoming = false)
            }
            val effectiveEmmaDepartures = if (isOfflineActive) offlineBoard!!.departures else emmaDepartures
            val rawDepartures = (effectiveEmmaDepartures + localDepartures)
                .distinctBy { listOf(it.routeId, it.directionId, it.expectedAt) }
                .sortedBy { it.expectedAt }
            val departures = (current.boardingSelection?.let { filter -> rawDepartures.filter(filter::matches) } ?: rawDepartures)
                .sameOr(current.departures)
            val emma = emmaLive?.takeIf {
                Duration.between(it.fetchedAt, now).seconds in 0..120
            }
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
            val next = current.copy(
                now = now,
                favoriteStations = if (!favoritesVisible) current.favoriteStations else favoriteStations(current).map { station ->
                    station.copy(loading=favoriteLoading && (station.stop.id in visibleFavorites || visibleFavorites.isEmpty()),
                        unavailable=station.stop.id in favoriteFailures || !favoriteLoading && station.stop.id.startsWith("emma:") && station.stop.id !in favoriteLines)
                },
                favoritesLoading = favoriteLoading || !current.stopIndexLoaded && !stopIndexFailed && current.favorites.any { it.startsWith("emma:") },
                nearbyStations = nearbySelection.mapNotNull { station ->
                    val rows = station.platforms.flatMap { platform ->
                        if (platform.id.startsWith("emma:")) EmmaStopsParser.freshDepartures(nearbyBoards[platform.id], platform.id, now, current.windowMinutes, EmmaStopsParser.STALE_BOARD_SECONDS)
                        else current.network?.let { repository.departures(it, snapshot, platform.id, now, current.windowMinutes) }.orEmpty()
                    }.distinctBy { listOf(it.routeId, it.directionId, it.expectedAt) }.sortedBy { it.expectedAt }
                    if (!nearbyLoading && rows.isEmpty()) {
                        null
                    } else {
                        station.copy(departures = rows, loading = nearbyLoading, sources = rows.mapNotNullTo(LinkedHashSet()) { it.source },
                            unavailable = station.platforms.any { it.id in nearbyFailed || (it.id.startsWith("emma:") && nearbyBoards[it.id]?.let { board -> Duration.between(board.fetchedAt, now).seconds !in 0..60 } != false) } && !nearbyLoading)
                    }
                }.take(3).sameOr(current.nearbyStations),
                nearbyLoading = nearbyLoading || (!current.stopIndexLoaded && !stopIndexFailed && nearbySelection.isEmpty()),
                boardUpdatedAt = if (hasEmma) validBoard?.fetchedAt else null,
                error = null,
                departureBoardLoading = hasEmma && boardLoading,
                departureBoardUnavailable = hasEmma && !boardLoading && !isOfflineActive && ((boardFailure && emmaDepartures.isEmpty()) || (validBoard == null && localDepartures.isEmpty())),
                departureStatus = if (isOfflineActive) {
                    getApplication<Application>().getString(R.string.ui_offline_timetable_active)
                } else if (!hasEmma) null else when {
                    boardLoading && validBoard == null -> getApplication<Application>().getString(R.string.emma_board_loading)
                    (boardFailure || boardStale) && validBoard != null -> getApplication<Application>().getString(R.string.emma_board_stale)
                    boardFailure || validBoard == null -> getApplication<Application>().getString(R.string.emma_board_unavailable)
                    validBoard.limitReached -> getApplication<Application>().getString(R.string.emma_board_limit)
                    else -> null
                },
                isOfflineTimetableActive = isOfflineActive,
                departures = departures,
                selectedVehicle = current.selectedVehicle?.let { selected -> vehicles.find { it.id == selected.id } ?: selected.copy(routeEstimate = null) },
                trackedVehicle = current.trackedVehicle?.let { tracked -> vehicles.find { it.id == tracked.id } ?: tracked.copy(routeEstimate = null) },
                emmaRoutes = liveRouteCatalogue.update(liveRouteVehicles, stopRoutes, lineCatalogue, bundledCatalogue),
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
            settle(current, next, now)
        }
        refreshVehicles(snapshot, now)
        ensureVehicleJourney()
    }

    fun selectVehicle(id: String) {
        // A searched vehicle may be outside the loaded map area; selecting it moves the map there.
        val vehicle = mutableVehicles.value.find { it.id == id } ?: state.value.remoteVehicles.vehicles.find { it.id == id } ?: return
        manualStop = false
        mutableState.update { it.copy(showNearby = false, selectedVehicle = vehicle, trackedVehicle = null, selectedStopId = null, selectedPlatformIds = emptySet(), selectedPlace = null, boardingSelection = null) }
        vehicle.routeId?.let { loadLine(it) }
        ensureVehicleJourney()
        ensureBkkVehicles()
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
        ensureStopDirections(bounds)
        ensureBkkVehicles()
        if (!state.value.showNearby) return
        // Zoomed out past where stops are drawn, "nearby" would mean whatever lies within 1.5 km of
        // the map centre: re-selecting it after every pan scanned every stop and fetched boards.
        if (mapZoom < NEARBY_MIN_ZOOM) return
        mapSettledJob?.cancel()
        mapSettledJob = viewModelScope.launch {
            // Camera updates already arrive on idle. Coalesce rapid idle callbacks
            // without making every nearby board wait another 1.2 seconds.
            delay(150)
            val prev = lastSettledCenter
            val dist = if (prev != null) distanceMeters(prev, center) else Double.MAX_VALUE
            if (dist > 30.0 || nearbySelection.isEmpty()) {
                lastSettledCenter = center
                ensureNearbyRequest()
            }
        }
    }

    /** Warm the map catalogue during gestures without requesting nearby departure boards. */
    fun prefetchMapArea(bounds: MapBounds) {
        ensureMapStops(bounds)
        ensureStopDirections(bounds)
    }

    private fun ensureStopDirections(bounds: MapBounds) {
        if (!mapVisible || NetworkAccessPolicy.offline.value) return
        val now = Instant.now()
        if (stopDirectionAreas.any { it.area.covers(bounds) && Duration.between(it.fetchedAt, now).seconds in 0 until 3600 }) return
        if (stopDirectionsJob?.isActive == true && stopDirectionsRequest?.covers(bounds) == true) return
        val area = BkkWorkerRepository.queryArea(bounds) ?: return
        stopDirectionsJob?.cancel()
        val generation = ++stopDirectionsGeneration
        val networkGeneration = NetworkAccessPolicy.generation
        stopDirectionsRequest = area
        stopDirectionsJob = viewModelScope.launch {
            try {
                val directions = bkkRepository.stopDirections(area)
                if (generation != stopDirectionsGeneration || networkGeneration != NetworkAccessPolicy.generation) return@launch
                stopDirectionAreas.removeAll { it.area == area || Duration.between(it.fetchedAt, now).seconds !in 0 until 3600 }
                stopDirectionAreas.addLast(StopDirectionArea(area, Instant.now(), directions))
                while (stopDirectionAreas.size > 16) stopDirectionAreas.removeFirst()
                val areas = stopDirectionAreas.toList()
                val merged = withContext(Dispatchers.Default) { buildMap {
                    areas.forEach { putAll(it.directions) }
                } }
                if (generation != stopDirectionsGeneration || networkGeneration != NetworkAccessPolicy.generation) return@launch
                mutableState.update { if (it.bkkStopDirections == merged) it else it.copy(bkkStopDirections = merged) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                // Keep shape-derived arrows when metadata is temporarily unavailable.
            } finally {
                if (generation == stopDirectionsGeneration) stopDirectionsRequest = null
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
        val pending = pendingMapBounds
        if (mapStopsJob?.isActive == true && pending != null &&
            bounds.south >= pending.south && bounds.north <= pending.north &&
            bounds.west >= pending.west && bounds.east <= pending.east) return

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

        pendingMapBounds = area
        val bkkArea = BkkWorkerRepository.queryArea(bounds)
        mapStopsJob = viewModelScope.launch {
            // BKK's own stops arrive independently of EMMA's national area query.
            if (bkkArea != null) launch {
                try {
                    val catalogue = bkkRepository.areaStops(bkkArea)
                    if (generation != mapStopsGeneration || networkGeneration != NetworkAccessPolicy.generation || state.value.stopIndexLoaded) return@launch
                    mergeStopRoutes(catalogue.routes, preferIncoming = false)
                    val networkStops = state.value.network?.stops.orEmpty()
                    var workerOnly = emptyList<String>()
                    mergeEmmaStops { stops ->
                        val known = TransitCatalogueIndexes.stops(networkStops, stops).byId
                        CatalogueMerge.upsertStops(stops, catalogue.stops) { existing, _ -> existing == null }
                            .also { merged -> workerOnly = if (merged === stops) emptyList() else catalogue.stops.map { it.id }.filterNot(known::containsKey) }
                    }
                    workerOnlyStopIds += workerOnly
                    recalculate()
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) { if (BuildConfig.DEBUG) android.util.Log.w("BkkWorker", "Map stops failed", error) }
            }
            try {
                val catalogue = stopsRepository.mapArea(area)
                if (generation != mapStopsGeneration || networkGeneration != NetworkAccessPolicy.generation || state.value.stopIndexLoaded) return@launch
                if (loadedMapBoundsList.size >= 16) {
                    loadedMapBoundsList.removeAt(0)
                }
                loadedMapBoundsList.add(area)
                loadedMapBounds = area
                mergeStopRoutes(catalogue.routes)
                val workerOnly = workerOnlyStopIds.toHashSet()
                mergeEmmaStops { stops ->
                    CatalogueMerge.upsertStops(stops, catalogue.stops) { existing, stop ->
                        existing == null || existing.routeIds.isEmpty() || existing.id in workerOnly ||
                            stop.transportMode == "SUBWAY" && stop.routeIds.isNotEmpty()
                    }
                }
                recalculate()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
            } finally {
                if (generation == mapStopsGeneration) pendingMapBounds = null
            }
        }
    }

    /**
     * BKK vehicles for a grid-snapped box around the viewport. Runs beside the nationwide EMMA
     * poll; neither waits for the other. Small pans reuse the same URL and hit the caches.
     */
    private fun bkkCoversView(): Boolean {
        val snapshot = bkkVehicles ?: return false
        val view = mapBounds ?: return false
        val covered = snapshot.covered
        return covered !== NO_AREA && covered.west <= view.west && covered.east >= view.east &&
            covered.south <= view.south && covered.north >= view.north && snapshot.vehicles.isNotEmpty() &&
            Duration.between(snapshot.fetchedAt, Instant.now()).seconds in 0..LiveVehicleFeed.BKK_MAX_AGE_SECONDS
    }

    private fun ensureBkkVehicles(force: Boolean = false) {
        if (NetworkAccessPolicy.offline.value || !mapVisible) return
        val area = mapBounds?.let { view ->
            val pendingArea = bkkVehicleRequest?.first?.takeIf { bkkVehicleJob?.isActive == true && it.covers(view) }
            val coveredArea = bkkVehicles?.takeIf {
                Duration.between(it.fetchedAt, Instant.now()).seconds in 0..LiveVehicleFeed.BKK_MAX_AGE_SECONDS
            }?.covered?.takeIf { it !== NO_AREA && it.covers(view) }
            pendingArea ?: coveredArea ?: BkkWorkerRepository.queryArea(view)
        }
        // The open vehicle stays on BKK even when the view is too wide for an area query or it is elsewhere.
        val followed = (state.value.selectedVehicle ?: state.value.trackedVehicle)?.takeIf { BkkWorkerRepository.workerVehicleId(it) != null }
        if (area == null && followed == null) return
        val request = area to followed?.id
        if (bkkVehicleJob?.isActive == true && request == bkkVehicleRequest) return
        val current = bkkVehicles
        if (!force && current != null && request == bkkSnapshotRequest && request == bkkVehicleRequest && Duration.between(current.fetchedAt, Instant.now()).toMillis() in 0 until BKK_VEHICLE_POLL_MS) return
        bkkVehicleJob?.cancel()
        bkkVehicleRequest = request
        val generation = NetworkAccessPolicy.generation
        bkkVehicleJob = viewModelScope.launch {
            try {
                val snapshot = coroutineScope {
                    val single = async { followed?.let { vehicle -> try { bkkRepository.vehicle(vehicle) }
                        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() } }.orEmpty() }
                    val inArea = area?.let { bkkRepository.vehicles(it) }
                    val extra = single.await().filter { found -> inArea?.vehicles?.none { it.vehicle.id == found.vehicle.id } != false }
                    when {
                        inArea != null -> if (extra.isEmpty()) inArea else inArea.copy(vehicles = inArea.vehicles + extra)
                        extra.isNotEmpty() -> BkkWorkerRepository.VehicleSnapshot(extra, NO_AREA, Instant.now())
                        else -> null
                    }
                }
                if (generation != NetworkAccessPolicy.generation || NetworkAccessPolicy.offline.value) return@launch
                TransitDiagnostics.event("bkk-result") { "vehicles=${snapshot?.vehicles?.size ?: 0} hasArea=${area != null} followed=${followed != null}" }
                bkkVehicles = snapshot
                bkkSnapshotRequest = request.takeIf { snapshot != null }
                recalculateWhenIdle()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                // EMMA keeps supplying BKK's relay until the Worker answers again.
                TransitDiagnostics.event("bkk-error") { "type=${error.javaClass.simpleName} status=${(error as? BkkWorkerException)?.status}" }
                if (BuildConfig.DEBUG) android.util.Log.w("BkkWorker", "Vehicle refresh failed", error)
            }
        }
    }

    /**
     * Merges the live feeds on [vehicleLane] and publishes [vehicles] only when the list changed;
     * a change then recalculates the UI state that depends on it (selection, route catalogue, status).
     */
    private fun refreshVehicles(local: LiveSnapshot?, now: Instant) {
        if (publishingVehicles) return
        val current = state.value
        val inputs = LiveVehicleFeed.Inputs(local?.vehicles.orEmpty(),
            emmaLive?.takeIf { Duration.between(it.fetchedAt, now).seconds in 0..120 }, bkkVehicles,
            current.network?.stops.orEmpty(), current.emmaStops, current.nationwideNetwork, bundledEmmaRoutes)
        val feed = vehicleFeed
        vehicleInputs = inputs to now
        // Never cancelled by a newer request: a burst of recalculations would otherwise starve the merge.
        if (vehicleJob?.isActive == true) return
        vehicleJob = viewModelScope.launch {
            while (true) {
                val (pending, at) = vehicleInputs ?: break
                vehicleInputs = null
                val result = withContext(vehicleLane) { feed.update(pending, at, mutableVehicles.value) }
                if (feed !== vehicleFeed || NetworkAccessPolicy.offline.value) break
                if (result.vehicles === mutableVehicles.value && result.routeVehicles === liveRouteVehicles) continue
                TransitDiagnostics.event("feed") { "merged=${result.vehicles.size} bkk=${pending.bkk?.vehicles?.size ?: 0} emma=${pending.emma?.vehicles?.size ?: 0} local=${pending.local.size}" }
                mutableVehicles.value = result.vehicles
                liveRouteVehicles = result.routeVehicles
                publishingVehicles = true
                try { recalculate() } finally { publishingVehicles = false }
            }
        }
    }

    /**
     * Merges into the national stop list (tens of thousands of entries) on a worker thread, then applies
     * it back on the main thread. If another merge landed meanwhile, merges again on top of that one.
     * [merge] runs off the main thread: it must only read what it captured.
     */
    private suspend fun mergeEmmaStops(merge: (List<TransitStop>) -> List<TransitStop>) {
        while (true) {
            val base = state.value.emmaStops
            val merged = withContext(Dispatchers.Default) { merge(base) }
            if (merged === base) return
            if (state.value.emmaStops !== base) continue
            mutableState.update { it.copy(emmaStops = merged) }
            return
        }
    }

    private fun ensureNearbyRequest(force: Boolean = false) {
        if (NetworkAccessPolicy.offline.value) { recalculateOffline(); return }
        if (!mapVisible || !departuresVisible || !nearbyActive || !state.value.showNearby) return
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
            val semaphore = kotlinx.coroutines.sync.Semaphore(3)
            val results = coroutineScope {
                // Six platforms share one request, whichever stations they belong to.
                stations.flatMap { it.platforms }.distinctBy { it.id }.filter { it.id.startsWith("emma:") }.chunked(6).map { platforms -> async {
                    val stationResults = fetchBoards(platforms, window, nearbyBoards, force, semaphore)
                    // One slow batch must not hold back departures that are already available.
                    if (generation == nearbyGeneration) {
                        nearbyFailed = nearbyFailed + stationResults.filter { it.second == null }.map { it.first }
                        stationResults.forEach { (id, board) -> if (board != null) nearbyBoards[id] = board }
                        while (nearbyBoards.size > 36) nearbyBoards.remove(nearbyBoards.keys.first())
                        mergeStopRoutes(stationResults.flatMap { it.second?.catalogue?.routes.orEmpty() })
                        recalculate()
                    }
                    stationResults
                } }.awaitAll().flatten()
            }
            if (generation != nearbyGeneration) return@launch
            val enriched = results.flatMap { it.second?.catalogue?.stops.orEmpty() }.associateBy { it.id }
            mergeEmmaStops { stops -> CatalogueMerge.replaceStops(stops, enriched) }
            if (generation != nearbyGeneration) return@launch
            nearbyLoading = false
            recalculate()
        }
    }

    private fun ensureVehicleJourney(force: Boolean = false) {
        if (NetworkAccessPolicy.offline.value) return
        val vehicle = state.value.selectedVehicle ?: state.value.trackedVehicle ?: return
        val key = vehicle.id to vehicle.tripId
        if (!force && key == journeyKey) return
        // The same vehicle can briefly lose its trip when its record switches feeds; keep what is shown.
        if (!force && vehicle.tripId == null && journeyKey?.first == vehicle.id) return
        val sameVehicle = journeyKey?.first == vehicle.id
        journeyKey = key
        val generation = ++journeyGeneration
        journeyJob?.cancel()
        val tripId = vehicle.tripId?.takeIf { vehicle.providerId in setOf("volan", "bkk", "hev", "mav") }
        // A reload for the vehicle already open keeps its stops on screen instead of blanking the panel.
        mutableState.update { it.copy(vehicleJourney = it.vehicleJourney.takeIf { sameVehicle && tripId != null },
            vehicleJourneyError = false, vehicleJourneyLoading = tripId != null) }
        if (tripId == null) return
        journeyJob = viewModelScope.launch {
            try {
                // BKK first whenever the vehicle has a BKK trip (its own, or BKK's trip for a train whose
                // identity is EMMA's); EMMA serves railway/Volán trips BKK cannot supply and any failure.
                // A stalled Worker must not hold the panel for the HTTP timeout: EMMA answers after a short wait.
                val fromBkk = try { kotlinx.coroutines.withTimeoutOrNull(BKK_JOURNEY_TIMEOUT_MS) { bkkRepository.journey(vehicle) }?.copy(tripId = tripId) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { if (BuildConfig.DEBUG) android.util.Log.w("BkkWorker", "Trip details failed", error); null }
                val journey = fromBkk ?: run {
                    val text = journeyClient.execute(EmmaJourneyParser.QUERY, org.json.JSONObject().put("id", tripId))
                    kotlinx.coroutines.withContext(Dispatchers.Default) { EmmaJourneyParser.parse(text, tripId) }
                }
                if (journeyGeneration == generation && journeyKey == key) mutableState.update { current ->
                    // Display catalogue names for stops the app already knows, so a BKK-served trip
                    // reads like its board and map markers; BKK-only HÉV platforms stay out of the map.
                    val known = current.stopsById
                    val shown = if (fromBkk == null) journey else journey.copy(stops = journey.stops.map { known[it.id] ?: it })
                    current.copy(vehicleJourney = shown,
                        emmaStops = CatalogueMerge.upsertStops(current.emmaStops, shown.stops.filter(BkkWorkerParser::sharesCatalogueIdentity)) { existing, _ -> existing == null })
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                if (journeyGeneration == generation && journeyKey == key) mutableState.update { it.copy(vehicleJourneyError = true) }
            } finally {
                if (journeyGeneration == generation && journeyKey == key) mutableState.update { it.copy(vehicleJourneyLoading = false) }
            }
        }
    }


    // One lane: rapid toggles apply in the order they were made, so the last tap always wins.
    private val offlineToggles = Dispatchers.IO.limitedParallelism(1)
    fun setOfflineMode(enabled: Boolean) {
        viewModelScope.launch(offlineToggles) {
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
        stopDirectionsJob?.cancel(); stopDirectionsGeneration++
        placeSearchGeneration++; lineGeneration++; journeyGeneration++; nearbyGeneration++; favoriteGeneration++
        placeSearchJob?.cancel(); lineJob?.cancel(); journeyJob?.cancel(); nearbyJob?.cancel(); favoriteJob?.cancel(); boardJob?.cancel()
        ota.cancel()
        board = null; boardKey = null; recentBoards.clear(); nearbyBoards.clear(); favoriteLines.clear()
        boardLoading = false; nearbyLoading = false; favoriteLoading = false
        live = null; emmaLive = null; bkkVehicles = null; bkkSnapshotRequest = null; bkkVehicleJob?.cancel(); vehicleJob?.cancel(); vehicleInputs = null; vehicleFeed = LiveVehicleFeed(); liveRouteVehicles = emptyList()
        mutableVehicles.value = emptyList()
        mutableState.update { it.copy(offlineMode = enabled, selectedVehicle = null, trackedVehicle = null,
            vehicleJourney = null, vehicleJourneyLoading = false, loadingLineId = null, placesLoading = false, placeResults = emptyList(),
            departureBoardLoading = false, lineDetails = emptyMap(), boardingSelection = null, otaCheckLoading = false, otaDownloadProgress = null, showOtaDialog = false) }
        if (enabled) {
            onlineLineCatalogue = lineCatalogue
            onlineNetwork = state.value.network
            onlineStopIndexLoaded = state.value.stopIndexLoaded
            onlineCatalogue = state.value.emmaStops to state.value.emmaRoutes
            loadOfflineCatalogue()
        } else {
            offlineBoardJob?.cancel()
            offlineLoadedId = null
            lineCatalogue = onlineLineCatalogue
            stopRoutes = emptyList()
            onlineCatalogue?.let { saved -> mutableState.update { it.copy(network = onlineNetwork ?: initialNetwork, emmaStops = saved.first, emmaRoutes = saved.second,
                stopIndexLoaded = onlineStopIndexLoaded, stopStatus = null, lineCatalogueStatus = null) } }
            recalculate()
            refresh()
            mapBounds?.let { ensureMapStops(it) }
            viewModelScope.launch { refreshLineCatalogue() }
            viewModelScope.launch { refreshStopIndex() }
        }
    }
    private suspend fun loadOfflineCatalogue() {
        offlineBoardJob?.cancel()
        offlineBoardJob = null
        offlineBoardPending = false
        val generation = NetworkAccessPolicy.generation
        val catalogue = withContext(Dispatchers.IO) { runCatching { offlineData.catalogueSnapshot() }.getOrNull() }
        if (!NetworkAccessPolicy.offline.value || generation != NetworkAccessPolicy.generation) return
        if (catalogue == null) {
            offlineLoadedId = null
            lineCatalogue = emptyList()
            val message = getApplication<Application>().getString(R.string.offline_pack_missing)
            mutableState.update { it.copy(network = TransitNetwork(emptyList(), emptyList(), emptyList(), Instant.now(), "offline"),
                emmaStops = emptyList(), emmaRoutes = emptyList(), departures = emptyList(), nearbyStations = emptyList(), favoriteStations = emptyList(),
                stopIndexLoaded = true, stopStatus = message, lineCatalogueLoading = false, lineCatalogueStatus = message,
                nearbyLoading = false, favoritesLoading = false, loading = false, refreshing = false,
                departureStatus = message, status = message, isOfflineTimetableActive = true) }
            return
        }
        val observedId = state.value.offlinePack.metadata?.id
        if (observedId != null && observedId != catalogue.metadata.id) return
        offlineLoadedId = catalogue.metadata.id
        lineCatalogue = catalogue.routes
        mutableState.update { it.copy(network = TransitNetwork(emptyList(), emptyList(), emptyList(), Instant.now(), "offline"), emmaStops = catalogue.stops, emmaRoutes = catalogue.routes, stopIndexLoaded = true, stopStatus = null,
            lineCatalogueLoading = false, lineCatalogueStatus = null, loading = false, refreshing = false) }
        recalculateOffline()
    }
    private fun recalculateOffline() {
        if (!NetworkAccessPolicy.offline.value || offlineLoadedId == null) return
        if (offlineBoardJob?.isActive == true) {
            offlineBoardPending = true
            return
        }
        offlineBoardPending = false
        val current = state.value
        val generation = NetworkAccessPolicy.generation
        val key = current.boardRequestKey
        offlineBoardJob = viewModelScope.launch {
            try {
            val now = Instant.now()
            val expired = current.offlinePack.metadata?.validUntil?.let { now.atZone(budapest).toLocalDate() > it } == true
            val offlineStatus = getApplication<Application>().getString(if (expired) R.string.offline_expired else R.string.offline_scheduled)
            val all = current.emmaStops
            val anchor = NearbyDepartures.anchor(mapCenter, mapBounds, current.userLocation)
            val nearby = NearbyDepartures.select(all, anchor, mapBounds)
            val favorites = FavoriteDepartures.stations(current.favorites, all)
            val result = withContext(Dispatchers.IO) {
                val scopes = listOf(current.selectedPlatforms.map { it.id }) + nearby.map { it.platforms.map { p -> p.id } } + favorites.map { it.platforms.map { p -> p.id } }
                val boards = runCatching { offlineData.boards(scopes, now, current.windowMinutes) }.getOrElse { scopes.map { emptyList() } }
                Triple(boards.first(), nearby.mapIndexed { index, station -> station.copy(departures = boards[index + 1]) },
                    favorites.mapIndexed { index, station -> station.copy(departures = boards[index + 1 + nearby.size]) })
            }
            if (generation == NetworkAccessPolicy.generation && key == state.value.boardRequestKey) {
                mutableVehicles.value = emptyList()
                mutableState.update {
                it.copy(now = now, departures = result.first.let { rows -> it.boardingSelection?.let { selection -> rows.filter(selection::matches) } ?: rows },
                    nearbyStations = result.second, favoriteStations = result.third, nearbyLoading = false, favoritesLoading = false,
                    departureBoardLoading = false, departureBoardUnavailable = false, liveStopMinutes = emptyList(),
                    departureStatus = offlineStatus, status = offlineStatus,
                    isOfflineTimetableActive = true, emmaStatus = getApplication<Application>().getString(R.string.offline_live_unavailable),
                    boardUpdatedAt = null, loading = false, refreshing = false)
                }
            }
            } finally {
                if (offlineBoardJob === coroutineContext[Job]) {
                    offlineBoardJob = null
                    if (offlineBoardPending) {
                        offlineBoardPending = false
                        recalculateOffline()
                    }
                }
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

    fun setThemeMode(mode: ThemeMode) {
        if (state.value.themeMode == mode) return
        preferences.edit().putString("theme_mode", mode.key).apply()
        mutableState.update { it.copy(themeMode = mode) }
    }

    fun setMapStyle(style: hu.app.bmenetrend.ui.MapStyle) {
        if (state.value.mapStyle == style) return
        preferences.edit().putString("map_style", style.key).apply()
        mutableState.update { it.copy(mapStyle = style) }
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
        mergeStopRoutes(index.catalogue.routes)
        mutableState.update { current ->
            val updated = index.catalogue.stops.associateByTo(LinkedHashMap(index.catalogue.stops.size)) { it.id }
            current.emmaStops.filter { it.routeIds.isNotEmpty() }.forEach { stop ->
                val indexed = updated[stop.id]
                updated[stop.id] = if (indexed == null) stop
                else if (indexed.transportMode == "SUBWAY" && indexed.routeIds.isNotEmpty()) indexed
                else indexed.copy(
                    routeIds = (indexed.routeIds + stop.routeIds).distinct(),
                    transportMode = indexed.transportMode ?: stop.transportMode)
            }
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

    // While the user drags or flings the map, periodic live-data refreshes do not re-emit UiState:
    // each emission recomposes the whole UI on the main thread, which also handles the gesture.
    // The update is applied as soon as the map settles. User actions still update immediately.
    private var mapGesture = false
    private var recalculatePending = false
    fun setMapGesture(active: Boolean) {
        mapGesture = active
        if (!active && recalculatePending) { recalculatePending = false; recalculate() }
    }
    private fun recalculateWhenIdle() {
        if (mapGesture && mapVisible) recalculatePending = true else recalculate()
    }

    // Nothing renders the zoom: keep it here instead of re-emitting UiState (a full recomposition)
    // after every pinch.
    private var mapZoom = 13.2
    fun updateMapZoom(zoom: Double) { mapZoom = zoom }

    private fun ensureBoardRequest(force: Boolean = false) {
        if (NetworkAccessPolicy.offline.value) { recalculateOffline(); return }
        if (!mapVisible || !departuresVisible) return
        if (state.value.showNearby) { boardJob?.cancel(); boardKey = null; return }
        val key = state.value.boardRequestKey
        if (!force && key == boardKey) return
        boardKey = key
        boardJob?.cancel()
        // Revisiting an exact station/platform/window selection can display its
        // still-fresh board immediately. Forced refresh always bypasses this path.
        val remembered = recentBoards[key]?.let { it to Duration.between(it.fetchedAt, Instant.now()).seconds }
        board = remembered?.takeIf { it.second in 0..EmmaStopsParser.STALE_BOARD_SECONDS }?.first
        boardFailure = false
        if (!force && board != null && remembered!!.second in 0..19) {
            boardLoading = false
            recalculate()
            return
        }
        // Tapping a stop from the nearby panel: show its already-fetched platforms at once and
        // refresh behind them instead of an empty, loading board.
        if (board == null) board = nearbySeed(key.stopId)
        val selectedId = key.stopId
        val emmaStops = if (selectedId != null) {
            state.value.selectedPlatforms.filter { it.id.startsWith("emma:") }
                .ifEmpty { if (selectedId.startsWith("emma:")) state.value.stops.filter { it.id == selectedId } else emptyList() }
        } else emptyList()
        boardLoading = emmaStops.isNotEmpty()
        recalculate()
        if (boardLoading) boardJob = viewModelScope.launch { refreshBoard(waitForCurrent = true) }
    }

    private fun nearbySeed(selectedId: String?): EmmaStopsParser.Board? {
        if (selectedId == null || nearbyWindow != state.value.windowMinutes) return null
        val targets = EmmaBoardTargets.select(state.value.selectedPlatforms.filter { it.id.startsWith("emma:") })
        if (targets.isEmpty()) return null
        val now = Instant.now()
        val boards = targets.map { target ->
            nearbyBoards[target.id]?.takeIf { Duration.between(it.fetchedAt, now).seconds in 0..EmmaStopsParser.FRESH_BOARD_SECONDS } ?: return null
        }
        return EmmaStopsParser.combineBoards(selectedId, boards)
    }

    private suspend fun refreshBoard(waitForCurrent: Boolean = false) {
        if (NetworkAccessPolicy.offline.value) { recalculateOffline(); return }
        if (!mapVisible || !departuresVisible || state.value.showNearby) return
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
                        if (!boardFailure) recentBoards[requestKey] = requireNotNull(board)
                        val routes = results.flatMap { it.catalogue.routes }.distinctBy { it.id }
                        mergeStopRoutes(routes, preferIncoming = false)
                        val newStopsMap = results.flatMap { it.catalogue.stops }.associateBy { it.id }
                        mutableState.update { current ->
                            val merged = CatalogueMerge.replaceStops(current.emmaStops, newStopsMap)
                            if (merged === current.emmaStops) current else current.copy(emmaStops = merged)
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
        if (vehicle?.routeId == null || journey == null || journey.patternCode == null && journey.directionId == null ||
            journey.tripId != vehicle.tripId || journey.stops.none { it.id == id }) {
            selectStop(id); return
        }
        // BKK trip details have no EMMA pattern code; the board then filters by source direction.
        val pattern = LinePattern(journey.patternCode ?: "direction:${journey.directionId}", setOfNotNull(journey.patternCode),
            journey.destination ?: vehicle.destination.orEmpty(), journey.stops, journey.geometry, directionId = journey.directionId)
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

    /**
     * A departure whose trip follows none of the line's published variants (a short working, such
     * as a 762 turning back at Biatorbágy, autóbusz-forduló) gets its own trip as an extra variant,
     * so the line card can show that trip's stops instead of "stops unavailable".
     */
    fun loadDepartureTrip(routeId: String, departure: Departure) {
        val trip = departure.directionId ?: return
        if (NetworkAccessPolicy.offline.value) return
        val line = state.value.lineDetails[routeId] ?: return
        if (line.patterns.any { trip in it.codes }) return
        if (departureTripId == trip && departureTripJob?.isActive == true) return
        departureTripId = trip
        departureTripJob?.cancel()
        val networkGeneration = NetworkAccessPolicy.generation
        // The card shows its loading bar, not "stops unavailable", while the trip is fetched.
        mutableState.update { it.copy(loadingLineId = routeId) }
        departureTripJob = viewModelScope.launch {
            try { loadDepartureTripPattern(routeId, departure, trip, networkGeneration) }
            finally { if (departureTripId == trip) mutableState.update { if (it.loadingLineId == routeId) it.copy(loadingLineId = null) else it } }
        }
    }

    private suspend fun loadDepartureTripPattern(routeId: String, departure: Departure, trip: String, networkGeneration: Long) {
        val journey = try {
            if (BkkWorkerParser.workerTripId(trip) != null) bkkRepository.journey(trip) else {
                val text = journeyClient.execute(EmmaJourneyParser.QUERY, org.json.JSONObject().put("id", trip))
                withContext(Dispatchers.Default) { EmmaJourneyParser.parse(text, trip) }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            if (BuildConfig.DEBUG) android.util.Log.w("BkkWorker", "Departure trip failed", error)
            null
        }
        if (journey == null || journey.stops.size < 2 || networkGeneration != NetworkAccessPolicy.generation) return
        val pattern = LinePattern(id = "trip:$trip", codes = setOf(trip),
            headsign = departure.destination.ifBlank { journey.destination ?: journey.stops.last().name },
            stops = journey.stops, geometry = journey.geometry, serviceCount = 1, directionId = departure.sourceDirectionId)
        mutableState.update { current ->
            val details = current.lineDetails[routeId] ?: return@update current
            if (details.patterns.any { trip in it.codes }) current
            else current.copy(lineDetails = current.lineDetails + (routeId to details.copy(patterns = details.patterns + pattern)))
        }
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
        val emmaRouteId = when {
            id.startsWith("emma:") -> id.removePrefix("emma:")
            id.startsWith("mav:") -> {
                val rest = id.removePrefix("mav:")
                if (rest.startsWith("1:") || rest.startsWith("BKK:")) rest else "1:$rest"
            }
            id.startsWith("bkk:") -> {
                val rest = id.removePrefix("bkk:")
                if (rest.startsWith("BKK:")) rest else "BKK:$rest"
            }
            id.startsWith("volan:") -> {
                val rest = id.removePrefix("volan:")
                if (rest.startsWith("hkir:")) rest else "hkir:$rest"
            }
            id.startsWith("hev:") -> {
                val rest = id.removePrefix("hev:")
                if (rest.startsWith("1:") || rest.startsWith("BKK:")) rest else "1:$rest"
            }
            else -> id.substringAfter(":")
        }
        val generation = ++lineGeneration
        lineJob?.cancel()
        mutableState.update { it.copy(loadingLineId = id, lineErrorId = null) }
        lineJob = viewModelScope.launch {
            try {
                // Any known BKK line identity first, including railway and HÉV lines.
                val today = LocalDate.now(budapest)
                val fromBkk = if (BkkWorkerParser.workerRouteId(id) == null) null else try { bkkRepository.routeDetails(id, today) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { if (BuildConfig.DEBUG) android.util.Log.w("BkkWorker", "Line details failed", error); null }
                val emmaRail = if (fromBkk != null && id.startsWith("mav:BKK:")) emmaRailLine(id, fromBkk, today) else null
                val details = emmaRail ?: fromBkk?.let { bkk ->
                    // Catalogue names for stops the app already knows, as for BKK trip details.
                    val known = state.value.stopsById
                    bkk.copy(patterns = bkk.patterns.map { pattern -> pattern.copy(stops = pattern.stops.map { stop ->
                        known[stop.id]?.let { it.copy(routeIds = (it.routeIds + id).distinct()) } ?: stop }) })
                } ?: run {
                    val text = journeyClient.execute(EmmaRouteParser.QUERY, org.json.JSONObject().put("id", emmaRouteId)
                        .put("date", today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)))
                    kotlinx.coroutines.withContext(Dispatchers.Default) { EmmaRouteParser.parse(text, id) }
                }
                if (lineGeneration == generation) {
                    lineLoadedAt[id] = Instant.now()
                    mergeStopRoutes(listOf(details.route.copy(name = if (details.route.name == details.route.number) (route?.name ?: details.route.name) else details.route.name)))
                    mutableState.update { current ->
                        current.copy(lineDetails = current.lineDetails + (id to details),
                            emmaStops = CatalogueMerge.upsertStops(current.emmaStops,
                                details.patterns.flatMap { it.stops }.filter(BkkWorkerParser::sharesCatalogueIdentity)) { existing, _ -> existing == null })
                    }
                    recalculate()
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
                android.util.Log.e("TransitViewModel", "Failed to load line $id", e)
                if (lineGeneration == generation) mutableState.update { it.copy(lineErrorId = id) }
            } finally {
                if (lineGeneration == generation) mutableState.update { it.copy(loadingLineId = null) }
            }
        }
    }

    /**
     * FUTÁR publishes some railway lines with only part of their variants: G10 has a single
     * Hegyeshalom → Budapest-Déli variant, so outbound trains matched no line direction. EMMA has
     * every variant; prefer it whenever it knows termini that BKK's details are missing.
     */
    private suspend fun emmaRailLine(id: String, bkk: LineDetails, today: LocalDate): LineDetails? {
        val number = bkk.route.number
        val emmaId = (lineCatalogue + stopRoutes).firstOrNull {
            it.providerId == "mav" && it.id.startsWith("mav:1:") && it.number.equals(number, ignoreCase = true)
        }?.id ?: return null
        return try {
            val text = journeyClient.execute(EmmaRouteParser.QUERY, org.json.JSONObject().put("id", emmaId.removePrefix("mav:"))
                .put("date", today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)))
            val emma = withContext(Dispatchers.Default) { EmmaRouteParser.parse(text, emmaId) }
            // This line's departures come from BKK and carry no EMMA pattern codes, so the variants
            // match them by terminus (BoardingSelection) instead of by codes they can never have.
            emma.copy(route = emma.route.copy(id = id), patterns = emma.patterns.map { pattern ->
                pattern.copy(codes = emptySet(), stops = pattern.stops.map { it.copy(routeIds = (it.routeIds - emmaId + id).distinct()) })
            }).takeIf { (it.patterns.mapTo(HashSet()) { p -> p.stops.last().id } - bkk.patterns.mapTo(HashSet()) { p -> p.stops.last().id }).isNotEmpty() }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { if (BuildConfig.DEBUG) android.util.Log.w("Emma", "Rail line details failed", error); null }
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

    /**
     * Map search. Stations and live vehicles are searched remotely whenever online (whatever the tab);
     * the geocoder only when [enabled]. BKK's station records lead; EMMA only adds stations BKK did not
     * return (railway/nationwide), and neither replaces the other's records in the results.
     */
    fun searchPlaces(query: String, enabled: Boolean) {
        val generation = ++placeSearchGeneration
        placeSearchJob?.cancel()
        val online = !NetworkAccessPolicy.offline.value && query.trim().length >= 2
        val shouldSearch = enabled && online
        val cachedPlaces = if (shouldSearch) placesRepository.cached(query) else null
        mutableState.update { it.copy(placeQuery = query, placeResults = cachedPlaces.orEmpty(), placesLoading = shouldSearch && cachedPlaces == null, placesUnavailable = false,
            remoteStations = it.remoteStations.takeIf { remote -> remote.query == query } ?: RemoteStationResults(),
            remoteVehicles = it.remoteVehicles.takeIf { remote -> remote.query == query } ?: RemoteVehicleResults()) }
        if (!online) return
        placeSearchJob = viewModelScope.launch {
            delay(250)
            try {
                // A cached/fast geocoder response must not wait for station search.
                val placesAsync = if (!shouldSearch) null else async {
                    val places = placesRepository.search(query)
                    if (generation == placeSearchGeneration) mutableState.update { it.copy(placeResults = places, placesLoading = false) }
                }
                val near = state.value.userLocation ?: mapCenter
                val vehiclesAsync = async {
                    val found = try { bkkRepository.searchVehicles(query, near).map { it.vehicle } }
                        catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
                    if (generation == placeSearchGeneration) mutableState.update { it.copy(remoteVehicles = RemoteVehicleResults(query, found)) }
                }
                val bkkAsync = async {
                    try { bkkRepository.search(query, near) }
                    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                }
                val stationsAsync = async {
                    runCatching { stopsRepository.search(query).stops }.getOrDefault(emptyList())
                }
                val bkk = bkkAsync.await()
                val bkkStops = bkk?.catalogue?.stops.orEmpty()
                // BKK's results are shown as soon as they arrive, before EMMA's supplement.
                if (bkkStops.isNotEmpty() && generation == placeSearchGeneration) {
                    publishRemoteStations(query, bkkStops, bkk!!.stopSources, emptyList())
                    mergeStopRoutes(bkk.catalogue.routes, preferIncoming = false)
                    mutableState.update { current ->
                        // The catalogue keeps saved-stop identities; BKK only fills stops it lacks.
                        val merged = CatalogueMerge.upsertStops(current.emmaStops, bkkStops) { existing, _ -> existing == null }
                        if (merged === current.emmaStops) current else {
                            bkkStops.forEach { if (it.id !in current.stopsById) workerOnlyStopIds += it.id }
                            current.copy(emmaStops = merged)
                        }
                    }
                    recalculate()
                }
                val stations = stationsAsync.await()
                if (generation != placeSearchGeneration) return@launch
                if (stations.isNotEmpty() || bkk != null) publishRemoteStations(query, bkkStops, bkk?.stopSources.orEmpty(), stations)
                if (stations.isNotEmpty()) {
                    mutableState.update { current ->
                        val merged = CatalogueMerge.upsertStops(current.emmaStops, stations) { existing, _ -> existing == null || existing.routeIds.isEmpty() || existing.id in workerOnlyStopIds }
                        if (merged === current.emmaStops) current else current.copy(emmaStops = merged)
                    }
                    recalculate()
                }
                placesAsync?.await()
                vehiclesAsync.await()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == placeSearchGeneration) mutableState.update { it.copy(placesLoading = false, placesUnavailable = shouldSearch) }
            }
        }
    }

    private suspend fun publishRemoteStations(query: String, bkk: List<TransitStop>, bkkSources: Map<String, DataSource>, emma: List<TransitStop>) {
        val results = withContext(Dispatchers.Default) {
            val bkkIds = bkk.mapTo(HashSet()) { it.id }
            val added = emma.filter { it.id !in bkkIds }
            val sourceOf = HashMap<String, DataSource>()
            bkk.forEach { sourceOf[it.id.removePrefix("emma:")] = bkkSources[it.id] ?: DataSource.BKK }
            added.forEach { sourceOf.putIfAbsent(it.id.removePrefix("emma:"), DataSource.EMMA) }
            // BKK first in the input keeps BKK's records as each consolidated group's members.
            val stops = StopClusters.consolidateForSearch(bkk + added)
            RemoteStationResults(query, stops, stops.associate { stop ->
                stop.id to (stop.sourceStopIds + stop.id.removePrefix("emma:")).mapNotNullTo(LinkedHashSet()) { sourceOf[it] }
            })
        }
        mutableState.update { if (it.placeQuery == query) it.copy(remoteStations = results) else it }
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

    private var nearestFix: Triple<GeoPoint, List<TransitStop>, String?>? = null
    /** GPS fixes arrive every couple of seconds: rescan the catalogue only after real movement or a catalogue change. */
    private fun nearestToFix(stops: List<TransitStop>, point: GeoPoint): String? {
        nearestFix?.let { (at, scanned, id) -> if (scanned === stops && distanceMeters(at, point) < NEAREST_RESCAN_METERS) return id }
        return nearestStop(stops, point).also { nearestFix = Triple(point, stops, it) }
    }

    fun updateHeading(degrees: Float?) { mutableHeading.value = degrees }

    fun updateLocation(point: GeoPoint) {
        mutableLivePosition.value = point
        val published = state.value.userLocation
        val now = android.os.SystemClock.elapsedRealtime()
        if (published != null && distanceMeters(published, point) < LOCATION_PUBLISH_METERS &&
            now - lastPublishedFixAt < LOCATION_PUBLISH_MS) return
        lastPublishedFixAt = now
        mutableState.update { current ->
            val nearest = nearestToFix(current.stops, point)
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

    fun setOtaChannel(channel: UpdateChannel) = ota.setChannel(channel)
    fun checkForUpdates(isAutoCheck: Boolean = false) = ota.check(isAutoCheck)
    fun startOtaDownloadAndInstall(context: Context, info: OtaUpdateInfo) = ota.downloadAndInstall(context, info)
    fun dismissOtaDialog() = ota.dismissDialog()
}


private val NOW_REFRESH: Duration = Duration.ofSeconds(15)
private const val NEAREST_RESCAN_METERS = 10.0
private const val EMMA_POLL_MS = 10_000L
/** Smaller moves only animate the map dot; stops, boards and the planner follow at this granularity. */
private const val LOCATION_PUBLISH_METERS = 8.0
private const val LOCATION_PUBLISH_MS = 5_000L
private const val EMMA_BACKGROUND_POLL_MS = 20_000L
private const val BKK_JOURNEY_TIMEOUT_MS = 5_000L
/** Covers nothing: a snapshot holding only the followed vehicle must not hide EMMA's vehicles anywhere. */
private val NO_AREA = MapBounds(0.0, 0.0, 0.0, 0.0)
/** BKK's vehicle cadence and the Worker's 5 s cache make a faster poll pointless. */
private const val BKK_VEHICLE_POLL_MS = 10_000L
/** Ordinary stops are drawn from zoom 12; nearby boards follow the map only from just below that. */
private const val NEARBY_MIN_ZOOM = 11.0

/** Returns [previous] when content-equal, so identity-keyed UI and map work is skipped. */
/** FUTÁR's arrivals endpoint answers at most this far ahead (see BkkWorkerRepository). */
private const val BKK_MAX_WINDOW_MINUTES = 120

internal fun <T> List<T>.sameOr(previous: List<T>): List<T> = if (this == previous) previous else this

internal fun nearestStop(network: TransitNetwork, location: GeoPoint): String? = nearestStop(network.stops, location)
/**
 * Runs over the national catalogue (tens of thousands of stops) on the main thread. Ranks by the
 * equirectangular approximation, no trigonometry per stop; at the distances where the choice
 * matters it orders stops exactly as the haversine distance does.
 */
internal fun nearestStop(stops: Collection<TransitStop>, location: GeoPoint): String? {
    val lonScale = cos(Math.toRadians(location.lat))
    var best: TransitStop? = null
    var bestSquared = Double.MAX_VALUE
    for (stop in stops) {
        if (stop.station) continue
        val dLat = stop.position.lat - location.lat
        val dLon = (stop.position.lon - location.lon) * lonScale
        val squared = dLat * dLat + dLon * dLon
        if (squared < bestSquared) { bestSquared = squared; best = stop }
    }
    return best?.id
}

internal fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val lat = Math.toRadians(b.lat - a.lat)
    val lon = Math.toRadians(b.lon - a.lon)
    val haversine = sin(lat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(lon / 2).pow(2)
    return 6_371_000 * 2 * atan2(sqrt(haversine.coerceIn(0.0, 1.0)), sqrt((1 - haversine).coerceIn(0.0, 1.0)))
}

private fun MapBounds.covers(view: MapBounds): Boolean =
    west <= view.west && east >= view.east && south <= view.south && north >= view.north
