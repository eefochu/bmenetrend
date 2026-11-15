package hu.app.bmenetrend.map

import hu.app.bmenetrend.TransitDiagnostics
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PointF
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.saveable.rememberSaveable
import hu.app.bmenetrend.ui.LocalDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.compose.LocalLifecycleOwner
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.MapBounds
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.TransitStop
import hu.app.bmenetrend.data.TransitNetwork
import hu.app.bmenetrend.data.TransitRoute
import hu.app.bmenetrend.data.Vehicle
import hu.app.bmenetrend.data.PlaceResult
import org.maplibre.android.MapLibre
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.maps.Style


/** Keyless OpenFreeMap basemap, with a bundled custom night style. */
@Composable
fun NativeTransitMap(
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
    offline: Boolean = false,
    topPaddingPx: Int? = null,
    routeOrigin: GeoPoint? = null,
    routeCatalogue: Map<String, TransitRoute> = emptyMap(),
    showTransitStops: Boolean = true,
    highlightedStopId: String? = selectedStopId,
    onViewportPrefetch: (MapBounds) -> Unit = {},
    /** True while the user's finger (or its fling) moves the map; programmatic camera moves don't count. */
    onGestureChanged: (Boolean) -> Unit = {},
    /** Every GPS fix, read only inside the position marker's effect so it never recomposes the map. */
    userPosition: () -> GeoPoint? = { userLocation },
    /** Compass heading in degrees from north, or null; drives the facing cone. */
    userHeading: () -> Float? = { null },
) {
    val googleContext = LocalContext.current
    // Reading GoogleMapHealth.failed here recomposes into the built-in map the moment Google gives up.
    if (hu.app.bmenetrend.ui.LocalMapAppearance.current.google && !offline && !GoogleMapHealth.failed &&
        remember { GoogleMapHealth.usable(googleContext) }) {
        GoogleTransitMap(
            network = network,
            vehicles = vehicles,
            selectedStopId = selectedStopId,
            userLocation = userLocation,
            modifier = modifier,
            onStopSelected = onStopSelected,
            onMapError = onMapError,
            cameraRequest = cameraRequest,
            bottomPaddingPx = bottomPaddingPx,
            additionalStops = additionalStops,
            onMapZoomChanged = onMapZoomChanged,
            onVehicleSelected = onVehicleSelected,
            selectedVehicle = selectedVehicle,
            routeGeometry = routeGeometry,
            routeStops = routeStops,
            routeColor = routeColor,
            dottedRoute = dottedRoute,
            fitRouteRequest = fitRouteRequest,
            selectedPlace = selectedPlace,
            placeCameraRequest = placeCameraRequest,
            lineFocus = lineFocus,
            browsingNearby = browsingNearby,
            locationCameraRequest = locationCameraRequest,
            onViewportChanged = onViewportChanged,
            stopBearings = stopBearings,
            onSharedStopSelected = onSharedStopSelected,
            onMapClick = onMapClick,
            topPaddingPx = topPaddingPx,
            routeOrigin = routeOrigin,
            routeCatalogue = routeCatalogue,
            showTransitStops = showTransitStops,
            highlightedStopId = highlightedStopId,
            onViewportPrefetch = onViewportPrefetch,
            onGestureChanged = onGestureChanged,
            userPosition = userPosition,
            userHeading = userHeading,
        )
        return
    }
    val darkTheme = LocalDarkTheme.current
    val centerColor = StationSprite.deepCenterColor(MaterialTheme.colorScheme.primary.toArgb())
    val satellite = hu.app.bmenetrend.ui.LocalMapAppearance.current.style == hu.app.bmenetrend.ui.MapStyle.SATELLITE && !offline
    val styleUri = if (offline) { if (darkTheme) "asset://map/offline-dark.json" else "asset://map/offline.json" }
        else if (satellite) "asset://map/satellite.json"
        else if (darkTheme) "asset://map/night.json" else "https://tiles.openfreemap.org/styles/positron"
    val context = LocalContext.current
    // Strings come from LocalResources so they follow configuration changes (Compose lint).
    val resources = androidx.compose.ui.platform.LocalResources.current
    val rapidTransitLines by produceState<String?>(null, context.applicationContext) {
        value = MetroNetworkOverlay.load(context.applicationContext)
    }
    val owner = LocalLifecycleOwner.current
    val onSelect by rememberUpdatedState(onStopSelected)
    val onSharedSelect by rememberUpdatedState(onSharedStopSelected)
    val onMapClicked by rememberUpdatedState(onMapClick)
    val onError by rememberUpdatedState(onMapError)
    val onArea by rememberUpdatedState(onViewportChanged)
    val onPrefetch by rememberUpdatedState(onViewportPrefetch)
    val prefetchStops by rememberUpdatedState(showTransitStops)
    val onZoom by rememberUpdatedState(onMapZoomChanged)
    val onVehicle by rememberUpdatedState(onVehicleSelected)
    val onGesture by rememberUpdatedState(onGestureChanged)
    // The camera follows the user only on the first fix or an explicit request (locationCameraRequest);
    // later fixes must not restart the camera effect every couple of seconds.
    val latestLocation by rememberUpdatedState(userLocation)
    // Read only inside effects (never in composition), so toggling it does not recompose.
    var userGesture by remember { mutableStateOf(false) }
    val networkStops = if (showTransitStops) network?.stops.orEmpty() else emptyList()
    val catalogueStops = if (showTransitStops) additionalStops else emptyList()
    var savedLat by rememberSaveable { mutableDoubleStateOf(47.473) }
    var savedLon by rememberSaveable { mutableDoubleStateOf(18.825) }
    var savedZoom by rememberSaveable { mutableDoubleStateOf(13.2) }
    var zoom by remember { mutableDoubleStateOf(savedZoom) }
    val focused = lineFocus != null
    val currentVehicleFocus by rememberUpdatedState(lineFocus)
    val currentSelectedVehicle by rememberUpdatedState(selectedVehicle)
    val focusStops = lineFocus?.displayStops.orEmpty()
    /** First record wins, in the order the scene deduplicates them: local network, national catalogue, route. */
    fun displayStop(id: String?): TransitStop? = if (id == null) null else if (focused) lineFocus!!.displayStops.find { it.id == id }
        else networkStops.find { it.id == id } ?: catalogueStops.find { it.id == id } ?: routeStops.find { it.id == id }
    val highlightedIds = remember(focusStops) { focusStops.map { it.id }.toSet() }
    val paths = lineFocus?.displayPaths ?: listOf(routeGeometry).filter { it.size >= 2 }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var previousCamera by rememberSaveable { mutableStateOf<CameraPosition?>(null) }
    var previousFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
    var fittedSignature by remember { mutableStateOf<Any?>(null) }
    val mapView = remember(offline) {
        MapLibre.getInstance(context.applicationContext)
        org.maplibre.android.module.http.HttpRequestUtil.setOkHttpClient(hu.app.bmenetrend.data.EmmaGraphqlClient.defaultHttpClient())
        MapView(context).apply { onCreate(null) }
    }
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    fun logVehicleFrame(ready: MapLibreMap, reason: String) {
        if (!TransitDiagnostics.enabled) return
        runCatching {
            val bounds = ready.projection.visibleRegion.latLngBounds
            val focus = currentVehicleFocus
            val list = vehicles()
            val inView = list.filter { bounds.contains(LatLng(it.position.lat, it.position.lon)) }
            val expected = inView.count { focus?.includes(it) != false }
            val rendered = ready.queryRenderedFeatures(android.graphics.RectF(0f, 0f,
                mapView.width.toFloat(), mapView.height.toFloat()), *VehicleMapScene.layers)
            val renderedIds = rendered.mapNotNull { it.getStringProperty("vehicle-id") }.distinct().size
            val tiled = ready.style?.getSourceAs<GeoJsonSource>(VehicleMapScene.SOURCE)
                ?.querySourceFeatures(org.maplibre.android.style.expressions.Expression.literal(true))
                ?.mapNotNull { it.getStringProperty("vehicle-id") }?.distinct()?.size ?: 0
            TransitDiagnostics.event("frame") {
                "reason=$reason total=${list.size} inView=${inView.size} expected=$expected rendered=$renderedIds tiled=$tiled zoom=${ready.cameraPosition.zoom} focus=${focus?.routeId} provider=${focus?.providerId}"
            }
        }.onFailure { error -> TransitDiagnostics.event("frame-error") { error.javaClass.simpleName } }
    }
    var viewport by remember { mutableStateOf<MapBounds?>(null) }
    var vehicleRegion by remember(mapView) { mutableStateOf<MapBounds?>(null) }
    // Only drives the catalogue prefetch before the national stop index loads; markers no longer
    // depend on the camera window, so this is not Compose state (no recomposition per pan).
    val stationWindow = remember(mapView) { java.util.concurrent.atomic.AtomicReference<StationWindow?>(null) }
    // Uploaded marker data; read by tap handling and the highlight effect.
    var markerData by remember(mapView) { mutableStateOf<StationMarkers.Data?>(null) }
    // Area whose road stops are uploaded; changes only when the view nears its edge.
    var markerRegion by remember(mapView) { mutableStateOf<MapBounds?>(null) }
    // Grouped and allocated in a camera-independent world, so pan, zoom, selection and highlights cannot
    // reshuffle it. Tens of thousands of national stops: deduplicated and indexed off the main thread.
    val sceneIndex by produceState<StationSceneIndex?>(null, networkStops, catalogueStops) {
        value = withContext(Dispatchers.Default) {
            StationSceneIndex((networkStops + catalogueStops).distinctBy { it.id })
        }
    }
    val stationScene by produceState<StationScene?>(null,sceneIndex,markerRegion,focused,focusStops,stopBearings) {
        val catalogue = sceneIndex
        if (!focused && catalogue == null) return@produceState
        val region = markerRegion
        val previousOffsets=value?.offsets.orEmpty()
        value=withContext(Dispatchers.Default) {
            val selectedStops = if (focused) focusStops else catalogue!!.select(region, false)
            val bounds=selectedStops.takeIf { it.isNotEmpty() }?.let { all -> MapBounds(
                all.minOf { it.position.lon },all.minOf { it.position.lat },all.maxOf { it.position.lon },all.maxOf { it.position.lat }) }
            val groups=traceMapWork("BMenetrend.StopGroups") { StopMapClustering.groups(selectedStops,bounds,16.0,stopBearings) }
            val merged = RailStationMarkers.mergePlatforms(groups).map { StationGroup(stationAnchor(it), it) }
            StationScene(merged, StationChunkIndex(merged) { it.anchor.position },
                if (focused) emptyMap() else StopMarkerLayout.offsets(merged.map { it.anchor },previousOffsets), region,
                highlightedIds.takeIf { focused })
        }
    }
    val markerImageNames = remember { HashMap<String, String>() }
    fun markerImageName(spriteKey: String) = synchronized(markerImageNames) { markerImageNames.getOrPut(spriteKey) { "st${markerImageNames.size}" } }
    val stationUploads = remember(mapView) { UploadCoalescer() }
    var styleLoaded by remember(mapView) { mutableStateOf(false) }
    var styleGeneration by remember(mapView) { mutableIntStateOf(0) }
    var locatedOnce by rememberSaveable { mutableStateOf(false) }
    var locatedRequest by remember { mutableIntStateOf(-1) }
    var centeredStop by remember { mutableStateOf<String?>(null) }
    var centeredRequest by remember { mutableIntStateOf(-1) }
    var lastBottomPadding by remember { mutableIntStateOf(-1) }
    val vehicleIconKeys = remember(mapView) { mutableSetOf<String>() }
    val uploadedVehicles = remember(mapView) { arrayOfNulls<String>(1) }
    val pins = remember(mapView) { MapPins(context) }
    val appliedPadding = remember(mapView) { intArrayOf(-1, -1) }
    val stationIconKeys=remember(mapView) { mutableSetOf<String>() }

    DisposableEffect(mapView, owner) {
        if (offline) OfflineBasemap.retain()
        val sourceListener = MapView.OnSourceChangedListener { id ->
            if (id == VehicleMapScene.SOURCE || id == StationMarkers.SOURCE) {
                TransitDiagnostics.event("native-source") { "id=$id" }
            }
        }
        mapView.addOnSourceChangedListener(sourceListener)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> { mapView.onStart(); boostRenderThread(mapView) }
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        val memoryCallbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            override fun onLowMemory() = mapView.onLowMemory()
            override fun onTrimMemory(level: Int) { if (MapMemoryPolicy.shouldTrim(level)) mapView.onLowMemory() }
        }
        context.applicationContext.registerComponentCallbacks(memoryCallbacks)
        mapView.addOnDidFailLoadingMapListener { onError(resources.getString(R.string.engine_map_unavailable)) }
        boostRenderThread(mapView)
        mapView.getMapAsync { ready ->
            map = ready
            // Keep the neighbouring zoom's basemap tiles warm for zoom-out/back-in.
            // The default preview is four levels away and may miss neighbouring
            // levels. Use the SDK's bounded cache rather than retaining maps ourselves.
            ready.setPrefetchZoomDelta(1)
            ready.uiSettings.apply {
                isAttributionEnabled = false
                isLogoEnabled = false
                isCompassEnabled = true
                val inset = (16 * context.resources.displayMetrics.density).toInt()
                val topInset = (80 * context.resources.displayMetrics.density).toInt()
                setCompassMargins(0, topInset, inset, 0)
            }
            ready.cameraPosition = CameraPosition.Builder().target(LatLng(savedLat, savedLon)).zoom(savedZoom).build()
            ready.setMinZoomPreference(5.0)
            var lastStationUpdate = 0L
            fun updateStationWindow() {
                val bounds = ready.projection.visibleRegion.latLngBounds
                val previous = stationWindow.get()
                val visible = MapBounds(bounds.longitudeWest,bounds.latitudeSouth,bounds.longitudeEast,bounds.latitudeNorth)
                vehicleRegion = VehicleSceneWindow.next(vehicleRegion, visible)
                val next = StationWindow.next(previous,visible,ready.cameraPosition.zoom)
                stationWindow.set(next)
                val region = StationMarkers.nextRegion(markerRegion,visible,ready.cameraPosition.zoom)
                if (region !== markerRegion) markerRegion = region
                if (prefetchStops && next != previous && next.detail > 0) onPrefetch(next.bounds)
            }
            ready.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && !userGesture) {
                    userGesture = true; onGesture(true)
                    TransitDiagnostics.event("gesture") { "active=true" }
                }
            }
            ready.addOnCameraMoveListener {
                val now = android.os.SystemClock.uptimeMillis()
                if (now-lastStationUpdate >= 120L) { lastStationUpdate=now; updateStationWindow() }
            }
            ready.addOnCameraIdleListener {
                if (userGesture) { userGesture = false; onGesture(false) }
                TransitDiagnostics.event("gesture") { "active=false idle" }
                logVehicleFrame(ready, "camera-idle")
                updateStationWindow()
                val bounds = ready.projection.visibleRegion.latLngBounds
                viewport = MapBounds(bounds.longitudeWest, bounds.latitudeSouth, bounds.longitudeEast, bounds.latitudeNorth)
                zoom = ready.cameraPosition.zoom
                savedLat = ready.cameraPosition.target!!.latitude
                savedLon = ready.cameraPosition.target!!.longitude
                savedZoom = zoom
                onZoom(zoom)
                onArea(viewport!!, GeoPoint(savedLat, savedLon))
            }
            ready.setMaxZoomPreference(19.0)
            /** Visible markers near a tap; never projects the whole national catalogue. */
            fun targets(tap: PointF): List<StopHitTesting.Target> {
                val data = markerData ?: return emptyList()
                val camera = ready.cameraPosition
                val projection = ready.projection
                val angle = Math.toRadians(camera.bearing)
                val cosine = kotlin.math.cos(angle)
                val sine = kotlin.math.sin(angle)
                val density = context.resources.displayMetrics.density.toDouble()
                // Hit radius (24dp) + largest marker displacement (24dp) + overlap check margin.
                val reach = (80 * density).toFloat()
                val corners = listOf(PointF(tap.x - reach, tap.y - reach), PointF(tap.x + reach, tap.y - reach),
                    PointF(tap.x - reach, tap.y + reach), PointF(tap.x + reach, tap.y + reach)).map(projection::fromScreenLocation)
                val window = StationWindow(StationWindow.cell(corners.minOf { it.longitude }), StationWindow.cell(corners.minOf { it.latitude }),
                    StationWindow.cell(corners.maxOf { it.longitude }), StationWindow.cell(corners.maxOf { it.latitude }), 3)
                return data.index.query(window).mapNotNull { marker ->
                    if (!TransitMapVisibility.stop(marker.group.anchor.transportMode, camera.zoom, data.focused)) return@mapNotNull null
                    val position = marker.group.anchor.position
                    val point = projection.toScreenLocation(LatLng(position.lat, position.lon))
                    val diameter = StopMarkerLayout.diameterDp(camera.zoom, data.focused)
                    val scale = diameter / StopMarkerLayout.DIAMETER_DP * density
                    val dx = marker.offset.x * scale
                    val dy = marker.offset.y * scale
                    StopHitTesting.Target(marker.stop, point.x + dx * cosine + dy * sine,
                        point.y - dx * sine + dy * cosine, diameter * density / 2)
                }
            }
            /** Drawn vehicles near a tap, with their on-screen centre and drawn radius. */
            fun vehicleTargets(ready: MapLibreMap, tap: PointF, density: Double): List<MapTapResolver.VehicleTarget> {
                val reach = ((MapTapResolver.MIN_REACH_DP + VehicleMarkerLayout.SELECTED_DIAMETER_DP / 2) * density).toFloat()
                val overview = currentVehicleFocus == null && ready.cameraPosition.zoom < TransitMapVisibility.VEHICLE_SYMBOL_MIN_ZOOM
                return ready.queryRenderedFeatures(android.graphics.RectF(tap.x - reach, tap.y - reach, tap.x + reach, tap.y + reach),
                    *VehicleMapScene.layers).mapNotNull { feature ->
                    if (!feature.hasProperty("vehicle-id")) return@mapNotNull null
                    val geometry = feature.geometry() as? org.maplibre.geojson.Point ?: return@mapNotNull null
                    val screen = ready.projection.toScreenLocation(LatLng(geometry.latitude(), geometry.longitude()))
                    val scale = if (feature.hasProperty("large-scale")) feature.getNumberProperty("large-scale").toDouble() else
                        VehicleMarkerLayout.DIAMETER_DP / VehicleMarkerLayout.ICON_DIAMETER_DP.toDouble()
                    val diameter = scale * VehicleMarkerLayout.ICON_DIAMETER_DP * if (overview) VehicleMarkerLayout.OVERVIEW_FACTOR else 1f
                    MapTapResolver.VehicleTarget(feature.getStringProperty("vehicle-id"), screen.x.toDouble(), screen.y.toDouble(), diameter * density / 2)
                }.distinctBy { it.id }
            }
            fun reveal(location: LatLng) {
                ready.animateCamera(CameraUpdateFactory.newLatLngZoom(location,
                    maxOf(16.0,ready.cameraPosition.zoom+2).coerceAtMost(19.0)),450)
            }
            ready.addOnMapClickListener { location ->
                val point=ready.projection.toScreenLocation(location)
                val density=context.resources.displayMetrics.density.toDouble()
                val hits=targets(point)
                val resolved=MapTapResolver.resolve(point.x.toDouble(),point.y.toDouble(),density,
                    vehicleTargets(ready,point,density),hits)
                if (resolved is MapTapResolver.VehicleHit) {
                    onVehicle(resolved.id)
                    return@addOnMapClickListener true
                }
                val hit=(resolved as? MapTapResolver.StopHit)?.target
                val candidate=hit?.stop
                if(candidate==null) {
                    onMapClicked()
                    true
                } else {
                    val members=markerData?.markers?.get(candidate.id)?.group?.members.orEmpty()
                    if(StopHitTesting.needsZoom(hits,hit,density,ready.cameraPosition.zoom)) reveal(location)
                    else if(members.flatMap { it.providerIds }.distinct().size>1 || members.size>1 && members.all(RailStationMarkers::isRail)) onSharedSelect(listOf(candidate.id)+members.map { it.id }.filterNot { it==candidate.id })
                    else onSelect(candidate.id)
                    true
                }
            }

        }
        onDispose {
            mapView.removeOnSourceChangedListener(sourceListener)
            if (userGesture) { userGesture = false; onGesture(false) }
            owner.lifecycle.removeObserver(observer)
            context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
            if (offline) OfflineBasemap.release(context.applicationContext)
        }
    }
    // Theme changes reload only the basemap, retaining camera and current selections.
    // Failed loads retry in the foreground using the currently selected theme.
    LaunchedEffect(map, owner, styleUri) {
        val ready = map ?: return@LaunchedEffect
        var active = true
        styleLoaded = false
        try {
            // Preparing/copying the archive and reading styles run on IO. HTTP is
            // never a fallback for missing or damaged offline map resources.
            val offlineStyle = if (offline) OfflineBasemap.style(context.applicationContext, darkTheme) else null
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                fun loadStyle() {
                    val builder = if (offlineStyle != null) Style.Builder().fromJson(offlineStyle) else Style.Builder().fromUri(styleUri)
                    ready.setStyle(builder) { _ ->
                        if (active) {
                            styleLoaded = true
                            styleGeneration++
                            onError(null)
                            val bounds = ready.projection.visibleRegion.latLngBounds
                            viewport = MapBounds(bounds.longitudeWest, bounds.latitudeSouth, bounds.longitudeEast, bounds.latitudeNorth)
                            stationWindow.set(StationWindow.next(stationWindow.get(), viewport!!, ready.cameraPosition.zoom))
                            StationMarkers.nextRegion(markerRegion, viewport!!, ready.cameraPosition.zoom).let { if (it !== markerRegion) markerRegion = it }
                            zoom = ready.cameraPosition.zoom
                            savedLat = ready.cameraPosition.target!!.latitude
                            savedLon = ready.cameraPosition.target!!.longitude
                            savedZoom = zoom
                            onZoom(zoom)
                            onArea(viewport!!, GeoPoint(savedLat, savedLon))
                        }
                    }
                }
                if (!styleLoaded) loadStyle()
                while (isActive) {
                    delay(30_000)
                    if (!styleLoaded) loadStyle()
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            onError(resources.getString(R.string.engine_map_unavailable))
        } finally { active = false }
    }
    LaunchedEffect(map, styleLoaded, styleGeneration, rapidTransitLines, darkTheme) {
        val ready = map ?: return@LaunchedEffect
        if (!styleLoaded) return@LaunchedEffect
        val json = rapidTransitLines ?: return@LaunchedEffect
        ready.style?.let { MetroNetworkOverlay.install(it,json,darkTheme) }
    }
    LaunchedEffect(map, styleLoaded, styleGeneration, paths, routeColor, darkTheme, dottedRoute) {
        val ready = map ?: return@LaunchedEffect
        if (!styleLoaded) return@LaunchedEffect
        val style = ready.style ?: return@LaunchedEffect
        if (paths.none { it.size >= 2 }) { RouteLineLayer.hide(style); return@LaunchedEffect }
        val json = withContext(Dispatchers.Default) { RouteLineLayer.geoJson(paths) { ensureActive() } }
        RouteLineLayer.show(style, json, routeColor, darkTheme, dottedRoute)
    }
    LaunchedEffect(map, styleLoaded, styleGeneration, lineFocus?.key, paths, focusStops, fitRouteRequest,
        selectedStopId, selectedPlace, placeCameraRequest, cameraRequest, bottomPaddingPx, topPaddingPx, size, browsingNearby, userLocation != null, locationCameraRequest) {
        val ready = map ?: return@LaunchedEffect
        if (!styleLoaded || size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val location = latestLocation
        if (focused && previousFocusKey == null) previousCamera = ready.cameraPosition
        val topInset = topPaddingPx ?: ((if (focused) 156 else 96) * context.resources.displayMetrics.density).toInt()
        val bottomInset = minOf(bottomPaddingPx, (size.height - topInset - 140 * context.resources.displayMetrics.density).toInt().coerceAtLeast(0))
        // Location fixes re-run this effect every couple of seconds; unchanged padding must not re-render.
        if (appliedPadding[0] != topInset || appliedPadding[1] != bottomInset) {
            ready.setPadding(0, topInset, 0, bottomInset)
            appliedPadding[0] = topInset; appliedPadding[1] = bottomInset
        }
        if (focused) {
            previousFocusKey = lineFocus!!.key
            val stop = displayStop(selectedStopId)
            if (stop != null && (centeredStop != stop.id || centeredRequest != cameraRequest)) {
                centeredStop = stop.id
                centeredRequest = cameraRequest
                ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(stop.position.lat, stop.position.lon), maxOf(15.0, ready.cameraPosition.zoom)), 650)
            } else {
                if (stop == null) centeredStop = null
                // Keep directions as separate paths: never invent links between their termini.
                val points = (paths.flatten() + focusStops.map { it.position }).distinct()
                val signature = listOf(lineFocus.key, points.toSet(), fitRouteRequest, bottomPaddingPx, size)
                if (signature != fittedSignature) {
                    fittedSignature = signature
                    if (points.size >= 2) {
                        val bounds = LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build()
                        val margin = (36 * context.resources.displayMetrics.density).toInt()
                        ready.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0.0, 0.0,
                            margin, topInset + margin, margin, bottomInset + margin), 650)
                    } else selectedVehicle?.let { ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.position.lat, it.position.lon), 13.5), 650) }
                }
            }
        } else if (previousFocusKey != null) {
            previousFocusKey = null
            fittedSignature = null
            if (selectedStopId == null) {
                previousCamera?.let { ready.animateCamera(CameraUpdateFactory.newCameraPosition(it), 650) }
            }
            previousCamera = null
        } else if (selectedPlace != null) {
            val bounds = selectedPlace.bounds
            var boundsAnimated = false
            if (bounds != null) {
                try {
                    val latNorth = maxOf(bounds.north, bounds.south)
                    val latSouth = minOf(bounds.north, bounds.south)
                    val lonEast = maxOf(bounds.east, bounds.west)
                    val lonWest = minOf(bounds.east, bounds.west)
                    val horizontalPadding = (48 * context.resources.displayMetrics.density).toInt()
                    val topPadding = if (topPaddingPx != null) topInset + horizontalPadding else (200 * context.resources.displayMetrics.density).toInt()
                    val bottomPadding = if (topPaddingPx != null) bottomInset + horizontalPadding else (110 * context.resources.displayMetrics.density).toInt()
                    if ((latNorth > latSouth || lonEast > lonWest) && horizontalPadding * 2 < size.width && (topPadding + bottomPadding) < size.height) {
                        ready.animateCamera(CameraUpdateFactory.newLatLngBounds(
                            LatLngBounds.from(maxOf(latNorth, latSouth + 0.0001), maxOf(lonEast, lonWest + 0.0001), latSouth, lonWest),
                            0.0, 0.0, horizontalPadding, topPadding, horizontalPadding, bottomPadding), 650)
                        boundsAnimated = true
                    }
                } catch (_: Exception) {
                    boundsAnimated = false
                }
            }
            if (!boundsAnimated) {
                ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(selectedPlace.position.lat, selectedPlace.position.lon), 15.0), 650)
            }
        } else if (browsingNearby && location != null && (!locatedOnce || (locatedRequest != locationCameraRequest && locationCameraRequest > 0))) {
            locatedOnce = true
            locatedRequest = locationCameraRequest
            ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.lat, location.lon), 15.0), 650)
        } else displayStop(selectedStopId)?.let { stop ->
            if (centeredStop != stop.id || centeredRequest != cameraRequest || lastBottomPadding != bottomPaddingPx) {
                ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(stop.position.lat, stop.position.lon), maxOf(15.0, ready.cameraPosition.zoom)), 650)
                centeredStop = stop.id; centeredRequest = cameraRequest; lastBottomPadding = bottomPaddingPx
            }
        }
    }
    key(mapView) { AndroidView(factory = { mapView }, modifier = modifier.onSizeChanged { size = it }) }
    // Every marker is built once per scene, focus, theme or palette change, off the main thread,
    // and uploaded as one source. MapLibre culls and tiles it natively, so panning and zooming
    // never rebuild, re-serialize or re-upload markers.
    LaunchedEffect(map,styleLoaded,styleGeneration,stationScene,markerRegion,highlightedIds,focused,routeColor,stopBearings,darkTheme,routeCatalogue,centerColor) {
        val nativeMap=map ?: return@LaunchedEffect
        if(!styleLoaded) return@LaunchedEffect
        val scene=stationScene ?: return@LaunchedEffect
        // Keep the previous upload until grouping for the new region is ready;
        // applying a new region to an old subset would briefly erase its stops.
        if (scene.preparedRegion != markerRegion || scene.focusIds != highlightedIds.takeIf { focused }) return@LaunchedEffect
        // A new input restarts this effect, so a burst of inputs is serialized and natively re-tiled once.
        val selectionChanged = markerData?.matchesFocus(focused, highlightedIds) != true
        if (!selectionChanged) delay(stationUploads.delayMillis())
        val style=nativeMap.style ?: return@LaunchedEffect
        val source=style.getSourceAs<GeoJsonSource>(StationMarkers.SOURCE)
        if(source==null) stationIconKeys.clear()
        val uploaded=markerData
        if(source!=null && uploaded!=null && withContext(Dispatchers.Default) {
                uploaded.matches(scene,markerRegion,focused,highlightedIds,stopBearings,routeCatalogue,darkTheme,routeColor,centerColor) }) {
            stationUploads.skipped()
            return@LaunchedEffect
        }
        val cachedIcons=stationIconKeys.toSet()
        val diameter=StopMarkerLayout.DIAMETER_DP.toInt()
        val arrowImage=StationSprite.arrowKey(diameter,darkTheme)
        val pending=LinkedHashMap<String,Bitmap>()
        try {
            val data=withContext(Dispatchers.Default) {
                val built=traceMapWork("BMenetrend.StationMarkers") {
                    StationMarkers.build(scene,markerRegion,focused,highlightedIds,stopBearings,routeCatalogue,darkTheme,routeColor,
                        imageName={ sprite -> markerImageName(sprite.key) },
                        ensureActive={ ensureActive() }, centerColor=centerColor)
                }
                built.sprites.forEach { (name,sprite) ->
                    ensureActive()
                    if(name !in cachedIcons) pending[name]=sprite.render()
                }
                if(arrowImage !in cachedIcons) pending[arrowImage]=StationSprite.renderArrow(diameter,darkTheme)
                built
            }
            // Re-tiling a source on the render thread mid-gesture is a visible hitch: upload once the
            // finger and its fling are done. A newer upload supersedes this one (effect restart).
            if (!selectionChanged) snapshotFlow { userGesture }.first { !it }
            // Textures go up in small batches so a first upload of many palettes spans frames.
            pending.entries.chunked(16).forEachIndexed { index, batch ->
                if(index>0) withFrameNanos { }
                style.addImages(HashMap(batch.associate { it.key to it.value }))
                stationIconKeys.addAll(batch.map { it.key })
            }
            traceMapWork("BMenetrend.MapUpload") {
                // Live route updates often leave every palette unchanged: skip the native re-tiling.
                val json=requireNotNull(data.json)
                // setGeoJson(String) only copies the string on this thread; MapLibre parses and tiles it on its workers.
                if(source!=null) { if(markerData?.payloadKey!=data.payloadKey) source.setGeoJson(json) }
                else StationMarkerLayers.install(style,json)
                TransitDiagnostics.event("station-upload") { "count=${data.markers.size} bytes=${json.length} focused=$focused" }
                StationMarkerLayers.configure(style,focused,arrowImage)
                // Palette textures are bounded by distinct colour sets; drop ones no marker uses.
                val used=data.sprites.keys+arrowImage
                if(stationIconKeys.size>used.size+128) {
                    stationIconKeys.filter { it !in used }.forEach { style.removeImage(it); stationIconKeys.remove(it) }
                }
                data.releaseJson()
                markerData=data
                stationUploads.uploaded()
            }
        } finally { pending.values.forEach(Bitmap::recycle) }
    }
    LaunchedEffect(map, styleLoaded, styleGeneration, markerData, focused, highlightedIds) {
        if (!styleLoaded) return@LaunchedEffect
        val style = map?.style ?: return@LaunchedEffect
        StationMarkerLayers.visible(style, markerData?.matchesFocus(focused, highlightedIds) == true)
    }
    LaunchedEffect(map,styleLoaded,styleGeneration,markerData,highlightedStopId,focused,highlightedIds) {
        val nativeMap=map ?: return@LaunchedEffect
        if(!styleLoaded) return@LaunchedEffect
        val data=markerData ?: return@LaunchedEffect
        val style=nativeMap.style ?: return@LaunchedEffect
        if(style.getSource(StationMarkers.SOURCE)==null) return@LaunchedEffect
        val marker=highlightedStopId?.takeIf { data.matchesFocus(focused, highlightedIds) }?.let(data.anchorByStop::get)?.let(data.markers::get)
        var image=""
        if(marker!=null) {
            val sprite=data.spriteFor(marker,selected=true)
            image=markerImageName(sprite.key)
            if(image !in stationIconKeys) {
                val bitmap=withContext(Dispatchers.Default) { sprite.render() }
                try { style.addImage(image,bitmap); stationIconKeys.add(image) } finally { bitmap.recycle() }
            }
        }
        StationMarkerLayers.highlight(style,marker?.id,image,
            if(marker?.rail==true) TransitMapVisibility.railStopMinZoom(focused) else TransitMapVisibility.stopMinZoom(focused))
    }
    // Focus changes only filters: it never waits for source preparation or an ongoing gesture.
    LaunchedEffect(map, styleLoaded, styleGeneration, lineFocus?.routeId, lineFocus?.providerId,
        lineFocus?.number, lineFocus?.mode, lineFocus?.selectedVehicleId) {
        if (styleLoaded) map?.style?.let {
            if (!it.isFullyLoaded) return@let
            runCatching {
                VehicleLayers.configure(it, lineFocus)
                TransitDiagnostics.event("filter") {
                    "route=${lineFocus?.routeId} provider=${lineFocus?.providerId} number=${lineFocus?.number} selected=${lineFocus?.selectedVehicleId != null} layer=${runCatching { it.getLayer(VehicleLayers.SYMBOLS) != null }.getOrDefault(false)} expression=${VehicleLayers.focusFilter(lineFocus)}"
                }
                map?.let { ready -> logVehicleFrame(ready, "filter-change") }
            }
        }
    }
    LaunchedEffect(map, styleLoaded, styleGeneration, selectedVehicle?.id) {
        val ready = map ?: return@LaunchedEffect
        if (!styleLoaded) return@LaunchedEffect
        val style = ready.style ?: return@LaunchedEffect
        snapshotFlow { Triple(vehicles(), vehicleRegion, currentSelectedVehicle) }.collectLatest { (allVehicles, region, selection) ->
            if (region == null) return@collectLatest
            val started = android.os.SystemClock.uptimeMillis()
            val list = withContext(Dispatchers.Default) { VehicleSceneWindow.select(allVehicles, region, selection?.id, selection) }
            TransitDiagnostics.event("prepare") { "count=${list.size} total=${allVehicles.size} gesture=$userGesture generation=$styleGeneration" }
            if (!style.isFullyLoaded) return@collectLatest
            runCatching {
                val source = style.getSourceAs<GeoJsonSource>(VehicleMapScene.SOURCE)
                if (source == null) { vehicleIconKeys.clear(); uploadedVehicles[0] = null }
                val cached = vehicleIconKeys.toSet()
                val pending = linkedMapOf<String, Bitmap>()
                try {
                    val json = withContext(Dispatchers.Default) {
                        VehicleMapScene.prepare(context.applicationContext, list, selection?.id, cached, pending)
                    }
                    // Live positions refresh every few seconds; never re-tile them under a moving finger.
                    TransitDiagnostics.event("prepared") { "ms=${android.os.SystemClock.uptimeMillis() - started} bytes=${json.length} waitingGesture=$userGesture" }
                    snapshotFlow { userGesture }.first { !it }
                    traceMapWork("BMenetrend.MapVehicles") {
                        if (!style.isFullyLoaded) return@traceMapWork
                        pending.forEach { (id, bitmap) -> style.addImage(id, bitmap); vehicleIconKeys.add(id) }
                        // MapLibre copies the string here and parses it on its own worker thread. A refresh
                        // that moved nothing (or only vehicles the focus hides) skips even that and the re-tiling.
                        if (source == null) VehicleLayers.install(style, json) else if (json != uploadedVehicles[0]) source.setGeoJson(json)
                        val changed = source == null || json != uploadedVehicles[0]
                        uploadedVehicles[0] = json
                        VehicleLayers.configure(style, currentVehicleFocus)
                        TransitDiagnostics.event("upload") { "count=${list.size} changed=$changed images=${pending.size} ms=${android.os.SystemClock.uptimeMillis() - started} focus=${currentVehicleFocus?.routeId}" }
                    }
                    if (TransitDiagnostics.enabled) {
                        delay(350)
                        logVehicleFrame(ready, "after-upload")
                    }
                } finally { pending.values.forEach(Bitmap::recycle) }
            }
        }
    }
    LaunchedEffect(map, styleLoaded, styleGeneration, selectedPlace, routeOrigin) {
        val nativeMap = map ?: return@LaunchedEffect
        if (!styleLoaded) return@LaunchedEffect
        pins.update(nativeMap, routeOrigin, selectedPlace)
    }
    val readPosition by rememberUpdatedState(userPosition)
    val readHeading by rememberUpdatedState(userHeading)
    LaunchedEffect(map, styleLoaded, styleGeneration) {
        if (!styleLoaded) return@LaunchedEffect
        val style = map?.style ?: return@LaunchedEffect
        val metrics = context.resources.displayMetrics
        UserLocationLayer.install(style, metrics.density, metrics.densityDpi)
        launch { snapshotFlow { readHeading() }.collect { UserLocationLayer.heading(style, it) } }
        UserLocationMarker.glide(snapshotFlow { readPosition() }) { UserLocationLayer.position(style, it) }
    }
}

/**
 * MapLibre's GL thread does ~10 ms of CPU work per frame while panning. At default priority the
 * scheduler regularly ran it on the slow cores and let GC and background work preempt it (measured on
 * a Kirin 970: frames that landed there missed vsync). Give it the priority Android uses for UI rendering.
 */
private fun boostRenderThread(mapView: MapView) = mapView.queueEvent {
    runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) }
}
