package hu.app.bmenetrend.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.runtime.withFrameNanos
import hu.app.bmenetrend.data.GeoPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.Flow
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/** The user's blue position dot and the translucent cone showing which way the phone faces. */
internal object UserLocationMarker {
    val BLUE = Color.rgb(53, 109, 219)
    const val DOT_DP = 18f
    /** Cone reach from the dot's centre. */
    const val CONE_RADIUS_DP = 44f
    const val CONE_SPREAD_DEGREES = 70f
    /** A fix is glided to over this long, about one GPS interval, so the dot moves continuously. */
    const val GLIDE_MS = 900L
    /** Farther jumps (a first accurate fix, a tunnel exit) snap instead of sliding across the map. */
    const val SNAP_METERS = 250.0

    /** White-ringed blue dot, [densityDpi] decides its pixel size; the bitmap carries that density. */
    fun renderDot(density: Float, densityDpi: Int): Bitmap {
        val size = (DOT_DP * density).toInt().coerceAtLeast(2)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            this.density = densityDpi
            val canvas = Canvas(this)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.argb(60, 0, 0, 0); canvas.drawCircle(size / 2f, size / 2f + density * .5f, size / 2f - density * .5f, paint)
            paint.color = Color.WHITE; canvas.drawCircle(size / 2f, size / 2f, size / 2f - density * .5f, paint)
            paint.color = BLUE; canvas.drawCircle(size / 2f, size / 2f, size / 2f - 3 * density, paint)
        }
    }

    /** North-pointing wedge centred on the bitmap's middle; the map rotates it to the heading. */
    fun renderCone(density: Float, densityDpi: Int): Bitmap {
        val radius = CONE_RADIUS_DP * density
        val size = (radius * 2).toInt().coerceAtLeast(2)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            this.density = densityDpi
            val c = size / 2f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(c, c, radius, intArrayOf(Color.argb(150, 53, 109, 219), Color.argb(0, 53, 109, 219)),
                    floatArrayOf(.15f, 1f), Shader.TileMode.CLAMP)
            }
            val path = Path().apply {
                moveTo(c, c)
                arcTo(RectF(c - radius, c - radius, c + radius, c + radius), -90f - CONE_SPREAD_DEGREES / 2, CONE_SPREAD_DEGREES)
                close()
            }
            Canvas(this).drawPath(path, paint)
        }
    }

    /**
     * Shows each new position by gliding the drawn one toward it over [GLIDE_MS], one step per frame.
     * A newer fix restarts the glide from wherever the marker currently is.
     */
    suspend fun glide(positions: Flow<GeoPoint?>, show: (GeoPoint?) -> Unit) {
        var shown: GeoPoint? = null
        positions.collectLatest { target ->
            val from = shown
            if (target == null || from == null || hu.app.bmenetrend.distanceMeters(from, target) > SNAP_METERS) {
                shown = target; show(target); return@collectLatest
            }
            if (from == target) return@collectLatest
            val start = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val t = ((now - start) / 1_000_000.0 / GLIDE_MS).coerceAtMost(1.0)
                val eased = 1 - (1 - t) * (1 - t)
                val point = GeoPoint(from.lat + (target.lat - from.lat) * eased, from.lon + (target.lon - from.lon) * eased)
                shown = point; show(point)
                if (t >= 1.0) break
            }
        }
    }
}

/** MapLibre style layers for [UserLocationMarker]: cone below, dot above, both over transit markers. */
internal object UserLocationLayer {
    const val SOURCE = "user-location"
    const val CONE = "user-location-cone"
    const val DOT = "user-location-dot"

    fun install(style: Style, density: Float, densityDpi: Int) {
        if (style.getSource(SOURCE) != null) return
        style.addImage(DOT, UserLocationMarker.renderDot(density, densityDpi))
        style.addImage(CONE, UserLocationMarker.renderCone(density, densityDpi))
        style.addSource(GeoJsonSource(SOURCE))
        fun layer(id: String) = SymbolLayer(id, SOURCE).withProperties(
            PropertyFactory.iconImage(id), PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconRotationAlignment("map"), PropertyFactory.iconPitchAlignment("map"))
        style.addLayer(layer(CONE).withProperties(PropertyFactory.visibility("none")))
        style.addLayer(layer(DOT))
    }

    fun position(style: Style, point: GeoPoint?) {
        val source = style.getSourceAs<GeoJsonSource>(SOURCE) ?: return
        source.setGeoJson(if (point == null) "{\"type\":\"FeatureCollection\",\"features\":[]}"
            else "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{\"type\":\"Point\",\"coordinates\":[${point.lon},${point.lat}]}}")
    }

    fun heading(style: Style, degrees: Float?) {
        style.getLayerAs<SymbolLayer>(CONE)?.setProperties(
            PropertyFactory.visibility(if (degrees == null) "none" else "visible"),
            PropertyFactory.iconRotate(degrees ?: 0f))
    }

    /** Layers added after this one go below it, so the position stays on top of stops and vehicles. */
    fun below(style: Style): String? = CONE.takeIf { style.getLayer(it) != null }
}
