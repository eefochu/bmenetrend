package hu.app.bmenetrend.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.WorkerThread
import org.json.JSONObject

/** Native counterpart of the web app's style-images.ts; no font or badge network requests. */
internal object WebStreetStyle {
    fun asset(dark: Boolean) = if (dark) "map/web-dark.json" else "map/web-light.json"

    /** Caller owns [images], including cleanup if preparation is cancelled or a style is replaced. */
    @WorkerThread
    fun prepareImages(context: Context, dark: Boolean, images: MutableMap<String, Bitmap>) {
        val style = JSONObject(context.assets.open(asset(dark)).bufferedReader().use { it.readText() })
        val metadata = style.getJSONObject("metadata").getJSONObject("bmenetrend:images")
        val glyphs = JSONObject(context.assets.open("map/web-poi-glyphs.json").bufferedReader().use { it.readText() })
        val font = Typeface.createFromAsset(context.assets, "map/fonts/MapSymbolsRounded.ttf")
        val ring = Color.parseColor(metadata.getString("ring"))
        val categories = metadata.getJSONObject("poi")
        // SDK sprites are measured in logical pixels. @2x matches the web's minimum badge ratio.
        val ratio = 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (category in categories.keys()) {
            val values = categories.getJSONArray(category)
            val bitmap = Bitmap.createBitmap(46, 46, Bitmap.Config.ARGB_8888).apply { density = 320 }
            images["bm-poi:$category"] = bitmap
            val canvas = Canvas(bitmap)
            paint.color = ring
            canvas.drawCircle(23f, 23f, 23f, paint)
            paint.color = Color.parseColor(values.getString(0))
            canvas.drawCircle(23f, 23f, 20f, paint)
            paint.typeface = font
            paint.textSize = 14f * ratio
            paint.textAlign = Paint.Align.CENTER
            paint.color = Color.WHITE
            val glyph = String(Character.toChars(glyphs.getInt(values.getString(1))))
            val bounds = android.graphics.Rect()
            paint.getTextBounds(glyph, 0, glyph.length, bounds)
            canvas.drawText(glyph, 23f, 23f - bounds.exactCenterY(), paint)
        }
    }
}
