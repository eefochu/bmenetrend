package hu.app.bmenetrend.map

/** Keep already uploaded stops through an overview pinch, without retaining stale vehicles or route-focus stops. */
internal object GoogleStopRetention {
    const val LIMIT = 256

    fun keep(previous: Collection<GoogleMarkerSpec>, previousFocusKey: String?, scene: GoogleScene): Set<String> {
        if (!scene.showStops || previousFocusKey != null || scene.focusKey != null) return emptySet()
        val active = scene.markers.mapTo(HashSet()) { it.id }
        return previous.asSequence().filter { it.stops.isNotEmpty() && it.id !in active }
            .map { it.id }.toList().takeLast(LIMIT).toSet()
    }
}
