package hu.app.bmenetrend.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.*

/** One bounded, composited marker: overlapping platforms have a clear front/back order. */
internal data class StationSprite(
    val colors: List<Int>,val diameterDp: Int,val focused: Boolean,val selected: Boolean,
    val dark: Boolean,val routeColor: Int,val arrows: List<Int> = emptyList(),
) {
    val key: String ="station-${colors.joinToString("_")}-$diameterDp-$focused-$selected-$dark-${if(focused && colors.size==1) routeColor else 0}-${arrows.joinToString("_")}"
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
        if(radius>=5 && (!selected || colors.size>1)) circle(radius-3,if(focused && dark) 0xFF10160F.toInt() else Color.WHITE)
        return bitmap
    }
}
