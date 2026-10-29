package hu.app.bmenetrend.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.PriorityQueue

/** A directed street-network walk. Geographic distance is only used to find candidates. */
data class WalkingLink(val from: PlanPoint, val to: PlanPoint, val seconds: Long, val meters: Double) {
    fun leg(start: Instant) = PlanLeg("WALK", start, start.plusSeconds(seconds), meters, from, to, null, null, false, false, emptyList())
}

data class LocalPath(val stopId: String, val arrival: Instant, val legs: List<PlanLeg>) {
    val boardings = legs.count { !it.walking && !it.stayOnBoard }
    internal val restricted = legs.any { it.restrictedService }
    internal val walkingDistance = legs.sumOf { if (it.walking) it.distance else 0.0 }
}

/** Earliest arrival with a label per boarding count; every reviewed local trip participates. */
class LocalTimetableRouter(private val network: TransitNetwork, private val ready: Instant) {
    val stops = network.stops.associateBy { it.id }
    private val routes = network.routes.associateBy { it.id }
    private val horizon = ready.plusSeconds(86_400)
    private data class Boarding(val trip: ScheduledTrip, val times: List<Instant>, val index: Int)
    private val boardings: Map<String, List<Boarding>>

    init {
        val day = ready.atZone(TripPlanner.zone).toLocalDate()
        val entries = mutableMapOf<String, MutableList<Boarding>>()
        for (date in listOf(day.minusDays(1), day, day.plusDays(1))) {
            for (trip in network.trips) {
                val route = routes[trip.routeId] ?: continue
                if (route.providerId !in network.verifiedProviderIds || trip.calendar != calendar(route.providerId, date)) continue
                val times = trip.stopTimes.map { instant(date, it.minuteOfDay) }
                if (times.any { it == null }) continue
                val valid = times.filterNotNull()
                if (valid.zipWithNext().any { (a, b) -> a > b } || valid.last() < ready || valid.first() > horizon) continue
                for (index in 0 until trip.stopTimes.lastIndex) {
                    entries.getOrPut(trip.stopTimes[index].stopId) { mutableListOf() }.add(Boarding(trip, valid, index))
                }
            }
        }
        boardings = entries.mapValues { (_, values) -> values.sortedBy { it.times[it.index] } }
    }

    fun search(seeds: List<LocalPath>, transfers: Map<Pair<String, String>, WalkingLink>): Map<String, List<LocalPath>> {
        val queue = PriorityQueue(compareBy<LocalPath> { it.arrival }.thenBy { it.boardings })
        val best = mutableMapOf<Triple<String, Int, Boolean>, LocalPath>()
        fun key(path: LocalPath) = Triple(path.stopId, path.boardings, path.restricted)
        val walks = transfers.entries.groupBy { it.key.first }
        // Compare cheap labels before allocating legs and intermediate stops for losing paths.
        fun offer(stopId: String, arrival: Instant, boardingCount: Int, restricted: Boolean,
                  walkingDistance: Double, build: () -> LocalPath) {
            if (arrival > horizon || boardingCount > 5) return
            val label = Triple(stopId, boardingCount, restricted)
            val previous = best[label]
            if (previous != null && (previous.arrival < arrival || previous.arrival == arrival &&
                    previous.walkingDistance <= walkingDistance)) return
            val path = build()
            best[label] = path
            queue.add(path)
        }
        for (seed in seeds) {
            offer(seed.stopId, seed.arrival, seed.boardings, seed.restricted, seed.walkingDistance) { seed }
        }
        while (queue.isNotEmpty()) {
            val path = queue.remove()
            if (best[key(path)] !== path) continue
            for (entry in walks[path.stopId].orEmpty()) {
                val arrival = path.arrival.plusSeconds(entry.value.seconds)
                offer(entry.key.second, arrival, path.boardings, path.restricted, path.walkingDistance + entry.value.meters) {
                    LocalPath(entry.key.second, arrival, path.legs + entry.value.leg(path.arrival))
                }
            }
            if (path.boardings >= 5) continue
            val earliest = earliestBoarding(path)
            val departures = boardings[path.stopId].orEmpty()
            var low = 0
            var high = departures.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (departures[mid].times[departures[mid].index] < earliest) low = mid + 1 else high = mid
            }
            for (boardingIndex in low until departures.size) {
                val boarding = departures[boardingIndex]
                val trip = boarding.trip
                val route = routes.getValue(trip.routeId)
                val direction = route.directions.first { it.id == trip.directionId }
                val from = stops.getValue(path.stopId).point()
                for (index in boarding.index + 1..trip.stopTimes.lastIndex) {
                    val to = stops.getValue(trip.stopTimes[index].stopId)
                    val arrival = boarding.times[index]
                    offer(to.id, arrival, path.boardings + 1, path.restricted || direction.restrictedService, path.walkingDistance) {
                        val intermediate = trip.stopTimes.subList(boarding.index + 1, index).map { stops.getValue(it.stopId).point() }
                        val ride = PlanLeg("BUS", boarding.times[boarding.index], arrival, 0.0, from, to.point(),
                            route, direction.name, false, false, intermediate, restrictedService = direction.restrictedService)
                        LocalPath(to.id, arrival, path.legs + ride)
                    }
                }
            }
        }
        return best.values.groupBy { it.stopId }
    }

    fun finish(paths: Map<String, List<LocalPath>>, egress: Map<String, WalkingLink>): List<TripItinerary> =
        egress.flatMap { (id, walk) -> paths[id].orEmpty().mapNotNull { path ->
            TripPlanner.itinerary(path.legs + walk.leg(path.arrival), ready)
        } }

    companion object {
        fun TransitStop.point() = PlanPoint(name, position, platformCode)
        fun calendar(provider: String, date: LocalDate): String? = when (provider) {
            "viabusz" -> ViabuszTimetableParser.activeCalendar(date)
            "kanyargo" -> KanyargoCalendar.activeCalendar(date)
            "homm" -> HommCalendar.activeCalendar(date)
            else -> null
        }
        private fun instant(date: LocalDate, minute: Int): Instant? {
            val local = date.atTime(LocalTime.MIDNIGHT).plusMinutes(minute.toLong())
            val offsets = TripPlanner.zone.rules.getValidOffsets(local)
            return if (offsets.size == 1) local.toInstant(offsets.single()) else null
        }
        private fun earliestBoarding(path: LocalPath): Instant {
            val last = path.legs.indexOfLast { !it.walking }
            if (last < 0) return path.arrival.plusSeconds(TripPlanner.BOARD_SECONDS)
            var walking = 0L
            for (index in last + 1 until path.legs.size) {
                val leg = path.legs[index]
                walking += Duration.between(leg.start, leg.end).seconds
            }
            return maxOf(path.arrival, path.legs[last].end.plusSeconds(walking + TripPlanner.TRANSFER_SECONDS))
        }
    }
}
