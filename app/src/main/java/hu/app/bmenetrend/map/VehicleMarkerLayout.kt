package hu.app.bmenetrend.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import hu.app.bmenetrend.data.Vehicle

internal object VehicleMarkerLayout {
    const val DIAMETER_DP = 26f
    const val SELECTED_DIAMETER_DP = 30f
    const val ICON_DIAMETER_DP = 36f
    const val ARROW_CANVAS_DP = 28f
    /** The arrow texture is drawn around a circle of this size and scaled with the marker. */
    const val ARROW_DESIGN_DIAMETER_DP = 18f

    /** Trains seen from overview zooms are drawn at this share of their normal size. */
    const val OVERVIEW_FACTOR = 0.7f

    fun diameterDp(selected: Boolean, overview: Boolean = false) =
        (if (selected) SELECTED_DIAMETER_DP else DIAMETER_DP) * if (overview) OVERVIEW_FACTOR else 1f
    fun arrowScale(selected: Boolean, overview: Boolean = false) = diameterDp(selected, overview) / ARROW_DESIGN_DIAMETER_DP

    fun heading(vehicle: Vehicle): Double? = vehicle.heading?.takeIf { it.isFinite() && it in 0.0..360.0 }?.rem(360.0)

    fun arrowKey(color: Int) = "vehicle-heading-$color"

    /** Shared north-facing texture; the map rotates it without re-rendering a bitmap per heading. */
    fun renderArrow(color: Int): Bitmap {
        val scale = 3f
        val side = (ARROW_CANVAS_DP * scale).toInt()
        return Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply {
            density = 480
            val c = side / 2f
            val path = Path().apply {
                moveTo(c, c - 13 * scale)
                lineTo(c + 3 * scale, c - 7 * scale)
                lineTo(c - 3 * scale, c - 7 * scale)
                close()
            }
            val canvas = Canvas(this)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = scale; this.color = Color.WHITE
                strokeJoin = Paint.Join.ROUND
            }
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL; paint.color = color
            canvas.drawPath(path, paint)
        }
    }
}
