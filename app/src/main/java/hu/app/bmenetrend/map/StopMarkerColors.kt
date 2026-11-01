package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.TransitStop
import hu.app.bmenetrend.data.TransitRoute
import hu.app.bmenetrend.data.HevSupport
import kotlin.math.*

object StopMarkerColors {
    fun palette(stops: List<TransitStop>, routes: Map<String, TransitRoute> = emptyMap()): List<Int> = stops.flatMap { stop ->
        val serving = stop.routeIds.mapNotNull(routes::get)
        if (serving.isEmpty()) stop.providerIds.map { StopMarkerLayout.color(it, stop.transportMode) }
        else serving.map(::serviceColor) + stop.providerIds.filter { provider -> serving.none { it.providerId == provider } }
            .map { StopMarkerLayout.color(it, stop.transportMode) }
    }.distinct().sorted()

    private val hexColor = Regex("[a-fA-F0-9]{6}")
    private fun serviceColor(route: TransitRoute): Int {
        MetroLineStyle.forLine(route.number, route.transportMode)?.let { return it.color }
        if (route.transportMode == "SUBURBAN_RAILWAY") {
            val color = route.color?.takeIf(hexColor::matches) ?: HevSupport.number(route.number, route.name)?.let(HevSupport::color)
            if (color != null) return (0xFF000000L or color.toLong(16)).toInt()
        }
        return StopMarkerLayout.color(route.providerId, route.transportMode)
    }

    data class Sector(val color: Int,val points: List<StopDirections.Point>)

    /** Simultaneous equal-colour slices; the map's centre circle masks them into a split ring. */
    fun sectors(colors: List<Int>,x: Double,y: Double,radius: Double): List<Sector> {
        require(colors.isNotEmpty() && radius>0 && radius.isFinite())
        val palette=colors.distinct()
        val sweep=2*PI/palette.size
        val steps=ceil(sweep/(PI/24)).toInt()
        val center=StopDirections.Point(x,y)
        return palette.mapIndexed { index,color ->
            val arc=(0..steps).map { step ->
                val angle=-PI/2+index*sweep+step*sweep/steps
                StopDirections.Point(x+cos(angle)*radius,y+sin(angle)*radius)
            }
            Sector(color,listOf(center)+arc+center)
        }
    }
    fun render(colors: List<Int>,radiusDp: Double): android.graphics.Bitmap {
        val scale=3.0
        val side=ceil((radiusDp+2)*2*scale).toInt()
        val bitmap=android.graphics.Bitmap.createBitmap(side,side,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.density=(160*scale).toInt()
        val canvas=android.graphics.Canvas(bitmap)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        sectors(colors,side/2.0,side/2.0,radiusDp*scale).forEach { sector ->
            val path=android.graphics.Path()
            sector.points.forEachIndexed { index,point ->
                if(index==0) path.moveTo(point.x.toFloat(),point.y.toFloat()) else path.lineTo(point.x.toFloat(),point.y.toFloat())
            }
            path.close()
            paint.style=android.graphics.Paint.Style.FILL; paint.color=sector.color
            canvas.drawPath(path,paint)
            paint.style=android.graphics.Paint.Style.STROKE; paint.strokeWidth=(.3*scale).toFloat(); paint.color=android.graphics.Color.WHITE
            canvas.drawPath(path,paint)
        }
        return bitmap
    }

}
