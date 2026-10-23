package hu.viabusz.app.map

import android.content.Context
import android.graphics.*

/** Render the numeric panel without modifying the original supplied emblem assets. */
object NumberCircleRenderer {
    fun render(context: Context, drawable: Int, height: Int, panel: Int = 0): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, drawable, options)
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (options.outHeight / (options.inSampleSize * 2) >= height) options.inSampleSize *= 2
        val source = BitmapFactory.decodeResource(context.resources, drawable, options)
        val side = source.height
        val left = if (panel == 0) source.width - side else panel * (side + (source.width - 3 * side) / 2)
        return Bitmap.createBitmap(height, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).drawBitmap(source, Rect(left, 0, left + side, side), Rect(0, 0, height, height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            source.recycle()
        }
    }
}
