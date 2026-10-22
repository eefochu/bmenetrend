package hu.viabusz.app.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.view.MotionEvent
import android.annotation.SuppressLint
import androidx.compose.runtime.*
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
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
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
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A native MapLibre map; no browser/WebView and no API key. */
@Composable
@SuppressLint("ClickableViewAccessibility") // Passive touch observation; MapView still handles every gesture and click.
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
) {
    val darkTheme = isSystemInDarkTheme()
    val styleUri = if (darkTheme) "https://tiles.openfreemap.org/styles/dark" else "https://tiles.openfreemap.org/styles/positron"
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val onSelect by rememberUpdatedState(onStopSelected)
    val onError by rememberUpdatedState(onMapError)
    val onZoom by rememberUpdatedState(onMapZoomChanged)
    val onVehicle by rememberUpdatedState(onVehicleSelected)
    val stops = remember(network, additionalStops) { network?.stops.orEmpty() + additionalStops }
    val touchPoint = remember { PointF(Float.NaN, Float.NaN) }
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply {
            onCreate(null)
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) touchPoint.set(event.x, event.y)
                false
            }
        }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var viewport by remember { mutableStateOf<MapBounds?>(null) }
    var styleLoaded by remember { mutableStateOf(false) }
    var centeredVehicle by remember { mutableStateOf<String?>(null) }
    var centeredStop by remember { mutableStateOf<String?>(null) }
    var centeredRequest by remember { mutableIntStateOf(-1) }
    var lastBottomPadding by remember { mutableIntStateOf(-1) }
    val markerVehicles = remember { mutableMapOf<Long, String>() }
    val markerStops = remember { mutableMapOf<Long, String>() }
    val stopHitTargets = remember { mutableMapOf<Long, Pair<LatLng, Float>>() }
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
            ready.cameraPosition = CameraPosition.Builder().target(LatLng(47.473, 18.825)).zoom(13.2).build()
            ready.setMinZoomPreference(5.0)
            ready.addOnCameraIdleListener {
                val bounds = ready.projection.visibleRegion.latLngBounds
                viewport = MapBounds(bounds.longitudeWest, bounds.latitudeSouth, bounds.longitudeEast, bounds.latitudeNorth)
                onZoom(ready.cameraPosition.zoom)
            }
            ready.setMaxZoomPreference(19.0)
            ready.setOnMarkerClickListener { marker ->
                val vehicle = markerVehicles[marker.id]
                if (vehicle != null) {
                    onVehicle(vehicle)
                    return@setOnMarkerClickListener true
                }
                val nearest = stopHitTargets.entries.mapNotNull { (id, target) ->
                    val point = ready.projection.toScreenLocation(target.first)
                    val distance = kotlin.math.hypot(point.x - touchPoint.x, point.y - touchPoint.y)
                    id.takeIf { distance <= target.second + 2 * context.resources.displayMetrics.density }?.let { it to distance }
                }.minByOrNull { it.second }?.first
                markerStops[nearest ?: marker.id]?.let { onSelect(it); true } ?: false
            }
            ready.addOnMapClickListener { location ->
                if (ready.cameraPosition.zoom >= StopMapClustering.DETAIL_ZOOM) false else {
                    val point = ready.projection.toScreenLocation(location)
                    val radius = 8 * context.resources.displayMetrics.density
                    val feature = ready.queryRenderedFeatures(RectF(point.x - radius, point.y - radius, point.x + radius, point.y + radius), "wide-stop-dots").firstOrNull()
                    val id = feature?.getStringProperty("stopId")
                    if (id != null) onSelect(id)
                    id != null
                }
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
    // Theme changes reload only the basemap, retaining camera and current selections.
    // Failed loads retry in the foreground using the currently selected theme.
    LaunchedEffect(map, owner, styleUri) {
        val ready = map ?: return@LaunchedEffect
        var active = true
        styleLoaded = false
        try {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                fun loadStyle() {
                    ready.setStyle(Style.Builder().fromUri(styleUri)) { loadedStyle ->
                        if (active) {
                            if (darkTheme) loadedStyle.layers.forEach { layer ->
                                when (layer) {
                                    is BackgroundLayer -> layer.setProperties(PropertyFactory.backgroundColor("#101823"))
                                    is FillLayer -> when (layer.id) {
                                        "water" -> layer.setProperties(PropertyFactory.fillColor("#173E59"))
                                        "building" -> layer.setProperties(PropertyFactory.fillColor("#293545"))
                                        "landuse_residential" -> layer.setProperties(PropertyFactory.fillColor("#17212C"))
                                        "landcover_wood", "landuse_park" -> layer.setProperties(PropertyFactory.fillColor("#1C352C"))
                                    }
                                    is LineLayer -> {
                                        val color = when {
                                            layer.id == "waterway" -> "#2E6D90"
                                            layer.id == "highway_minor" -> "#43566D"
                                            layer.id == "highway_major_inner" -> "#687F99"
                                            layer.id == "highway_motorway_inner" -> "#AA8C68"
                                            layer.id.endsWith("_casing") -> "#29394B"
                                            layer.id.startsWith("railway") -> "#786C87"
                                            else -> null
                                        }
                                        color?.let { layer.setProperties(PropertyFactory.lineColor(it)) }
                                    }
                                }
                            }
                            if (darkTheme) loadedStyle.layers.filterIsInstance<SymbolLayer>()
                                .filter { it.id.startsWith("place_") || it.id.startsWith("highway_name_") || it.id == "water_name" }
                                .forEach { it.setProperties(
                                    PropertyFactory.textColor("#B9C2D0"),
                                    PropertyFactory.textHaloColor("#11161D"),
                                    PropertyFactory.textHaloWidth(1f),
                                ) }
                            styleLoaded = true
                            onError(null)
                            val bounds = ready.projection.visibleRegion.latLngBounds
                            viewport = MapBounds(bounds.longitudeWest, bounds.latitudeSouth, bounds.longitudeEast, bounds.latitudeNorth)
                            onZoom(ready.cameraPosition.zoom)
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
    LaunchedEffect(map, styleLoaded, stops) {
        val ready = map ?: return@LaunchedEffect
        if (!styleLoaded) return@LaunchedEffect
        val collection = withContext(Dispatchers.Default) {
            FeatureCollection.fromFeatures(stops.distinctBy { it.id }.map { stop ->
                Feature.fromGeometry(Point.fromLngLat(stop.position.lon, stop.position.lat)).apply {
                    addStringProperty("stopId", stop.id)
                    addStringProperty("color", "#%06X".format(StopMarkerLayout.color(stop.providerId, stop.transportMode) and 0xFFFFFF))
                }
            })
        }
        val style = ready.style ?: return@LaunchedEffect
        val source = style.getSourceAs<GeoJsonSource>("all-stop-points")
        if (source != null) source.setGeoJson(collection) else {
            style.addSource(GeoJsonSource("all-stop-points", collection))
            val layer = CircleLayer("wide-stop-dots", "all-stop-points").withProperties(
                PropertyFactory.circleRadius(1.5f), PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(.4f)
            ).apply { maxZoom = StopMapClustering.DETAIL_ZOOM.toFloat() }
            val labels = style.layers.firstOrNull { it is SymbolLayer }?.id
            if (labels != null) style.addLayerBelow(layer, labels) else style.addLayer(layer)
        }
    }
    LaunchedEffect(map, styleLoaded, routeGeometry, routeColor) {
        val ready = map ?: return@LaunchedEffect
        if (!styleLoaded) return@LaunchedEffect
        val style = ready.style ?: return@LaunchedEffect
        val features = if (routeGeometry.size < 2) emptyArray() else arrayOf(Feature.fromGeometry(
            LineString.fromLngLats(routeGeometry.map { Point.fromLngLat(it.lon, it.lat) })))
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
            val labelLayer = style.layers.firstOrNull { it is SymbolLayer }?.id
            if (labelLayer != null) style.addLayerBelow(casing, labelLayer) else style.addLayer(casing)
            style.addLayerAbove(line, "selected-line-casing")
        }
        style.getLayerAs<LineLayer>("selected-line-stroke")?.setProperties(PropertyFactory.lineColor(routeColor))
    }
    LaunchedEffect(map, styleLoaded, fitRouteRequest) {
        val ready = map ?: return@LaunchedEffect
        if (styleLoaded && fitRouteRequest > 0 && routeGeometry.size >= 2) {
            val bounds = LatLngBounds.Builder().includes(routeGeometry.map { LatLng(it.lat, it.lon) }).build()
            ready.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, (40 * context.resources.displayMetrics.density).toInt()), 650)
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)

    LaunchedEffect(map, styleLoaded, viewport, network, additionalStops, vehicles, routeStops, routeColor, selectedVehicle?.id, selectedStopId, userLocation, cameraRequest, bottomPaddingPx) {
        val nativeMap = map ?: return@LaunchedEffect
        if (!styleLoaded) return@LaunchedEffect
        nativeMap.setPadding(0, 0, 0, bottomPaddingPx)
        nativeMap.clear()
        markerStops.clear()
        stopHitTargets.clear()
        markerVehicles.clear()
        if (iconCache.size > 256) iconCache.clear()
        val zoom = nativeMap.cameraPosition.zoom
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
            val sizeDp = (when {
                zoom < 8 -> 10
                zoom < 10 -> 14
                zoom < 12 -> 18
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
        if (zoom >= StopMapClustering.DETAIL_ZOOM) {
            val nodes = StopMapClustering.groups(stops, viewport, zoom).flatten().map { stop ->
                val point = nativeMap.projection.toScreenLocation(LatLng(stop.position.lat, stop.position.lon))
                StopMarkerLayout.Node(stop, point.x.toDouble(), point.y.toDouble())
            }
            StopMarkerLayout.layout(nodes, density.toDouble(), if (zoom >= 14) 22.0 else 16.0).forEach { placement ->
                val stop = placement.stop
                val position = nativeMap.projection.fromScreenLocation(PointF(placement.x.toFloat(), placement.y.toFloat()))
                val provider = if (stop.providerIds.size > 1) "shared" else stop.providerId
                val marker = nativeMap.addMarker(MarkerOptions().position(position).title(stop.name)
                    .icon(dotIcon(provider, stop.id == selectedStopId, mode = stop.transportMode, sizeOverride = placement.sizePx.toInt())))
                markerStops[marker.id] = stop.id
                stopHitTargets[marker.id] = position to (placement.sizePx / 2).toFloat()
            }
        }
        val visibleVehicles = vehicles.filter { vehicle ->
            viewport?.let { vehicle.position.lat in it.south..it.north && vehicle.position.lon in it.west..it.east } ?: false
        }
        visibleVehicles.forEach { vehicle ->
            val number = vehicle.routeNumber ?: network?.routes?.find { it.id == vehicle.routeId }?.number ?: context.getString(if (vehicle.providerId == "bkk") R.string.emma_vehicle else R.string.engine_vehicle_bus)
            val marker = nativeMap.addMarker(MarkerOptions()
                .position(LatLng(vehicle.position.lat, vehicle.position.lon))
                .title("${when (vehicle.providerId) { "kanyargo" -> "KanyarGO"; "homm" -> "HOMM"; "volan" -> "Volán"; "bkk" -> "BKK"; else -> "ViaBusz" }} · $number · ${vehicle.registration ?: vehicle.id.substringAfter(":")}")
                .snippet(listOfNotNull(vehicle.destination?.takeIf(String::isNotBlank), vehicle.nextStopName?.takeIf(String::isNotBlank), context.getString(if (vehicle.providerId in setOf("volan", "bkk")) R.string.volan_observation_time else R.string.engine_gps_time, formatter.format(vehicle.observedAt)), if (vehicle.providerId in setOf("volan", "bkk")) context.getString(R.string.volan_position_source) else null,
                    vehicle.speedKmh?.let { context.getString(R.string.emma_speed, it.toInt()) }, if (vehicle.routeId == null) context.getString(R.string.engine_no_vehicle_route) else null).joinToString("\n"))
                .icon(vehicleIcon(vehicle)))
            markerVehicles[marker.id] = vehicle.id
        }
        userLocation?.let {
            nativeMap.addMarker(MarkerOptions().position(LatLng(it.lat, it.lon)).title(context.getString(R.string.engine_you_are_here)).icon(dotIcon("location", location = true)))
        }
        if (selectedVehicle != null) {
            if (centeredVehicle != selectedVehicle.id || lastBottomPadding != bottomPaddingPx || centeredRequest != cameraRequest) {
                nativeMap.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    LatLng(selectedVehicle.position.lat, selectedVehicle.position.lon), maxOf(13.5, nativeMap.cameraPosition.zoom)), 650)
                centeredVehicle = selectedVehicle.id
                centeredRequest = cameraRequest
                lastBottomPadding = bottomPaddingPx
            }
        } else {
            centeredVehicle = null
        }
        if (selectedVehicle == null) stops.find { it.id == selectedStopId }?.let { stop ->
            if (centeredStop != stop.id || centeredRequest != cameraRequest || lastBottomPadding != bottomPaddingPx) {
                val currentZoom = nativeMap.cameraPosition.zoom
                val targetZoom = if (currentZoom < 15.0) 15.0 else currentZoom
                nativeMap.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(stop.position.lat, stop.position.lon), targetZoom), 650)
                centeredStop = stop.id
                centeredRequest = cameraRequest
                lastBottomPadding = bottomPaddingPx
            }
        }
    }
}
