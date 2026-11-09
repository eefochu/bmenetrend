package hu.app.bmenetrend

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import hu.app.bmenetrend.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant

enum class PlannerField { ORIGIN, DESTINATION }
enum class PlannerError { UNAVAILABLE, NO_ROUTE, SAME_PLACE, LOCATION, OUTSIDE_AREA, DEPARTURE_PASSED, OFFLINE_DATE, OFFLINE_ENDPOINT, OFFLINE_PACK }
@Immutable
data class PlannerState(
    val origin: PlannerEndpoint? = null, val destination: PlannerEndpoint? = null,
    val useLocation: Boolean = true, val editing: PlannerField? = null, val query: String = "",
    val suggestions: List<PlannerEndpoint> = emptyList(), val searching: Boolean = false,
    val nearbyOriginSearch: Boolean = false,
    val searchUnavailable: Boolean = false, val planning: Boolean = false,
    val error: PlannerError? = null, val result: TripPlan? = null, val selectedId: String? = null,
    val departure: Instant? = null,
    val offline: Boolean = false,
    val offlineValidUntil: String? = null,
    val userLocation: GeoPoint? = null,
    val recommendation: PlannerEndpoint? = null,
    val localProviders: Set<String> = LocalPlanningService.providerIds,
)

class PlannerViewModel internal constructor(
    application: Application,
    private val backend: PlannerBackend,
    private val loadNetwork: suspend () -> TransitNetwork,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application,
        EmmaPlannerBackend(EmmaGraphqlClient(EmmaGraphqlClient.plannerHttpClient())),
        TransitRepository(application)::planningNetwork)

    private val offlineStore = hu.app.bmenetrend.offline.OfflinePackStore(application)
    private val offlinePlanner = hu.app.bmenetrend.offline.OfflineTripPlanner(offlineStore)

    private val places = PlacesRepository(application)
    private val stopsRepository = EmmaStopsRepository(application)
    private val planner = HybridTripPlanner(backend)
    private var stopCatalogue = emptyList<TransitStop>()
    private val mutable = MutableStateFlow(PlannerState())
    val state: StateFlow<PlannerState> = mutable
    private var searchJob: Job? = null
    private var planJob: Job? = null
    private var warmupJob: Job? = null
    private var warmupStarted = false
    private var searchGeneration = 0
    private var planGeneration = 0
    private var index = emptyList<TransitSearch.StopEntry>()
    private var indexJob: Job? = null
    private var indexedLocal: List<TransitStop>? = null
    private var indexedNational: List<TransitStop>? = null
    private var location: GeoPoint? = null
    private var offlinePackId: String? = null

    init {
        viewModelScope.launch {
            hu.app.bmenetrend.offline.NetworkAccessPolicy.offline.collect { enabled ->
                invalidate()
                backend.clearRouteCache()
                closeSearch()
                val metadata = withContext(Dispatchers.IO) { offlineStore.metadata() }
                mutable.update { it.copy(offline = enabled, offlineValidUntil = metadata?.validUntil?.toString()) }
            }
        }
    }

    fun updateStops(stops: List<TransitStop>, national: List<TransitStop> = emptyList()) {
        val local = if (hu.app.bmenetrend.offline.NetworkAccessPolicy.offline.value) emptyList() else stops
        if (indexedLocal === local && indexedNational === national) return
        indexedLocal = local
        indexedNational = national
        indexJob?.cancel()
        indexJob = viewModelScope.launch {
            val prepared = withContext(Dispatchers.Default) {
                val catalogue = if (national.isEmpty()) local else local + national
                catalogue to TransitSearch.indexStops(local, national)
            }
            stopCatalogue = prepared.first
            index = prepared.second
            if (state.value.nearbyOriginSearch) loadNearbyOrigins()
            else if (state.value.editing != null) search(state.value.query)
        }
    }

    fun updateLocation(point: GeoPoint?) {
        location = point
        mutable.update {
            val updated = if (point != null && it.useLocation) it.copy(origin = currentEndpoint(point), error = null) else it
            updated.copy(userLocation = point)
        }
        if (state.value.nearbyOriginSearch) loadNearbyOrigins()
    }

    fun updateOfflinePack(metadata: hu.app.bmenetrend.offline.OfflinePackMetadata?) {
        if (state.value.offline && offlinePackId != metadata?.id) invalidate()
        offlinePackId = metadata?.id
        mutable.update { it.copy(offlineValidUntil = metadata?.validUntil?.toString()) }
    }

    private fun currentEndpoint(point: GeoPoint) = PlannerEndpoint("current", "", "", point)

    fun useCurrentLocation() {
        invalidate()
        if (state.value.offline) {
            val first = state.value.suggestions.firstOrNull() ?: location?.let { point ->
                stopCatalogue.asSequence()
                    .filter { !it.station && it.routeIds.isNotEmpty() }
                    .map { it to StopClusters.distanceMeters(point, it.position) }
                    .filter { it.second <= 1_500.0 }
                    .minByOrNull { it.second }
                    ?.first?.let { PlannerEndpoint.fromStop(it) }
            }
            if (state.value.nearbyOriginSearch && first != null) {
                choose(first)
                return
            }
            mutable.update { it.copy(editing = PlannerField.ORIGIN, query = "", nearbyOriginSearch = true,
                recommendation = null, suggestions = emptyList(), searchUnavailable = false) }
            loadNearbyOrigins()
            return
        }
        mutable.update { it.copy(origin = location?.let(::currentEndpoint), useLocation = true, editing = null,
            query = "", suggestions = emptyList(), error = if (location == null) PlannerError.LOCATION else null) }
        closeSearch()
    }

    private fun loadNearbyOrigins() {
        val generation = ++searchGeneration
        searchJob?.cancel()
        val point = location
        mutable.update { it.copy(searching = point != null, error = if (point == null) PlannerError.LOCATION else null) }
        if (point == null) return
        val catalogue = stopCatalogue
        searchJob = viewModelScope.launch {
            val nearby = withContext(Dispatchers.Default) {
                catalogue.asSequence()
                    .filter { !it.station && it.routeIds.isNotEmpty() }
                    .map { it to StopClusters.distanceMeters(point, it.position) }
                    .filter { it.second <= 1_500.0 }
                    .sortedBy { it.second }
                    .distinctBy { it.first.id }
                    .take(12)
                    .map { PlannerEndpoint.fromStop(it.first) }
                    .toList()
            }
            if (generation == searchGeneration && state.value.nearbyOriginSearch) {
                mutable.update { it.copy(suggestions = nearby, searching = false) }
            }
        }
    }

    fun edit(field: PlannerField) {
        warmupJob?.cancel()
        warmupStarted = false
        mutable.update { it.copy(editing = field, nearbyOriginSearch = false, query = "", suggestions = emptyList(), recommendation = null, searchUnavailable = false) }
    }

    fun closeSearch() {
        searchGeneration++; searchJob?.cancel()
        mutable.update { it.copy(editing = null, nearbyOriginSearch = false, searching = false, recommendation = null) }
        warmOnlinePlan()
    }

    internal fun consolidateEndpoints(endpoints: List<PlannerEndpoint>): List<PlannerEndpoint> {
        if (endpoints.isEmpty()) return emptyList()
        val stations = endpoints.filter { it.isStation }
        val places = endpoints.filterNot { it.isStation }

        val groups = stations.groupBy { StopClusters.cleanName(it.name) }
        val consolidatedStations = mutableListOf<PlannerEndpoint>()

        for ((_, nameGroup) in groups) {
            if (nameGroup.size == 1) {
                consolidatedStations.add(nameGroup[0])
                continue
            }
            val visited = BooleanArray(nameGroup.size)
            for (i in nameGroup.indices) {
                if (visited[i]) continue
                visited[i] = true
                val seed = nameGroup[i]
                val cluster = mutableListOf(seed)
                for (j in i + 1 until nameGroup.size) {
                    if (visited[j]) continue
                    val other = nameGroup[j]
                    val dist = StopClusters.distanceMeters(seed.position, other.position)
                    if (dist <= StopClusters.MAX_HUB_DISTANCE_METERS) {
                        visited[j] = true
                        cluster.add(other)
                    }
                }
                if (cluster.size == 1) {
                    consolidatedStations.add(seed)
                } else {
                    val railEndpoint = cluster.firstOrNull {
                        it.transportMode == "RAIL" || it.providerIds.contains("mav") ||
                            it.name.contains("vasútállomás", ignoreCase = true) || it.name.contains("pályaudvar", ignoreCase = true)
                    }
                    val primary = railEndpoint ?: cluster.first()
                    val bestName = cluster.map { it.name }
                        .filterNot { it.contains("(") || it.contains("[") }
                        .minByOrNull { it.length } ?: primary.name
                    val isRail = cluster.any { it.transportMode == "RAIL" || it.providerIds.contains("mav") }

                    consolidatedStations.add(
                        primary.copy(
                            name = bestName,
                            transportMode = if (isRail) "RAIL" else primary.transportMode,
                            sourceStopIds = cluster.flatMap { it.sourceStopIds }.distinct(),
                            providerIds = cluster.flatMap { it.providerIds }.distinct(),
                            routeIds = cluster.flatMap { it.routeIds }.distinct(),
                            isStation = true
                        )
                    )
                }
            }
        }

        // Secondary pass for closely matching hub names
        val finalStations = mutableListOf<PlannerEndpoint>()
        val merged = BooleanArray(consolidatedStations.size)
        for (i in consolidatedStations.indices) {
            if (merged[i]) continue
            merged[i] = true
            val seed = consolidatedStations[i]
            val cluster = mutableListOf(seed)
            for (j in i + 1 until consolidatedStations.size) {
                if (merged[j]) continue
                val other = consolidatedStations[j]
                if (StopClusters.areNamesCloselyMatching(seed.name, other.name) &&
                    StopClusters.distanceMeters(seed.position, other.position) <= StopClusters.MAX_HUB_DISTANCE_METERS) {
                    merged[j] = true
                    cluster.add(other)
                }
            }
            if (cluster.size == 1) {
                finalStations.add(seed)
            } else {
                val railEndpoint = cluster.firstOrNull {
                    it.transportMode == "RAIL" || it.providerIds.contains("mav") ||
                        it.name.contains("vasútállomás", ignoreCase = true) || it.name.contains("pályaudvar", ignoreCase = true)
                }
                val primary = railEndpoint ?: cluster.first()
                val bestName = cluster.map { it.name }
                    .filterNot { it.contains("(") || it.contains("[") }
                    .minByOrNull { it.length } ?: primary.name
                val isRail = cluster.any { it.transportMode == "RAIL" || it.providerIds.contains("mav") }

                finalStations.add(
                    primary.copy(
                        name = bestName,
                        transportMode = if (isRail) "RAIL" else primary.transportMode,
                        sourceStopIds = cluster.flatMap { it.sourceStopIds }.distinct(),
                        providerIds = cluster.flatMap { it.providerIds }.distinct(),
                        routeIds = cluster.flatMap { it.routeIds }.distinct(),
                        isStation = true
                    )
                )
            }
        }

        return finalStations + places
    }

    fun search(query: String) {
        val generation = ++searchGeneration
        searchJob?.cancel()
        mutable.update { it.copy(query = query, nearbyOriginSearch = false, suggestions = emptyList(), recommendation = null, searching = query.trim().length >= 2, searchUnavailable = false) }
        if (query.trim().length < 2) return
        searchJob = viewModelScope.launch {
            val (localStationResults, consolidatedLocal, recommendation) = withContext(Dispatchers.Default) {
                val parsed = TransitSearch.query(query)
                val literal = TransitSearch.stops(index, parsed, location, emptySet()).take(10).map(PlannerEndpoint::fromStop)
                Triple(literal, consolidateEndpoints(literal), StationRecommendations.stops(index, parsed, location, emptySet()).firstOrNull()?.let(PlannerEndpoint::fromStop))
            }
            if (generation != searchGeneration) return@launch
            val cachedPlaces = if (state.value.offline) emptyList() else places.cached(query).orEmpty().take(6)
                .map { PlannerEndpoint(it.id, it.name, it.address, it.position, isStation = false) }
            mutable.update { it.copy(suggestions = consolidatedLocal + cachedPlaces, recommendation = recommendation) }
            if (state.value.offline) {
                mutable.update { it.copy(searching = false) }; return@launch
            }
            try {
                // Local matches are ready immediately; debounce only remote geocoder and station search.
                delay(300)
                val remoteStationsAsync = async {
                    runCatching {
                        stopsRepository.search(query).stops.take(8).map { PlannerEndpoint.fromStop(it) }
                    }.getOrDefault(emptyList())
                }
                val placesAsync = async {
                    val results = places.search(query).take(6).map { PlannerEndpoint(it.id, it.name, it.address, it.position, isStation = false) }
                    if (generation == searchGeneration) mutable.update {
                        it.copy(suggestions = it.suggestions.filter { endpoint -> endpoint.isStation } + results)
                    }
                }
                val remoteStations = remoteStationsAsync.await()
                val stationResults = withContext(Dispatchers.Default) { consolidateEndpoints(localStationResults + remoteStations) }
                if (generation == searchGeneration) {
                    mutable.update { it.copy(suggestions = stationResults + it.suggestions.filterNot { endpoint -> endpoint.isStation }) }
                }
                placesAsync.await()
                // Places remain available below the station recommendation, even at the same address.
                if (generation == searchGeneration) {
                    mutable.update { it.copy(searching = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == searchGeneration) mutable.update { it.copy(searching = false, searchUnavailable = true) }
            }
        }
    }

    fun choose(endpoint: PlannerEndpoint) {
        val field = state.value.editing ?: return
        invalidate()
        mutable.update { if (field == PlannerField.ORIGIN) it.copy(origin = endpoint, useLocation = false)
            else it.copy(destination = endpoint) }
        closeSearch()
    }

    fun swap() {
        invalidate()
        mutable.update { it.copy(origin = it.destination, destination = it.origin, useLocation = false) }
        warmOnlinePlan()
    }

    private fun invalidate(cancelWarmup: Boolean = true) {
        if (cancelWarmup) { warmupJob?.cancel(); warmupStarted = false }
        planGeneration++; planJob?.cancel()
        mutable.update { it.copy(result = null, planning = false, error = null, selectedId = null) }
    }

    fun cancelPlan() { invalidate() }

    fun selectItinerary(id: String?) { mutable.update { it.copy(selectedId = id) } }

    fun setDeparture(departure: Instant?) {
        if (departure == state.value.departure) return
        invalidate()
        mutable.update { it.copy(departure = departure) }
        warmOnlinePlan()
    }

    fun setLocalProvider(providerId: String, enabled: Boolean) {
        if (providerId !in LocalPlanningService.providerIds || (providerId in state.value.localProviders) == enabled) return
        invalidate()
        mutable.update { it.copy(localProviders = if (enabled) it.localProviders + providerId else it.localProviders - providerId) }
        warmOnlinePlan()
    }

    /** Prepare online national results while the completed form is on screen. */
    private fun warmOnlinePlan() {
        warmupJob?.cancel()
        warmupStarted = false
        val current = state.value
        if (current.offline || hu.app.bmenetrend.offline.NetworkAccessPolicy.offline.value || current.editing != null || current.planning) return
        val origin = if (current.useLocation) location?.let(::currentEndpoint) ?: current.origin else current.origin
        val destination = current.destination ?: return
        if (origin == null || !TripPlanner.supported(origin.position) || !TripPlanner.supported(destination.position) ||
            StopClusters.distanceMeters(origin.position, destination.position) < 25) return
        val requested = Instant.now()
        val ready = current.departure ?: requested
        if (ready < requested) return
        warmupJob = viewModelScope.launch {
            delay(250)
            warmupStarted = true
            supervisorScope {
                listOf(false, true).map { bus -> async {
                    try { backend.routes(origin, destination, ready, bus) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Submission can retry a failed prefetch. */ }
                } }.awaitAll()
            }
        }
    }

    fun plan(force: Boolean = false) {
        val current = state.value
        val origin = (if (current.useLocation) location?.let(::currentEndpoint) ?: current.origin else current.origin)
        if (origin == null) {
            if (current.useLocation) mutable.update { it.copy(error = PlannerError.LOCATION) }
            return
        }
        val destination = current.destination ?: return
        // Keep an HTTP prefetch already in progress, but cancel a timer that has
        // not started: the foreground submission will start its own calls now.
        invalidate(cancelWarmup = force || !warmupStarted)
        if (force) backend.clearRouteCache()
        if (!TripPlanner.supported(origin.position) || !TripPlanner.supported(destination.position)) {
            mutable.update { it.copy(error = PlannerError.OUTSIDE_AREA) }; return
        }
        if (StopClusters.distanceMeters(origin.position, destination.position) < 25) {
            mutable.update { it.copy(error = PlannerError.SAME_PLACE) }; return
        }
        val generation = ++planGeneration
        val requested = Instant.now()
        val ready = current.departure ?: requested
        if (ready < requested) {
            mutable.update { it.copy(error = PlannerError.DEPARTURE_PASSED) }; return
        }
        mutable.update { it.copy(origin = origin, planning = true) }
        planJob = viewModelScope.launch {
            try {
                val result = if (current.offline) offlinePlanner.plan(origin, destination, requested, ready, current.localProviders)
                else planWithRecovery(online = {
                    planner.plan(origin, destination, requested, ready, stopCatalogue, loadNetwork, current.localProviders) { partial ->
                        if (generation == planGeneration) mutable.update { it.copy(result = partial) }
                    }
                }, saved = { offlinePlanner.plan(origin, destination, requested, ready, current.localProviders) })
                if (generation == planGeneration) mutable.update { it.copy(planning = false, result = result,
                    selectedId = it.selectedId?.takeIf { id -> result.itineraries.any { route -> route.id == id } },
                    error = if (result.itineraries.isEmpty()) {
                        if (current.offline) PlannerError.NO_ROUTE
                        else PlannerError.NO_ROUTE
                    } else null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val reason = when (error) {
                    is hu.app.bmenetrend.offline.OfflineDateUnavailable -> PlannerError.OFFLINE_DATE
                    is hu.app.bmenetrend.offline.OfflineEndpointUnavailable -> PlannerError.OFFLINE_ENDPOINT
                    is hu.app.bmenetrend.offline.OfflinePackUnavailable -> PlannerError.OFFLINE_PACK
                    else -> PlannerError.UNAVAILABLE
                }
                if (generation == planGeneration) mutable.update { it.copy(planning = false, error = reason) }
            }
        }
    }
}
