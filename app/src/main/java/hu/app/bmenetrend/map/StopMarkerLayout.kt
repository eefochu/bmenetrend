package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.TransitStop
import kotlin.math.*

/** Individual source platforms with consistent sizing and the smallest collision offset. */
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

    fun layout(nodes: List<Node>, density: Double, maxSizeDp: Double = 22.0): List<Placement> {
        require(density > 0 && density.isFinite() && maxSizeDp >= 6)
        val sorted = nodes.distinctBy { it.stop.id }.sortedBy { it.stop.id }
        val cellSize = (maxSizeDp + 2) * density
        fun cell(x: Double, y: Double) = floor(x / cellSize).toInt() to floor(y / cellSize).toInt()
        val occupied = mutableMapOf<Pair<Int, Int>, MutableList<Placement>>()
        return sorted.map { node ->
            val size = maxSizeDp * density
            fun free(x: Double, y: Double): Boolean {
                val (gx, gy) = cell(x, y)
                for (dx in -1..1) for (dy in -1..1) {
                    if (occupied[gx + dx to gy + dy].orEmpty().any { hypot(x - it.x, y - it.y) < (size + it.sizePx) / 2 + density }) return false
                }
                return true
            }
            var x = node.x; var y = node.y; var ring = 0
            while (!free(x, y)) {
                ring++
                val radius = ring * 2 * density
                val count = max(8, ring * 8)
                val candidate = (0 until count).map { angle ->
                    val theta = angle * 2 * PI / count
                    node.x + cos(theta) * radius to node.y + sin(theta) * radius
                }.firstOrNull { free(it.first, it.second) }
                if (candidate != null) { x = candidate.first; y = candidate.second; break }
            }
            Placement(node.stop, x, y, size).also { occupied.getOrPut(cell(x, y)) { mutableListOf() }.add(it) }
        }
    }
}
