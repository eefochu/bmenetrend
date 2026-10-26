package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.TransitStop
import kotlin.math.*

/** Source coordinates never move: crowded markers shrink, isolated platforms retain their larger ring. */
object StopMarkerLayout {
    data class Node(val stop: TransitStop, val x: Double, val y: Double)
    data class Placement(val stop: TransitStop, val x: Double, val y: Double, val sizePx: Double)

    fun color(provider: String, mode: String?): Int = when {
        mode == "TRAM" -> 0xFFFFD800.toInt()
        mode == "TROLLEYBUS" -> 0xFFE31B23.toInt()
        mode == "RAIL" || mode == "TRAMTRAIN" -> 0xFF0066AA.toInt()
        provider == "hev" || mode == "SUBURBAN_RAILWAY" -> 0xFF267D44.toInt()
        provider == "volan" -> 0xFFF9B900.toInt()
        provider == "bkk" -> 0xFF199DCF.toInt()
        provider == "viabusz" -> 0xFF205C48.toInt()
        provider == "kanyargo" -> 0xFF6750A4.toInt()
        provider == "homm" -> 0xFFBF5700.toInt()
        else -> 0xFF40576A.toInt()
    }

    fun layout(nodes: List<Node>,density: Double,maxSizeDp: Double=22.0,highlighted: Boolean=false): List<Placement> {
        require(density>0 && density.isFinite() && maxSizeDp>=6)
        val ordered=nodes.distinctBy { it.stop.id }.sortedBy { it.stop.id }
        val maximum=maxSizeDp*density
        if(highlighted) return ordered.map { Placement(it.stop,it.x,it.y,maximum) }
        val gap=3*density
        // A spatial index limits comparisons to markers close enough to constrain the requested size.
        val buckets=ordered.groupBy { floor(it.x/maximum).toInt() to floor(it.y/maximum).toInt() }
        return ordered.map { node ->
            val cx=floor(node.x/maximum).toInt(); val cy=floor(node.y/maximum).toInt()
            var nearest=maximum+gap
            for(dx in -2..2) for(dy in -2..2) {
                buckets[cx+dx to cy+dy].orEmpty().forEach { other ->
                    if(other.stop.id!=node.stop.id) nearest=minOf(nearest,hypot(other.x-node.x,other.y-node.y))
                }
            }
            Placement(node.stop,node.x,node.y,(nearest-gap).coerceIn(6*density,maximum))
        }
    }
}
