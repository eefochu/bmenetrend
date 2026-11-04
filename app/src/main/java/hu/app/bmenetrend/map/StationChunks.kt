package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.MapBounds
import kotlin.math.floor

/** Loading tiers mirror the restored renderer exactly: rail at 9, road at 12, arrows at 14. */
internal data class StationWindow(val west: Int, val south: Int, val east: Int, val north: Int, val detail: Int) {
    val zoom: Double get() = when (detail) { 0 -> 0.0; 1 -> 9.0; 2 -> 12.0; else -> 14.0 }
    val bounds: MapBounds get() = MapBounds(west * CELL, south * CELL, (east + 1) * CELL, (north + 1) * CELL)
    companion object {
        private const val CELL = .005
        fun cell(value: Double) = floor(value / CELL).toInt()
        private fun tier(zoom: Double) = when { zoom < 9 -> 0; zoom < 12 -> 1; zoom < 14 -> 2; else -> 3 }
        fun around(bounds: MapBounds, zoom: Double) = StationWindow(cell(bounds.west)-2, cell(bounds.south)-2,
            cell(bounds.east)+2, cell(bounds.north)+2, tier(zoom))
        fun next(previous: StationWindow?, bounds: MapBounds, zoom: Double): StationWindow {
            if (previous != null && previous.detail == tier(zoom) &&
                previous.east-previous.west <= (cell(bounds.east)-cell(bounds.west)+4)*2 &&
                previous.north-previous.south <= (cell(bounds.north)-cell(bounds.south)+4)*2 &&
                cell(bounds.west) >= previous.west+1 && cell(bounds.east) <= previous.east-1 &&
                cell(bounds.south) >= previous.south+1 && cell(bounds.north) <= previous.north-1) return previous
            return around(bounds, zoom)
        }
    }
}

/** Local cell lookup avoids rescanning the country on every pan; broad views visit occupied cells. */
internal class StationChunkIndex<T>(items: List<T>, position: (T)->GeoPoint) {
    private class Cell<T>(val x: Int, val y: Int, val items: MutableList<T> = ArrayList())
    private val buckets = LongCellMap<Cell<T>>(items.size)
    private val occupied = ArrayList<Cell<T>>()
    init {
        for (item in items) {
            val point = position(item)
            val x = StationWindow.cell(point.lon); val y = StationWindow.cell(point.lat)
            val key = key(x,y)
            val bucket = buckets[key] ?: Cell<T>(x,y).also { buckets[key]=it; occupied.add(it) }
            bucket.items.add(item)
        }
    }
    fun query(window: StationWindow): List<T> {
        val result = ArrayList<T>()
        val count = (window.east.toLong()-window.west+1)*(window.north.toLong()-window.south+1)
        if (count <= occupied.size) {
            for (y in window.south..window.north) for (x in window.west..window.east)
                buckets[key(x,y)]?.let { result.addAll(it.items) }
        } else for (cell in occupied) if (cell.x in window.west..window.east && cell.y in window.south..window.north)
            result.addAll(cell.items)
        return result
    }
    private fun key(x: Int,y: Int) = (y.toLong() shl 32) xor (x.toLong() and 0xffffffffL)
}
