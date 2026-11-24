package hu.app.bmenetrend.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import hu.app.bmenetrend.data.RailSupport
import hu.app.bmenetrend.data.Vehicle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Camera-independent vehicle data. MapLibre handles visibility and size during gestures. */
internal object VehicleMapScene {
    const val SOURCE = "live-vehicles"
    val layers = arrayOf(VehicleLayers.SYMBOLS, VehicleLayers.ARROWS, VehicleLayers.RAIL_OVERVIEW, VehicleLayers.RAIL_OVERVIEW_ARROWS)

    suspend fun prepare(
        context: Context,
        vehicles: List<Vehicle>,
        selectedId: String?,
        cachedIcons: Set<String>,
        pending: MutableMap<String, Bitmap>,
    ): String {
        // Written straight to JSON: no per-vehicle Feature/Point/Gson object graph on every refresh.
        val json = StringBuilder(vehicles.size * 230 + 64).append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        for (vehicle in vehicles) {
            currentCoroutineContext().ensureActive()
            val marker = VehicleMarkerStyle.forVehicle(vehicle)
            val metro = MetroLineStyle.forLine(vehicle.routeNumber, vehicle.transportMode)
            val hev = HevLineStyle.forLine(vehicle.routeNumber, vehicle.transportMode)
            val key = "vehicle-${marker.name}-${metro?.number}-${hev?.number}"
            val color = hev?.color ?: metro?.color ?: marker.color
            val heading = VehicleMarkerLayout.heading(vehicle)
            val arrow = VehicleMarkerLayout.arrowKey(color).takeIf { heading != null }
            if (key !in cachedIcons && key !in pending) {
                pending[key] = when {
                    hev != null -> HevLineStyle.render(context, hev, 108)
                    metro != null -> MetroLineStyle.render(context, metro, 108)
                    else -> renderVehicle(context, marker)
                }.apply { density = 480 }
            }
            if (arrow != null && arrow !in cachedIcons && arrow !in pending) pending[arrow] = VehicleMarkerLayout.renderArrow(color)
            val selected = vehicle.id == selectedId
            val diameter = VehicleMarkerLayout.diameterDp(selected)
            val lon = vehicle.position.lon; val lat = vehicle.position.lat
            if (!lon.isFinite() || !lat.isFinite()) continue
            if (!first) json.append(',')
            first = false
            json.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
                .append(lon).append(',').append(lat).append("]},\"properties\":{\"vehicle-id\":").appendJsonString(vehicle.id)
                .append(",\"provider-id\":").appendJsonString(vehicle.providerId)
                .append(",\"route-id\":").appendJsonString(vehicle.routeId.orEmpty())
                .append(",\"route-number\":").appendJsonString(vehicle.routeNumber.orEmpty())
                .append(",\"transport-mode\":").appendJsonString(vehicle.transportMode.orEmpty())
                .append(",\"icon\":").appendJsonString(key)
                .append(",\"rail\":").append(RailSupport.isTrain(vehicle.transportMode))
                .append(",\"priority\":").append(if (selected) 1 else 0)
                .append(",\"large-scale\":").append(diameter / VehicleMarkerLayout.ICON_DIAMETER_DP)
            if (arrow != null) {
                json.append(",\"arrow\":").appendJsonString(arrow)
                    .append(",\"heading\":").append(heading)
                    .append(",\"arrow-scale\":").append(VehicleMarkerLayout.arrowScale(selected))
            }
            json.append("}}")
        }
        return json.append("]}").toString()
    }

    private fun renderVehicle(context: Context, marker: VehicleMarkerStyle): Bitmap {
        val size = 108
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
        canvas.drawCircle(54f, 54f, 54f, paint)
        if (marker == VehicleMarkerStyle.VIABUSZ_BUS || marker == VehicleMarkerStyle.HOMM_BUS ||
            marker == VehicleMarkerStyle.KANYARGO_BUS) {
            context.getDrawable(marker.drawableRes)?.mutate()?.apply {
                setBounds(3, 3, 105, 105)
                draw(canvas)
            }
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(context.resources, marker.drawableRes, options)
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (options.outWidth / (options.inSampleSize * 2) >= size) options.inSampleSize *= 2
            val source = BitmapFactory.decodeResource(context.resources, marker.drawableRes, options)
            try { canvas.drawBitmap(source, null, RectF(3f, 3f, 105f, 105f), paint) }
            finally { source.recycle() }
        }
        return bitmap
    }
}
