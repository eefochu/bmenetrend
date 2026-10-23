package hu.viabusz.app.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import hu.viabusz.app.R
import hu.viabusz.app.data.MapBounds
import hu.viabusz.app.data.GeoPoint
import hu.viabusz.app.data.TransitStop
import hu.viabusz.app.data.TransitNetwork
import hu.viabusz.app.data.Vehicle
import hu.viabusz.app.data.PlaceResult
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
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val STOP_MIN_ZOOM = 12.0
private const val VEHICLE_MIN_ZOOM = 9.0
private const val VEHICLE_ICON_MIN_ZOOM = 12.0

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
    fitRouteRequest: Int = 0,
    selectedPlace: PlaceResult? = null,
    placeCameraRequest: Int = 0,
    lineFocus: LineMapFocus? = null,
) {
    val darkTheme = isSystemInDarkTheme()
    val styleUri = if (darkTheme) "asset://map/night.json" else "https://tiles.openfreemap.org/styles/positron"
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val onSelect by rememberUpdatedState(onStopSelected)
    val onError by rememberUpdatedState(onMapError)
    val onZoom by rememberUpdatedState(onMapZoomChanged)
    val onVehicle by rememberUpdatedState(onVehicleSelected)
    val stops = remember(network?.stops, additionalStops, routeStops) { (network?.stops.orEmpty() + additionalStops + routeStops).distinctBy { it.id } }
    var savedLat by rememberSaveable { mutableDoubleStateOf(47.473) }
    var savedLon by rememberSaveable { mutableDoubleStateOf(18.825) }
    var savedZoom by rememberSaveable { mutableDoubleStateOf(13.2) }
    var zoom by remember { mutableDoubleStateOf(savedZoom) }
    val focused = lineFocus != null
    val focusStops = lineFocus?.stops.orEmpty()
    val displayStops = if (focused) focusStops else stops
    val paths = lineFocus?.paths ?: listOf(routeGeometry).filter { it.size >= 2 }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var previousCamera by rememberSaveable { mutableStateOf<CameraPosition?>(null) }
    var previousFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
    var fittedSignature by remember { mutableStateOf<Any?>(null) }
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var viewport by remember { mutableStateOf<MapBounds?>(null) }
    var styleLoaded by remember { mutableStateOf(false) }
    var centeredStop by remember { mutableStateOf<String?>(null) }
    var centeredRequest by remember { mutableIntStateOf(-1) }
    var lastBottomPadding by remember { mutableIntStateOf(-1) }
    val vehicleMarkers = remember { mutableMapOf<String, Marker>() }
    var locationMarker by remember { mutableStateOf<Marker?>(null) }
    var placeMarker by remember { mutableStateOf<Marker?>(null) }
    val markerVehicles = remember { mutableMapOf<Long, String>() }
    val stopHitTargets = remember { mutableMapOf<String, Pair<LatLng, Float>>() }
    val stopGroups = remember { mutableMapOf<String, List<TransitStop>>() }
    val iconCache = remember { mutableMapOf<String, Icon>() }
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Europe/Budapest")) }

    DisposableEffect(mapView, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
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
            override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) mapView.onLowMemory() }
        }
        context.applicationContext.registerComponentCallbacks(memoryCallbacks)
        mapView.addOnDidFailLoadingMapListener { onError(context.getString(R.string.engine_map_unavailable)) }
        mapView.getMapAsync { ready ->
            map = ready
            ready.uiSettings.apply {
                isAttributionEnabled = true
                isLogoEnabled = false
                isCompassEnabled = true
                val inset = (16 * context.resources.displayMetrics.density).toInt()
                val topInset = (80 * context.resources.displayMetrics.density).toInt()
                setCompassMargins(0, topInset, inset, 0)
                setAttributionMargins(12, 0, 0, 12)
            }
            ready.cameraPosition = CameraPosition.Builder().target(LatLng(savedLat, savedLon)).zoom(savedZoom).build()
            ready.setMinZoomPreference(5.0)
            ready.addOnCameraIdleListener {
                val bounds = ready.projection.visibleRegion.latLngBounds
                viewport = MapBounds(bounds.longitudeWest, bounds.latitudeSouth, bounds.longitudeEast, bounds.latitudeNorth)
                zoom = ready.cameraPosition.zoom
                savedLat = ready.cameraPosition.target!!.latitude
                savedLon = ready.cameraPosition.target!!.longitude
                savedZoom = zoom
                onZoom(zoom)
            }
            ready.setMaxZoomPreference(19.0)
            ready.setOnMarkerClickListener { marker ->
                val vehicle = markerVehicles[marker.id]
                if (vehicle != null) {
                    onVehicle(vehicle)
                    return@setOnMarkerClickListener true
                }
                false
            }
            ready.addOnMapClickListener { location ->
                val hit = ready.projection.toScreenLocation(location)
                val nearest = stopHitTargets.entries.mapNotNull { (id, target) ->
                    val point = ready.projection.toScreenLocation(target.first)
                    val distance = kotlin.math.hypot(point.x - hit.x, point.y - hit.y)
                    id.takeIf { distance <= target.second + 2 * context.resources.displayMetrics.density }?.let { it to distance }
                }.minByOrNull { it.second }?.first
                if (nearest != null) {
                    val members = stopGroups[nearest].orEmpty()
                    if (members.size > 1) {
                        val center = stopHitTargets[nearest]!!.first
                        ready.animateCamera(CameraUpdateFactory.newLatLngZoom(center, maxOf(StopMapClustering.PLATFORM_ZOOM + .3, ready.cameraPosition.zoom + 1.0)), 500)
                    } else onSelect(members.firstOrNull()?.id ?: nearest)
                    return@addOnMapClickListener true
                }
                false
            }
        }
        onDispose {
            owner.lifecycle.removeObserver(observer)
            context.applicationContext.unregisterComponentCallbacks(memoryCallbacks)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    LaunchedEffect(map, bottomPaddingPx, selectedPlace) {
        val inset = (12 * context.resources.displayMetrics.density).toInt()
        val bottom = maxOf(bottomPaddingPx, if (selectedPlace != null) (110 * context.resources.displayMetrics.density).toInt() else 0)
        map?.uiSettings?.setAttributionMargins(inset, 0, 0, bottom + inset)
    }
    // Theme changes reload only the basemap, retaining camera and current selections.
    // Failed loads retry in the foreground using the currently selected theme.
    LaunchedEffect(map, owner, styleUri) {
        val ready = map ?: return@LaunchedEffect
        var active = true
        styleLoaded = false
        try {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                fun loadStyle() {
                    ready.setStyle(Style.Builder().fromUri(styleUri)) { _ ->
                        if (active) {
                            styleLoaded = true
                            onError(null)
                            val bounds = ready.projection.visibleRegion.latLngBounds
                            viewport = MapBounds(bounds.longitudeWest, bounds.latitudeSouth, bounds.longitudeEast, bounds.latitudeNorth)
                            zoom = ready.cameraPosition.zoom
                            savedLat = ready.cameraPosition.target!!.latitude
                            savedLon = ready.cameraPosition.target!!.longitude
                            savedZoom = zoom
                            onZoom(zoom)
                        }
                    }
                }
                loadStyle()
                while (isActive) {
                    delay(30_000)
                    if (!styleLoaded) loadStyle()
                }
            }
        } finally { active = false }
    }
    LaunchedEffect(map, styleLoaded, paths, routeColor, darkTheme) {
        val ready = map ?: return@LaunchedEffect
        if (!styleLoaded) return@LaunchedEffect
        val style = ready.style ?: return@LaunchedEffect
        val features = paths.filter { it.size >= 2 }.map { path -> Feature.fromGeometry(
            LineString.fromLngLats(path.map { Point.fromLngLat(it.lon, it.lat) })) }.toTypedArray()
        val collection = FeatureCollection.fromFeatures(features)
        val source = style.getSourceAs<GeoJsonSource>("selected-line")
        if (source != null) source.setGeoJson(collection) else {
            style.addSource(GeoJsonSource("selected-line", collection))
            val casing = LineLayer("selected-line-casing", "selected-line").withProperties(
                PropertyFactory.lineColor(if (darkTheme) "#17212D" else "#FFFFFF"), PropertyFactory.lineWidth(8f),
                PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round"))
            val line = LineLayer("selected-line-stroke", "selected-line").withProperties(
                PropertyFactory.lineColor(routeColor), PropertyFactory.lineWidth(5f),
                PropertyFactory.lineCap("round"), PropertyFactory.lineJoin("round"))
            val aboveRoute = style.getLayer("detail-stop-halo")?.id ?: MapLibreConstants.LAYER_ID_ANNOTATIONS
            if (style.getLayer(aboveRoute) != null) style.addLayerBelow(casing, aboveRoute) else style.addLayer(casing)
            style.addLayerAbove(line, "selected-line-casing")
        }
        style.getLayerAs<LineLayer>("selected-line-stroke")?.setProperties(PropertyFactory.lineColor(routeColor))
    }
    LaunchedEffect(map, styleLoaded, lineFocus?.key, paths, focusStops, fitRouteRequest,
        selectedStopId, selectedPlace, placeCameraRequest, cameraRequest, bottomPaddingPx, size) {
        val ready = map ?: return@LaunchedEffect
        if (!styleLoaded || size.width <= 0 || size.height <= 0) return@LaunchedEffect
        if (focused && previousFocusKey == null) previousCamera = ready.cameraPosition
        val topInset = ((if (focused) 156 else 96) * context.resources.displayMetrics.density).toInt()
        val bottomInset = minOf(bottomPaddingPx, (size.height - topInset - 140 * context.resources.displayMetrics.density).toInt().coerceAtLeast(0))
        ready.setPadding(0, topInset, 0, bottomInset)
        if (focused) {
            previousFocusKey = lineFocus!!.key
            // Keep directions as separate paths: never invent links between their termini.
            val points = (paths.flatten() + focusStops.map { it.position }).distinct()
            val signature = listOf(lineFocus.key, points, fitRouteRequest, bottomPaddingPx, size)
            if (signature != fittedSignature) {
                fittedSignature = signature
                if (points.size >= 2) {
                    val bounds = LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build()
                    val margin = (36 * context.resources.displayMetrics.density).toInt()
                    ready.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 0.0, 0.0,
                        margin, topInset + margin, margin, bottomInset + margin), 650)
                } else selectedVehicle?.let { ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.position.lat, it.position.lon), 13.5), 650) }
            }
        } else if (previousFocusKey != null) {
            previousFocusKey = null
            fittedSignature = null
            previousCamera?.let { ready.animateCamera(CameraUpdateFactory.newCameraPosition(it), 650) }
            previousCamera = null
            centeredStop = selectedStopId
            centeredRequest = cameraRequest
            lastBottomPadding = bottomPaddingPx
        } else if (selectedPlace != null) {
            val bounds = selectedPlace.bounds
            if (bounds != null) ready.animateCamera(CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.from(bounds.north, bounds.east, bounds.south, bounds.west), (48 * context.resources.displayMetrics.density).toInt()), 650)
            else ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(selectedPlace.position.lat, selectedPlace.position.lon), 15.0), 650)
        } else displayStops.find { it.id == selectedStopId }?.let { stop ->
            if (centeredStop != stop.id || centeredRequest != cameraRequest || lastBottomPadding != bottomPaddingPx) {
                ready.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(stop.position.lat, stop.position.lon), maxOf(15.0, ready.cameraPosition.zoom)), 650)
                centeredStop = stop.id; centeredRequest = cameraRequest; lastBottomPadding = bottomPaddingPx
            }
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier.onSizeChanged { size = it })

    LaunchedEffect(map, styleLoaded, viewport, zoom, displayStops, vehicles, lineFocus, routeColor, selectedVehicle?.id, selectedStopId, selectedPlace, userLocation) {
        val nativeMap = map ?: return@LaunchedEffect
        if (!styleLoaded) return@LaunchedEffect
        val activeVehicles = mutableSetOf<String>()
        stopHitTargets.clear()
        stopGroups.clear()
        markerVehicles.clear()
        if (iconCache.size > 256) iconCache.clear()
        val density = context.resources.displayMetrics.density
        fun dotIcon(provider: String, selected: Boolean = false, location: Boolean = false, mode: String? = null, sizeOverride: Int? = null): Icon {
            val sizeDp = if (location) 18 else if (selected) { if (zoom >= 14) 32 else if (zoom >= 12.5) 28 else 16 } else if (zoom >= 14) 22 else if (zoom >= 12.5) 16 else 6
            val key = "dot-$provider-$mode-$sizeDp-$selected-$location-$sizeOverride"
            return iconCache.getOrPut(key) {
                val size = sizeOverride ?: (sizeDp * density).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
                val radius = size / 2f
                canvas.drawCircle(radius, radius, radius, paint)
                paint.color = if (location) android.graphics.Color.rgb(53, 109, 219) else StopMarkerLayout.color(provider, mode)
                val border = minOf(size / 5f, if (zoom >= 12.5 && !location) 2 * density else density)
                canvas.drawCircle(radius, radius, (radius - border).coerceAtLeast(1f), paint)
                if (zoom >= 12.5 && !selected && !location) {
                    paint.color = android.graphics.Color.WHITE
                    canvas.drawCircle(radius, radius, size / 5f, paint)
                }
                IconFactory.getInstance(context).fromBitmap(bitmap)
            }
        }
        fun vehicleIcon(vehicle: Vehicle): Icon {
            val style = VehicleMarkerStyle.forVehicle(vehicle)
            if (!focused && zoom < VEHICLE_ICON_MIN_ZOOM) {
                val sizeDp = if (vehicle.id == selectedVehicle?.id) 10 else 6
                return iconCache.getOrPut("transport-dot-$style-$sizeDp") {
                    val size = (sizeDp * density).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
                    val radius = size / 2f
                    canvas.drawCircle(radius, radius, radius, paint)
                    paint.color = style.color
                    canvas.drawCircle(radius, radius, (radius - .65f * density).coerceAtLeast(1f), paint)
                    IconFactory.getInstance(context).fromBitmap(bitmap)
                }
            }
            val sizeDp = (when {
                zoom < 13.5 -> 24
                else -> 36
            }) + if (vehicle.id == selectedVehicle?.id) 6 else 0
            return iconCache.getOrPut("transport-$style-$sizeDp") {
                val drawable = style.drawableRes
                val size = (sizeDp * density).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = android.graphics.Color.WHITE }
                canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
                val inset = density
                if (style == VehicleMarkerStyle.VIABUSZ_BUS) {
                    context.getDrawable(drawable)?.apply {
                        setBounds(inset.toInt(), inset.toInt(), (size - inset).toInt(), (size - inset).toInt())
                        draw(canvas)
                    }
                } else {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeResource(context.resources, drawable, options)
                    options.inJustDecodeBounds = false
                    options.inSampleSize = 1
                    while (options.outWidth / (options.inSampleSize * 2) >= size) options.inSampleSize *= 2
                    val source = BitmapFactory.decodeResource(context.resources, drawable, options)
                    canvas.drawBitmap(source, null, RectF(inset, inset, size - inset, size - inset), paint)
                    source.recycle()
                }
                IconFactory.getInstance(context).fromBitmap(bitmap)
            }
        }
        val detailFeatures = mutableListOf<Feature>()
        if (focused || zoom >= STOP_MIN_ZOOM) {
            val groups = StopMapClustering.groups(displayStops, viewport, zoom)
            val grouped = zoom < StopMapClustering.PLATFORM_ZOOM
            val representatives = groups.map { members ->
                val first = members.firstOrNull { it.id == selectedStopId } ?: members.first()
                stopGroups[first.id] = members
                first.copy(position = GeoPoint(members.map { it.position.lat }.average(), members.map { it.position.lon }.average()))
            }
            val nodes = representatives.map { stop ->
                val point = nativeMap.projection.toScreenLocation(LatLng(stop.position.lat, stop.position.lon))
                StopMarkerLayout.Node(stop, point.x.toDouble(), point.y.toDouble())
            }
            val diameter = when {
                !grouped -> if (focused) 24.0 else 22.0
                focused -> 16.0
                zoom >= StopMapClustering.DETAIL_ZOOM -> 12.0
                else -> 8.0
            }
            StopMarkerLayout.layout(nodes, density.toDouble(), diameter).forEach { placement ->
                val stop = placement.stop
                val position = nativeMap.projection.fromScreenLocation(PointF(placement.x.toFloat(), placement.y.toFloat()))
                val members = stopGroups[stop.id].orEmpty()
                val selected = members.any { it.id == selectedStopId }
                val colors = members.map { StopMarkerLayout.color(it.providerId, it.transportMode) }.distinct()
                val dotColor = colors.singleOrNull() ?: StopMarkerLayout.color("shared", null)
                detailFeatures += Feature.fromGeometry(Point.fromLngLat(position.longitude, position.latitude)).apply {
                    addStringProperty("stopId", stop.id)
                    addStringProperty("color", "#%06X".format(dotColor and 0xFFFFFF))
                    addNumberProperty("radius", placement.sizePx / density / 2)
                    addNumberProperty("inner", if (selected) 0 else placement.sizePx / density / 5)
                }
                stopHitTargets[stop.id] = position to (placement.sizePx / 2).toFloat()
            }
        }
        val style = nativeMap.style ?: return@LaunchedEffect
        val details = FeatureCollection.fromFeatures(detailFeatures)
        val stopSource = style.getSourceAs<GeoJsonSource>("detail-stops")
        if (stopSource != null) stopSource.setGeoJson(details) else {
            style.addSource(GeoJsonSource("detail-stops", details))
            fun addStopLayer(layer: Layer) {
                if (style.getLayer(MapLibreConstants.LAYER_ID_ANNOTATIONS) != null)
                    style.addLayerBelow(layer, MapLibreConstants.LAYER_ID_ANNOTATIONS) else style.addLayer(layer)
            }
            addStopLayer(CircleLayer("detail-stop-halo", "detail-stops").withProperties(
                PropertyFactory.circleRadius(Expression.sum(Expression.get("radius"), Expression.literal(if (focused) 2.5 else 1.0))),
                PropertyFactory.circleColor(if (darkTheme) "#111D2C" else "#FFFFFF")))
            addStopLayer(CircleLayer("detail-stop-ring", "detail-stops").withProperties(
                PropertyFactory.circleRadius(Expression.get("radius")), PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(1.5f)))
            addStopLayer(CircleLayer("detail-stop-center", "detail-stops").withProperties(
                PropertyFactory.circleRadius(Expression.get("inner")), PropertyFactory.circleColor("#FFFFFF")))
        }
        style.getLayerAs<CircleLayer>("detail-stop-halo")?.setProperties(
            PropertyFactory.circleRadius(Expression.sum(Expression.get("radius"), Expression.literal(if (focused) 2.5 else 1.0))))
        val visibleVehicles = vehicles.filter { vehicle ->
            (lineFocus?.includes(vehicle) ?: true) && (focused || zoom >= VEHICLE_MIN_ZOOM) && viewport?.let { vehicle.position.lat in it.south..it.north && vehicle.position.lon in it.west..it.east } == true
        }
        visibleVehicles.forEach { vehicle ->
            val number = vehicle.routeNumber ?: network?.routes?.find { it.id == vehicle.routeId }?.number ?: context.getString(if (vehicle.providerId == "bkk") R.string.emma_vehicle else R.string.engine_vehicle_bus)
            val options = MarkerOptions()
                .position(LatLng(vehicle.position.lat, vehicle.position.lon))
                .title("${when (vehicle.providerId) { "kanyargo" -> "KanyarGO"; "homm" -> "HOMM"; "volan" -> "Volán"; "bkk" -> "BKK"; else -> "ViaBusz" }} · $number · ${vehicle.registration ?: vehicle.id.substringAfter(":")}")
                .snippet(listOfNotNull(vehicle.destination?.takeIf(String::isNotBlank), vehicle.nextStopName?.takeIf(String::isNotBlank), context.getString(if (vehicle.providerId in setOf("volan", "bkk")) R.string.volan_observation_time else R.string.engine_gps_time, formatter.format(vehicle.observedAt)), if (vehicle.providerId in setOf("volan", "bkk")) context.getString(R.string.volan_position_source) else null,
                    vehicle.speedKmh?.let { context.getString(R.string.emma_speed, it.toInt()) }, if (vehicle.routeId == null) context.getString(R.string.engine_no_vehicle_route) else null).joinToString("\n"))
                .icon(vehicleIcon(vehicle))
            val marker = vehicleMarkers.getOrPut(vehicle.id) { nativeMap.addMarker(options) }
            marker.position = options.position
            marker.icon = options.icon
            marker.title = options.title
            marker.snippet = options.snippet
            activeVehicles += vehicle.id
            markerVehicles[marker.id] = vehicle.id
        }
        vehicleMarkers.keys.filter { it !in activeVehicles }.toList().forEach { vehicleMarkers.remove(it)?.let(nativeMap::removeMarker) }
        if (userLocation == null) {
            locationMarker?.let(nativeMap::removeMarker); locationMarker = null
        } else {
            val position = LatLng(userLocation.lat, userLocation.lon)
            if (locationMarker == null) locationMarker = nativeMap.addMarker(MarkerOptions().position(position).title(context.getString(R.string.engine_you_are_here)).icon(dotIcon("location", location = true)))
            else locationMarker?.position = position
        }
        if (selectedPlace == null) {
            placeMarker?.let(nativeMap::removeMarker); placeMarker = null
        } else {
            val position = LatLng(selectedPlace.position.lat, selectedPlace.position.lon)
            if (placeMarker == null) placeMarker = nativeMap.addMarker(MarkerOptions().position(position).title(selectedPlace.name))
            else { placeMarker?.position = position; placeMarker?.title = selectedPlace.name }
        }
    }
}
