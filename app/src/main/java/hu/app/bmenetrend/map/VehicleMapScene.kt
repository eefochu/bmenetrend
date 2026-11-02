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
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Camera-independent vehicle data. MapLibre handles visibility during gestures; icons keep a fixed screen size. */
internal object VehicleMapScene {
    const val SOURCE = "live-vehicles"
    const val ICON_SCALE = 24f / 36f
    val layers = arrayOf("vehicle-symbols", "vehicle-dots-rail", "vehicle-dots-road")

    suspend fun prepare(
        context: Context,
        vehicles: List<Vehicle>,
        focus: LineMapFocus?,
        selectedId: String?,
        cachedIcons: Set<String>,
        pending: MutableMap<String, Bitmap>,
    ): String {
        val features = ArrayList<Feature>(vehicles.size)
        for (vehicle in vehicles) {
            currentCoroutineContext().ensureActive()
            if (focus != null && !focus.includes(vehicle)) continue
            val marker = VehicleMarkerStyle.forVehicle(vehicle)
            val metro = MetroLineStyle.forLine(vehicle.routeNumber, vehicle.transportMode)
            val hev = HevLineStyle.forLine(vehicle.routeNumber, vehicle.transportMode)
            val key = "vehicle-${marker.name}-${metro?.number}-${hev?.number}"
            if (key !in cachedIcons && key !in pending) {
                pending[key] = when {
                    hev != null -> HevLineStyle.render(context, hev, 108)
                    metro != null -> MetroLineStyle.render(context, metro, 108)
                    else -> renderVehicle(context, marker)
                }.apply { density = 480 }
            }
            val selected = vehicle.id == selectedId
            features += Feature.fromGeometry(Point.fromLngLat(vehicle.position.lon, vehicle.position.lat)).apply {
                addStringProperty("vehicle-id", vehicle.id)
                addStringProperty("icon", key)
                addBooleanProperty("rail", RailSupport.isTrain(vehicle.transportMode))
                addNumberProperty("priority", if (selected) 1 else 0)

            }
        }
        return FeatureCollection.fromFeatures(features).toJson()
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
