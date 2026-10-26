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
        assertEquals(Color.WHITE,bitmap.getPixel(c,c))
        assertEquals(0,Color.alpha(bitmap.getPixel(0,0)))
        assertTrue(bitmap.byteCount<40_000)
        bitmap.recycle()
    }
    @Test fun DirectionArrowUsesWorldBearingAndFitsInsideTexture() {
        val bitmap=StationSprite(listOf(green),22,false,false,true,0,listOf(0)).render()
        assertEquals(Color.WHITE,bitmap.getPixel(bitmap.width/2,6))
        assertEquals(0,Color.alpha(bitmap.getPixel(0,0)))
        assertTrue(bitmap.byteCount<60_000)
        bitmap.recycle()
    }
    @Test fun UnusedRouteColoursReuseSameCachedTexture() {
        val sprite=StationSprite(listOf(green,yellow),16,true,false,false,Color.RED)
        assertEquals(sprite.key,sprite.copy(routeColor=Color.BLUE).key)
        val ordinary=sprite.copy(colors=listOf(green),focused=false)
        assertEquals(ordinary.key,ordinary.copy(routeColor=Color.BLUE).key)
        val highlighted=sprite.copy(colors=listOf(green))
        assertNotEquals(highlighted.key,highlighted.copy(routeColor=Color.BLUE).key)
    }
}
