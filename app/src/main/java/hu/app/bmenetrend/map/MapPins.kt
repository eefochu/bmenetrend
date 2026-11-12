package hu.app.bmenetrend.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.PlaceResult
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/** Planner/search endpoints; they never change on camera gestures or vehicle ticks. The user's position is [UserLocationLayer]. */
internal class MapPins(private val context: Context) {
    private var origin: Marker? = null
    private var place: Marker? = null

    fun update(map: MapLibreMap, routeOrigin: GeoPoint?, selectedPlace: PlaceResult?) {
        val resources = context.resources
        origin = map.pin(origin, routeOrigin) {
            MarkerOptions().title(resources.getString(R.string.planner_from)).icon(endpointIcon("origin-pin-marker", Color.rgb(117, 117, 117)))
        }
        place = map.pin(place, selectedPlace?.position) {
            MarkerOptions().title(selectedPlace?.name).snippet(selectedPlace?.address?.takeIf { it.isNotBlank() })
                .icon(endpointIcon("place-pin-marker", Color.rgb(211, 47, 47)))
        }
        if (selectedPlace != null) place?.apply {
            title = selectedPlace.name
            snippet = selectedPlace.address.takeIf { it.isNotBlank() }
        }
    }

    private fun MapLibreMap.pin(current: Marker?, point: GeoPoint?, options: () -> MarkerOptions): Marker? {
        if (point == null) { current?.let(::removeMarker); return null }
        val position = LatLng(point.lat, point.lon)
        if (current == null) return addMarker(options().position(position))
        if (current.position != position) current.position = position
        return current
    }

    private val density get() = context.resources.displayMetrics.density

    private fun endpointIcon(key: String, color: Int): Icon = icons.getOrPut("$key@$density") {
        val size = (34 * density).toInt().coerceAtLeast(1)
        val radius = size / 2f
        circles(size, listOf(radius to Color.WHITE, (radius - 2.5f * density).coerceAtLeast(1f) to color,
            (radius - 6f * density).coerceAtLeast(1f) to Color.WHITE))
    }

    private fun circles(size: Int, rings: List<Pair<Float, Int>>): Icon {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        rings.forEach { (radius, color) -> paint.color = color; canvas.drawCircle(size / 2f, size / 2f, radius, paint) }
        return IconFactory.getInstance(context).fromBitmap(bitmap)
    }

    private companion object {
        /** Shared by every map instance: theme and style reloads reuse the same few pin textures. */
        val icons = HashMap<String, Icon>()
    }
}
