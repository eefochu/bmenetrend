package hu.app.bmenetrend

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import hu.app.bmenetrend.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.util.concurrent.TimeUnit

enum class PlannerField { ORIGIN, DESTINATION }
enum class PlannerError { UNAVAILABLE, NO_ROUTE, SAME_PLACE, LOCATION, OUTSIDE_AREA, DEPARTURE_PASSED, OFFLINE_DATE, OFFLINE_ENDPOINT }
@Immutable
data class PlannerState(
    val origin: PlannerEndpoint? = null, val destination: PlannerEndpoint? = null,
    val useLocation: Boolean = true, val editing: PlannerField? = null, val query: String = "",
    val suggestions: List<PlannerEndpoint> = emptyList(), val searching: Boolean = false,
    val searchUnavailable: Boolean = false, val planning: Boolean = false,
    val error: PlannerError? = null, val result: TripPlan? = null, val selectedId: String? = null,
    val departure: Instant? = null,
    val offline: Boolean = false,
    val offlineValidUntil: String? = null,
)

class PlannerViewModel internal constructor(
    application: Application,
    backend: PlannerBackend,
    private val loadNetwork: suspend () -> TransitNetwork,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application,
        EmmaPlannerBackend(EmmaGraphqlClient(EmmaGraphqlClient.defaultHttpClient().newBuilder()
            .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build())),
        TransitRepository(application)::planningNetwork)

    private val offlineStore = hu.app.bmenetrend.offline.OfflinePackStore(application)
    private val offlinePlanner = hu.app.bmenetrend.offline.OfflineTripPlanner(offlineStore)

    private val places = PlacesRepository(application)
    private val planner = HybridTripPlanner(backend)
    private var stopCatalogue = emptyList<TransitStop>()
    private val mutable = MutableStateFlow(PlannerState())
    val state: StateFlow<PlannerState> = mutable
    private var searchJob: Job? = null
    private var planJob: Job? = null
    private var searchGeneration = 0
    private var planGeneration = 0
    private var index = emptyList<TransitSearch.StopEntry>()
    private var indexJob: Job? = null
    private var location: GeoPoint? = null

    init {
        viewModelScope.launch {
            hu.app.bmenetrend.offline.NetworkAccessPolicy.offline.collect { enabled ->
                invalidate()
                closeSearch()
                val metadata = withContext(Dispatchers.IO) { offlineStore.metadata() }
                mutable.update { it.copy(offline = enabled, offlineValidUntil = metadata?.validUntil?.toString()) }
            }
        }
    }


    fun updateStops(stops: List<TransitStop>, national: List<TransitStop> = emptyList()) {
        val local = if (hu.app.bmenetrend.offline.NetworkAccessPolicy.offline.value) emptyList() else stops
        val available = national.filterNot { it.station }
        stopCatalogue = if (available.isEmpty()) local else local + available
        indexJob?.cancel()
        indexJob = viewModelScope.launch {
            index = withContext(Dispatchers.Default) { TransitSearch.indexStops(local, available) }
            if (state.value.editing != null) search(state.value.query)
        }
    }
    fun updateLocation(point: GeoPoint?) {
        location = point
        if (point != null && state.value.useLocation && state.value.origin == null) mutable.update { it.copy(origin = currentEndpoint(point), error = null) }
    }
    private fun currentEndpoint(point: GeoPoint) = PlannerEndpoint("current", "", "", point)
    fun useCurrentLocation() {
        invalidate()
        mutable.update { it.copy(origin = location?.let(::currentEndpoint), useLocation = true, editing = null,
            query = "", suggestions = emptyList(), error = if (location == null) PlannerError.LOCATION else null) }
        closeSearch()
    }
    fun edit(field: PlannerField) {
        mutable.update { it.copy(editing = field, query = "", suggestions = emptyList(), searchUnavailable = false) }
    }
    fun closeSearch() {
        searchGeneration++; searchJob?.cancel()
        mutable.update { it.copy(editing = null, searching = false) }
    }
    fun search(query: String) {
        val generation = ++searchGeneration
        searchJob?.cancel()
        mutable.update { it.copy(query = query, suggestions = emptyList(), searching = query.trim().length >= 2, searchUnavailable = false) }
        if (query.trim().length < 2) return
        searchJob = viewModelScope.launch {
            delay(400)
            val stationResults = withContext(Dispatchers.Default) {
                TransitSearch.stops(index, TransitSearch.query(query), location, emptySet()).take(6).map {
                    PlannerEndpoint.fromStop(it)
                }
            }
            if (generation != searchGeneration) return@launch
            mutable.update { it.copy(suggestions = stationResults) }
            if (state.value.offline) {
                mutable.update { it.copy(searching = false) }; return@launch
            }
            try {
                val results = places.search(query).take(6).map { PlannerEndpoint(it.id, it.name, it.address, it.position) }
                if (generation == searchGeneration) mutable.update { it.copy(suggestions = (results + stationResults).distinctBy { it.id }, searching = false) }
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
    }
    private fun invalidate() {
        planGeneration++; planJob?.cancel()
        mutable.update { it.copy(result = null, planning = false, error = null, selectedId = null) }
    }
    fun cancelPlan() { invalidate() }
    fun selectItinerary(id: String?) { mutable.update { it.copy(selectedId = id) } }
    fun setDeparture(departure: Instant?) {
        if (departure == state.value.departure) return
        invalidate()
        mutable.update { it.copy(departure = departure) }
    }
    fun plan() {
        val current = state.value
        val origin = (if (current.useLocation) location?.let(::currentEndpoint) ?: current.origin else current.origin) ?: return
        val destination = current.destination ?: return
        invalidate()
        if (!TripPlanner.supported(origin.position) || !TripPlanner.supported(destination.position)) {
            mutable.update { it.copy(error = PlannerError.OUTSIDE_AREA) }; return
        }
        if (StopClusters.distanceMeters(origin.position, destination.position) < 25) {
            mutable.update { it.copy(error = PlannerError.SAME_PLACE) }; return
        }
        val generation = ++planGeneration
        val requested = Instant.now()
        val ready = current.departure ?: requested.plusSeconds(TripPlanner.PREPARATION_SECONDS)
        if (ready < requested) {
            mutable.update { it.copy(error = PlannerError.DEPARTURE_PASSED) }; return
        }
        mutable.update { it.copy(origin = origin, planning = true) }
        planJob = viewModelScope.launch {
            try {
                val result = if (current.offline) offlinePlanner.plan(origin, destination, requested, ready) else planner.plan(origin, destination, requested, ready, stopCatalogue, loadNetwork) { partial ->
                    if (generation == planGeneration) mutable.update { it.copy(result = partial) }
                }
                if (generation == planGeneration) mutable.update { it.copy(planning = false, result = result,
                    selectedId = null,
                    error = if (result.itineraries.isEmpty()) {
                        if (result.partial) PlannerError.UNAVAILABLE else PlannerError.NO_ROUTE
                    } else null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: hu.app.bmenetrend.offline.OfflineDateUnavailable) { if (generation == planGeneration) mutable.update { it.copy(planning = false, error = PlannerError.OFFLINE_DATE) } }
            catch (_: hu.app.bmenetrend.offline.OfflineEndpointUnavailable) { if (generation == planGeneration) mutable.update { it.copy(planning = false, error = PlannerError.OFFLINE_ENDPOINT) } }
            catch (_: Exception) { if (generation == planGeneration) mutable.update { it.copy(planning = false, error = PlannerError.UNAVAILABLE) } }
        }
    }
}
