package hu.app.bmenetrend.map

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
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.MapBounds
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.TransitStop
import hu.app.bmenetrend.data.TransitNetwork
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val VEHICLE_ICON_MIN_ZOOM = 12.0
private data class StationHitTarget(val position: LatLng,val offset: StopMarkerLayout.Offset,val focused: Boolean)
private data class StationScene(val groups: List<List<TransitStop>>,val offsets: Map<String,StopMarkerLayout.Offset>)
private fun stationAnchor(members: List<TransitStop>): TransitStop = members.first().let { first ->
    first.copy(position=(members.firstOrNull { it.providerId=="volan" } ?: first).position)
}

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
    browsingNearby: Boolean = false,
    locationCameraRequest: Int = 0,
    onViewportChanged: (MapBounds, GeoPoint) -> Unit = { _, _ -> },
    stopBearings: Map<String,List<Double>> = emptyMap(),
    onSharedStopSelected: (List<String>)->Unit = { ids -> ids.firstOrNull()?.let(onStopSelected) },
) {
    val darkTheme = isSystemInDarkTheme()
    val styleUri = if (darkTheme) "asset://map/night.json" else "https://tiles.openfreemap.org/styles/positron"
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val onSelect by rememberUpdatedState(onStopSelected)
    val onSharedSelect by rememberUpdatedState(onSharedStopSelected)
    val onError by rememberUpdatedState(onMapError)
    val onArea by rememberUpdatedState(onViewportChanged)
    val onZoom by rememberUpdatedState(onMapZoomChanged)
    val onVehicle by rememberUpdatedState(onVehicleSelected)
    val stops = remember(network?.stops, additionalStops, routeStops) { (network?.stops.orEmpty() + additionalStops + routeStops).distinctBy { it.id } }
    // Group and allocate in a camera-independent world. Pan, zoom, selection and highlights cannot reshuffle it.
    val sceneStops=remember(stops,lineFocus?.displayStops) { (stops+lineFocus?.displayStops.orEmpty()).distinctBy { it.id } }
    val stationScene by produceState<StationScene?>(null,sceneStops,stopBearings) {
        val previousOffsets=value?.offsets.orEmpty()
        value=withContext(Dispatchers.Default) {
            val bounds=sceneStops.takeIf { it.isNotEmpty() }?.let { all -> MapBounds(
                all.minOf { it.position.lon },all.minOf { it.position.lat },all.maxOf { it.position.lon },all.maxOf { it.position.lat }) }
            val groups=traceMapWork("BMenetrend.StopGroups") { StopMapClustering.groups(sceneStops,bounds,16.0,stopBearings) }
            StationScene(groups,StopMarkerLayout.offsets(groups.map(::stationAnchor),previousOffsets))
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
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var viewport by remember { mutableStateOf<MapBounds?>(null) }
    var styleLoaded by remember { mutableStateOf(false) }
    var locatedOnce by rememberSaveable { mutableStateOf(false) }
    var locatedRequest by remember { mutableIntStateOf(-1) }
    var centeredStop by remember { mutableStateOf<String?>(null) }
    var centeredRequest by remember { mutableIntStateOf(-1) }
    var lastBottomPadding by remember { mutableIntStateOf(-1) }
    val vehicleMarkers = remember { mutableMapOf<String, Marker>() }
    var locationMarker by remember { mutableStateOf<Marker?>(null) }
    var placeMarker by remember { mutableStateOf<Marker?>(null) }
    val markerVehicles = remember { mutableMapOf<Long, String>() }
    val stopHitTargets = remember { mutableMapOf<String,StationHitTarget>() }
    val stopGroups = remember { mutableMapOf<String, List<TransitStop>>() }
    val iconCache = remember { mutableMapOf<String, Icon>() }
    val stationIconKeys=remember { mutableSetOf<String>() }
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
                onArea(viewport!!, GeoPoint(savedLat, savedLon))
            }
            ready.setMaxZoomPreference(19.0)
            fun targets(): List<StopHitTesting.Target> = stopHitTargets.mapNotNull { (id,target) ->
                val stop=stopGroups[id]?.firstOrNull { it.id==id } ?: return@mapNotNull null
                val point=ready.projection.toScreenLocation(target.position)
                val angle=Math.toRadians(ready.cameraPosition.bearing)
                val density=context.resources.displayMetrics.density.toDouble()
                val diameter=StopMarkerLayout.diameterDp(ready.cameraPosition.zoom,target.focused)
                val scale=diameter/StopMarkerLayout.DIAMETER_DP*density
                val dx=target.offset.x*scale;val dy=target.offset.y*scale
                val x=point.x+dx*kotlin.math.cos(angle)+dy*kotlin.math.sin(angle)
                val y=point.y-dx*kotlin.math.sin(angle)+dy*kotlin.math.cos(angle)
                StopHitTesting.Target(stop,x,y,diameter*density/2)
            }
            fun reveal(location: LatLng) {
                ready.animateCamera(CameraUpdateFactory.newLatLngZoom(location,
                    maxOf(16.0,ready.cameraPosition.zoom+2).coerceAtMost(19.0)),450)
            }
            ready.setOnMarkerClickListener { marker ->
                val vehicle=markerVehicles[marker.id]
                if(vehicle!=null) {
                    onVehicle(vehicle)
                    true
                } else false
            }
            ready.addOnMapClickListener { location ->
                val point=ready.projection.toScreenLocation(location)
                val hits=targets()
                val density=context.resources.displayMetrics.density.toDouble()
                val candidate=StopHitTesting.candidates(hits,point.x.toDouble(),point.y.toDouble(),density).firstOrNull()
                if(candidate==null) false else {
                    if(StopHitTesting.needsZoom(hits,point.x.toDouble(),point.y.toDouble(),density,ready.cameraPosition.zoom)) reveal(location)
                    else if(stopGroups[candidate.id].orEmpty().flatMap { it.providerIds }.distinct().size>1) onSharedSelect(listOf(candidate.id)+stopGroups[candidate.id].orEmpty().map { it.id }.filterNot { it==candidate.id })
                    else onSelect(candidate.id)
                    true
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
                            onArea(viewport!!, GeoPoint(savedLat, savedLon))
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
            val aboveRoute = style.getLayer("station-symbols")?.id ?: MapLibreConstants.LAYER_ID_ANNOTATIONS
            if (style.getLayer(aboveRoute) != null) style.addLayerBelow(casing, aboveRoute) else style.addLayer(casing)
            style.addLayerAbove(line, "selected-line-casing")
        }
        style.getLayerAs<LineLayer>("selected-line-casing")?.setProperties(PropertyFactory.lineColor(if (darkTheme) "#17212D" else "#FFFFFF"))
        style.getLayerAs<LineLayer>("selected-line-stroke")?.setProperties(PropertyFactory.lineColor(routeColor))
    }
    LaunchedEffect(map, styleLoaded, lineFocus?.key, paths, focusStops, fitRouteRequest,
        selectedStopId, selectedPlace, placeCameraRequest, cameraRequest, bottomPaddingPx, size, browsingNearby, userLocation, locationCameraRequest) {
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
            var boundsAnimated = false
            if (bounds != null) {
                try {
                    val latNorth = maxOf(bounds.north, bounds.south)
                    val latSouth = minOf(bounds.north, bounds.south)
                    val lonEast = maxOf(bounds.east, bounds.west)
                    val lonWest = minOf(bounds.east, bounds.west)
                    val padding = (48 * context.resources.displayMetrics.density).toInt()
                    if (latNorth - latSouth > 0.0001 && lonEast - lonWest > 0.0001 && padding * 2 < size.width && padding * 2 < size.height) {
                        ready.animateCamera(CameraUpdateFactory.newLatLngBounds(
                            LatLngBounds.from(latNorth, lonEast, latSouth, lonWest), padding), 650)
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
    AndroidView(factory = { mapView }, modifier = modifier.onSizeChanged { size = it })
    LaunchedEffect(map,styleLoaded,viewport,zoom,stationScene,highlightedIds,focused,routeColor,selectedStopId,stopBearings,darkTheme) {
        val nativeMap=map ?: return@LaunchedEffect
        if(!styleLoaded) return@LaunchedEffect
        val scene=stationScene ?: return@LaunchedEffect
        val bounds=viewport ?: return@LaunchedEffect
        val groups=scene.groups.filter { members ->
            val anchor=stationAnchor(members)
            anchor.position.lat in bounds.south..bounds.north && anchor.position.lon in bounds.west..bounds.east &&
                TransitMapVisibility.stop(anchor.transportMode,zoom,focused) && (!focused || members.any { it.id in highlightedIds })
        }
        val style=nativeMap.style ?: return@LaunchedEffect
        val source=style.getSourceAs<GeoJsonSource>("station-markers")
        if(source==null) stationIconKeys.clear()
        val nextGroups=mutableMapOf<String,List<TransitStop>>()
        val nextTargets=mutableMapOf<String,StationHitTarget>()
        val features=mutableListOf<Feature>()
        val sprites=mutableMapOf<String,StationSprite>()
        traceMapWork("BMenetrend.MapLayout") {
            groups.forEach { members ->
                // Marker identity and anchor never depend on the selected member or highlighted line.
                val stop=stationAnchor(members)
                nextGroups[stop.id]=members
                val selected=members.any { it.id==selectedStopId }
                val sizeDp=StopMarkerLayout.DIAMETER_DP.toInt()
                val directions=if(zoom>=StopMapClustering.PLATFORM_ZOOM && sizeDp>=10) members.flatMap { stopBearings[it.id].orEmpty() }
                    .fold(mutableListOf<Double>()) { angles,value ->
                        if(angles.none { StopDirections.difference(it,value)<35 }) angles.add(value)
                        angles
                    }.takeIf { it.size<=2 }.orEmpty().map { ((kotlin.math.round(it/5)*5).toInt()+360)%360 }.distinct().sorted() else emptyList()
                val sprite=StationSprite(StopMarkerColors.palette(members),sizeDp,focused,selected,darkTheme,routeColor,directions)
                sprites[sprite.key]=sprite
                val offset=if(focused) StopMarkerLayout.Offset() else scene.offsets.getValue(stop.id)
                val position=LatLng(stop.position.lat,stop.position.lon)
                features+=Feature.fromGeometry(Point.fromLngLat(stop.position.lon,stop.position.lat)).apply {
                    addStringProperty("icon",sprite.key);addBooleanProperty("selected",selected)
                    val iconOffset=com.google.gson.JsonArray().apply { add(offset.x);add(offset.y) }
                    addProperty("offset",iconOffset)
                }
                nextTargets[stop.id]=StationHitTarget(position,offset,focused)
            }
        }
        val cachedIcons=stationIconKeys.toSet()
        val pending=mutableListOf<Pair<String,Bitmap>>()
        try {
            val json=withContext(Dispatchers.Default) {
                sprites.forEach { (key,sprite) ->
                    if(key !in cachedIcons) pending.add(key to sprite.render())
                }
                FeatureCollection.fromFeatures(features).toJson()
            }
            traceMapWork("BMenetrend.MapUpload") {
                pending.forEach { (key,bitmap) -> style.addImage(key,bitmap);stationIconKeys.add(key) }
                if(source!=null) source.setGeoJson(json) else {
                    style.addSource(GeoJsonSource("station-markers",json))
                    fun layer(id: String,selected: Boolean)=SymbolLayer(id,"station-markers").withProperties(
                        PropertyFactory.iconImage(Expression.get("icon")),PropertyFactory.iconOffset(Expression.get("offset")),
                        PropertyFactory.iconAllowOverlap(true),PropertyFactory.iconIgnorePlacement(true),
                        PropertyFactory.iconRotationAlignment("map"),PropertyFactory.iconPitchAlignment("viewport"),
                        PropertyFactory.symbolZOrder("viewport-y")
                    ).withFilter(Expression.eq(Expression.get("selected"),Expression.literal(selected)))
                    val ordinary=layer("station-symbols",false)
                    if(style.getLayer(MapLibreConstants.LAYER_ID_ANNOTATIONS)!=null) style.addLayerBelow(ordinary,MapLibreConstants.LAYER_ID_ANNOTATIONS) else style.addLayer(ordinary)
                    style.addLayerAbove(layer("station-selected",true),"station-symbols")
                }
                val iconScale=if(focused) Expression.literal(16f/StopMarkerLayout.DIAMETER_DP.toFloat()) else Expression.interpolate(
                    Expression.linear(),Expression.zoom(),*StopMarkerLayout.sizeStops.map { (z,size) ->
                        Expression.stop(z,size/StopMarkerLayout.DIAMETER_DP)
                    }.toTypedArray())
                listOf("station-symbols","station-selected").forEach { id ->
                    style.getLayerAs<SymbolLayer>(id)?.setProperties(PropertyFactory.iconSize(iconScale))
                }
                stopGroups.clear();stopGroups.putAll(nextGroups)
                stopHitTargets.clear();stopHitTargets.putAll(nextTargets)
                // Sizes/angles are quantized, and unused textures have a bounded lifetime.
                if(stationIconKeys.size>256) {
                    val expired=stationIconKeys.filter { it !in sprites }.take(stationIconKeys.size-256)
                    expired.forEach { style.removeImage(it);stationIconKeys.remove(it) }
                }
            }
        } finally { pending.forEach { it.second.recycle() } }
    }
    LaunchedEffect(map,styleLoaded,viewport,zoom,vehicles,lineFocus?.routeId,lineFocus?.providerId,lineFocus?.number,lineFocus?.mode,
        selectedVehicle?.id,selectedStopId,selectedPlace,userLocation,network?.routes) {
        val nativeMap=map ?: return@LaunchedEffect
        if(!styleLoaded) return@LaunchedEffect
        traceMapWork("BMenetrend.MapVehicles") {
            val activeVehicles=mutableSetOf<String>()
            markerVehicles.clear()
            if(iconCache.size>256) iconCache.clear()
            val density=context.resources.displayMetrics.density
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
        fun placeIcon(): Icon = iconCache.getOrPut("place-pin-marker") {
            val size = (34 * density).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val radius = size / 2f
            paint.color = android.graphics.Color.WHITE
            canvas.drawCircle(radius, radius, radius, paint)
            paint.color = android.graphics.Color.parseColor("#D32F2F")
            canvas.drawCircle(radius, radius, (radius - 2.5f * density).coerceAtLeast(1f), paint)
            paint.color = android.graphics.Color.WHITE
            canvas.drawCircle(radius, radius, (radius - 6f * density).coerceAtLeast(1f), paint)
            IconFactory.getInstance(context).fromBitmap(bitmap)
        }
        fun vehicleIcon(vehicle: Vehicle): Icon {
            val style = VehicleMarkerStyle.forVehicle(vehicle)
            val metro = MetroLineStyle.forLine(vehicle.routeNumber, vehicle.transportMode)
            val hev = HevLineStyle.forLine(vehicle.routeNumber, vehicle.transportMode)
            if (!focused && zoom < VEHICLE_ICON_MIN_ZOOM) {
                val sizeDp = if (vehicle.id == selectedVehicle?.id) 10 else 6
                return iconCache.getOrPut("transport-dot-$style-${metro?.number}-${hev?.number}-$sizeDp") {
                    val size = (sizeDp * density).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
                    val radius = size / 2f
                    canvas.drawCircle(radius, radius, radius, paint)
                    paint.color = hev?.color ?: metro?.color ?: style.color
                    canvas.drawCircle(radius, radius, (radius - .65f * density).coerceAtLeast(1f), paint)
                    IconFactory.getInstance(context).fromBitmap(bitmap)
                }
            }
            val sizeDp = (when {
                zoom < 13.5 -> 24
                else -> 36
            }) + if (vehicle.id == selectedVehicle?.id) 6 else 0
            if (hev != null) return iconCache.getOrPut("hev-${hev.number}-$sizeDp") {
                IconFactory.getInstance(context).fromBitmap(HevLineStyle.render(context, hev, (sizeDp * density).toInt().coerceAtLeast(1)))
            }
            if (metro != null) return iconCache.getOrPut("metro-${metro.number}-$sizeDp") {
                IconFactory.getInstance(context).fromBitmap(MetroLineStyle.render(context, metro, (sizeDp * density).toInt().coerceAtLeast(1)))
            }
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
        val visibleVehicles = vehicles.filter { vehicle ->
            (lineFocus?.includes(vehicle) ?: true) && TransitMapVisibility.vehicle(vehicle.transportMode, zoom, focused) && viewport?.let { vehicle.position.lat in it.south..it.north && vehicle.position.lon in it.west..it.east } == true
        }
        visibleVehicles.forEach { vehicle ->
            val number = if (vehicle.providerId == "mav") vehicle.displayNumber else vehicle.displayRouteNumber ?: network?.routes?.find { it.id == vehicle.routeId }?.number ?: context.getString(if (vehicle.providerId == "bkk") R.string.emma_vehicle else R.string.engine_vehicle_bus)
            val options = MarkerOptions()
                .position(LatLng(vehicle.position.lat, vehicle.position.lon))
                .title("${when (vehicle.providerId) { "mav" -> "MÁV / GYSEV"; "hev" -> "HÉV"; "kanyargo" -> "KanyarGO"; "homm" -> "HOMM"; "volan" -> "Volán"; "bkk" -> "BKK"; else -> "ViaBusz" }} · $number${if (vehicle.isRouteEstimated) " · ${context.getString(R.string.vehicle_route_estimated)}" else ""} · ${vehicle.registration ?: vehicle.id.substringAfter(":")}")
                .snippet(listOfNotNull(vehicle.destination?.takeIf(String::isNotBlank), vehicle.nextStopName?.takeIf(String::isNotBlank), context.getString(if (vehicle.providerId in setOf("volan", "bkk", "hev", "mav")) R.string.volan_observation_time else R.string.engine_gps_time, formatter.format(vehicle.observedAt)), if (vehicle.providerId in setOf("volan", "bkk", "hev", "mav")) context.getString(R.string.volan_position_source) else null,
                    vehicle.speedKmh?.let { context.getString(R.string.emma_speed, it.toInt()) }, if (vehicle.routeId == null) context.getString(R.string.engine_no_vehicle_route) else null).joinToString("\n"))
                .icon(vehicleIcon(vehicle))
            val marker = vehicleMarkers.getOrPut(vehicle.id) { nativeMap.addMarker(options) }
            if(marker.position!=options.position) marker.position=options.position
            if(marker.icon!=options.icon) marker.icon=options.icon
            if(marker.title!=options.title) marker.title=options.title
            if(marker.snippet!=options.snippet) marker.snippet=options.snippet
            activeVehicles += vehicle.id
            markerVehicles[marker.id] = vehicle.id
        }
        vehicleMarkers.keys.filter { it !in activeVehicles }.toList().forEach { vehicleMarkers.remove(it)?.let(nativeMap::removeMarker) }
        if (userLocation == null) {
            locationMarker?.let(nativeMap::removeMarker); locationMarker = null
        } else {
            val position = LatLng(userLocation.lat, userLocation.lon)
            if (locationMarker == null) locationMarker = nativeMap.addMarker(MarkerOptions().position(position).title(context.getString(R.string.engine_you_are_here)).icon(dotIcon("location", location = true)))
            else if(locationMarker?.position!=position) locationMarker?.position=position
        }
        if (selectedPlace == null) {
            placeMarker?.let(nativeMap::removeMarker); placeMarker = null
        } else {
            val position = LatLng(selectedPlace.position.lat, selectedPlace.position.lon)
            val pIcon = placeIcon()
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
}
