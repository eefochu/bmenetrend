package hu.app.bmenetrend.data

/** One immutable generation, owned by the ViewModel's main thread; [update] is called on every UI recalculation.
 * Position/countdown updates do not invalidate route metadata or its search index.
 */
internal class LiveRouteCatalogue {
    private var stops: List<TransitRoute>? = null
    private var lines: List<TransitRoute>? = null
    private var bundled: List<TransitRoute>? = null
    private var base = emptyList<TransitRoute>()
    private var previousVehicles: List<Vehicle>? = null
    private var result = emptyList<TransitRoute>()

    fun update(vehicles: List<Vehicle>, stopRoutes: List<TransitRoute>, lineRoutes: List<TransitRoute>, bundledRoutes: List<TransitRoute>): List<TransitRoute> {
        val sourcesChanged = stops !== stopRoutes || lines !== lineRoutes || bundled !== bundledRoutes
        if (sourcesChanged) {
            stops = stopRoutes; lines = lineRoutes; bundled = bundledRoutes
            base = (stopRoutes + lineRoutes + bundledRoutes).distinctBy { it.id }
        }
        val previous = previousVehicles
        // The feed keeps list identity while nothing changed, so most recalculations stop here.
        if (!sourcesChanged && previous === vehicles) return result
        if (!sourcesChanged && previous != null && previous.size == vehicles.size && vehicles.indices.all { i ->
                val a = previous[i]; val b = vehicles[i]
                a.routeId == b.routeId && a.routeNumber == b.routeNumber && a.routeName == b.routeName &&
                    a.destination == b.destination && a.providerId == b.providerId &&
                    a.transportMode == b.transportMode && a.markerColor == b.markerColor
            }) return result
        previousVehicles = vehicles
        val updated = EmmaParser.routeCatalogue(vehicles, base)
        if (updated != result) result = updated
        return result
    }
}
