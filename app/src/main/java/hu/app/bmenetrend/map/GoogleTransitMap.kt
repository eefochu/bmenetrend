package hu.app.bmenetrend.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMapOptions
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapColorScheme
import hu.app.bmenetrend.R
import hu.app.bmenetrend.TransitDiagnostics
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.ui.LocalDarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString

@Composable
internal fun GoogleTransitMap(
    network: TransitNetwork?,
    /** Read inside the vehicle layer's effect only: a live position tick re-uploads vehicles without recomposing. */
    vehicles: () -> List<Vehicle>,
    selectedStopId: String?,
    userLocation: GeoPoint?,
    modifier: Modifier = Modifier,
    onStopSelected: (String) -> Unit,
    onMapError: (String?) -> Unit = {},
    cameraRequest: Int = 0,
    bottomPaddingPx: Int = 0,
    additionalStops: List<TransitStop> = emptyList(),
    onMapZoomChanged: (Double) -> Unit = {},
    onVehicleSelected: (String) -> Unit = {},
    selectedVehicle: Vehicle? = null,
    routeGeometry: List<GeoPoint> = emptyList(),
    routeStops: List<TransitStop> = emptyList(),
    routeColor: Int = 0xFF199DCF.toInt(),
    dottedRoute: Boolean = false,
    fitRouteRequest: Int = 0,
    selectedPlace: PlaceResult? = null,
    placeCameraRequest: Int = 0,
    lineFocus: LineMapFocus? = null,
    browsingNearby: Boolean = false,
    locationCameraRequest: Int = 0,
    onViewportChanged: (MapBounds, GeoPoint) -> Unit = { _, _ -> },
    stopBearings: Map<String,List<Double>> = emptyMap(),
    onSharedStopSelected: (List<String>)->Unit = { ids -> ids.firstOrNull()?.let(onStopSelected) },
    onMapClick: () -> Unit = {},
    topPaddingPx: Int? = null,
    routeOrigin: GeoPoint? = null,
    routeCatalogue: Map<String, TransitRoute> = emptyMap(),
    showTransitStops: Boolean = true,
    highlightedStopId: String? = selectedStopId,
    onViewportPrefetch: (MapBounds) -> Unit = {},
    /** True while the user's finger (or its fling) moves the map; programmatic camera moves don't count. */
    onGestureChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val dark = LocalDarkTheme.current
    var savedLat by rememberSaveable { mutableDoubleStateOf(47.473) }
    var savedLon by rememberSaveable { mutableDoubleStateOf(18.825) }
    var savedZoom by rememberSaveable { mutableDoubleStateOf(13.2) }
    // No map ID: standard Maps SDK map display stays on the unlimited no-charge SKU.
    val view = remember { MapView(context, GoogleMapOptions()
        .camera(CameraPosition.fromLatLngZoom(LatLng(savedLat, savedLon), savedZoom.toFloat()))).apply { onCreate(null) } }
    var map by remember(view) { mutableStateOf<GoogleMap?>(null) }
    var renderer by remember(view) { mutableStateOf<GoogleMapRenderer?>(null) }
    var loaded by remember(view) { mutableStateOf(false) }
    var region by remember(view) { mutableStateOf<MapBounds?>(null) }
    var viewport by remember(view) { mutableStateOf<MapBounds?>(null) }
    var userGesture by remember(view) { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentFocus by rememberUpdatedState(lineFocus)
    val currentVehicleClick by rememberUpdatedState(onVehicleSelected)
    val currentStopClick by rememberUpdatedState(onStopSelected)
    val currentSharedClick by rememberUpdatedState(onSharedStopSelected)
    val currentMapClick by rememberUpdatedState(onMapClick)
    val currentArea by rememberUpdatedState(onViewportChanged)
    val currentPrefetch by rememberUpdatedState(onViewportPrefetch)
    val currentZoom by rememberUpdatedState(onMapZoomChanged)
    val currentGesture by rememberUpdatedState(onGestureChanged)
    val currentLocation by rememberUpdatedState(userLocation)
    val currentError by rememberUpdatedState(onMapError)
    val networkStops = network?.stops.orEmpty()
    val allStops by produceState<List<TransitStop>>(emptyList(), networkStops, additionalStops, routeStops) {
        value = withContext(Dispatchers.Default) {
            (networkStops + additionalStops + routeStops).distinctBy { it.id }
        }
    }
    val paths = lineFocus?.displayPaths ?: listOf(routeGeometry).filter { it.size >= 2 }
    val tracks by produceState<List<Pair<List<GeoPoint>, Int>>>(emptyList(), context.applicationContext) {
        value = withContext(Dispatchers.Default) {
            FeatureCollection.fromJson(MetroNetworkOverlay.load(context.applicationContext)).features().orEmpty().mapNotNull { feature ->
                val geometry = feature.geometry() as? LineString ?: return@mapNotNull null
                geometry.coordinates().map { GeoPoint(it.latitude(), it.longitude()) } to Color.parseColor(feature.getStringProperty("color"))
            }
        }
    }
    DisposableEffect(view, owner) {
        var active = true
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_START -> view.onStart()
            Lifecycle.Event.ON_RESUME -> view.onResume()
            Lifecycle.Event.ON_PAUSE -> view.onPause()
            Lifecycle.Event.ON_STOP -> view.onStop()
            else -> Unit
        } }
        val memory = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = view.onLowMemory()
            override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) view.onLowMemory() }
        }
        owner.lifecycle.addObserver(observer)
        context.applicationContext.registerComponentCallbacks(memory)
        view.getMapAsync { ready ->
            if (active) {
                map = ready
                renderer = GoogleMapRenderer(ready, context.resources.displayMetrics.density)
                ready.setMinZoomPreference(5f); ready.setMaxZoomPreference(19f)
                ready.isBuildingsEnabled = false
                ready.uiSettings.apply {
                    isMapToolbarEnabled = false; isZoomControlsEnabled = false
                    isMyLocationButtonEnabled = false; isIndoorLevelPickerEnabled = false
                }
                ready.setOnMapLoadedCallback { loaded = true; currentError(null) }
                ready.setOnCameraMoveStartedListener { reason ->
                    if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                        userGesture = true; currentGesture(true)
                    }
                }
                fun publishViewport() {
                    val bounds = ready.projection.visibleRegion.latLngBounds
                    val area = MapBounds(bounds.southwest.longitude, bounds.southwest.latitude,
                        bounds.northeast.longitude, bounds.northeast.latitude)
                    viewport = area
                    region = VehicleSceneWindow.next(region, area)
                    savedLat = ready.cameraPosition.target.latitude
                    savedLon = ready.cameraPosition.target.longitude
                    savedZoom = ready.cameraPosition.zoom.toDouble()
                    renderer?.focus(currentFocus, savedZoom)
                    currentZoom(savedZoom)
                    currentPrefetch(area)
                    currentArea(area, GeoPoint(savedLat, savedLon))
                }
                ready.setOnCameraIdleListener {
                    userGesture = false; currentGesture(false)
                    publishViewport()
                }
                ready.setOnMarkerClickListener { marker ->
                    val spec = renderer?.target(marker)
                    when {
                        spec?.vehicle != null -> currentVehicleClick(spec.vehicle.id)
                        spec != null && spec.stops.isNotEmpty() -> {
                            val ids = spec.stops.map { it.id }
                            if (ids.size > 1) currentSharedClick(ids) else currentStopClick(ids.first())
                        }
                    }
                    true
                }
                ready.setOnMapClickListener { currentMapClick() }
                if (view.width > 0 && view.height > 0) publishViewport()
            }
        }
        onDispose {
            active = false
            if (userGesture) currentGesture(false)
            renderer?.clear()
            owner.lifecycle.removeObserver(observer)
            context.applicationContext.unregisterComponentCallbacks(memory)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
            view.onDestroy()
        }
    }
    AndroidView(factory = { view }, modifier = modifier.onSizeChanged { size = it })
    LaunchedEffect(map, size) {
        val ready = map ?: return@LaunchedEffect
        if (size.width > 0 && size.height > 0 && region == null) {
            val bounds = ready.projection.visibleRegion.latLngBounds
            val area = MapBounds(bounds.southwest.longitude, bounds.southwest.latitude,
                bounds.northeast.longitude, bounds.northeast.latitude)
            viewport = area
            region = VehicleSceneWindow.next(null, area)
        }
    }
    LaunchedEffect(map, dark) {
        map?.setMapColorScheme(if (dark) MapColorScheme.DARK else MapColorScheme.LIGHT)
    }
    LaunchedEffect(map) {
        if (map != null) {
            delay(20_000)
            if (!loaded) currentError(context.getString(R.string.engine_map_unavailable))
        }
    }
    LaunchedEffect(renderer, lineFocus?.key, lineFocus?.routeId, lineFocus?.providerId,
        lineFocus?.number, lineFocus?.mode, lineFocus?.selectedVehicleId) {
        renderer?.focus(lineFocus, savedZoom)
    }
    LaunchedEffect(renderer, allStops, lineFocus, highlightedStopId, routeCatalogue, stopBearings,
        dark, routeColor, showTransitStops, selectedVehicle?.id) {
        val drawing = renderer ?: return@LaunchedEffect
        snapshotFlow { Triple(vehicles(), region, savedZoom to viewport) }.collectLatest { (live, area, camera) ->
            if (area == null) return@collectLatest
            val (zoom, visible) = camera
            val images = linkedMapOf<String, Bitmap>()
            try {
                val cached = drawing.cachedIcons
                val previousOffsets = drawing.cachedOffsets
                val scene = withContext(Dispatchers.Default) {
                    GoogleMapScene.prepare(context.applicationContext, live, area, selectedVehicle?.id,
                        allStops, lineFocus, highlightedStopId, routeCatalogue, stopBearings,
                        zoom, dark, routeColor, showTransitStops, cached, images, viewport = visible ?: area,
                        previousOffsets = previousOffsets)
                }
                snapshotFlow { userGesture }.first { !it }
                drawing.sync(scene, images, currentFocus, savedZoom)
                images.clear() // ownership moved to the renderer
                TransitDiagnostics.event("google-scene") { "markers=${scene.markers.size} total=${live.size} focus=${currentFocus?.routeId}" }
            } finally { images.values.forEach(Bitmap::recycle) }
        }
    }
    LaunchedEffect(renderer, paths, routeColor, dark, dottedRoute) {
        renderer?.route(paths, routeColor, dark, dottedRoute)
    }
    LaunchedEffect(renderer, tracks) { renderer?.tracks(tracks) }
    LaunchedEffect(renderer, userLocation, routeOrigin, selectedPlace) {
        renderer?.apply {
            pin("location", userLocation, Color.rgb(53, 109, 219))
            pin("origin", routeOrigin, Color.GRAY)
            pin("place", selectedPlace?.position, Color.rgb(211, 47, 47), selectedPlace?.name)
        }
    }
    var previousCamera by remember { mutableStateOf<CameraPosition?>(null) }
    var previousFocus by remember { mutableStateOf<String?>(null) }
    var fitted by remember { mutableStateOf<Any?>(null) }
    var centeredStop by remember { mutableStateOf<String?>(null) }
    var centeredRequest by remember { mutableIntStateOf(-1) }
    var locatedRequest by remember { mutableIntStateOf(-1) }
    LaunchedEffect(map, size, paths, lineFocus?.key, allStops, selectedStopId, selectedPlace, placeCameraRequest,
        cameraRequest, fitRouteRequest, bottomPaddingPx, topPaddingPx, browsingNearby, locationCameraRequest, userLocation != null) {
        val ready = map ?: return@LaunchedEffect
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val density = context.resources.displayMetrics.density
        val top = topPaddingPx ?: ((if (lineFocus != null) 156 else 96) * density).toInt()
        val bottom = minOf(bottomPaddingPx, (size.height - top - 140 * density).toInt().coerceAtLeast(0))
        ready.setPadding(0, top, 0, bottom)
        fun fit(points: List<GeoPoint>) {
            val unique = points.distinct()
            if (unique.size >= 2) {
                val bounds = LatLngBounds.Builder().apply { unique.forEach { include(it.latLng()) } }.build()
                ready.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, (36 * density).toInt()), 650, null)
            } else unique.firstOrNull()?.let { ready.animateCamera(CameraUpdateFactory.newLatLngZoom(it.latLng(), 15f), 650, null) }
        }
        val stop = selectedStopId?.let { id -> lineFocus?.displayStops?.find { it.id == id } ?: allStops.find { it.id == id } }
        when {
            lineFocus != null -> {
                if (previousFocus == null) previousCamera = ready.cameraPosition
                previousFocus = lineFocus.key
                if (stop != null && (centeredStop != stop.id || centeredRequest != cameraRequest)) {
                    centeredStop = stop.id; centeredRequest = cameraRequest
                    ready.animateCamera(CameraUpdateFactory.newLatLngZoom(stop.position.latLng(), maxOf(15f, ready.cameraPosition.zoom)), 650, null)
                } else {
                    val signature = listOf(lineFocus.key, paths, lineFocus.displayStops, fitRouteRequest, bottom, size)
                    if (signature != fitted) {
                        fitted = signature
                        val points = paths.flatten() + lineFocus.displayStops.map { it.position }
                        if (points.isNotEmpty()) fit(points)
                        else selectedVehicle?.let { ready.animateCamera(CameraUpdateFactory.newLatLngZoom(it.position.latLng(), 13.5f), 650, null) }
                    }
                }
            }
            previousFocus != null -> {
                previousFocus = null; fitted = null
                if (selectedStopId == null) previousCamera?.let { ready.animateCamera(CameraUpdateFactory.newCameraPosition(it), 650, null) }
                previousCamera = null
            }
            selectedPlace != null -> {
                val bounds = selectedPlace.bounds
                if (bounds != null) fit(listOf(GeoPoint(bounds.south, bounds.west), GeoPoint(bounds.north, bounds.east)))
                else ready.animateCamera(CameraUpdateFactory.newLatLngZoom(selectedPlace.position.latLng(), 15f), 650, null)
            }
            browsingNearby && currentLocation != null && locatedRequest != locationCameraRequest -> {
                locatedRequest = locationCameraRequest
                ready.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLocation!!.latLng(), 15f), 650, null)
            }
            stop != null && (centeredStop != stop.id || centeredRequest != cameraRequest) -> {
                centeredStop = stop.id; centeredRequest = cameraRequest
                ready.animateCamera(CameraUpdateFactory.newLatLngZoom(stop.position.latLng(), maxOf(15f, ready.cameraPosition.zoom)), 650, null)
            }
            paths.isNotEmpty() -> {
                val signature = listOf(paths, fitRouteRequest, bottom, size)
                if (fitted != signature) { fitted = signature; fit(paths.flatten()) }
            }
        }
    }
}
