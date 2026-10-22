package hu.viabusz.app.map

import hu.viabusz.app.data.TransitStop
import java.text.Normalizer
import kotlin.math.*

/** Display-only layout: source platforms and their geographic coordinates remain untouched. */
object StopMarkerLayout {
    data class Node(val stop: TransitStop, val x: Double, val y: Double)
    data class Placement(val stop: TransitStop, val x: Double, val y: Double)
    data class Group(val members: List<Placement>)

    fun layout(nodes: List<Node>, density: Double): List<Group> {
        require(density > 0 && density.isFinite())
        if (nodes.isEmpty()) return emptyList()
        val sorted = nodes.distinctBy { it.stop.id }.sortedBy { it.stop.id }
        val parent = IntArray(sorted.size) { it }
        fun root(i: Int): Int { var n = i; while (parent[n] != n) { parent[n] = parent[parent[n]]; n = parent[n] }; return n }
        val lonScale = 111_320 * cos(Math.toRadians(sorted.first().stop.position.lat))
        val names = sorted.map { normalizedName(it.stop.name) }
        val geoCells = mutableMapOf<Pair<Int, Int>, MutableList<Int>>()
        sorted.forEachIndexed { i, node ->
            val gx = floor(node.stop.position.lon * lonScale / 200).toInt()
            val gy = floor(node.stop.position.lat * 111_320 / 200).toInt()
            for (dx in -1..1) for (dy in -1..1) geoCells[gx + dx to gy + dy].orEmpty().forEach { j ->
                val other = sorted[j]
                val metres = hypot((node.stop.position.lon - other.stop.position.lon) * lonScale,
                    (node.stop.position.lat - other.stop.position.lat) * 111_320)
                val sameParent = node.stop.parentStationId != null && node.stop.parentStationId == other.stop.parentStationId
                val sameName = names[i].isNotEmpty() && names[i] == names[j]
                val samePlace = metres <= 18 || (sameName && metres <= 120) || (sameParent && metres <= 200)
                if (samePlace && hypot(node.x - other.x, node.y - other.y) <= 120 * density) parent[root(i)] = root(j)
            }
            geoCells.getOrPut(gx to gy) { mutableListOf() }.add(i)
        }
        // Reserve the largest stop-ring diameter even when unselected, so selection never reshuffles dots.
        val pitch = 38 * density
        val occupied = mutableMapOf<Pair<Int, Int>, MutableList<Pair<Double, Double>>>()
        fun cell(x: Double, y: Double) = floor(x / pitch).toInt() to floor(y / pitch).toInt()
        fun free(x: Double, y: Double): Boolean {
            val (cx, cy) = cell(x, y)
            for (dx in -1..1) for (dy in -1..1) {
                if (occupied[cx + dx to cy + dy].orEmpty().any { hypot(x - it.first, y - it.second) < pitch - .01 }) return false
            }
            return true
        }
        return sorted.indices.groupBy { root(it) }.values.map { indices ->
            val members = indices.map { sorted[it] }
            val cx = members.map { it.x }.average(); val cy = members.map { it.y }.average()
            val columns = minOf(4, members.size)
            val rows = (members.size + columns - 1) / columns
            // A short snake keeps larger interchanges compact, with one continuous platform chain.
            val offsets = members.indices.map { index ->
                val row = index / columns
                val column = if (row % 2 == 0) index % columns else columns - 1 - index % columns
                (column - (columns - 1) / 2.0) * pitch to (row - (rows - 1) / 2.0) * pitch
            }
            var shiftX = 0.0; var shiftY = 0.0; var ring = 0
            while (!offsets.all { free(cx + it.first + shiftX, cy + it.second + shiftY) }) {
                ring++
                var found = false
                for (dy in -ring..ring) {
                    for (dx in -ring..ring) {
                        if (abs(dx) != ring && abs(dy) != ring) continue
                        if (offsets.all { free(cx + it.first + dx * pitch, cy + it.second + dy * pitch) }) {
                            shiftX = dx * pitch; shiftY = dy * pitch; found = true; break
                        }
                    }
                    if (found) break
                }
                if (found) break
            }
            Group(members.mapIndexed { index, node ->
                val x = cx + offsets[index].first + shiftX; val y = cy + offsets[index].second + shiftY
                occupied.getOrPut(cell(x, y)) { mutableListOf() }.add(x to y)
                Placement(node.stop, x, y)
            })
        }
    }

    private fun normalizedName(name: String): String = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").removePrefix("budapest, ")
        .replace(Regex("\\s+[mh]$"), "").replace(Regex("\\s+"), " ").trim()
}
