package hu.viabusz.app.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.compose.LocalLifecycleOwner
import hu.viabusz.app.R
import hu.viabusz.app.data.GeoPoint
import hu.viabusz.app.data.TransitNetwork
import hu.viabusz.app.data.Vehicle
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A native MapLibre map; no browser/WebView and no API key. */
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
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val onSelect by rememberUpdatedState(onStopSelected)
    val onError by rememberUpdatedState(onMapError)
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleLoaded by remember { mutableStateOf(false) }
    var centeredStop by remember { mutableStateOf<String?>(null) }
    var centeredRequest by remember { mutableIntStateOf(-1) }
    var lastBottomPadding by remember { mutableIntStateOf(-1) }
    val markerStops = remember { mutableMapOf<Long, String>() }
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
            ready.setMinZoomPreference(10.0)
            ready.setMaxZoomPreference(19.0)
            ready.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/positron")) {
                styleLoaded = true
                onError(null)
            }
            ready.setOnMarkerClickListener { marker ->
                markerStops[marker.id]?.let { onSelect(it); true } ?: false
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
    // A failed style request is retried when connectivity returns, only in the foreground.
    LaunchedEffect(map, owner) {
        val ready = map ?: return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                delay(30_000)
                if (!styleLoaded) ready.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/positron")) {
                    styleLoaded = true
                    onError(null)
                }
            }
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)

    LaunchedEffect(map, styleLoaded, network, vehicles, selectedStopId, userLocation, cameraRequest, bottomPaddingPx) {
        val nativeMap = map ?: return@LaunchedEffect
        if (!styleLoaded) return@LaunchedEffect
        nativeMap.setPadding(0, 0, 0, bottomPaddingPx)
        nativeMap.clear()
        markerStops.clear()
        fun icon(key: String, text: String, selected: Boolean = false, vehicle: Boolean = false, providerId: String = "viabusz"): Icon = iconCache.getOrPut(key) {
            val scale = context.resources.displayMetrics.density
            val width = (if (vehicle) 58f else if (selected) 32f else 22f) * scale
            val height = (if (vehicle) 34f else if (selected) 32f else 22f) * scale
            val bitmap = Bitmap.createBitmap(width.toInt(), height.toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val providerColor = when (providerId) {
                "kanyargo" -> android.graphics.Color.rgb(103, 80, 164)
                "homm" -> android.graphics.Color.rgb(191, 87, 0)
                "shared" -> android.graphics.Color.rgb(0, 104, 115)
                else -> android.graphics.Color.rgb(32, 92, 72)
            }
            paint.color = android.graphics.Color.WHITE
            if (vehicle) {
                canvas.drawRoundRect(0f, 0f, width, height, 11 * scale, 11 * scale, paint)
                paint.color = providerColor
                canvas.drawRoundRect(2 * scale, 2 * scale, width - 2 * scale, height - 2 * scale, 9 * scale, 9 * scale, paint)
                paint.color = android.graphics.Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                paint.textSize = 12 * scale
                canvas.drawText(text, width / 2, height / 2 - (paint.ascent() + paint.descent()) / 2, paint)
            } else {
                canvas.drawCircle(width / 2, height / 2, width / 2, paint)
                paint.color = if (key == "location") android.graphics.Color.rgb(53, 109, 219) else providerColor
                canvas.drawCircle(width / 2, height / 2, width / 2 - 2 * scale, paint)
                if (!selected && key != "location") {
                    paint.color = android.graphics.Color.WHITE
                    canvas.drawCircle(width / 2, height / 2, width / 5, paint)
                }
            }
            IconFactory.getInstance(context).fromBitmap(bitmap)
        }
        network?.stops?.forEach { stop ->
            val selected = stop.id == selectedStopId
            val markerProvider = if (stop.providerIds.size > 1) "shared" else stop.providerId
            val marker = nativeMap.addMarker(MarkerOptions()
                .position(LatLng(stop.position.lat, stop.position.lon))
                .title(stop.name)
                .icon(icon("$markerProvider-${if (selected) "selected-stop" else "stop"}", "", selected, providerId = markerProvider)))
            markerStops[marker.id] = stop.id
        }
        vehicles.forEach { vehicle ->
            val number = network?.routes?.find { it.id == vehicle.routeId }?.number ?: context.getString(R.string.engine_vehicle_bus)
            nativeMap.addMarker(MarkerOptions()
                .position(LatLng(vehicle.position.lat, vehicle.position.lon))
                .title("${when (vehicle.providerId) { "kanyargo" -> "KanyarGO"; "homm" -> "HOMM"; else -> "ViaBusz" }} · $number · ${vehicle.id.substringAfter(":")}")
                .snippet(listOfNotNull(vehicle.nextStopName?.takeIf(String::isNotBlank), context.getString(R.string.engine_gps_time, formatter.format(vehicle.observedAt)), if (vehicle.routeId == null) context.getString(R.string.engine_no_vehicle_route) else null).joinToString("\n"))
                .icon(icon("vehicle-${vehicle.providerId}-$number", number, vehicle = true, providerId = vehicle.providerId)))
        }
        userLocation?.let {
            nativeMap.addMarker(MarkerOptions().position(LatLng(it.lat, it.lon)).title(context.getString(R.string.engine_you_are_here)).icon(icon("location", "")))
        }
        network?.stops?.find { it.id == selectedStopId }?.let { stop ->
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
