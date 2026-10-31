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
    /** Same distance/ID ordering as candidates(), without materializing or sorting all hits. */
    fun nearest(targets: List<Target>, x: Double, y: Double, density: Double): Target? {
        require(density.isFinite() && density > 0)
        var best: Target? = null
        var bestDistance = Double.POSITIVE_INFINITY
        for (target in targets) {
            val distance = hypot(target.x - x, target.y - y)
            if (distance <= max(24 * density, target.radius + 2 * density) &&
                (best == null || distance < bestDistance || distance == bestDistance && target.stop.id < best.stop.id)) {
                best = target
                bestDistance = distance
            }
        }
        return best
    }

    fun needsZoom(targets: List<Target>, x: Double, y: Double, density: Double, zoom: Double): Boolean {
        if (zoom >= StopMapClustering.PLATFORM_ZOOM) return false
        val firstId = nearest(targets, x, y, density)?.stop?.id ?: return false
        return needsZoom(targets, targets.firstOrNull { it.stop.id == firstId }, density, zoom)
    }

    fun needsZoom(targets: List<Target>, first: Target?, density: Double, zoom: Double): Boolean {
        if (zoom >= StopMapClustering.PLATFORM_ZOOM || first == null) return false
        return targets.any { it.stop.id != first.stop.id &&
            hypot(it.x - first.x, it.y - first.y) < it.radius + first.radius + 4 * density }
    }
}
