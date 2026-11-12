package hu.app.bmenetrend.map

import kotlin.math.hypot
import kotlin.math.max

/**
 * Decides whether a tap meant a vehicle or a stop, with the same rules on both map engines.
 *
 * A tap inside a drawn marker is a direct hit; among direct hits the one tapped closest to its
 * centre (relative to its size) wins, so a tap on a bus parked at a stop picks whichever circle the
 * finger is more clearly on. Only when nothing is hit directly does a nearby marker within the
 * finger slop count, nearest edge first. Vehicles and stops get the same slop.
 */
object MapTapResolver {
    sealed interface Hit
    data class VehicleHit(val id: String) : Hit
    data class StopHit(val target: StopHitTesting.Target) : Hit

    data class VehicleTarget(val id: String, val x: Double, val y: Double, val radius: Double)

    /** Extra reach around a marker's edge for a tap that just misses it. */
    const val SLOP_DP = 10.0
    /** Small markers are still this easy to hit (radius in dp). */
    const val MIN_REACH_DP = 22.0

    fun resolve(
        x: Double, y: Double, density: Double,
        vehicles: List<VehicleTarget>, stops: List<StopHitTesting.Target>,
    ): Hit? {
        require(density.isFinite() && density > 0)
        var direct: Hit? = null
        var directScore = Double.POSITIVE_INFINITY
        var near: Hit? = null
        var nearScore = Double.POSITIVE_INFINITY
        fun consider(hit: Hit, tx: Double, ty: Double, radius: Double) {
            val distance = hypot(tx - x, ty - y)
            val r = max(radius, 1.0)
            if (distance <= r) {
                // Relative depth into the circle. Vehicles are considered first and draw on top,
                // so a strict comparison lets them keep exact ties.
                val score = distance / r
                if (score < directScore) { direct = hit; directScore = score }
            } else if (distance <= max(r + SLOP_DP * density, MIN_REACH_DP * density)) {
                val score = distance - r
                if (score < nearScore) { near = hit; nearScore = score }
            }
        }
        for (vehicle in vehicles) consider(VehicleHit(vehicle.id), vehicle.x, vehicle.y, vehicle.radius)
        for (stop in stops.sortedBy { it.stop.id }) consider(StopHit(stop), stop.x, stop.y, stop.radius)
        return direct ?: near
    }
}
