package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.MapBounds
import hu.app.bmenetrend.data.Vehicle

/** Retains nearby vehicles across gestures without repeatedly tiling the nationwide feed. */
internal object VehicleSceneWindow {
    fun next(current: MapBounds?, viewport: MapBounds): MapBounds {
        val halfLon = maxOf(viewport.east - viewport.west, .06)
        val halfLat = maxOf(viewport.north - viewport.south, .04)
        val oversized = current != null && ((current.east - current.west) > halfLon * 5 ||
            (current.north - current.south) > halfLat * 5)
        if (current != null && !oversized) {
            val lonInset = (current.east - current.west) * .15
            val latInset = (current.north - current.south) * .15
            if (viewport.west >= current.west + lonInset && viewport.east <= current.east - lonInset &&
                viewport.south >= current.south + latInset && viewport.north <= current.north - latInset) return current
        }
        val lon = (viewport.west + viewport.east) / 2
        val lat = (viewport.south + viewport.north) / 2
        return MapBounds(lon - halfLon, lat - halfLat, lon + halfLon, lat + halfLat)
    }

    fun select(vehicles: List<Vehicle>, region: MapBounds, selectedId: String?, selectedVehicle: Vehicle? = null): List<Vehicle> {
        val nearby = vehicles.filter { it.id == selectedId ||
            it.position.lon in region.west..region.east && it.position.lat in region.south..region.north }
        // A partial feed refresh must not erase the train the user is inspecting.
        return if (selectedVehicle != null && selectedVehicle.id == selectedId && nearby.none { it.id == selectedId })
            nearby + selectedVehicle else nearby
    }
}
