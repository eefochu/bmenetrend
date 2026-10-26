package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.*

/** Line identity is provider-scoped; opposite directions share it, unrelated same-number lines do not. */
data class LineMapFocus(
    val key: String,
    val routeId: String?,
    val providerId: String,
    val number: String?,
    val mode: String?,
    val paths: List<List<GeoPoint>>,
    val stops: List<TransitStop>,
    val selectedVehicleId: String? = null,
    val contextStops: List<TransitStop> = emptyList(),
    val contextPaths: List<List<GeoPoint>> = emptyList(),
) {
    val displayStops: List<TransitStop> = (stops+contextStops).distinctBy { it.id }
    val displayPaths: List<List<GeoPoint>> = (paths+contextPaths).distinct()

    fun includes(vehicle: Vehicle): Boolean = when {
        vehicle.id == selectedVehicleId -> true
        vehicle.providerId != providerId -> false
        routeId != null -> vehicle.routeId == routeId
        number != null -> vehicle.routeNumber == number && vehicle.transportMode == mode
        else -> false
    }

    companion object {
        fun create(
            vehicle: Vehicle?,
            boarding: BoardingSelection?,
            routes: List<TransitRoute>,
            details: Map<String, LineDetails>,
            journey: VehicleJourney?,
            allStops: List<TransitStop>
        ): LineMapFocus? {
            if (vehicle == null && boarding == null) return null
            val routeId = vehicle?.routeId ?: boarding?.routeId
            val route = routes.find { it.id == routeId }
            val provider = vehicle?.providerId ?: route?.providerId ?: return null
            val number = vehicle?.routeNumber ?: route?.number
            val mode = vehicle?.transportMode ?: route?.transportMode

            val line = details[routeId]
            val patterns = line?.patterns.orEmpty().let { all -> all.filter { it.serviceCount > 0 }.ifEmpty { all } }

            val stops: List<TransitStop>
            val paths: List<List<GeoPoint>>

            if (vehicle != null) {
                val actualJourney = journey?.takeIf { it.tripId == vehicle.tripId }
                if (actualJourney != null && actualJourney.stops.isNotEmpty()) {
                    stops = actualJourney.stops
                    val journeyPath = actualJourney.geometry.takeIf { it.size >= 2 }
                    val patternPath = patterns.find { actualJourney.patternCode != null && (actualJourney.patternCode in it.codes || it.id == actualJourney.patternCode) }?.geometry?.takeIf { it.size >= 2 }
                    paths = listOfNotNull(journeyPath ?: patternPath)
                } else {
                    val matchingPattern = findMatchingPattern(vehicle, patterns)
                    if (matchingPattern != null) {
                        stops = matchingPattern.stops
                        paths = listOf(matchingPattern.geometry).filter { it.size >= 2 }
                    } else {
                        val allStopsById = allStops.associateBy { it.id }
                        val matchingDirection = findMatchingDirection(vehicle, route?.directions.orEmpty(), allStopsById)
                        if (matchingDirection != null) {
                            stops = matchingDirection.stopIds.mapNotNull { allStopsById[it] }
                            paths = emptyList()
                        } else {
                            val localStops = route?.directions.orEmpty().flatMap { it.stopIds }.toSet()
                            stops = (patterns.flatMap { it.stops } + allStops.filter { it.id in localStops || (routeId != null && routeId in it.routeIds) }).distinctBy { it.id }
                            paths = patterns.map { it.geometry }.filter { it.size >= 2 }.distinct()
                        }
                    }
                }
            } else {
                val selectedPattern=boarding?.pattern
                val selectedLocal=route?.directions?.find { it.id==boarding?.localDirectionId }
                if(selectedPattern!=null) {
                    stops=selectedPattern.stops
                    paths=listOf(selectedPattern.geometry).filter { it.size>=2 }
                } else if(selectedLocal!=null) {
                    val byId=allStops.associateBy { it.id }
                    stops=selectedLocal.stopIds.mapNotNull(byId::get)
                    paths=emptyList()
                } else {
                    val localStops=route?.directions.orEmpty().flatMap { it.stopIds }.toSet()
                    stops=(patterns.flatMap { it.stops }+allStops.filter { it.id in localStops || routeId!=null && routeId in it.routeIds }).distinctBy { it.id }
                    paths=patterns.map { it.geometry }.filter { it.size>=2 }.distinct()
                }
            }

            val directionKey = vehicle?.tripId ?: boarding?.pattern?.id ?: boarding?.localDirectionId ?: "all"
            return LineMapFocus(
                key = "${routeId ?: "vehicle:${vehicle!!.id}"}:$directionKey",
                routeId = routeId,
                providerId = provider,
                number = number,
                mode = mode,
                paths = paths,
                stops = stops,
                selectedVehicleId = vehicle?.id,
                contextPaths = line?.patterns.orEmpty().map { it.geometry }.filter { it.size>=2 && it !in paths }.distinct(),
                contextStops = (line?.patterns.orEmpty().flatMap { it.stops } + allStops.filter { stop ->
                    routeId!=null && (routeId in stop.routeIds || route?.directions.orEmpty().any { stop.id in it.stopIds })
                }).distinctBy { it.id }.filterNot { other -> stops.any { it.id==other.id } },
            )
        }

        private fun findMatchingPattern(vehicle: Vehicle, patterns: List<LinePattern>): LinePattern? {
            if (patterns.isEmpty()) return null
            if (patterns.size == 1) return patterns.first()

            if (vehicle.tripId != null) {
                val byTrip = patterns.find { it.directionId == vehicle.tripId || vehicle.tripId in it.codes }
                if (byTrip != null) return byTrip
            }

            val scored = patterns.map { pattern ->
                var score = 0
                val dest = vehicle.destination
                if (!dest.isNullOrBlank()) {
                    val cleanDest = StopClusters.cleanName(dest)
                    val cleanHeadsign = StopClusters.cleanName(pattern.headsign)
                    if (cleanHeadsign.isNotEmpty() && cleanDest.isNotEmpty()) {
                        if (cleanHeadsign == cleanDest) score += 50
                        else if (cleanHeadsign.contains(cleanDest) || cleanDest.contains(cleanHeadsign)) score += 30
                        else if (StopClusters.areNamesCloselyMatching(pattern.headsign, dest)) score += 25
                    }
                    val lastStop = pattern.stops.lastOrNull()
                    if (lastStop != null && StopClusters.areNamesCloselyMatching(lastStop.name, dest)) {
                        score += 30
                    }
                }
                if (vehicle.nextStopId != null) {
                    val nextIdx = pattern.stops.indexOfFirst { it.id == vehicle.nextStopId }
                    if (nextIdx >= 0) score += 40
                }
                if (!vehicle.nextStopName.isNullOrBlank()) {
                    val hasNext = pattern.stops.any { StopClusters.areNamesCloselyMatching(it.name, vehicle.nextStopName) }
                    if (hasNext) score += 30
                }
                if (vehicle.nextStopParentId != null) {
                    if (pattern.stops.any { it.parentStationId == vehicle.nextStopParentId }) score += 20
                }

                val dist = distanceToPattern(vehicle.position, pattern)
                Triple(pattern, score, dist)
            }

            val maxScore = scored.maxOf { it.second }
            return if (maxScore > 0) {
                scored.filter { it.second == maxScore }.minByOrNull { it.third }?.first
            } else {
                scored.minByOrNull { it.third }?.first
            }
        }

        private fun findMatchingDirection(vehicle: Vehicle, directions: List<RouteDirection>, allStopsById: Map<String, TransitStop>): RouteDirection? {
            if (directions.isEmpty()) return null
            if (directions.size == 1) return directions.first()

            val scored = directions.map { dir ->
                var score = 0
                val dest = vehicle.destination
                if (!dest.isNullOrBlank()) {
                    val cleanDest = StopClusters.cleanName(dest)
                    val cleanName = StopClusters.cleanName(dir.name)
                    if (cleanName.isNotEmpty() && cleanDest.isNotEmpty()) {
                        if (cleanName == cleanDest) score += 50
                        else if (cleanName.contains(cleanDest) || cleanDest.contains(cleanName)) score += 30
                        else if (StopClusters.areNamesCloselyMatching(dir.name, dest)) score += 25
                    }
                    val lastStop = dir.stopIds.lastOrNull()?.let { allStopsById[it] }
                    if (lastStop != null && StopClusters.areNamesCloselyMatching(lastStop.name, dest)) {
                        score += 30
                    }
                }
                if (vehicle.nextStopId != null && vehicle.nextStopId in dir.stopIds) {
                    score += 40
                }
                if (!vehicle.nextStopName.isNullOrBlank()) {
                    val hasNext = dir.stopIds.any { id ->
                        val s = allStopsById[id]
                        s != null && StopClusters.areNamesCloselyMatching(s.name, vehicle.nextStopName)
                    }
                    if (hasNext) score += 30
                }

                val dist = dir.stopIds.mapNotNull { allStopsById[it] }
                    .minOfOrNull { StopClusters.distanceMeters(vehicle.position, it.position) } ?: Double.MAX_VALUE
                Triple(dir, score, dist)
            }

            val maxScore = scored.maxOf { it.second }
            return if (maxScore > 0) {
                scored.filter { it.second == maxScore }.minByOrNull { it.third }?.first
            } else {
                scored.minByOrNull { it.third }?.first
            }
        }

        private fun distanceToPattern(position: GeoPoint, pattern: LinePattern): Double {
            val stopDist = pattern.stops.minOfOrNull { StopClusters.distanceMeters(position, it.position) } ?: Double.MAX_VALUE
            val geomDist = pattern.geometry.minOfOrNull { StopClusters.distanceMeters(position, it) } ?: Double.MAX_VALUE
            return minOf(stopDist, geomDist)
        }
    }
}
