package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.TransitStop
import kotlin.math.*

/** Compact at overview zoom; close platforms get a bounded four-dp nudge and readable overlapping rings. */
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

    fun layout(nodes: List<Node>,density: Double,maxSizeDp: Double=22.0,highlighted: Boolean=false,zoom: Double=0.0): List<Placement> {
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
            var neighbour: Node?=null
            for(dx in -2..2) for(dy in -2..2) {
                buckets[cx+dx to cy+dy].orEmpty().forEach { other ->
                    if(other.stop.id!=node.stop.id) {
                        val distance=hypot(other.x-node.x,other.y-node.y)
                        if(distance<nearest) { nearest=distance;neighbour=other }
                    }
                }
            }
            if(zoom>=15 && neighbour!=null) {
                val overlap=3*density
                val size=floor((nearest+11*density).coerceIn(minOf(10*density,maximum),maximum)/density)*density
                val nudge=((size-overlap-nearest)/2).coerceIn(0.0,4*density)
                val other=neighbour!!
                val ux=if(nearest>.001) (node.x-other.x)/nearest else 0.0
                val uy=if(nearest>.001) (node.y-other.y)/nearest else if(node.stop.id<other.stop.id) -1.0 else 1.0
                Placement(node.stop,node.x+ux*nudge,node.y+uy*nudge,size)
            } else Placement(node.stop,node.x,node.y,floor((nearest-gap).coerceIn(6*density,maximum)/density)*density)
        }
    }
}
