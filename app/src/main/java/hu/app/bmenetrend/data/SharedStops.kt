package hu.app.bmenetrend.data

/** Reviewed physical platforms, not proximity clustering. Opposite platforms stay separate.
 * Source coordinates and the user's station identification are documented in docs/KOZOS-MEGALLOK.md.
 * The railway station's arrival and departure source IDs share one passenger-facing platform;
 * trip boundaries still determine whether a time is a boarding departure.
 */
object SharedStops {
    private val aliases = mapOf(
        "kanyargo:29" to "1", "kanyargo:30" to "1",
        "kanyargo:50" to "2", "kanyargo:48" to "67",
        "kanyargo:51" to "10", "kanyargo:42" to "26",
    )
    fun canonicalId(sourceId: String): String = aliases[sourceId] ?: sourceId

    /** Also works with an unmerged single-provider network used by import verification. */
    fun resolve(network: TransitNetwork, sourceId: String): String =
        network.stopIds[sourceId] ?: sourceId

    fun merge(network: TransitNetwork): TransitNetwork {
        val stops = network.stops.groupBy { canonicalId(it.id) }.map { (id, members) ->
            val primary = members.firstOrNull { it.id == id } ?: members.first()
            primary.copy(
                id = id,
                routeIds = members.flatMap { it.routeIds }.distinct(),
                providerIds = members.flatMap { it.providerIds }.distinct(),
                aliases = members.flatMap { listOf(it.name) + it.aliases }.distinct().filterNot { it == primary.name },
                sourceStopIds = members.flatMap { it.sourceStopIds }.distinct(),
            )
        }
        val routes = network.routes.map { route ->
            if (route.directions.none { direction -> direction.stopIds.any(aliases::containsKey) }) route
            else route.copy(directions = route.directions.map { direction ->
                if (direction.stopIds.none(aliases::containsKey)) direction
                else direction.copy(stopIds = direction.stopIds.map(::canonicalId))
            })
        }
        val trips = network.trips.map { trip ->
            if (trip.stopTimes.none { it.stopId in aliases }) trip
            else trip.copy(stopTimes = trip.stopTimes.map { time ->
                val id = canonicalId(time.stopId)
                if (id == time.stopId) time else time.copy(stopId = id)
            })
        }
        return network.copy(stops = stops, routes = routes, trips = trips)
    }
}
