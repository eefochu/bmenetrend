package hu.app.bmenetrend.data

/**
 * Catalogue merges that return the original list when they would not change its contents.
 *
 * Stop and route catalogues are nationwide. Their list identity keys catalogue indexes, map
 * clustering, marker layout, stop bearings and search indexes, so a content-equal copy made on
 * a periodic refresh redoes all of that work for nothing.
 */
internal object CatalogueMerge {
    /** Same contents as `existing.map { replacements[it.id] ?: it }`. */
    fun replaceStops(existing: List<TransitStop>, replacements: Map<String, TransitStop>): List<TransitStop> {
        if (replacements.isEmpty()) return existing
        val changed = existing.any { stop -> replacements[stop.id].let { it != null && it != stop } }
        return if (changed) existing.map { replacements[it.id] ?: it } else existing
    }

    /**
     * Same contents as building `existing.associateBy { it.id }`, storing each incoming stop for
     * which [accept] (current value, incoming) is true, and returning the map's values.
     */
    inline fun upsertStops(existing: List<TransitStop>, incoming: Iterable<TransitStop>,
                           accept: (current: TransitStop?, incoming: TransitStop) -> Boolean): List<TransitStop> {
        val merged = LinkedHashMap<String, TransitStop>(existing.size + 16)
        for (stop in existing) merged[stop.id] = stop
        var changed = merged.size != existing.size
        for (stop in incoming) {
            val current = merged[stop.id]
            if (accept(current, stop) && current != stop) {
                merged[stop.id] = stop
                changed = true
            }
        }
        return if (changed) merged.values.toList() else existing
    }

    /**
     * Same route set as `(incoming + existing).distinctBy { it.id }` when [preferIncoming], otherwise
     * `(existing + incoming).distinctBy { it.id }`. [existingById] must index the distinct [existing].
     */
    fun mergeRoutes(existing: List<TransitRoute>, incoming: List<TransitRoute>, preferIncoming: Boolean,
                    existingById: Map<String, TransitRoute> = existing.associateBy { it.id }): List<TransitRoute> {
        if (incoming.isEmpty()) return existing
        val unchanged = if (preferIncoming) {
            val seen = HashSet<String>(incoming.size)
            // Only the first incoming record of an id survives distinctBy.
            incoming.all { route -> !seen.add(route.id) || existingById[route.id] == route }
        } else incoming.all { it.id in existingById }
        if (unchanged) return existing
        return if (preferIncoming) (incoming + existing).distinctBy { it.id } else (existing + incoming).distinctBy { it.id }
    }
}
