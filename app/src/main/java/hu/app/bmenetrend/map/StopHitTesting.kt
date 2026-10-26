package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.TransitStop
import kotlin.math.hypot
import kotlin.math.max

/** Nearest-dot selection at close zoom; genuinely overlapping overview rings zoom in directly. */
object StopHitTesting {
    data class Target(val stop: TransitStop,val x: Double,val y: Double,val radius: Double)
    fun candidates(targets: List<Target>, x: Double,y: Double,density: Double): List<TransitStop> {
        require(density.isFinite() && density>0)
        return targets.map { it to hypot(it.x-x,it.y-y) }
            .filter { (target,distance) -> distance <= max(24*density,target.radius+2*density) }
            .sortedWith(compareBy<Pair<Target,Double>> { it.second }.thenBy { it.first.stop.id })
            .map { it.first.stop }.distinctBy { it.id }
    }
    fun needsZoom(targets: List<Target>,x: Double,y: Double,density: Double,zoom: Double): Boolean {
        if(zoom>=StopMapClustering.PLATFORM_ZOOM) return false
        val hits=candidates(targets,x,y,density)
        val first=hits.firstOrNull()?.let { stop -> targets.first { it.stop.id==stop.id } } ?: return false
        return targets.any { it.stop.id!=first.stop.id &&
            hypot(it.x-first.x,it.y-first.y)<it.radius+first.radius+4*density }
    }

}
