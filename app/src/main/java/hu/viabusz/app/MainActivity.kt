package hu.viabusz.app

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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.viabusz.app.data.GeoPoint
import hu.viabusz.app.map.NativeTransitMap
import hu.viabusz.app.ui.ViaBuszScreen
import hu.viabusz.app.ui.ViaBuszTheme
import kotlinx.coroutines.awaitCancellation

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val model: TransitViewModel = viewModel()
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
            ViaBuszTheme {
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
                ) { modifier, bottomPaddingPx ->
                    NativeTransitMap(state.network, state.vehicles, state.selectedStopId, state.userLocation, modifier, model::selectStop, model::mapMessage, state.cameraRequest, bottomPaddingPx)
                }
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
