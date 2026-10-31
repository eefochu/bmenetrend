package hu.app.bmenetrend.data

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.LocalDate

/** Provider IDs stay stable in source assets; verified shared platforms are merged for display. */
@Immutable
data class GeoPoint(val lat: Double, val lon: Double)

@Immutable
data class MapBounds(val west: Double, val south: Double, val east: Double, val north: Double)

@Immutable
data class TransitStop(
    val id: String, val name: String, val position: GeoPoint, val routeIds: List<String>,
    val providerId: String = "viabusz",
    val providerIds: List<String> = listOf(providerId),
    val aliases: List<String> = emptyList(),
    val sourceStopIds: List<String> = listOf(id),
    val platformCode: String? = null,
    val station: Boolean = false,
    val parentStationId: String? = null,
    val transportMode: String? = null,
    val isMultiStop: Boolean = false,
)

@Immutable
data class RouteDirection(val id: String, val name: String, val stopIds: List<String>, val restrictedService: Boolean = false)

@Immutable
data class TransitRoute(val id: String, val number: String, val name: String, val directions: List<RouteDirection>, val providerId: String = "viabusz", val sourceUrl: String? = null, val transportMode: String? = null, val color: String? = null)

@Immutable
data class StopTime(val stopId: String, val minuteOfDay: Int)

@Immutable
data class ScheduledTrip(val id: String, val routeId: String, val directionId: String, val stopTimes: List<StopTime>, val calendar: String = "working_days")

@Immutable
data class TransitNetwork(
    val stops: List<TransitStop>,
    val routes: List<TransitRoute>,
    val trips: List<ScheduledTrip>,
    val fetchedAt: Instant,
    val source: String,
    val serviceDate: LocalDate? = null,
    val calendarLabel: String = "Munkanapokon",
    val warning: String? = null,
    val providerServiceDates: Map<String, LocalDate?> = emptyMap(),
    val providerWarnings: Map<String, String> = emptyMap(),
    /** Document verification is independent of whether an operator runs on the fetch date. */
    val verifiedProviderIds: Set<String> = emptySet(),
) {
    internal val stopIds: Map<String, String> by lazy {
        buildMap {
            for (stop in stops) for (sourceId in stop.sourceStopIds) putIfAbsent(sourceId, stop.id)
            // Exact platform IDs override source aliases; duplicate aliases keep their first platform.
            for (stop in stops) put(stop.id, stop.id)
        }
    }
    // Timetable lookup belongs to the immutable network snapshot, not to each board refresh.
    internal val departureIndex by lazy { DepartureIndex(this) }
}

@Immutable
data class VehicleRouteEstimate(val routeId: String, val number: String)

@Immutable
data class Vehicle(
    val id: String,
    val position: GeoPoint,
    val observedAt: Instant,
    val nextStopName: String? = null,
    val routeId: String? = null,
    val providerId: String = "viabusz",
    val routeNumber: String? = null,
    val destination: String? = null,
    val registration: String? = null,
    val positionMayBeInterpolated: Boolean = false,
    val tripId: String? = null,
    val routeName: String? = null,
    val transportMode: String? = null,
    val heading: Double? = null,
    val speedKmh: Double? = null,
    val markerColor: String? = null,
    val nextStopId: String? = null,
    val nextStopPosition: Int? = null,
    val nextStopPoint: GeoPoint? = null,
    val trainNumber: String? = null,
    val serviceName: String? = null,
    val nextStopParentId: String? = null,
    val routeEstimate: VehicleRouteEstimate? = null,
) {
    val displayRouteNumber: String? get() = routeNumber ?: routeEstimate?.number
    val isRouteEstimated: Boolean get() = routeNumber == null && routeEstimate != null
    val displayNumber: String get() = if (RailSupport.isTrain(transportMode)) RailSupport.label(routeNumber, trainNumber) else displayRouteNumber ?: "?"
}

enum class ArrivalKind { SCHEDULED, LIVE }

@Immutable
data class Departure(
    val routeId: String,
    val routeNumber: String,
    val destination: String,
    val stopId: String,
    val scheduledAt: Instant?,
    val expectedAt: Instant,
    val kind: ArrivalKind,
    val vehicleId: String? = null,
    val directionId: String? = null,
    val restrictedService: Boolean = false,
    val patternCode: String? = null,
    val sourceDirectionId: String? = null,
    val wheelchairAccessible: String? = null,
    val bikesAllowed: String? = null,
    val realtimeState: String? = null,
    val trainNumber: String? = null,
    val serviceName: String? = null,
    val platformCode: String? = null,
) {
    val isCancelled: Boolean get() = realtimeState == "CANCELED"
    val delayMinutes: Long get() = scheduledAt?.let { java.time.Duration.between(it,expectedAt).toMinutes() } ?: 0L
}

@Immutable
data class LiveArrival(
    val stopId: String,
    val minutes: Int,
    val directionId: String,
    val vehicleId: String? = null,
    val routeId: String? = null,
    // The aggregate getStartTimesMP feed does not identify whether a value is GPS-based.
    val isVerifiedRealtime: Boolean = false,
)

@Immutable
data class LiveSnapshot(
    val vehicles: List<Vehicle>,
    val arrivals: List<LiveArrival>,
    val fetchedAt: Instant,
    val warning: String? = null,
    val vehicleDataAvailable: Boolean = true,
    val providerWarnings: Map<String, String> = emptyMap(),
)
