package hu.viabusz.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import hu.viabusz.app.data.*
import hu.viabusz.app.ui.UiState
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
    private val initialNetwork = repository.bundledNetwork()
    private val mutableState = MutableStateFlow(UiState(
        network = initialNetwork,
        selectedStopId = initialNetwork.stops.firstOrNull { it.id == "1" }?.id ?: initialNetwork.stops.firstOrNull()?.id,
        favorites = preferences.getStringSet("favorites", emptySet())?.map(SharedStops::canonicalId)?.toSet().orEmpty(),
        windowMinutes = preferences.getInt("window", 60).takeIf { it in listOf(30, 60, 120) } ?: 60,
    ))
    val state: StateFlow<UiState> = mutableState
    private val refreshMutex = Mutex()
    private var live: LiveSnapshot? = null
    private var manualStop = false
    private var loadedDate: LocalDate? = null
    private var lastNetworkAttempt: Instant? = null
    private val budapest = ZoneId.of("Europe/Budapest")
    private var mapError: String? = null
    private var liveError: String? = null

    /** Owned by the activity's STARTED lifecycle; all polling stops in the background. */
    suspend fun observe() = coroutineScope {
        launch {
            while (isActive) {
                recalculate()
                delay(1_000)
            }
        }
        while (isActive) {
            refreshData()
            delay(15_000)
        }
    }

    // Resource strings stored in state must be refreshed after the system language changes.
    fun onLocaleChanged() {
        loadedDate = null
        lastNetworkAttempt = null
        mapError = null
        liveError = null
        live = null
        mutableState.update { it.copy(error = null, status = null, locationMessage = null) }
    }

    fun refresh() { viewModelScope.launch { refreshData(forceNetwork = true) } }

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
                        val nearest = current.userLocation?.let { location -> nearestStop(network, location) }
                        val selected = when {
                            !manualStop && nearest != null -> nearest
                            network.stops.any { it.id == current.selectedStopId } -> current.selectedStopId
                            else -> network.stops.firstOrNull { it.name.contains("Vasút", ignoreCase = true) }?.id ?: network.stops.firstOrNull()?.id
                        }
                        current.copy(network = network, selectedStopId = selected, nearestStopId = nearest, loading = false)
                    }
                }
                live = repository.refresh()
                liveError = null
                mutableState.update { it.copy(error = null, loading = false, lastUpdated = live?.fetchedAt) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                liveError = getApplication<Application>().getString(R.string.engine_live_unavailable)
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
            val departures = current.network?.let { network -> current.selectedStopId?.let { stop ->
                repository.departures(network, snapshot, stop, now, current.windowMinutes)
            }}.orEmpty()
            val vehicles = snapshot?.vehicles.orEmpty().filter {
                Duration.between(it.observedAt, now).seconds in -30..180
            }
            val stop = current.network?.stops?.firstOrNull { it.id == current.selectedStopId }
            val stopProviders = stop?.providerIds.orEmpty()
            val hommOnly = stopProviders == listOf("homm")
            val networkWarning = stopProviders.mapNotNull { current.network?.providerWarnings?.get(it) }
                .distinct().joinToString("\n").takeIf { it.isNotBlank() }
            val liveWarning = stopProviders.mapNotNull { snapshot?.providerWarnings?.get(it) }
                .distinct().joinToString("\n").takeIf { it.isNotBlank() }
            current.copy(
                now = now,
                error = liveError.takeUnless { hommOnly },
                departures = departures,
                vehicles = vehicles,
                // Route-less ETA signals must never be attached to a guessed bus route.
                liveStopMinutes = snapshot?.arrivals.orEmpty()
                    .filter { current.network?.let { network -> SharedStops.resolve(network, it.stopId) } == current.selectedStopId && it.isVerifiedRealtime }
                    .map { max(0, it.minutes - Duration.between(snapshot!!.fetchedAt, now).toMinutes().toInt()) }
                    .filter { it in 1..current.windowMinutes }.distinct().sorted(),
                status = mapError ?: networkWarning ?: if (hommOnly) null else liveWarning ?: if (vehicles.isNotEmpty())
                    getApplication<Application>().getString(R.string.engine_live_positions)
                else getApplication<Application>().getString(R.string.engine_no_live_positions),
            )
        }
    }

    fun selectStop(id: String) {
        manualStop = true
        val selectedId = state.value.network?.let { SharedStops.resolve(it, id) } ?: SharedStops.canonicalId(id)
        mutableState.update { it.copy(selectedStopId = selectedId, cameraRequest = it.cameraRequest + 1) }
        recalculate()
    }

    fun recenter() {
        if (state.value.selectedStopId != null) {
            mutableState.update { it.copy(cameraRequest = it.cameraRequest + 1) }
        }
    }

    fun useNearest() {
        manualStop = false
        state.value.nearestStopId?.let { id -> mutableState.update { it.copy(selectedStopId = id, cameraRequest = it.cameraRequest + 1) } }
        recalculate()
    }

    fun updateLocation(point: GeoPoint) {
        mutableState.update { current ->
            val nearest = current.network?.let { nearestStop(it, point) }
            current.copy(userLocation = point, nearestStopId = nearest,
                selectedStopId = if (!manualStop && nearest != null) nearest else current.selectedStopId,
                locationMessage = null)
        }
        recalculate()
    }

    fun locationMessage(message: String?) { mutableState.update { it.copy(locationMessage = message) } }
    fun mapMessage(message: String?) { mapError = message; recalculate() }
    fun setWindow(minutes: Int) {
        if (minutes !in listOf(30, 60, 120)) return
        preferences.edit().putInt("window", minutes).apply()
        mutableState.update { it.copy(windowMinutes = minutes) }
        recalculate()
    }
    fun toggleFavorite(id: String) {
        val canonicalId = SharedStops.canonicalId(id)
        val updated = state.value.favorites.toMutableSet().apply { if (!add(canonicalId)) remove(canonicalId) }.toSet()
        preferences.edit().putStringSet("favorites", updated).apply()
        mutableState.update { it.copy(favorites = updated) }
    }
}

internal fun nearestStop(network: TransitNetwork, location: GeoPoint): String? =
    network.stops.minByOrNull { distanceMeters(location, it.position) }?.id

internal fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val lat = Math.toRadians(b.lat - a.lat)
    val lon = Math.toRadians(b.lon - a.lon)
    val haversine = sin(lat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(lon / 2).pow(2)
    return 6_371_000 * 2 * atan2(sqrt(haversine.coerceIn(0.0, 1.0)), sqrt((1 - haversine).coerceIn(0.0, 1.0)))
}
