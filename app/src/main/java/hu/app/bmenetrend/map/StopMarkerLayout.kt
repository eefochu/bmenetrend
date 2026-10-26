package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.TransitStop
import kotlin.math.*

/** Overview rings stay on the route; close-zoom dots fan out with connectors to their source positions. */
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

    data class Obstacle(val x: Double,val y: Double,val radiusPx: Double)
    data class Bounds(val left: Double,val top: Double,val right: Double,val bottom: Double) {
        fun contains(x: Double,y: Double,radius: Double) = x-radius>=left && x+radius<=right && y-radius>=top && y+radius<=bottom
    }

    fun layout(nodes: List<Node>,density: Double,maxSizeDp: Double=22.0,zoom: Double=13.0,
        obstacles: List<Obstacle> = emptyList(),bounds: Bounds? = null): List<Placement> {
        require(density>0 && density.isFinite() && maxSizeDp>=6)
        val ordered=nodes.distinctBy { it.stop.id }.sortedBy { it.stop.id }
        val size=maxSizeDp*density
        if(zoom<StopMapClustering.PLATFORM_ZOOM) return ordered.map { Placement(it.stop,it.x,it.y,size) }
        val radius=size/2
        val gap=18*density
        val cell=size+gap
        val occupied=mutableMapOf<Pair<Int,Int>,MutableList<Obstacle>>()
        fun key(x: Double,y: Double)=floor(x/cell).toInt() to floor(y/cell).toInt()
        fun add(dot: Obstacle) { occupied.getOrPut(key(dot.x,dot.y)) { mutableListOf() }.add(dot) }
        obstacles.forEach(::add)
        val reach=ceil((radius+maxOf(radius,obstacles.maxOfOrNull { it.radiusPx } ?: 0.0)+gap)/cell).toInt()
        fun free(x: Double,y: Double): Boolean {
            if(bounds!=null && !bounds.contains(x,y,radius)) return false
            val (cx,cy)=key(x,y)
            for(dx in -reach..reach) for(dy in -reach..reach) {
                if(occupied[cx+dx to cy+dy].orEmpty().any { hypot(it.x-x,it.y-y)<it.radiusPx+radius+gap }) return false
            }
            return true
        }
        return ordered.map { node ->
            var x=node.x; var y=node.y
            // Leave points behind screen controls in place; do not pull hidden stops into view.
            if(bounds==null || bounds.contains(x,y,radius)) {
                if(!free(x,y)) {
                    var found=false
                    for(ring in 1..128) {
                        val distance=ring*cell/2
                        val samples=maxOf(8,ceil(2*PI*distance/(cell/2)).toInt())
                        for(i in 0 until samples) {
                            val angle=2*PI*i/samples-PI/2
                            val candidateX=node.x+cos(angle)*distance
                            val candidateY=node.y+sin(angle)*distance
                            if(free(candidateX,candidateY)) { x=candidateX; y=candidateY; found=true; break }
                        }
                        if(found) break
                        // If the viewport is full, extending the fan beyond it is still reachable by panning.
                        if(ring==128) {
                            x=node.x+cell*(ordered.indexOf(node)+1); y=node.y
                        }
                    }
                }
                add(Obstacle(x,y,radius))
            }
            Placement(node.stop,x,y,size)
        }
    }
}
