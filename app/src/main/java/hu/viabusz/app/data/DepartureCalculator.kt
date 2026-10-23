package hu.viabusz.app.data

import java.time.Duration
import java.time.Instant

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
        val routes = network.routes.associateBy { it.id }
        val selectedId = SharedStops.resolve(network, stopId)
        val scheduled = network.trips.flatMap { trip ->
                val route = routes[trip.routeId] ?: return@flatMap emptyList()
                val serviceDate = if (route.providerId in network.providerServiceDates) network.providerServiceDates[route.providerId] else network.serviceDate
                if (serviceDate != today) return@flatMap emptyList()
                val activeCalendar = when (route.providerId) {
                    "homm" -> HommCalendar.activeCalendar(today)
                    "kanyargo" -> KanyargoCalendar.activeCalendar(today)
                    else -> if (trip.calendar == "working_days") "working_days".takeIf { ServiceCalendar.isConfirmedWorkingDay(today) }
                        else ViabuszTimetableParser.activeCalendar(today)
                }
                if (trip.calendar != activeCalendar) return@flatMap emptyList()
                val direction = route.directions.find { it.id == trip.directionId } ?: return@flatMap emptyList()
                trip.stopTimes.dropLast(1).filter { it.stopId == selectedId }.mapNotNull { time ->
                    val departure = today.atStartOfDay(ViabuszParser.zone).plusMinutes(time.minuteOfDay.toLong()).toInstant()
                    if (departure < now || departure > until) null else Departure(
                        route.id, route.number, direction.name, selectedId, departure, departure, ArrivalKind.SCHEDULED,
                        directionId = direction.id, restrictedService = direction.restrictedService,
                    )
                }
            }
        // A stop countdown is NEVER assigned to a route by nearest scheduled time.
        // The provider currently exposes no route IDs; this branch is intentionally dormant.
        val realtime = if (live != null && Duration.between(live.fetchedAt, now).seconds in -30..45) {
            live.arrivals.filter { SharedStops.resolve(network, it.stopId) == selectedId && it.isVerifiedRealtime && it.routeId in routes && it.minutes > 0 }
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
