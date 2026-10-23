package hu.viabusz.app.data

import java.time.Instant
import java.time.LocalDate

/** Provider IDs stay stable in source assets; verified shared platforms are merged for display. */
data class GeoPoint(val lat: Double, val lon: Double)
data class MapBounds(val west: Double, val south: Double, val east: Double, val north: Double)
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
data class RouteDirection(val id: String, val name: String, val stopIds: List<String>, val restrictedService: Boolean = false)
data class TransitRoute(val id: String, val number: String, val name: String, val directions: List<RouteDirection>, val providerId: String = "viabusz", val sourceUrl: String? = null, val transportMode: String? = null, val color: String? = null)
data class StopTime(val stopId: String, val minuteOfDay: Int)
data class ScheduledTrip(val id: String, val routeId: String, val directionId: String, val stopTimes: List<StopTime>, val calendar: String = "working_days")
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
)
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
)
enum class ArrivalKind { SCHEDULED, LIVE }
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
)
data class LiveArrival(
    val stopId: String,
    val minutes: Int,
    val directionId: String,
    val vehicleId: String? = null,
    val routeId: String? = null,
    // The aggregate getStartTimesMP feed does not identify whether a value is GPS-based.
    val isVerifiedRealtime: Boolean = false,
)
data class LiveSnapshot(
    val vehicles: List<Vehicle>,
    val arrivals: List<LiveArrival>,
    val fetchedAt: Instant,
    val warning: String? = null,
    val vehicleDataAvailable: Boolean = true,
    val providerWarnings: Map<String, String> = emptyMap(),
)
