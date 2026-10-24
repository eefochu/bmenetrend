package hu.app.bmenetrend.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/** Never upload the original 3840px operator artwork for a small scrolling UI badge. */
internal object TransitIconBitmap {
    private data class Key(val drawable: Int, val pixels: Int, val density: Int, val mode: Int)
    private val cache = object : LruCache<Key, Bitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.byteCount
    }

    fun load(context: Context, drawable: Int, pixels: Int): Bitmap {
        val size = pixels.coerceAtLeast(1)
        val key = Key(drawable, size, context.resources.displayMetrics.densityDpi, context.resources.configuration.uiMode)
        return synchronized(cache) {
            cache.get(key) ?: render(context, drawable, size).also { cache.put(key, it) }
        }
    }

    private fun render(context: Context, drawable: Int, size: Int): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, drawable, options)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (options.outWidth > 0 && options.outHeight > 0) {
            options.inJustDecodeBounds = false
            options.inScaled = false
            options.inPreferredConfig = Bitmap.Config.ARGB_8888
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / (options.inSampleSize * 2) >= size) options.inSampleSize *= 2
            val source = requireNotNull(BitmapFactory.decodeResource(context.resources, drawable, options))
            val ratio = size.toFloat() / maxOf(source.width, source.height)
            val width = source.width * ratio
            val height = source.height * ratio
            val left = (size - width) / 2
            val top = (size - height) / 2
            canvas.drawBitmap(source, null, RectF(left, top, left + width, top + height), Paint(Paint.FILTER_BITMAP_FLAG))
            source.recycle() // Only the private decode is recycled, never a bitmap held by Compose/GPU.
        } else {
            requireNotNull(context.getDrawable(drawable)).apply { setBounds(0, 0, size, size); draw(canvas) }
        }
        return bitmap
    }
}

@Composable
internal fun rememberTransitIcon(drawable: Int, size: Dp): ImageBitmap {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    return remember(context.resources, drawable, pixels, configuration.uiMode, configuration.densityDpi) {
        TransitIconBitmap.load(context, drawable, pixels).asImageBitmap()
    }
}
