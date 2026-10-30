package hu.app.bmenetrend.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TransitIconBitmapTest {
    @Test fun largeBusArtworkIsSampledToBadgeSizeBeforeItCanReachTheGpu() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val bitmap=TransitIconBitmap.load(context,R.drawable.vehicle_bkk_bus,56)
        assertEquals(56,bitmap.width)
        assertEquals(56,bitmap.height)
        assertEquals(56*56*4,bitmap.byteCount)
        assertEquals(Bitmap.Config.ARGB_8888,bitmap.config)
        assertFalse(bitmap.isRecycled)
    }
    @Test fun repeatedScrollingBadgesShareTheSmallBitmapRatherThanDecodingAnotherLargeTexture() {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val first=TransitIconBitmap.load(context,R.drawable.vehicle_bkk_bus,56)
        val repeated=TransitIconBitmap.load(context,R.drawable.vehicle_bkk_bus,56)
        assertSame(first,repeated)
        assertNotSame(first,TransitIconBitmap.load(context,R.drawable.vehicle_bkk_bus,84))
        assertFalse(first.isRecycled)
    }
    @Test fun vectorLocalBusIconUsesTheSameBoundedSoftwareBitmapPath() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        for (resId in listOf(R.drawable.vehicle_viabusz_bus, R.drawable.vehicle_homm_bus, R.drawable.vehicle_kanyargo_bus)) {
            val bitmap = TransitIconBitmap.load(context, resId, 56)
            assertEquals(56, bitmap.width)
            assertEquals(56, bitmap.height)
            assertEquals(56 * 56 * 4, bitmap.byteCount)
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
            assertFalse(bitmap.isRecycled)
        }
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun boundedBitmapLoadsForHommAndKanyargoDrawablesWithNativeColorRendering() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val homm = TransitIconBitmap.load(context, R.drawable.vehicle_homm_bus, 56)
        assertEquals(56, homm.width)
        assertEquals(56, homm.height)
        assertEquals(56 * 56 * 4, homm.byteCount)
        assertEquals(Bitmap.Config.ARGB_8888, homm.config)
        assertFalse(homm.isRecycled)
        assertSame(homm, TransitIconBitmap.load(context, R.drawable.vehicle_homm_bus, 56))

        val kanyargo = TransitIconBitmap.load(context, R.drawable.vehicle_kanyargo_bus, 56)
        assertEquals(56, kanyargo.width)
        assertEquals(56, kanyargo.height)
        assertEquals(56 * 56 * 4, kanyargo.byteCount)
        assertEquals(Bitmap.Config.ARGB_8888, kanyargo.config)
        assertFalse(kanyargo.isRecycled)
        assertSame(kanyargo, TransitIconBitmap.load(context, R.drawable.vehicle_kanyargo_bus, 56))

        assertNotSame(homm, kanyargo)

        // Native graphics sampled background pixels: top center of circle above bus body (28, 6)
        assertEquals(0xFFBF5700.toInt(), homm.getPixel(28, 6))
        assertEquals(0xFF6750A4.toInt(), kanyargo.getPixel(28, 6))
    }
    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun splitStopIconsKeepBothOperatorColoursAndDensityIndependentSize() {
        val green=hu.app.bmenetrend.map.StopMarkerLayout.color("viabusz",null)
        val yellow=hu.app.bmenetrend.map.StopMarkerLayout.color("volan",null)
        val bitmap=hu.app.bmenetrend.map.StopMarkerColors.render(listOf(green,yellow),11.0)
        val center=bitmap.width/2
        assertEquals(480,bitmap.density)
        assertEquals(26,bitmap.width/3)
        assertEquals(green,bitmap.getPixel(center+24,center))
        assertEquals(yellow,bitmap.getPixel(center-24,center))
        assertEquals(0,android.graphics.Color.alpha(bitmap.getPixel(0,0)))
        assertTrue(bitmap.byteCount<30_000)
        bitmap.recycle()
    }

}
