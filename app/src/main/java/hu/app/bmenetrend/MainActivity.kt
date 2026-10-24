package hu.app.bmenetrend

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import hu.app.bmenetrend.ui.FavoritesScreen
import hu.app.bmenetrend.ui.MainTab
import hu.app.bmenetrend.ui.MainNavigation
import hu.app.bmenetrend.ui.PlannerScreen
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.map.NativeTransitMap
import hu.app.bmenetrend.map.LineMapFocus
import hu.app.bmenetrend.ui.ViaBuszScreen
import hu.app.bmenetrend.ui.ViaBuszTheme
import hu.app.bmenetrend.ui.lineColor
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.awaitCancellation

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        // Use Hungarian for Material dialogs and date formatting as well as app strings.
        super.attachBaseContext(newBase.withHungarianLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: TransitViewModel = viewModel()
            val planner: PlannerViewModel = viewModel()
            var tab by rememberSaveable { mutableStateOf(MainTab.MAP) }
            val state by model.state.collectAsStateWithLifecycle()
            val language = LocalConfiguration.current.locales.toLanguageTags()
            LaunchedEffect(language) { model.onLocaleChanged() }
            var locationAllowed by remember { mutableStateOf(hasLocationPermission()) }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                locationAllowed = result.values.any { it } || hasLocationPermission()
                if (!locationAllowed) model.locationMessage(getString(R.string.engine_location_denied))
            }
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        locationAllowed = hasLocationPermission()
                        if (!locationAllowed) model.locationMessage(getString(R.string.engine_location_denied))
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(Unit) {
                if (!locationAllowed) permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }
            LaunchedEffect(model) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { model.observe() }
            }
            LaunchedEffect(locationAllowed) {
                if (locationAllowed) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    val close = startLocation(model)
                    try { awaitCancellation() } finally { close() }
                }
            }
            LaunchedEffect(tab) { model.setMapVisible(tab == MainTab.MAP); model.setFavoritesVisible(tab == MainTab.FAVORITES) }
            LaunchedEffect(state.userLocation) { planner.updateLocation(state.userLocation) }
            LaunchedEffect(tab, state.network?.stops, state.emmaStops) {
                if (tab == MainTab.PLANNER) planner.updateStops(state.stops)
            }
            val requestLocation: () -> Unit = {
                locationAllowed = hasLocationPermission()
                if (!locationAllowed) permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                else if (!LocationManagerCompat.isLocationEnabled(getSystemService(Context.LOCATION_SERVICE) as LocationManager))
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            ViaBuszTheme(accent = state.accentColor, customArgb = state.customAccentArgb) {
                MainNavigation(tab, { tab = it }, planner = { PlannerScreen(planner, requestLocation) }, favorites = {
                    FavoritesScreen(state, onStop = { model.selectStop(it); tab = MainTab.MAP }, onRemove = model::toggleFavorite,
                        onRefresh = model::refreshFavorites, onWindow = model::setWindow, onVisible = model::setVisibleFavorites)
                }, map = {
                ViaBuszScreen(
                    state = state,
                    onStopSelected = model::selectStop,
                    onRefresh = model::refresh,
                    onLocate = {
                        model.useNearest()
                        locationAllowed = hasLocationPermission()
                        if (!locationAllowed) permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                        else {
                            val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                            if (!LocationManagerCompat.isLocationEnabled(manager)) startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                            else if (state.userLocation == null) model.locationMessage(getString(R.string.engine_locating))
                        }
                    },
                    onWindowChanged = model::setWindow,
                    onToggleFavorite = model::toggleFavorite,
                    onRecenter = model::recenter,
                    onNationwideNetworkChanged = model::setNationwideNetwork,
                    onVehicleClosed = model::returnToNearby,
                    onAccentColorChanged = model::setAccentColor,
                    onCustomAccentChanged = model::setCustomAccentColor,
                    onLineOpened = model::loadLine,
                    onBoardingStop = model::selectBoardingStop,
                    onClearBoarding = model::clearBoardingSelection,
                    onShowWholeRoute = model::showWholeRoute,
                    onJourneyBoardingStop = model::selectJourneyBoardingStop,
                    onVehicleOpened = model::selectVehicle,
                    onSearchPlaces = model::searchPlaces,
                    onPlaceSelected = model::selectPlace,
                    onPlaceClosed = model::clearPlace,
                ) { modifier, bottomPaddingPx ->
                    val vehicle = state.selectedVehicle
                    val pattern = state.boardingSelection?.pattern
                    val route = state.boardingSelection?.routeId?.let(state::findRoute)
                    val focus = remember(vehicle?.id, vehicle?.routeId, state.boardingSelection, state.network, state.emmaRoutes, state.lineDetails, state.vehicleJourney, state.emmaStops) {
                        LineMapFocus.create(vehicle, state.boardingSelection, state.routes, state.lineDetails, state.vehicleJourney, state.stops)
                    }
                    val geometry = if (vehicle != null) state.vehicleJourney?.geometry.orEmpty() else pattern?.geometry.orEmpty()
                    val stops = if (vehicle != null) state.vehicleJourney?.stops.orEmpty() else pattern?.stops.orEmpty()
                    val color = if (vehicle != null) lineColor(vehicle.providerId, vehicle.transportMode, vehicle.markerColor)
                        else route?.let(::lineColor) ?: androidx.compose.ui.graphics.Color(0xFF199DCF)
                    val onMapStop: (String) -> Unit = { id ->
                        if (state.selectedVehicle != null && state.vehicleJourney?.stops?.any { it.id == id } == true) model.selectJourneyBoardingStop(id)
                        else {
                            val linePattern = focus?.routeId?.let { state.lineDetails[it] }?.patterns?.firstOrNull { pattern -> pattern.stops.any { it.id == id } }
                            if (focus?.routeId != null && linePattern != null) model.selectBoardingStop(focus.routeId, linePattern.id, id)
                            else model.selectStop(id)
                        }
                    }
                    NativeTransitMap(state.network, state.vehicles, state.selectedStopId.takeUnless { state.showNearby }, state.userLocation, modifier, onMapStop, model::mapMessage, state.cameraRequest, bottomPaddingPx, state.emmaStops, model::updateMapZoom, model::selectVehicle, state.selectedVehicle, routeGeometry = geometry, routeStops = stops, routeColor = color.toArgb(), fitRouteRequest = state.fitRouteRequest, selectedPlace = state.selectedPlace, placeCameraRequest = state.placeCameraRequest, lineFocus = focus, browsingNearby = state.showNearby, locationCameraRequest = state.locationCameraRequest, onViewportChanged = model::updateMapArea)
                }
                })
            }
        }
    }

    private fun hasLocationPermission(): Boolean = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission")
    private fun startLocation(model: TransitViewModel): () -> Unit {
        if (!hasLocationPermission()) return {}
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.accuracy <= 3000 && location.latitude.isFinite() && location.longitude.isFinite()) {
                    model.updateLocation(GeoPoint(location.latitude, location.longitude))
                }
            }
            override fun onProviderDisabled(provider: String) {
                if (!LocationManagerCompat.isLocationEnabled(manager)) model.locationMessage(getString(R.string.engine_location_disabled))
            }
            override fun onProviderEnabled(provider: String) = Unit
            @Deprecated("Required for LocationListener on Android 8–10")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        try {
            if (!LocationManagerCompat.isLocationEnabled(manager)) model.locationMessage(getString(R.string.engine_location_disabled_browsing))
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter { it in manager.allProviders }
            providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .filter { System.currentTimeMillis() - it.time in 0..120_000 }
                .minByOrNull { it.accuracy }?.let(listener::onLocationChanged)
            providers.forEach { provider -> runCatching { manager.requestLocationUpdates(provider, 15_000L, 25f, listener, mainLooper) } }
        } catch (_: SecurityException) {
            model.locationMessage(getString(R.string.engine_location_required))
        }
        return { manager.removeUpdates(listener) }
    }
}
