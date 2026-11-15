package hu.app.bmenetrend

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.core.content.ContextCompat
import hu.app.bmenetrend.data.GeoPoint
import kotlin.math.abs

/**
 * GPS and network fixes plus a compass heading for the map's position marker.
 *
 * GPS is asked for a fix every [GPS_INTERVAL_MS] with no distance filter, so walking shows up
 * immediately; the network provider fills in indoors and before the first satellite fix, but never
 * overrides a recent, more accurate GPS fix. The heading comes from the fused rotation-vector sensor,
 * smoothed and only published when it turns by at least [HEADING_STEP_DEGREES].
 */
internal class UserLocationTracker(
    private val context: Context,
    private val onLocation: (GeoPoint) -> Unit,
    private val onHeading: (Float?) -> Unit,
) {
    private val locations = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var best: Location? = null
    private var smoothed = Float.NaN
    private var published = Float.NaN
    private var publishedAt = 0L
    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    private val locationListener = LocationListener { location -> accept(location) }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            // Keep "up on the screen" as the facing direction whichever way the display is turned.
            val (x, y) = when (displayRotation()) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            SensorManager.remapCoordinateSystem(rotation, x, y, remapped)
            SensorManager.getOrientation(remapped, orientation)
            val azimuth = ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toFloat()
            smoothed = if (smoothed.isNaN()) azimuth else HeadingMath.blend(smoothed, azimuth, SMOOTHING)
            val now = SystemClock.uptimeMillis()
            if (published.isNaN() || HeadingMath.difference(published, smoothed) >= HEADING_STEP_DEGREES &&
                now - publishedAt >= MIN_HEADING_INTERVAL_MS) {
                published = smoothed; publishedAt = now
                onHeading(smoothed)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locations.isProviderEnabled(it) }.getOrDefault(false) }
            .ifEmpty { listOf(LocationManager.PASSIVE_PROVIDER) }
        for (provider in providers) {
            val interval = if (provider == LocationManager.GPS_PROVIDER) GPS_INTERVAL_MS else NETWORK_INTERVAL_MS
            runCatching { locations.requestLocationUpdates(provider, interval, 0f, locationListener, Looper.getMainLooper()) }
        }
        providers.mapNotNull { runCatching { locations.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }?.let(::accept)
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) onHeading(null)
        else sensors.registerListener(sensorListener, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        locations.removeUpdates(locationListener)
        sensors.unregisterListener(sensorListener)
        smoothed = Float.NaN; published = Float.NaN
        onHeading(null)
    }

    private fun accept(location: Location) {
        val previous = best
        if (previous != null && location.provider != LocationManager.GPS_PROVIDER &&
            previous.provider == LocationManager.GPS_PROVIDER &&
            location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos < GPS_PRIORITY_NANOS &&
            (!location.hasAccuracy() || previous.hasAccuracy() && location.accuracy > previous.accuracy)) return
        best = location
        onLocation(GeoPoint(location.latitude, location.longitude))
    }

    private var cachedRotation = Surface.ROTATION_0
    private var lastRotationCheck = 0L

    private fun displayRotation(): Int {
        val now = SystemClock.uptimeMillis()
        if (now - lastRotationCheck > 1000L) {
            lastRotationCheck = now
            cachedRotation = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    context.display?.rotation ?: Surface.ROTATION_0
                } else {
                    @Suppress("DEPRECATION")
                    ContextCompat.getDisplayOrDefault(context).rotation
                }
            }.getOrDefault(Surface.ROTATION_0)
        }
        return cachedRotation
    }

    companion object {
        const val GPS_INTERVAL_MS = 1_000L
        const val NETWORK_INTERVAL_MS = 3_000L
        /** A network fix is ignored for this long after a more accurate GPS fix. */
        const val GPS_PRIORITY_NANOS = 10_000_000_000L
        const val HEADING_STEP_DEGREES = 2f
        const val MIN_HEADING_INTERVAL_MS = 40L
        const val SMOOTHING = 0.2f
    }
}

/** Angle helpers that respect the 359° → 0° wrap. */
internal object HeadingMath {
    /** Smallest absolute difference between two headings, 0..180. */
    fun difference(a: Float, b: Float): Float { val d = abs(((a - b) % 360 + 540) % 360 - 180); return d }

    /** Moves [from] toward [to] by [factor] along the shorter way round; result in 0..360. */
    fun blend(from: Float, to: Float, factor: Float): Float {
        val delta = ((to - from) % 360 + 540) % 360 - 180
        return ((from + delta * factor) % 360 + 360) % 360
    }
}
