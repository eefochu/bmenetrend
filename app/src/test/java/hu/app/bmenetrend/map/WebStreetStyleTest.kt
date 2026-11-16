package hu.app.bmenetrend.map

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebStreetStyleTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun style(dark: Boolean) = JSONObject(app.assets.open(WebStreetStyle.asset(dark)).bufferedReader().use { it.readText() })

    @Test fun fullWebDetailAndSamePaletteArePresentInBothThemes() {
        for (dark in listOf(false, true)) {
            val style = style(dark)
            assertFalse("Browser font loading cannot be used in Native", style.has("font-faces"))
            val layers = style.getJSONArray("layers")
            assertEquals(105, layers.length())
            val byId = (0 until layers.length()).map { layers.getJSONObject(it) }.associateBy { it.getString("id") }
            assertEquals(layers.length(), byId.size)
            for (id in listOf("building", "landcover_wood", "landcover_grass", "water", "road_motorway", "road_major_rail")) {
                assertTrue("Missing detailed layer $id", byId.containsKey(id))
            }
            if (dark) {
                assertEquals("#1f1f1f", byId.getValue("background").getJSONObject("paint").getString("background-color"))
                assertEquals("#1d2e23", byId.getValue("landcover_wood").getJSONObject("paint").getString("fill-color"))
                assertEquals("#212c24", byId.getValue("landcover_grass").getJSONObject("paint").getString("fill-color"))
                assertEquals("#163f56", byId.getValue("water").getJSONObject("paint").getString("fill-color"))
            }
            // Unknown POI classes must use the supplied fallback instead of disappearing.
            val poi = byId.getValue("poi_r20").getJSONObject("layout").getJSONArray("icon-image")
            assertEquals("*", poi.getJSONArray(2).getString(4))
        }
    }

    @Test fun everyCategoryHasALegibleNativeBadgeAtTheWebSize() {
        for (dark in listOf(false, true)) {
            val images = linkedMapOf<String, Bitmap>()
            try {
                WebStreetStyle.prepareImages(app, dark, images)
                assertEquals(75, images.size)
                assertTrue(images.containsKey("bm-poi:*"))
                for ((id, bitmap) in images) {
                    assertEquals("$id must render at 23 logical pixels", 320, bitmap.density)
                    assertEquals(46, bitmap.width)
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    assertTrue("Missing symbol for $id", pixels.indices.count { index ->
                        val x = index % bitmap.width - 23
                        val y = index / bitmap.width - 23
                        val pixel = pixels[index]
                        x * x + y * y < 256 && Color.alpha(pixel) > 200 &&
                            Color.red(pixel) > 235 && Color.green(pixel) > 235 && Color.blue(pixel) > 235
                    } > 20)
                }
            } finally { images.values.forEach { it.recycle() } }
        }
    }
}
