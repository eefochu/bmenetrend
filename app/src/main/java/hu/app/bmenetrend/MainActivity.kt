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
import hu.app.bmenetrend.plugins.TicketsPlugin
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import java.io.File
import hu.app.bmenetrend.ui.CrashReportDialog

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        // Use Hungarian for Material dialogs and date formatting as well as app strings.
        super.attachBaseContext(newBase.withHungarianLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val crashReports = remember { CrashReports(File(filesDir, "last-crash.txt")) }
            var pendingCrash by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(crashReports) {
                pendingCrash = withContext(Dispatchers.IO) { crashReports.read() }
            }
            val model: TransitViewModel = viewModel()
            val planner: PlannerViewModel = viewModel()
            // Optional MÁV tickets module; null when the mav-tickets folder is not in the build.
            val tickets = TicketsPlugin.host()
            var offlineSettingsRequest by rememberSaveable { mutableIntStateOf(0) }
            var searchRequest by rememberSaveable { mutableIntStateOf(0) }
            var mapFullscreen by remember { mutableStateOf(false) }
            var tab by rememberSaveable { mutableStateOf(MainTab.MAP) }
            val state by model.state.collectAsStateWithLifecycle()
            // Not delegated, and passed on as a reader: the map's vehicle layer and the search read it
            // where they use it, so a live position tick recomposes nothing else.
            val liveVehicles = model.vehicles.collectAsStateWithLifecycle()
            val readVehicles = remember(liveVehicles) { { liveVehicles.value } }
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
            LaunchedEffect(tab) {
                model.setDeparturesVisible(tab == MainTab.MAP)
                model.setMapVisible(tab == MainTab.MAP || tab == MainTab.PLANNER)
                model.setFavoritesVisible(tab == MainTab.FAVORITES)
            }
            LaunchedEffect(state.userLocation) { planner.updateLocation(state.userLocation) }
            LaunchedEffect(state.offlinePack.metadata?.id) { planner.updateOfflinePack(state.offlinePack.metadata) }
            LaunchedEffect(tab, state.network?.stops, state.emmaStops) {
                if (tab == MainTab.PLANNER) planner.updateStops(state.network?.stops.orEmpty(), state.emmaStops)
            }
            // Both map surfaces share catalogue updates and marker directions.
            // Only routes with stop sequences contribute bearings. Live vehicles add direction-less
            // routes every few seconds; they must not rescan the national catalogue.
            val stopBearings by produceState(
                initialValue = emptyMap<String, List<Double>>(),
                state.network?.routes, state.emmaRoutes, state.network?.stops, state.emmaStops, state.lineDetails, state.vehicleJourney
            ) {
                val snapshot = state
                // Filtering thousands of routes stays off the main thread, like the bearing math itself.
                val bearingRoutes = withContext(Dispatchers.Default) {
                    snapshot.routes.filter { route -> route.directions.any { it.stopIds.size >= 2 } }
                }
                value = hu.app.bmenetrend.map.StopDirections.collectAsync(bearingRoutes, snapshot.stops, snapshot.lineDetails.values, snapshot.vehicleJourney)
            }
            val requestLocation: () -> Unit = {
                locationAllowed = hasLocationPermission()
                if (!locationAllowed) permission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
                else if (!LocationManagerCompat.isLocationEnabled(getSystemService(Context.LOCATION_SERVICE) as LocationManager))
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            ViaBuszTheme(accent = state.accentColor, customArgb = state.customAccentArgb, themeMode = state.themeMode) {
                val darkTheme = hu.app.bmenetrend.ui.LocalDarkTheme.current
                SideEffect {
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                pendingCrash?.let { report -> CrashReportDialog(report) {
                    crashReports.clear()
                    pendingCrash = null
                } }
                val isViewingTicket = tickets?.isViewingTicket() == true
                CompositionLocalProvider(hu.app.bmenetrend.ui.LocalMapAppearance provides
                    hu.app.bmenetrend.ui.MapAppearance(state.mapStyle, state.offlineMode)) {
                MainNavigation(
                    tab,
                    { tab = it },
                    offline = state.offlineMode,
                    hideNavigation = (tab == MainTab.MAP && mapFullscreen) || isViewingTicket,
                    onOfflineSettings = { tab = MainTab.MAP; offlineSettingsRequest++ },
                    planner = {
                        PlannerScreen(
                            model = planner,
                            onRequestLocation = requestLocation,
                            onStopDepartures = { stopId ->
                                model.selectStop(stopId)
                                tab = MainTab.MAP
                            },
                            onClose = null,
                            transitMapContent = { mapModifier, routeGeometry, destinationPlace, cameraRequest, isDotted, originPosition ->
                                val primaryColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
                                val accentArgb = remember(primaryColor) { primaryColor.toArgb() }
                                NativeTransitMap(
                                    network = state.network,
                                    vehicles = readVehicles,
                                    selectedStopId = null,
                                    userLocation = state.userLocation,
                                    modifier = mapModifier,
                                    topPaddingPx = 0,
                                    onStopSelected = { id ->
                                        state.findStop(id)?.let { stop ->
                                            planner.edit(PlannerField.DESTINATION)
                                            planner.choose(hu.app.bmenetrend.data.PlannerEndpoint.fromStop(stop))
                                        }
                                    },
                                    onMapError = model::mapMessage,
                                    additionalStops = state.emmaStops,
                                    onMapZoomChanged = model::updateMapZoom,
                                    onViewportChanged = model::updateMapArea,
                                    stopBearings = stopBearings,
                                    routeCatalogue = state.routesById,
                                    onVehicleSelected = { id -> model.selectVehicle(id); tab = MainTab.MAP },
                                    browsingNearby = true,
                                    showTransitStops = false,
                                    routeGeometry = routeGeometry,
                                    routeColor = accentArgb,
                                    selectedPlace = destinationPlace,
                                    routeOrigin = originPosition,
                                    placeCameraRequest = cameraRequest,
                                    dottedRoute = isDotted,
                                    offline = state.offlineMode,
                                    onGestureChanged = model::setMapGesture,
                                )
                            }
                        )
                    },
                    favorites = {
                        FavoritesScreen(
                            state = state,
                            onStop = { model.selectStop(it); tab = MainTab.MAP },
                            onRemove = model::toggleFavorite,
                            onRefresh = model::refreshFavorites,
                            onVisible = model::setVisibleFavorites,
                            onBrowse = { tab = MainTab.MAP; searchRequest++ }
                        )
                    },
                    tickets = tickets?.let { host -> @Composable { host.Screen() } },
                    map = {
                ViaBuszScreen(
                    state = state,
                    vehicles = readVehicles,
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
                    onVehicleClosed = model::closeVehicle,
                    onReturnToNearby = model::returnToNearby,
                    onReturnToTrackedVehicle = model::returnToTrackedVehicle,
                    onAccentColorChanged = model::setAccentColor,
                    onCustomAccentChanged = model::setCustomAccentColor,
                    onThemeModeChanged = model::setThemeMode,
                    onMapStyleChanged = model::setMapStyle,
                    onLineOpened = model::loadLine,
                    onBoardingStop = model::selectBoardingStop,
                    onClearBoarding = model::clearBoardingSelection,
                    onShowWholeRoute = model::showWholeRoute,
                    onJourneyBoardingStop = model::selectJourneyBoardingStop,
                    onVehicleOpened = model::selectVehicle,
                    onSearchPlaces = model::searchPlaces,
                    onPlaceSelected = model::selectPlace,
                    onPlaceClosed = model::clearPlace,
                    onDownloadTimetable = model::downloadStationTimetable,
                    onDeleteTimetable = model::deleteStationTimetable,
                    onClearAllTimetables = model::clearAllStationTimetables,
                    onOfflineMode = model::setOfflineMode, onOfflineDownload = model::downloadOfflinePack,
                    onAutomaticOfflineUpdates = model::setAutomaticOfflineUpdates, onOfflineCancel = model::cancelOfflineDownload, onOfflineDelete = model::deleteOfflinePack,
                    onOfflineImport = model::importOfflineFeed, openSettingsRequest = offlineSettingsRequest,
                    openSearchRequest = searchRequest, onFullscreenChanged = { mapFullscreen = it },
                    onOtaChannelChanged = model::setOtaChannel,
                    onCheckOtaUpdates = { model.checkForUpdates(false) },
                    onDownloadAndInstallOta = { info -> model.startOtaDownloadAndInstall(this@MainActivity, info) },
                ) { modifier, bottomPaddingPx, browsingRoute, browsingDirection, onDismissHighlight ->
                    val vehicle = state.selectedVehicle ?: state.trackedVehicle
                    val browsingPattern=browsingRoute?.let { line -> state.lineDetails[line.id]?.patterns?.find { it.id==browsingDirection } }
                    val preview=browsingRoute?.let { hu.app.bmenetrend.data.BoardingSelection(it.id,it.name,browsingPattern,
                        localDirectionId=browsingDirection.takeIf { _ -> it.providerId !in setOf("volan","bkk","hev","mav") }) }
                    val boarding=preview ?: state.boardingSelection
                    val pattern = boarding?.pattern
                    val route = browsingRoute ?: state.boardingSelection?.routeId?.let(state::findRoute)
                    // create() reads only the focused route from the catalogue: key on it, not on the
                    // whole live route list, which changes with every vehicle refresh.
                    val focusRouteId = vehicle?.routeId ?: boarding?.routeId
                    val focusRoute = focusRouteId?.let { id -> state.routes.find { it.id == id } }
                    // Filters the national stop catalogue: built off the main thread, so opening a line never stalls a frame.
                    val focusState by produceState<LineMapFocus?>(null, vehicle?.id, vehicle?.routeId, boarding, focusRoute,
                        state.network?.stops, state.lineDetails, state.vehicleJourney, state.emmaStops) {
                        if (vehicle == null && boarding == null) { value = null; return@produceState }
                        val snapshot = state
                        value = withContext(Dispatchers.Default) {
                            val stops = hu.app.bmenetrend.ui.TransitCatalogueIndexes.stops(snapshot.network?.stops.orEmpty(), snapshot.emmaStops).values
                            val routes = hu.app.bmenetrend.ui.TransitCatalogueIndexes.routes(snapshot.network?.routes.orEmpty(), snapshot.emmaRoutes).values
                            hu.app.bmenetrend.map.traceMapWork("BMenetrend.LineFocus") {
                                LineMapFocus.create(vehicle, boarding, routes, snapshot.lineDetails, snapshot.vehicleJourney, stops)
                            }
                        }
                    }
                    val immediateFocus = remember(vehicle?.id, vehicle?.routeId, vehicle?.routeNumber, vehicle?.transportMode, boarding, focusRoute) {
                        LineMapFocus.create(vehicle, boarding, listOfNotNull(focusRoute), emptyMap(), null, emptyList())
                    }
                    val focus = focusState.takeIf { (vehicle != null || boarding != null) && it?.routeId == focusRouteId && it?.selectedVehicleId == vehicle?.id } ?: immediateFocus
                    val geometry = if (vehicle != null) state.vehicleJourney?.geometry.orEmpty().ifEmpty { focus?.paths?.firstOrNull().orEmpty() } else pattern?.geometry.orEmpty().ifEmpty { focus?.paths?.firstOrNull().orEmpty() }
                    val stops = if (vehicle != null) state.vehicleJourney?.stops.orEmpty().ifEmpty { focus?.stops.orEmpty() } else pattern?.stops.orEmpty().ifEmpty { focus?.stops.orEmpty() }
                    val color = if (vehicle != null) lineColor(vehicle.providerId, vehicle.transportMode, vehicle.markerColor)
                        else route?.let(::lineColor) ?: androidx.compose.ui.graphics.Color(0xFF199DCF)
                    val onMapStop: (String) -> Unit = { id ->
                        if ((state.selectedVehicle != null || state.trackedVehicle != null) && state.vehicleJourney?.stops?.any { it.id == id } == true) model.selectJourneyBoardingStop(id)
                        else {
                            val linePattern=boarding?.pattern?.takeIf { it.stops.any { stop -> stop.id==id } }
                            val localDirection=boarding?.localDirectionId?.takeIf { direction ->
                                route?.directions?.any { it.id==direction && id in it.stopIds } == true
                            }
                            if(focus?.routeId!=null && linePattern!=null) model.selectBoardingStop(focus.routeId,linePattern.id,id)
                            else if(focus?.routeId!=null && localDirection!=null) model.selectBoardingStop(focus.routeId,localDirection,id)
                            else model.selectStop(id)
                        }
                    }
                    val onMapSharedStop: (List<String>) -> Unit = { ids ->
                        val matchedStopId = if (state.selectedVehicle != null || state.trackedVehicle != null) {
                            ids.find { id -> state.vehicleJourney?.stops?.any { it.id == id } == true }
                        } else if (focus?.routeId != null) {
                            ids.find { id ->
                                val rId = focus.routeId
                                boarding?.pattern?.stops?.any { it.id == id } == true ||
                                    state.lineDetails[rId]?.patterns?.any { p -> p.stops.any { it.id == id } } == true ||
                                    route?.directions?.any { d -> id in d.stopIds } == true
                            }
                        } else null

                        if (matchedStopId != null) {
                            onMapStop(matchedStopId)
                        } else {
                            model.selectStopGroup(ids)
                        }
                    }
                    NativeTransitMap(state.network, readVehicles, state.selectedStopId.takeUnless { state.showNearby || browsingRoute!=null || state.selectedVehicle!=null }, state.userLocation, modifier, onMapStop, model::mapMessage, state.cameraRequest, bottomPaddingPx, state.emmaStops, model::updateMapZoom, model::selectVehicle, vehicle, routeGeometry = geometry, routeStops = stops, routeColor = color.toArgb(), fitRouteRequest = state.fitRouteRequest, selectedPlace = state.selectedPlace, placeCameraRequest = state.placeCameraRequest, lineFocus = focus, browsingNearby = state.showNearby, locationCameraRequest = state.locationCameraRequest, onViewportChanged = model::updateMapArea,stopBearings=stopBearings,onSharedStopSelected=onMapSharedStop,onMapClick=onDismissHighlight, offline = state.offlineMode, routeCatalogue = state.routesById, highlightedStopId = state.selectedStopId, onViewportPrefetch = model::prefetchMapArea, onGestureChanged = model::setMapGesture)
                }
                })

                }
                if (state.showOtaDialog && state.otaUpdateAvailable != null) {
                    hu.app.bmenetrend.ui.OtaUpdateDialog(
                        update = state.otaUpdateAvailable!!,
                        downloadProgress = state.otaDownloadProgress,
                        error = state.otaCheckError,
                        isDownloaded = state.otaDownloadedApk?.exists() == true,
                        onDownloadAndInstall = { model.startOtaDownloadAndInstall(this@MainActivity, it) },
                        onDismiss = model::dismissOtaDialog
                    )
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    @SuppressLint("MissingPermission")
    private fun startLocation(model: TransitViewModel): () -> Unit {
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = LocationListener { loc ->
            model.updateLocation(GeoPoint(loc.latitude, loc.longitude))
        }
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> LocationManager.PASSIVE_PROVIDER
        }
        manager.requestLocationUpdates(provider, 2000L, 2f, listener)
        manager.getLastKnownLocation(provider)?.let { listener.onLocationChanged(it) }
        return { manager.removeUpdates(listener) }
    }
}
