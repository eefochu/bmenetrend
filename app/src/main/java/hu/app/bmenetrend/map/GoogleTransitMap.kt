package hu.app.bmenetrend.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.toArgb
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
import com.google.android.gms.maps.model.MapStyleOptions
import hu.app.bmenetrend.R
import hu.app.bmenetrend.TransitDiagnostics
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.ui.LocalDarkTheme
import hu.app.bmenetrend.ui.LocalMapAppearance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    userPosition: () -> GeoPoint? = { userLocation },
    userHeading: () -> Float? = { null },
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val dark = LocalDarkTheme.current
    val satellite = LocalMapAppearance.current.satellite
    val centerColor = StationSprite.deepCenterColor(MaterialTheme.colorScheme.primary.toArgb())
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
    // Padded area and quantized zoom the stop markers are built for; both change rarely during a gesture.
    var stopRegion by remember(view) { mutableStateOf<MapBounds?>(null) }
    var sceneZoom by remember(view) { mutableDoubleStateOf(GoogleMapScene.sceneZoom(savedZoom)) }
    // Not Compose state: read by the renderer only, so tracking a pinch never recomposes.
    val liveZoom = remember(view) { doubleArrayOf(savedZoom) }
    var userGesture by remember(view) { mutableStateOf(false) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // Where the finger last went down on the map, in map-view pixels; the SDK's click callbacks omit it.
    var lastTap by remember(view) { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    val currentFocus by rememberUpdatedState(lineFocus)
    val currentSelectedVehicle by rememberUpdatedState(selectedVehicle)
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
                /** Cheap enough to run while the camera moves: markers for a newly revealed area start loading mid-gesture. */
                fun trackCamera(): MapBounds {
                    val bounds = ready.projection.visibleRegion.latLngBounds
                    val area = MapBounds(bounds.southwest.longitude, bounds.southwest.latitude,
                        bounds.northeast.longitude, bounds.northeast.latitude)
                    val zoom = ready.cameraPosition.zoom.toDouble()
                    val focused = currentFocus != null
                    val crossed = listOf(null, "RAIL").any { mode ->
                        TransitMapVisibility.stop(mode, zoom, focused) != TransitMapVisibility.stop(mode, liveZoom[0], focused) ||
                            TransitMapVisibility.vehicle(mode, zoom, focused) != TransitMapVisibility.vehicle(mode, liveZoom[0], focused)
                    }
                    liveZoom[0] = zoom
                    stopRegion = GoogleMapScene.stopRegion(stopRegion, area, zoom)
                    sceneZoom = GoogleMapScene.sceneZoom(zoom)
                    region = VehicleSceneWindow.next(region, area)
                    // Existing markers show or hide the moment the zoom crosses their threshold.
                    if (crossed) renderer?.focus(currentFocus, zoom)
                    return area
                }
                var lastTracked = 0L
                ready.setOnCameraMoveListener {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastTracked >= 150L) { lastTracked = now; trackCamera() }
                }
                fun publishViewport() {
                    val area = trackCamera()
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
                // The SDK hands a tap to the topmost marker whose (padded) icon contains it, which let
                // station icons swallow taps aimed at a bus beside them. Resolve every tap ourselves.
                fun handleTap(fallback: GoogleMarkerSpec?) {
                    val drawing = renderer
                    val tap = lastTap
                    val resolved = if (drawing != null && tap != null) {
                        val (vehicles, stops) = drawing.tapTargets(tap.x, tap.y)
                        MapTapResolver.resolve(tap.x.toDouble(), tap.y.toDouble(), context.resources.displayMetrics.density.toDouble(), vehicles, stops)
                    } else null
                    when {
                        resolved is MapTapResolver.VehicleHit -> currentVehicleClick(resolved.id)
                        resolved is MapTapResolver.StopHit -> {
                            val spec = drawing?.spec(resolved.target.stop.id)
                            val ids = spec?.stops?.map { it.id } ?: listOf(resolved.target.stop.id)
                            if (ids.size > 1) currentSharedClick(ids) else currentStopClick(ids.first())
                        }
                        tap == null && fallback?.vehicle != null -> currentVehicleClick(fallback.vehicle.id)
                        tap == null && fallback != null && fallback.stops.isNotEmpty() -> {
                            val ids = fallback.stops.map { it.id }
                            if (ids.size > 1) currentSharedClick(ids) else currentStopClick(ids.first())
                        }
                        else -> currentMapClick()
                    }
                }
                ready.setOnMarkerClickListener { marker -> handleTap(renderer?.target(marker)); true }
                ready.setOnMapClickListener { handleTap(null) }
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
    AndroidView(factory = { view }, modifier = modifier.onSizeChanged { size = it }.pointerInput(view) {
        awaitEachGesture {
            // Observe only: the map still receives and handles every event.
            lastTap = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).position
        }
    })
    LaunchedEffect(map, size) {
        val ready = map ?: return@LaunchedEffect
        if (size.width > 0 && size.height > 0 && region == null) {
            val bounds = ready.projection.visibleRegion.latLngBounds
            val area = MapBounds(bounds.southwest.longitude, bounds.southwest.latitude,
                bounds.northeast.longitude, bounds.northeast.latitude)
            stopRegion = GoogleMapScene.stopRegion(null, area, savedZoom)
            region = VehicleSceneWindow.next(null, area)
        }
    }
    LaunchedEffect(map, dark, satellite) {
        map?.apply {
            setMapColorScheme(if (dark) MapColorScheme.DARK else MapColorScheme.LIGHT)
            mapType = if (satellite) GoogleMap.MAP_TYPE_HYBRID else GoogleMap.MAP_TYPE_NORMAL
            // Style only Google's background station labels, not our selectable stop overlays.
            setMapStyle(if (satellite) null else MapStyleOptions(GoogleBasemapStyle.json(dark)))
        }
    }
    val unavailableMessage by rememberUpdatedState(stringResource(R.string.engine_google_unavailable))
    // Keyed on the view, not the map: a map that never even arrives (getMapAsync stuck) also falls back.
    LaunchedEffect(view) {
        run {
            delay(GoogleMapHealth.LOAD_TIMEOUT_MS)
            if (!loaded) {
                // Never drew (software-GPU emulators): show the built-in map instead of a blank one.
                GoogleMapHealth.markFailed()
                currentError(unavailableMessage)
            }
        }
    }
    LaunchedEffect(renderer, lineFocus?.key, lineFocus?.routeId, lineFocus?.providerId,
        lineFocus?.number, lineFocus?.mode, lineFocus?.selectedVehicleId) {
        renderer?.focus(lineFocus, liveZoom[0])
    }
    LaunchedEffect(renderer, allStops, lineFocus, highlightedStopId, routeCatalogue, stopBearings,
        dark, routeColor, centerColor, showTransitStops, selectedVehicle?.id) {
        val drawing = renderer ?: return@LaunchedEffect
        snapshotFlow { Triple(vehicles(), region, sceneZoom to stopRegion) to currentSelectedVehicle }.collectLatest { (inputs, selection) ->
            val (live, area, camera) = inputs
            if (area == null) return@collectLatest
            val (zoom, visible) = camera
            val images = linkedMapOf<String, Bitmap>()
            try {
                val cached = drawing.cachedIcons
                val previousOffsets = drawing.cachedOffsets
                val scene = withContext(Dispatchers.Default) {
                    GoogleMapScene.prepare(context.applicationContext, live, area, selection?.id,
                        allStops, lineFocus, highlightedStopId, routeCatalogue, stopBearings,
                        zoom, dark, routeColor, showTransitStops, cached, images, viewport = visible ?: area,
                        previousOffsets = previousOffsets, selectedVehicle = selection, centerColor = centerColor)
                }
                // Markers are created while the map moves too, at a smaller per-frame budget.
                drawing.sync(scene, images, currentFocus, liveZoom[0]) {
                    if (userGesture) GoogleMapRenderer.GESTURE_BUDGET else GoogleMapRenderer.IDLE_BUDGET
                }
                images.clear() // ownership moved to the renderer
                TransitDiagnostics.event("google-scene") { "markers=${scene.markers.size} total=${live.size} focus=${currentFocus?.routeId}" }
            } finally { images.values.forEach(Bitmap::recycle) }
        }
    }
    LaunchedEffect(renderer, paths, routeColor, dark, dottedRoute) {
        renderer?.route(paths, routeColor, dark, dottedRoute)
    }
    LaunchedEffect(renderer, tracks) { renderer?.tracks(tracks) }
    val readPosition by rememberUpdatedState(userPosition)
    val readHeading by rememberUpdatedState(userHeading)
    LaunchedEffect(renderer) {
        val drawing = renderer ?: return@LaunchedEffect
        launch { snapshotFlow { readHeading() }.collect { drawing.userHeading(it) } }
        UserLocationMarker.glide(snapshotFlow { readPosition() }) { drawing.userPosition(it) }
    }
    LaunchedEffect(renderer, routeOrigin, selectedPlace) {
        renderer?.apply {
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
