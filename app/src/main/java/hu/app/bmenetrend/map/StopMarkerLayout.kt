package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.TransitStop
import kotlin.math.*

/** Equal-size rings with small, deterministic map-aligned offsets; never rearranged by the viewport. */
object StopMarkerLayout {
    data class Node(val stop: TransitStop, val x: Double, val y: Double)

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

    const val DIAMETER_DP=22.0
    const val MAX_OFFSET_DP=24.0
    private const val REFERENCE_WORLD=512.0*65536 // fixed zoom 16, independent of camera/density
    data class Offset(val x: Double=0.0,val y: Double=0.0)

    private val candidates = arrayOf(Offset()) + (1..4).flatMap { ring ->
        (0 until 16).map { step ->
            val angle = step * PI / 8 - PI / 2
            Offset(cos(angle) * ring * MAX_OFFSET_DP / 4, sin(angle) * ring * MAX_OFFSET_DP / 4)
        }
    }.toTypedArray()

    // Native icon-size uses this same continuous zoom curve; every ordinary stop has equal size.
    val sizeStops=listOf(9.0 to 6.0,12.0 to 8.0,13.0 to 12.0,15.0 to DIAMETER_DP,19.0 to DIAMETER_DP)
    fun diameterDp(zoom: Double,highlighted: Boolean=false): Double {
        if(highlighted) return 16.0
        val upper=sizeStops.indexOfFirst { zoom<=it.first }
        if(upper<=0) return if(upper==0) sizeStops.first().second else sizeStops.last().second
        val (a,small)=sizeStops[upper-1];val (b,large)=sizeStops[upper]
        return small+(large-small)*(zoom-a)/(b-a)
    }

    /** Allocate once in world coordinates, before viewport/selection filtering. Never fit to visible gaps. */
    fun offsets(stops: List<TransitStop>,previous: Map<String,Offset> = emptyMap()): Map<String,Offset> {
        val nodes=stops.distinctBy { it.id }.sortedBy { it.id }.map { stop ->
            val lat=Math.toRadians(stop.position.lat.coerceIn(-85.0,85.0))
            Node(stop,(stop.position.lon+180)/360*REFERENCE_WORLD,
                (1-ln(tan(lat)+1/cos(lat))/PI)/2*REFERENCE_WORLD)
        }
        val spacing=DIAMETER_DP+2
        val buckets = LongCellMap<MutableList<Pair<Double, Double>>>(nodes.size)
        fun cellKey(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
        fun cell(x: Double, y: Double) = cellKey(floor(x / spacing).toInt(), floor(y / spacing).toInt())
        fun place(x: Double, y: Double) {
            val key = cell(x, y)
            val bucket = buckets[key] ?: ArrayList<Pair<Double, Double>>().also { buckets[key] = it }
            bucket.add(x to y)
        }
        val neighbours = ArrayList<Pair<Double, Double>>()
        // Incoming provider records are placed around existing markers, never displacing them.
        nodes.forEach { node -> previous[node.stop.id]?.let { offset ->
            val x=node.x+offset.x;val y=node.y+offset.y
            place(x, y)
        } }
        return nodes.associate { node ->
            previous[node.stop.id]?.let { return@associate node.stop.id to it }
            val cx = floor(node.x / spacing).toInt()
            val cy = floor(node.y / spacing).toInt()
            neighbours.clear()
            // A candidate moves at most 24px and overlaps only within another
            // 24px. Cells three steps away cannot contribute any penalty.
            for (dx in -2..2) for (dy in -2..2) {
                buckets[cellKey(cx + dx, cy + dy)]?.let(neighbours::addAll)
            }
            fun overlap(offset: Offset)=neighbours.sumOf { (x,y) ->
                max(0.0,spacing-hypot(node.x+offset.x-x,node.y+offset.y-y)).pow(2)
            }
            var offset=Offset()
            var penalty=overlap(offset)
            if(penalty>.001) for(candidateIndex in 1 until candidates.size) {
                val candidate = candidates[candidateIndex]
                val score=overlap(candidate)
                if(score<penalty-.001 || abs(score-penalty)<.001 && hypot(candidate.x,candidate.y)<hypot(offset.x,offset.y)) {
                    offset=candidate;penalty=score
                }
                if(penalty<.001) break
            }
            val x=node.x+offset.x;val y=node.y+offset.y
            place(x, y)
            node.stop.id to offset
        }
    }
}
