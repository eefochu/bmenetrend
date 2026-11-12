package hu.app.bmenetrend.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.ColorUtils
import kotlin.math.*

/** One bounded, composited marker: overlapping platforms have a clear front/back order. */
internal data class StationSprite(
    val colors: List<Int>,val diameterDp: Int,val focused: Boolean,val selected: Boolean,
    val dark: Boolean,val routeColor: Int,val arrows: List<Int> = emptyList(),
    val centerColor: Int = DEFAULT_CENTER_COLOR,
) {
    val key: String ="station-${colors.joinToString("_")}-$diameterDp-$focused-$selected-$dark-${if(focused && colors.size==1) routeColor else 0}-${arrows.joinToString("_")}-${if(diameterDp>=10 && (!selected || colors.size>1)) centerColor else 0}"
    fun render(): Bitmap {
        val scale=3.0
        val radius=diameterDp/2.0
        val extent=radius+if(arrows.isEmpty()) 4 else 8
        val side=ceil(extent*2*scale).toInt()
        val bitmap=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888).apply { density=480 }
        val canvas=Canvas(bitmap)
        val c=side/2.0
        val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        fun circle(r: Double,color: Int) { paint.style=Paint.Style.FILL;paint.color=color;canvas.drawCircle(c.toFloat(),c.toFloat(),(r*scale).toFloat(),paint) }
        fun polygon(points: List<StopDirections.Point>,color: Int,outline: Boolean=false) {
            val path=Path()
            points.forEachIndexed { i,p -> if(i==0) path.moveTo(p.x.toFloat(),p.y.toFloat()) else path.lineTo(p.x.toFloat(),p.y.toFloat()) }
            path.close();paint.style=Paint.Style.FILL;paint.color=color;canvas.drawPath(path,paint)
            if(outline) { paint.style=Paint.Style.STROKE;paint.strokeWidth=(.3*scale).toFloat();paint.color=Color.WHITE;canvas.drawPath(path,paint) }
        }
        arrows.forEach { angle ->
            val heading=Math.toRadians(angle.toDouble())
            polygon(StopDirections.triangle(c,c,radius*scale,sin(heading),-cos(heading),scale),if(dark) Color.WHITE else 0xFF334455.toInt())
        }
        circle(radius+if(focused) 2.5 else 1.0,if(dark) 0xFF111D2C.toInt() else Color.WHITE)
        circle(radius+1.5,Color.WHITE)
        circle(radius,if(colors.size>1) Color.WHITE else if(focused) routeColor else colors.first())
        if(colors.size>1) StopMarkerColors.sectors(colors,c,c,(radius-.75)*scale).forEach { polygon(it.points,it.color,true) }
        if(radius>=5 && (!selected || colors.size>1)) {
            circle(radius-4,Color.WHITE)
            circle(radius-4.5,centerColor)
        }
        return bitmap
    }

    companion object {
        const val DEFAULT_CENTER_COLOR = 0xFF0B1215.toInt()
        fun deepCenterColor(accent: Int): Int = ColorUtils.blendARGB(Color.BLACK, accent, .10f)

        fun arrowKey(diameterDp: Int, dark: Boolean) = "station-arrow-$diameterDp-$dark"

        /**
         * One north-pointing direction arrow on the same canvas, scale and centre as an arrowed
         * sprite, so the map can rotate a single texture per heading instead of baking every
         * heading combination into its own marker bitmap.
         */
        fun renderArrow(diameterDp: Int, dark: Boolean): Bitmap {
            val scale=3.0
            val radius=diameterDp/2.0
            val side=ceil((radius+8)*2*scale).toInt()
            val bitmap=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888).apply { density=480 }
            val c=side/2.0
            val path=Path()
            StopDirections.triangle(c,c,radius*scale,0.0,-1.0,scale).forEachIndexed { i,p ->
                if(i==0) path.moveTo(p.x.toFloat(),p.y.toFloat()) else path.lineTo(p.x.toFloat(),p.y.toFloat())
            }
            path.close()
            Canvas(bitmap).drawPath(path,Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style=Paint.Style.FILL; color=if(dark) Color.WHITE else 0xFF334455.toInt()
            })
            return bitmap
        }
    }
}
