package hu.app.bmenetrend.data

import java.time.Duration
import java.time.Instant

/** Date-independent boarding rows, built once for each immutable timetable snapshot. */
internal class DepartureIndex(network: TransitNetwork) {
    data class Boarding(val route: TransitRoute, val direction: RouteDirection, val calendar: String, val minute: Int)

    val routes = network.routes.associateBy { it.id }
    val stopIds = network.stopIds
    val boardings: Map<String, List<Boarding>> = buildMap<String, MutableList<Boarding>> {
        for (trip in network.trips) {
            val route = routes[trip.routeId] ?: continue
            val direction = route.directions.find { it.id == trip.directionId } ?: continue
            for (index in 0 until trip.stopTimes.lastIndex) {
                val time = trip.stopTimes[index]
                getOrPut(time.stopId) { ArrayList() }.add(Boarding(route, direction, trip.calendar, time.minuteOfDay))
            }
        }
    }
}

object DepartureCalculator {
    fun departures(
        network: TransitNetwork,
        live: LiveSnapshot?,
        stopId: String,
        now: Instant = Instant.now(),
        windowMinutes: Int = 90,
    ): List<Departure> {
        val today = now.atZone(ViabuszParser.zone).toLocalDate()
        val until = now.plusSeconds(windowMinutes.coerceIn(1, 300) * 60L)
        val index = network.departureIndex
        val routes = index.routes
        val selectedId = index.stopIds[stopId] ?: stopId
        val startOfDay = today.atStartOfDay(ViabuszParser.zone)
        // Evaluate only calendars used by this board, once per call (including null results).
        val calendars = mutableMapOf<String, String?>()
        val scheduled = index.boardings[selectedId].orEmpty().mapNotNull { boarding ->
            val route = boarding.route
            val serviceDate = if (route.providerId in network.providerServiceDates) network.providerServiceDates[route.providerId] else network.serviceDate
            if (serviceDate != today) return@mapNotNull null
            val calendarKey = when (route.providerId) {
                "homm", "kanyargo" -> route.providerId
                else -> if (boarding.calendar == "working_days") "working_days" else "viabusz"
            }
            if (!calendars.containsKey(calendarKey)) calendars[calendarKey] = when (calendarKey) {
                "homm" -> HommCalendar.activeCalendar(today)
                "kanyargo" -> KanyargoCalendar.activeCalendar(today)
                "working_days" -> "working_days".takeIf { ServiceCalendar.isConfirmedWorkingDay(today) }
                else -> ViabuszTimetableParser.activeCalendar(today)
            }
            val activeCalendar = calendars[calendarKey]
            if (boarding.calendar != activeCalendar) return@mapNotNull null
            val direction = boarding.direction
            val departure = startOfDay.plusMinutes(boarding.minute.toLong()).toInstant()
            if (departure < now || departure > until) null else Departure(
                route.id, route.number, direction.name, selectedId, departure, departure, ArrivalKind.SCHEDULED,
                directionId = direction.id, restrictedService = direction.restrictedService,
            )
        }
        // A stop countdown is NEVER assigned to a route by nearest scheduled time.
        // The provider currently exposes no route IDs; this branch is intentionally dormant.
        val realtime = if (live != null && Duration.between(live.fetchedAt, now).seconds in -30..45) {
            live.arrivals.filter { (index.stopIds[it.stopId] ?: it.stopId) == selectedId && it.isVerifiedRealtime && it.routeId in routes && it.minutes > 0 }
                .mapNotNull { arrival ->
                    val route = routes[arrival.routeId] ?: return@mapNotNull null
                    val direction = route.directions.find { it.id == arrival.directionId }
                    val expected = live.fetchedAt.plusSeconds(arrival.minutes * 60L)
                    if (expected < now || expected > until) null else Departure(
                        route.id, route.number, direction?.name ?: route.name, selectedId, null, expected, ArrivalKind.LIVE, arrival.vehicleId,
                        directionId = direction?.id, restrictedService = direction?.restrictedService ?: false,
                    )
                }
        } else emptyList()
        return (scheduled + realtime).sortedBy { it.expectedAt }
    }
}
