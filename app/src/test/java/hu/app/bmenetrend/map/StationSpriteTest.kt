package hu.app.bmenetrend.map

import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StationSpriteTest {
    private val green=StopMarkerLayout.color("viabusz",null)
    private val yellow=StopMarkerLayout.color("volan",null)
    @Test fun completeSharedMarkerKeepsBothColoursAndHollowCentreInOneSmallTexture() {
        val bitmap=StationSprite(listOf(green,yellow),22,false,false,false,0).render()
        val c=bitmap.width/2
        assertEquals(480,bitmap.density)
        assertEquals(green,bitmap.getPixel(c+27,c))
        assertEquals(yellow,bitmap.getPixel(c-27,c))
        assertEquals(StationSprite.DEFAULT_CENTER_COLOR,bitmap.getPixel(c,c))
        assertEquals(Color.WHITE,bitmap.getPixel(c+35,c))
        assertEquals(Color.WHITE,bitmap.getPixel(c-35,c))
        assertEquals(0,Color.alpha(bitmap.getPixel(0,0)))
        assertTrue(bitmap.byteCount<40_000)
        bitmap.recycle()
    }
    @Test fun suppliedDeepCenterColorAndWhiteOuterRimRenderAccurately() {
        val accent = 0xFF199DCF.toInt()
        val deepCenter = StationSprite.deepCenterColor(accent)
        val bitmap = StationSprite(
            colors = listOf(green, yellow),
            diameterDp = 22,
            focused = false,
            selected = false,
            dark = false,
            routeColor = 0,
            centerColor = deepCenter,
        ).render()
        val c = bitmap.width / 2
        assertEquals(deepCenter, bitmap.getPixel(c, c))
        assertEquals(Color.WHITE, bitmap.getPixel(c + 35, c))
        assertEquals(Color.WHITE, bitmap.getPixel(c - 35, c))
        assertEquals(green, bitmap.getPixel(c + 27, c))
        assertEquals(yellow, bitmap.getPixel(c - 27, c))
        bitmap.recycle()
    }
    @Test fun DirectionArrowUsesWorldBearingAndFitsInsideTexture() {
        val darkBitmap=StationSprite(listOf(green),22,false,false,true,0,listOf(0)).render()
        assertEquals(Color.WHITE,darkBitmap.getPixel(darkBitmap.width/2,6))
        assertEquals(0,Color.alpha(darkBitmap.getPixel(0,0)))
        assertTrue(darkBitmap.byteCount<60_000)
        darkBitmap.recycle()

        val lightBitmap=StationSprite(listOf(green),22,focused=false,selected=false,dark=false,routeColor=0,arrows=listOf(0)).render()
        assertEquals(0xFF334455.toInt(),lightBitmap.getPixel(lightBitmap.width/2,6))
        assertEquals(0,Color.alpha(lightBitmap.getPixel(0,0)))
        assertTrue(lightBitmap.byteCount<60_000)
        lightBitmap.recycle()
    }
    @Test fun selectedSingleMarkerKeepsRouteColorWhileSelectedMultiRendersHollowCenter() {
        val customCenter = StationSprite.deepCenterColor(0xFF199DCF.toInt())

        // Selected single route marker: center must keep route color (green), NOT centerColor
        val singleSelected = StationSprite(
            colors = listOf(green),
            diameterDp = 22,
            focused = false,
            selected = true,
            dark = false,
            routeColor = 0,
            centerColor = customCenter,
        ).render()
        val c1 = singleSelected.width / 2
        assertEquals(green, singleSelected.getPixel(c1, c1))
        assertEquals(Color.WHITE, singleSelected.getPixel(c1 + 35, c1))
        singleSelected.recycle()

        // Selected multi-route marker: hollow center IS rendered with customCenter
        val multiSelected = StationSprite(
            colors = listOf(green, yellow),
            diameterDp = 22,
            focused = false,
            selected = true,
            dark = false,
            routeColor = 0,
            centerColor = customCenter,
        ).render()
        val c2 = multiSelected.width / 2
        assertEquals(customCenter, multiSelected.getPixel(c2, c2))
        assertEquals(green, multiSelected.getPixel(c2 + 27, c2))
        assertEquals(yellow, multiSelected.getPixel(c2 - 27, c2))
        assertEquals(Color.WHITE, multiSelected.getPixel(c2 + 35, c2))
        multiSelected.recycle()
    }
    @Test fun centerColorInvalidatesKeyWhenRenderedButSharesWhenUnusedOrNotRendered() {
        val c1 = StationSprite.DEFAULT_CENTER_COLOR
        val c2 = StationSprite.deepCenterColor(0xFF199DCF.toInt())

        // Multi-color rendered: changing centerColor MUST change key
        val multi1 = StationSprite(listOf(green, yellow), 22, focused = false, selected = false, dark = false, routeColor = 0, centerColor = c1)
        val multi2 = StationSprite(listOf(green, yellow), 22, focused = false, selected = false, dark = false, routeColor = 0, centerColor = c2)
        assertNotEquals(multi1.key, multi2.key)

        // Unselected single-color rendered: changing centerColor MUST change key
        val singleUnselected1 = StationSprite(listOf(green), 22, focused = false, selected = false, dark = false, routeColor = 0, centerColor = c1)
        val singleUnselected2 = StationSprite(listOf(green), 22, focused = false, selected = false, dark = false, routeColor = 0, centerColor = c2)
        assertNotEquals(singleUnselected1.key, singleUnselected2.key)

        // Selected single-color: center is NOT rendered, so key remains the same (centerColor has no visual effect)
        val singleSelected1 = StationSprite(listOf(green), 22, focused = false, selected = true, dark = false, routeColor = 0, centerColor = c1)
        val singleSelected2 = StationSprite(listOf(green), 22, focused = false, selected = true, dark = false, routeColor = 0, centerColor = c2)
        assertEquals(singleSelected1.key, singleSelected2.key)
    }
    @Test fun UnusedRouteColoursReuseSameCachedTexture() {
        val sprite=StationSprite(listOf(green,yellow),16,true,false,false,Color.RED)
        assertEquals(sprite.key,sprite.copy(routeColor=Color.BLUE).key)
        val ordinary=sprite.copy(colors=listOf(green),focused=false)
        assertEquals(ordinary.key,ordinary.copy(routeColor=Color.BLUE).key)
        val highlighted=sprite.copy(colors=listOf(green))
        assertNotEquals(highlighted.key,highlighted.copy(routeColor=Color.BLUE).key)
    }
    @Test fun stationNewColoredRingPixel_previouslyCenterNowPalette_whileCenterWhiteOuterAndArrowsPreserved() {
        // radius - 4 vs radius - 3 gives a thicker colored ring:
        // for diameterDp = 22, radius is 11dp (33px at 3x scale).
        // Center circle radius is now (11 - 4) = 7dp (21px), previously (11 - 3) = 8dp (24px).
        // Pixel at distance 23px (c + 23) was previously inside center circle (23 < 24 -> centerColor),
        // but now is outside center circle (23 > 21) and inside colored ring -> palette color!
        val bitmap = StationSprite(listOf(green, yellow), 22, false, false, false, 0).render()
        val c = bitmap.width / 2
        // Distance 23px from center: now palette colors (thicker colored ring)
        assertEquals("Pixel at c + 23 was previously centerColor, now green palette", green, bitmap.getPixel(c + 23, c))
        assertEquals("Pixel at c - 23 was previously centerColor, now yellow palette", yellow, bitmap.getPixel(c - 23, c))
        // Distance 27px from center: still palette colors
        assertEquals(green, bitmap.getPixel(c + 27, c))
        assertEquals(yellow, bitmap.getPixel(c - 27, c))
        // Center pixel (c, c): deep center color is preserved
        assertEquals(StationSprite.DEFAULT_CENTER_COLOR, bitmap.getPixel(c, c))
        // Outer ring: white ring preserved at c +- 35
        assertEquals(Color.WHITE, bitmap.getPixel(c + 35, c))
        assertEquals(Color.WHITE, bitmap.getPixel(c - 35, c))
        bitmap.recycle()

        // Direction arrows still preserved with the thicker colored ring
        val arrowBitmap = StationSprite(listOf(green), 22, focused = false, selected = false, dark = true, routeColor = 0, arrows = listOf(0)).render()
        val cArrow = arrowBitmap.width / 2
        assertEquals(Color.WHITE, arrowBitmap.getPixel(cArrow, 6))
        assertEquals(StationSprite.DEFAULT_CENTER_COLOR, arrowBitmap.getPixel(cArrow, cArrow))
        arrowBitmap.recycle()
    }
}
