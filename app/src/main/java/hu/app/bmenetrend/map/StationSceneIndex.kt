package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.MapBounds
import hu.app.bmenetrend.data.TransitStop

/** Index once; expensive platform grouping/offsets only run for the retained map region. */
internal class StationSceneIndex(private val all: List<TransitStop>) {
    private val parents = all.mapNotNull { it.parentStationId }.toSet()
    private val rail = all.filter(RailStationMarkers::isRail)
    private val roads = StationChunkIndex(all.filterNot { RailStationMarkers.isRail(it) || it.station && it.id in parents }) { it.position }

    fun select(region: MapBounds?, focused: Boolean): List<TransitStop> {
        if (focused) return all
        if (region == null) return rail
        // Whole cells plus two neighbouring cells retain platform matches and collision
        // neighbours across the visible region boundary (both reach less than 100m).
        return rail + roads.query(StationWindow.around(region, 16.0))
    }
}
