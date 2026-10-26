package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.TransitStop
import kotlin.math.hypot
import kotlin.math.max

/** Visual circles may overlap. Every source platform remains reachable through the tap picker. */
object StopHitTesting {
    data class Target(val stop: TransitStop,val x: Double,val y: Double,val radius: Double)
    fun candidates(targets: List<Target>, x: Double,y: Double,density: Double): List<TransitStop> {
        require(density.isFinite() && density>0)
        return targets.map { it to hypot(it.x-x,it.y-y) }
            .filter { (target,distance) -> distance <= max(24*density,target.radius+2*density) }
            .sortedWith(compareBy<Pair<Target,Double>> { it.second }.thenBy { it.first.stop.id })
            .map { it.first.stop }.distinctBy { it.id }
    }
}
