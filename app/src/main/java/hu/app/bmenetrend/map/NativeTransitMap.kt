package hu.app.bmenetrend.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import hu.app.bmenetrend.ui.LocalDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
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
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Marker
import org.maplibre.android.constants.MapLibreConstants
import org.maplibre.android.style.layers.Layer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue


/** Keyless OpenFreeMap basemap, with a bundled custom night style. */
@Composable
fun NativeTransitMap(
    network: TransitNetwork?,
    vehicles: List<Vehicle>,
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
) {
    val darkTheme = LocalDarkTheme.current
    val styleUri = if (offline) { if (darkTheme) "asset://map/offline-dark.json" else "asset://map/offline.json" } else if (darkTheme) "asset://map/night.json" else "https://tiles.openfreemap.org/styles/positron"
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
    // Read only inside effects (never in composition), so toggling it does not recompose.
    var userGesture by remember { mutableStateOf(false) }
    val stops = remember(network?.stops, additionalStops, routeStops, showTransitStops) {
        ((if (showTransitStops) network?.stops.orEmpty() + additionalStops else emptyList()) + routeStops).distinctBy { it.id }
    }
    // Group and allocate in a camera-independent world. Pan, zoom, selection and highlights cannot reshuffle it.
    val sceneStops=remember(stops,lineFocus?.displayStops) { (stops+lineFocus?.displayStops.orEmpty()).distinctBy { it.id } }
    val stationScene by produceState<StationScene?>(null,sceneStops,stopBearings) {
        val previousOffsets=value?.offsets.orEmpty()
        value=withContext(Dispatchers.Default) {
            val bounds=sceneStops.takeIf { it.isNotEmpty() }?.let { all -> MapBounds(
                all.minOf { it.position.lon },all.minOf { it.position.lat },all.maxOf { it.position.lon },all.maxOf { it.position.lat }) }
            val groups=traceMapWork("BMenetrend.StopGroups") { StopMapClustering.groups(sceneStops,bounds,16.0,stopBearings) }
            val merged = RailStationMarkers.mergePlatforms(groups).map { StationGroup(stationAnchor(it), it) }
            StationScene(merged, StationChunkIndex(merged) { it.anchor.position },
                StopMarkerLayout.offsets(merged.map { it.anchor },previousOffsets))
        }
    }
    var savedLat by rememberSaveable { mutableDoubleStateOf(47.473) }
    var savedLon by rememberSaveable { mutableDoubleStateOf(18.825) }
    var savedZoom by rememberSaveable { mutableDoubleStateOf(13.2) }
    var zoom by remember { mutableDoubleStateOf(savedZoom) }
    val focused = lineFocus != null
    val focusStops = lineFocus?.displayStops.orEmpty()
    val displayStops = if (focused) lineFocus!!.displayStops else stops
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
    var viewport by remember { mutableStateOf<MapBounds?>(null) }
    // Only drives the catalogue prefetch before the national stop index loads; markers no longer
    // depend on the camera window, so this is not Compose state (no recomposition per pan).
    val stationWindow = remember(mapView) { java.util.concurrent.atomic.AtomicReference<StationWindow?>(null) }
    // Uploaded marker data; read by tap handling and the highlight effect.
    var markerData by remember(mapView) { mutableStateOf<StationMarkers.Data?>(null) }
    // Area whose road stops are uploaded; changes only when the view nears its edge.
    var markerRegion by remember(mapView) { mutableStateOf<MapBounds?>(null) }
    val markerImageNames = remember { HashMap<String, String>() }
    var styleLoaded by remember(mapView) { mutableStateOf(false) }
    var styleGeneration by remember(mapView) { mutableIntStateOf(0) }
    var locatedOnce by rememberSaveable { mutableStateOf(false) }
    var locatedRequest by remember { mutableIntStateOf(-1) }
    var centeredStop by remember { mutableStateOf<String?>(null) }
    var centeredRequest by remember { mutableIntStateOf(-1) }
    var lastBottomPadding by remember { mutableIntStateOf(-1) }
    val vehicleIconKeys = remember(mapView) { mutableSetOf<String>() }
    var locationMarker by remember(mapView) { mutableStateOf<Marker?>(null) }
    var placeMarker by remember(mapView) { mutableStateOf<Marker?>(null) }
    var originMarker by remember(mapView) { mutableStateOf<Marker?>(null) }
    val iconCache = remember { mutableMapOf<String, Icon>() }
    val stationIconKeys=remember(mapView) { mutableSetOf<String>() }

    DisposableEffect(mapView, owner) {
        if (offline) OfflineBasemap.retain()
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
                val next = StationWindow.next(previous,visible,ready.cameraPosition.zoom)
                stationWindow.set(next)
                val region = StationMarkers.nextRegion(markerRegion,visible,ready.cameraPosition.zoom)
                if (region !== markerRegion) markerRegion = region
                if (prefetchStops && next != previous && next.detail > 0) onPrefetch(next.bounds)
            }
            ready.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE && !userGesture) {
                    userGesture = true; onGesture(true)
                }
            }
            ready.addOnCameraMoveListener {
                val now = android.os.SystemClock.uptimeMillis()
                if (now-lastStationUpdate >= 120L) { lastStationUpdate=now; updateStationWindow() }
            }
            ready.addOnCameraIdleListener {
                if (userGesture) { userGesture = false; onGesture(false) }
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
            fun reveal(location: LatLng) {
                ready.animateCamera(CameraUpdateFactory.newLatLngZoom(location,
                    maxOf(16.0,ready.cameraPosition.zoom+2).coerceAtMost(19.0)),450)
            }
            ready.addOnMapClickListener { location ->
                val point=ready.projection.toScreenLocation(location)
                val vehicle = ready.queryRenderedFeatures(point, *VehicleMapScene.layers)
                    .firstOrNull { it.hasProperty("vehicle-id") }?.getStringProperty("vehicle-id")
                if (vehicle != null) {
                    onVehicle(vehicle)
                    return@addOnMapClickListener true
                }
                val hits=targets(point)
                val density=context.resources.displayMetrics.density.toDouble()
                val hit=StopHitTesting.nearest(hits,point.x.toDouble(),point.y.toDouble(),density)
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
        val collection = withContext(Dispatchers.Default) {
            val features = paths.filter { it.size >= 2 }.map { path ->
                ensureActive()
                Feature.fromGeometry(LineString.fromLngLats(path.map { Point.fromLngLat(it.lon, it.lat) }))
            }
            FeatureCollection.fromFeatures(features).toJson()
        }
        val source = style.getSourceAs<GeoJsonSource>("selected-line")
        if (source != null) source.setGeoJson(collection) else {
            style.addSource(GeoJsonSource("selected-line", collection))
            val casingProps = mutableListOf<PropertyValue<*>>(
                PropertyFactory.lineColor(if (darkTheme) "#17212D" else "#FFFFFF"),
                PropertyFactory.lineWidth(if (dottedRoute) 6f else 8f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round")
            )
            if (dottedRoute) casingProps.add(PropertyFactory.lineDasharray(arrayOf(2f, 2f)))
            val casing = LineLayer("selected-line-casing", "selected-line").apply {
                setProperties(*casingProps.toTypedArray())
            }

            val lineProps = mutableListOf<PropertyValue<*>>(
                PropertyFactory.lineColor(routeColor),
                PropertyFactory.lineWidth(if (dottedRoute) 4f else 5f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round")
            )
            if (dottedRoute) lineProps.add(PropertyFactory.lineDasharray(arrayOf(2f, 2f)))
            val line = LineLayer("selected-line-stroke", "selected-line").apply {
                setProperties(*lineProps.toTypedArray())
            }

            // Below every station layer (arrows are the lowest), above the basemap.
            val aboveRoute = style.getLayer(StationMarkers.ARROW_LAYERS.first())?.id ?: style.getLayer("vehicle-dots-rail")?.id ?: MapLibreConstants.LAYER_ID_ANNOTATIONS
            if (style.getLayer(aboveRoute) != null) style.addLayerBelow(casing, aboveRoute) else style.addLayer(casing)
            style.addLayerAbove(line, "selected-line-casing")
        }
        val casingLayer = style.getLayerAs<LineLayer>("selected-line-casing")
        casingLayer?.setProperties(
            PropertyFactory.lineColor(if (darkTheme) "#17212D" else "#FFFFFF"),
            PropertyFactory.lineWidth(if (dottedRoute) 6f else 8f)
        )
        if (dottedRoute) {
            casingLayer?.setProperties(PropertyFactory.lineDasharray(arrayOf(2f, 2f)))
        } else {
            casingLayer?.setProperties(PropertyFactory.lineDasharray(arrayOf(1f, 0f)))
        }

        val strokeLayer = style.getLayerAs<LineLayer>("selected-line-stroke")
        strokeLayer?.setProperties(
            PropertyFactory.lineColor(routeColor),
            PropertyFactory.lineWidth(if (dottedRoute) 4f else 5f)
        )
        if (dottedRoute) {
            strokeLayer?.setProperties(PropertyFactory.lineDasharray(arrayOf(2f, 2f)))
        } else {
            strokeLayer?.setProperties(PropertyFactory.lineDasharray(arrayOf(1f, 0f)))
        }
    }
    LaunchedEffect(map, styleLoaded, styleGeneration, lineFocus?.key, paths, focusStops, fitRouteRequest,
        selectedStopId, selectedPlace, placeCameraRequest, cameraRequest, bottomPaddingPx, topPaddingPx, size, browsingNearby, userLocation, locationCameraRequest) {
        val ready = map ?: return@LaunchedEffect
        if (!styleLoaded || size.width <= 0 || size.height <= 0) return@LaunchedEffect
        if (focused && previousFocusKey == null) previousCamera = ready.cameraPosition
        val topInset = topPaddingPx ?: ((if (focused) 156 else 96) * context.resources.displayMetrics.density).toInt()
        val bottomInset = minOf(bottomPaddingPx, (size.height - topInset - 140 * context.resources.displayMetrics.density).toInt().coerceAtLeast(0))
        ready.setPadding(0, topInset, 0, bottomInset)
        if (focused) {
            previousFocusKey = lineFocus!!.key
            val stop = displayStops.find { it.id == selectedStopId }
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
        } else if (browsingNearby && userLocation != null && (!locatedOnce || (locatedRequest != locationCameraRequest && locationCameraRequest > 0))) {
            locatedOnce = true
            locatedRequest = locationCameraRequest
            ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(userLocation.lat, userLocation.lon), 15.0), 650)
        } else displayStops.find { it.id == selectedStopId }?.let { stop ->
            if (centeredStop != stop.id || centeredRequest != cameraRequest || lastBottomPadding != bottomPaddingPx) {
                ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(stop.position.lat, stop.position.lon), maxOf(15.0, ready.cameraPosition.zoom)), 650)
                centeredStop = stop.id; centeredRequest = cameraRequest; lastBottomPadding = bottomPaddingPx
            }
        }
    }
    key(mapView) { AndroidView(factory = { mapView }, modifier = modifier.onSizeChanged { size = it }) }
    // Every marker is built once per scene, focus, theme or palette change, off the main thread,
    // and uploaded as one source. MapLibre culls and tiles it natively, so panning and zooming
    // never rebuild, re-serialize or re-upload markers (previously: per camera window).
    LaunchedEffect(map,styleLoaded,styleGeneration,stationScene,markerRegion,highlightedIds,focused,routeColor,stopBearings,darkTheme,routeCatalogue) {
        val nativeMap=map ?: return@LaunchedEffect
        if(!styleLoaded) return@LaunchedEffect
        val scene=stationScene ?: return@LaunchedEffect
        // Startup delivers scene, bearings and route colours in quick succession: settle first so
        // the country is serialized and natively re-tiled once, not once per input.
        delay(300)
        val style=nativeMap.style ?: return@LaunchedEffect
        val source=style.getSourceAs<GeoJsonSource>(StationMarkers.SOURCE)
        if(source==null) stationIconKeys.clear()
        val uploaded=markerData
        if(source!=null && uploaded!=null && withContext(Dispatchers.Default) {
                uploaded.matches(scene,markerRegion,focused,highlightedIds,stopBearings,routeCatalogue,darkTheme,routeColor) }) return@LaunchedEffect
        val cachedIcons=stationIconKeys.toSet()
        val diameter=StopMarkerLayout.DIAMETER_DP.toInt()
        val arrowImage=StationSprite.arrowKey(diameter,darkTheme)
        val pending=LinkedHashMap<String,Bitmap>()
        try {
            val data=withContext(Dispatchers.Default) {
                val built=traceMapWork("BMenetrend.StationMarkers") {
                    StationMarkers.build(scene,markerRegion,focused,highlightedIds,stopBearings,routeCatalogue,darkTheme,routeColor,
                        imageName={ sprite -> synchronized(markerImageNames) { markerImageNames.getOrPut(sprite.key) { "st${markerImageNames.size}" } } },
                        ensureActive={ ensureActive() })
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
            snapshotFlow { userGesture }.first { !it }
            // Textures go up in small batches so a first upload of many palettes spans frames.
            pending.entries.chunked(16).forEachIndexed { index, batch ->
                if(index>0) withFrameNanos { }
                style.addImages(HashMap(batch.associate { it.key to it.value }))
                stationIconKeys.addAll(batch.map { it.key })
            }
            traceMapWork("BMenetrend.MapUpload") {
                // Live route updates often leave every palette unchanged: skip the native re-tiling.
                val json=requireNotNull(data.json)
                if(source!=null) { if(markerData?.payloadKey!=data.payloadKey) source.setGeoJson(json) } else {
                    style.addSource(GeoJsonSource(StationMarkers.SOURCE,json,
                        org.maplibre.android.style.sources.GeoJsonOptions().withMaxZoom(StationMarkers.SOURCE_MAX_ZOOM)))
                    fun dots(id: String,rail: Boolean)=SymbolLayer(id,StationMarkers.SOURCE).withProperties(
                        PropertyFactory.iconImage(Expression.get("icon")),PropertyFactory.iconOffset(Expression.get("offset")),
                        PropertyFactory.iconAllowOverlap(true),PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.iconRotationAlignment("map"),PropertyFactory.iconPitchAlignment("viewport"),
                        PropertyFactory.symbolZOrder("viewport-y")
                    ).withFilter(Expression.eq(Expression.get("rail"),Expression.literal(rail)))
                    fun arrows(id: String,index: Int)=SymbolLayer(id,StationMarkers.SOURCE).withProperties(
                        PropertyFactory.iconRotate(Expression.get("a$index")),PropertyFactory.iconOffset(Expression.get("o$index")),
                        PropertyFactory.iconAllowOverlap(true),PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.iconRotationAlignment("map"),PropertyFactory.iconPitchAlignment("viewport")
                    ).withFilter(Expression.has("a$index"))
                    val selected=SymbolLayer(StationMarkers.SELECTED_LAYER,StationMarkers.SOURCE).withProperties(
                        PropertyFactory.iconOffset(Expression.get("offset")),
                        PropertyFactory.iconAllowOverlap(true),PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.iconRotationAlignment("map"),PropertyFactory.iconPitchAlignment("viewport")
                    ).withFilter(Expression.eq(Expression.get("id"),Expression.literal("")))
                    // Arrows sit under dots, as they did inside each baked sprite.
                    val below=style.getLayer("vehicle-dots-rail")?.id
                        ?: MapLibreConstants.LAYER_ID_ANNOTATIONS.takeIf { style.getLayer(it)!=null }
                    (StationMarkers.ARROW_LAYERS.mapIndexed { index,id -> arrows(id,index) } +
                        listOf(dots(StationMarkers.RAIL_LAYER,true),dots(StationMarkers.ROAD_LAYER,false),selected)).forEach { layer ->
                        if(below!=null) style.addLayerBelow(layer,below) else style.addLayer(layer)
                    }
                }
                val iconScale=if(focused) Expression.literal(16f/StopMarkerLayout.DIAMETER_DP.toFloat()) else Expression.interpolate(
                    Expression.linear(),Expression.zoom(),*StopMarkerLayout.sizeStops.map { (z,size) ->
                        Expression.stop(z,size/StopMarkerLayout.DIAMETER_DP)
                    }.toTypedArray())
                (StationMarkers.ARROW_LAYERS+listOf(StationMarkers.RAIL_LAYER,StationMarkers.ROAD_LAYER,StationMarkers.SELECTED_LAYER)).forEach { id ->
                    style.getLayerAs<SymbolLayer>(id)?.setProperties(PropertyFactory.iconSize(iconScale))
                }
                StationMarkers.ARROW_LAYERS.forEach { id ->
                    style.getLayerAs<SymbolLayer>(id)?.apply { setProperties(PropertyFactory.iconImage(arrowImage)); minZoom=StationMarkers.ARROW_MIN_ZOOM }
                }
                style.getLayer(StationMarkers.RAIL_LAYER)?.minZoom=if(focused) 0f else StationMarkers.RAIL_MIN_ZOOM
                style.getLayer(StationMarkers.ROAD_LAYER)?.minZoom=if(focused) 0f else StationMarkers.ROAD_MIN_ZOOM
                // Palette textures are bounded by distinct colour sets; drop ones no marker uses.
                val used=data.sprites.keys+arrowImage
                if(stationIconKeys.size>used.size+128) {
                    stationIconKeys.filter { it !in used }.forEach { style.removeImage(it); stationIconKeys.remove(it) }
                }
                data.releaseJson()
                markerData=data
            }
        } finally { pending.values.forEach(Bitmap::recycle) }
    }
    // The highlighted stop only swaps filters and one texture; it never touches marker data.
    LaunchedEffect(map,styleLoaded,styleGeneration,markerData,highlightedStopId) {
        val nativeMap=map ?: return@LaunchedEffect
        if(!styleLoaded) return@LaunchedEffect
        val data=markerData ?: return@LaunchedEffect
        val style=nativeMap.style ?: return@LaunchedEffect
        if(style.getSource(StationMarkers.SOURCE)==null) return@LaunchedEffect
        val marker=highlightedStopId?.let(data.anchorByStop::get)?.let(data.markers::get)
        var image=""
        if(marker!=null) {
            val sprite=data.spriteFor(marker,selected=true)
            image=synchronized(markerImageNames) { markerImageNames.getOrPut(sprite.key) { "st${markerImageNames.size}" } }
            if(image !in stationIconKeys) {
                val bitmap=withContext(Dispatchers.Default) { sprite.render() }
                try { style.addImage(image,bitmap); stationIconKeys.add(image) } finally { bitmap.recycle() }
            }
        }
        val selectedId=Expression.literal(marker?.id.orEmpty())
        style.getLayerAs<SymbolLayer>(StationMarkers.SELECTED_LAYER)?.apply {
            setProperties(PropertyFactory.iconImage(image))
            setFilter(Expression.eq(Expression.get("id"),selectedId))
            minZoom=when { data.focused -> 0f; marker?.rail==true -> StationMarkers.RAIL_MIN_ZOOM; else -> StationMarkers.ROAD_MIN_ZOOM }
        }
        listOf(StationMarkers.RAIL_LAYER to true,StationMarkers.ROAD_LAYER to false).forEach { (id,rail) ->
            style.getLayerAs<SymbolLayer>(id)?.setFilter(Expression.all(
                Expression.eq(Expression.get("rail"),Expression.literal(rail)),Expression.neq(Expression.get("id"),selectedId)))
        }
    }
    // Source data changes only for live updates/selection. Camera zoom and visibility are GPU style expressions.
    LaunchedEffect(map, styleLoaded, styleGeneration, vehicles, lineFocus?.routeId, lineFocus?.providerId,
        lineFocus?.number, lineFocus?.mode, lineFocus?.selectedVehicleId, selectedVehicle?.id) {
        val ready = map ?: return@LaunchedEffect
        if (!styleLoaded) return@LaunchedEffect
        val style = ready.style ?: return@LaunchedEffect
        val source = style.getSourceAs<GeoJsonSource>(VehicleMapScene.SOURCE)
        if (source == null) vehicleIconKeys.clear()
        val cached = vehicleIconKeys.toSet()
        val pending = linkedMapOf<String, Bitmap>()
        try {
            val json = withContext(Dispatchers.Default) {
                VehicleMapScene.prepare(context.applicationContext, vehicles, lineFocus, selectedVehicle?.id, cached, pending)
            }
            // Live positions refresh every few seconds; never re-tile them under a moving finger.
            snapshotFlow { userGesture }.first { !it }
            traceMapWork("BMenetrend.MapVehicles") {
                pending.forEach { (id, bitmap) -> style.addImage(id, bitmap); vehicleIconKeys.add(id) }
                if (source != null) source.setGeoJson(json) else {
                    style.addSource(GeoJsonSource(VehicleMapScene.SOURCE, json))
                    fun layer(id: String, dot: Boolean) = SymbolLayer(id, VehicleMapScene.SOURCE).withProperties(
                        PropertyFactory.iconImage(Expression.get(if (dot) "dot" else "icon")),
                        PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.iconRotationAlignment("viewport"), PropertyFactory.iconPitchAlignment("viewport"),
                        PropertyFactory.symbolSortKey(Expression.get("priority")),
                        PropertyFactory.iconSize(if (dot) Expression.get("dot-scale") else Expression.step(
                            Expression.zoom(), Expression.get("small-scale"),
                            Expression.stop(13.5, Expression.get("large-scale"))))
                    )
                    val rail = layer("vehicle-dots-rail", true).withFilter(Expression.eq(Expression.get("rail"), Expression.literal(true)))
                    val road = layer("vehicle-dots-road", true).withFilter(Expression.eq(Expression.get("rail"), Expression.literal(false)))
                    listOf(rail, road, layer("vehicle-symbols", false)).forEach { layer ->
                        if (style.getLayer(MapLibreConstants.LAYER_ID_ANNOTATIONS) != null)
                            style.addLayerBelow(layer, MapLibreConstants.LAYER_ID_ANNOTATIONS)
                        else style.addLayer(layer)
                    }
                }
                style.getLayerAs<SymbolLayer>("vehicle-dots-rail")?.apply {
                    minZoom = TransitMapVisibility.TRAIN_VEHICLE_MIN_ZOOM; maxZoom = TransitMapVisibility.VEHICLE_SYMBOL_MIN_ZOOM
                    setProperties(PropertyFactory.visibility(if (focused) "none" else "visible"))
                }
                style.getLayerAs<SymbolLayer>("vehicle-dots-road")?.apply {
                    minZoom = TransitMapVisibility.OTHER_VEHICLE_MIN_ZOOM; maxZoom = TransitMapVisibility.VEHICLE_SYMBOL_MIN_ZOOM
                    setProperties(PropertyFactory.visibility(if (focused) "none" else "visible"))
                }
                style.getLayerAs<SymbolLayer>("vehicle-symbols")?.minZoom = if (focused) 0f else TransitMapVisibility.VEHICLE_SYMBOL_MIN_ZOOM
            }
        } finally { pending.values.forEach(Bitmap::recycle) }
    }
    // Personal and planner endpoint pins never refresh on camera gestures or vehicle ticks.
    LaunchedEffect(map, styleLoaded, styleGeneration, selectedPlace, userLocation, routeOrigin) {
        val nativeMap = map ?: return@LaunchedEffect
        if (!styleLoaded) return@LaunchedEffect
        val density = context.resources.displayMetrics.density
        fun locationIcon(): Icon = iconCache.getOrPut("user-location") {
            val size = (18 * density).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            paint.color = android.graphics.Color.rgb(53, 109, 219)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2 * density, paint)
            IconFactory.getInstance(context).fromBitmap(bitmap)
        }
        fun endpointIcon(key: String, color: Int): Icon = iconCache.getOrPut(key) {
            val size = (34 * density).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val radius = size / 2f
            paint.color = android.graphics.Color.WHITE
            canvas.drawCircle(radius, radius, radius, paint)
            paint.color = color
            canvas.drawCircle(radius, radius, (radius - 2.5f * density).coerceAtLeast(1f), paint)
            paint.color = android.graphics.Color.WHITE
            canvas.drawCircle(radius, radius, (radius - 6f * density).coerceAtLeast(1f), paint)
            IconFactory.getInstance(context).fromBitmap(bitmap)
        }
        if (userLocation == null) {
            locationMarker?.let(nativeMap::removeMarker); locationMarker = null
        } else {
            val position = LatLng(userLocation.lat, userLocation.lon)
            if (locationMarker == null) locationMarker = nativeMap.addMarker(MarkerOptions().position(position).title(resources.getString(R.string.engine_you_are_here)).icon(locationIcon()))
            else if(locationMarker?.position!=position) locationMarker?.position=position
        }
        if (routeOrigin == null) {
            originMarker?.let(nativeMap::removeMarker); originMarker = null
        } else {
            val position = LatLng(routeOrigin.lat, routeOrigin.lon)
            if (originMarker == null) originMarker = nativeMap.addMarker(MarkerOptions().position(position)
                .title(resources.getString(R.string.planner_from))
                .icon(endpointIcon("origin-pin-marker", android.graphics.Color.rgb(117, 117, 117))))
            else if (originMarker?.position != position) originMarker?.position = position
        }
        if (selectedPlace == null) {
            placeMarker?.let(nativeMap::removeMarker); placeMarker = null
        } else {
            val position = LatLng(selectedPlace.position.lat, selectedPlace.position.lon)
            val pIcon = endpointIcon("place-pin-marker", android.graphics.Color.rgb(211, 47, 47))
            if (placeMarker == null) {
                placeMarker = nativeMap.addMarker(
                    MarkerOptions()
                        .position(position)
                        .title(selectedPlace.name)
                        .snippet(selectedPlace.address.takeIf { it.isNotBlank() })
                        .icon(pIcon)
                )
            } else {
                placeMarker?.position = position
                placeMarker?.title = selectedPlace.name
                placeMarker?.snippet = selectedPlace.address.takeIf { it.isNotBlank() }
                placeMarker?.icon = pIcon
            }
        }
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
