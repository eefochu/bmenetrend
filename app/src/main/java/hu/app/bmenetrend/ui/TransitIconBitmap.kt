package hu.app.bmenetrend.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private fun key(context: Context, drawable: Int, pixels: Int) =
        Key(drawable, pixels.coerceAtLeast(1), context.resources.displayMetrics.densityDpi, context.resources.configuration.uiMode)

    fun cached(context: Context, drawable: Int, pixels: Int): Bitmap? = synchronized(cache) { cache.get(key(context, drawable, pixels)) }

    fun load(context: Context, drawable: Int, pixels: Int): Bitmap {
        val key = key(context, drawable, pixels)
        synchronized(cache) { cache.get(key) }?.let { return it }
        // Decode outside the lock so a slow first decode never blocks a cache hit on the main thread.
        val rendered = render(context, drawable, key.pixels)
        return synchronized(cache) { cache.get(key) ?: rendered.also { cache.put(key, it) } }
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

/**
 * First use of each artwork decodes a large PNG (~12 ms). That runs off the main thread so list rows
 * and panels never stall a frame; callers give the image a fixed size, so the late icon cannot shift layout.
 */
@Composable
internal fun rememberTransitIcon(drawable: Int, size: Dp): Painter {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    return rememberDecodedPainter(drawable, pixels, configuration.uiMode, configuration.densityDpi,
        cached = { TransitIconBitmap.cached(context, drawable, pixels) }) { TransitIconBitmap.load(context, drawable, pixels) }
}

@Composable
internal fun rememberDecodedPainter(vararg keys: Any?, cached: () -> Bitmap?, decode: () -> Bitmap): Painter {
    val initial = remember(*keys) { cached()?.asImageBitmap() }
    val bitmap by produceState(initial, *keys) {
        if (value == null) value = withContext(Dispatchers.Default) { decode() }.asImageBitmap()
    }
    return remember(bitmap) { bitmap?.let(::BitmapPainter) ?: ColorPainter(Color.Transparent) }
}
