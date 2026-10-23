package hu.viabusz.app.data

/** Saved station identities stay independent from distance and the current map viewport. */
object FavoriteDepartures {
    fun stations(ids: Set<String>, stops: List<TransitStop>): List<NearbyStation> {
        val byId = stops.associateBy { it.id }
        return ids.mapNotNull { id ->
            val stop = byId[id] ?: return@mapNotNull null
            val platforms = EmmaBoardTargets.select(StopClusters.clusterFor(stop, stops))
            NearbyStation(stop, platforms.ifEmpty { listOf(stop) }, 0.0)
        }.sortedBy { it.stop.name }
    }
}
