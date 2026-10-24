package hu.app.bmenetrend.data

/** Source stop identity is authoritative; modes keep mixed-station feeds out of a platform card. */
object StopBoardScope {
    fun acceptsStop(selected: TransitStop, sourceId: String?): Boolean {
        if (sourceId == null || sourceId == selected.id.removePrefix("emma:")) return true
        val parent = selected.parentStationId?.removePrefix("emma:") ?: return false
        return selected.providerId == "mav" && selected.id == "emma:${parent}_0" && sourceId.startsWith("${parent}_")
    }
    fun acceptsMode(stopMode: String?, routeMode: String?): Boolean = when (stopMode) {
        null -> true
        "BUS", "COACH" -> routeMode in setOf("BUS", "COACH")
        "RAIL", "TRAMTRAIN" -> routeMode in setOf("RAIL", "TRAMTRAIN")
        else -> stopMode == routeMode
    }
}
