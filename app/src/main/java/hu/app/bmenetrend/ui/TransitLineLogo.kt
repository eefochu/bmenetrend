package hu.app.bmenetrend.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import hu.app.bmenetrend.R

/** Full mode emblem + line number for UI only. Map marker artwork stays separate. */
internal object TransitLineLogo {
    data class Logo(val drawable: Int, val numberPanel: Int = 0)

    fun metro(number: String): Logo = Logo(when (number) {
        "1" -> R.drawable.ui_metro_m1
        "2" -> R.drawable.ui_metro_m2
        "3" -> R.drawable.ui_metro_m3
        else -> R.drawable.ui_metro_m4
    })

    fun hev(number: String): Logo = when (number[1]) {
        '5' -> Logo(R.drawable.ui_hev_h5)
        '6' -> Logo(R.drawable.ui_hev_h6)
        '7' -> Logo(R.drawable.ui_hev_h7)
        '8' -> Logo(R.drawable.ui_hev_h8_h9, 1)
        else -> Logo(R.drawable.ui_hev_h8_h9, 2)
    }

    private data class Key(val logo: Logo, val height: Int)
    private val cache = object : LruCache<Key, Bitmap>(1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }

    /** Decode near display resolution; reuse the small raster across rows and screens. */
    fun cached(logo: Logo, height: Int): Bitmap? = cache.get(Key(logo, height))

    fun render(context: Context, logo: Logo, height: Int): Bitmap {
        val key = Key(logo, height)
        cache.get(key)?.let { return it }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, logo.drawable, options)
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (options.outHeight / (options.inSampleSize * 2) >= height) options.inSampleSize *= 2
        val source = BitmapFactory.decodeResource(context.resources, logo.drawable, options)
        val width = (height * 2.1f).toInt()
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (logo.numberPanel == 0) {
            canvas.drawBitmap(source, null, Rect(0, 0, width, height), paint)
        } else {
            // H8/H9 share one source sheet: retain the H symbol and the chosen number.
            val side = source.height
            val left = logo.numberPanel * (side + (source.width - 3 * side) / 2)
            canvas.drawBitmap(source, Rect(0, 0, side, side), Rect(0, 0, height, height), paint)
            canvas.drawBitmap(source, Rect(left, 0, left + side, side), Rect(width - height, 0, width, height), paint)
        }
        source.recycle()
        cache.put(key, output)
        return output
    }
}
