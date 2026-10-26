package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.TransitStop

/** Source positions stay exact; overlapping hit areas are resolved by StopHitTesting. */
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
        return nodes.distinctBy { it.stop.id }.sortedBy { it.stop.id }.map { node ->
            Placement(node.stop,node.x,node.y,maxSizeDp*density)
        }
    }
}
