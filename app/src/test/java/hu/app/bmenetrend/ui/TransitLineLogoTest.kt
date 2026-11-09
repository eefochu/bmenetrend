package hu.app.bmenetrend.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.map.HevLineStyle
import hu.app.bmenetrend.map.MetroLineStyle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransitLineLogoTest {
    @Test fun hevBranchesHaveTheSameModeSymbolButDifferentNumbersAndMapStillUsesOnlyTheNumber() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val h8 = TransitLineLogo.render(context, TransitLineLogo.hev("H8"), 96)
        val h9 = TransitLineLogo.render(context, TransitLineLogo.hev("H9"), 96)
        val left8 = Bitmap.createBitmap(h8, 0, 0, 96, 96)
        val left9 = Bitmap.createBitmap(h9, 0, 0, 96, 96)
        assertTrue(left8.sameAs(left9))
        assertFalse(h8.sameAs(h9))
        assertTrue(h8.width > h8.height * 2)
        val map = HevLineStyle.render(context, HevLineStyle.forLine("H8", "SUBURBAN_RAILWAY")!!, 96)
        assertEquals(96, map.width)
        assertEquals(96, map.height)
        val metro = MetroLineStyle.render(context, MetroLineStyle.forLine("M1", "SUBWAY")!!, 96)
        assertEquals(96, metro.width)
        assertEquals(96, metro.height)
    }

    @Test fun suppliedUiLogosRenderAtDisplayResolution() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val logos = (1..4).map { TransitLineLogo.metro("$it") } + (5..9).map { TransitLineLogo.hev("H$it") }
        val sheet = Bitmap.createBitmap(480, 9 * 112, Bitmap.Config.ARGB_8888)
        sheet.eraseColor(android.graphics.Color.LTGRAY)
        val canvas = Canvas(sheet)
        logos.forEachIndexed { i, logo ->
            val bitmap = TransitLineLogo.render(context, logo, 96)
            assertTrue(bitmap.width in 200..202)
            canvas.drawBitmap(bitmap, 20f, (i * 112 + 8).toFloat(), null)
        }
        val file = File("build/reports/ui/transit-line-logos.png")
        file.parentFile.mkdirs()
        file.outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
