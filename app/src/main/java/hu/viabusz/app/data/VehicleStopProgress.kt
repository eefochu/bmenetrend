package hu.viabusz.app.data

import java.time.Duration
import java.time.Instant
import kotlin.math.*

/** Source-reported next stop + exact trip order; proximity is used only for an explicitly estimated at-stop state. */
object VehicleStopProgress {
    data class Progress(val at: TransitStop? = null, val previous: TransitStop? = null, val next: TransitStop? = null, val nextIndex: Int? = null)
    fun resolve(vehicle: Vehicle, journey: VehicleJourney?, now: Instant): Progress? {
        if (Duration.between(vehicle.observedAt, now).seconds !in -30..180) return null
        val id = vehicle.nextStopId ?: return null
        val stops = journey?.takeIf { it.tripId == vehicle.tripId }?.stops.orEmpty()
        val matches = stops.indices.filter { stops[it].id == id }
        val index = vehicle.nextStopPosition?.let { position ->
            listOf(position, position - 1).distinct().filter { it in matches }.singleOrNull()
        } ?: matches.singleOrNull()
        val next = index?.let { stops[it] } ?: vehicle.nextStopPoint?.let {
            TransitStop(id, vehicle.nextStopName ?: return@let null, it, emptyList(), vehicle.providerId)
        } ?: return null
        val previous = index?.takeIf { it > 0 }?.let { stops[it - 1] }
        val stationary = vehicle.speedKmh?.let { it <= 8 } == true
        val at = if (!stationary) null else listOfNotNull(previous, next).minByOrNull { distance(vehicle.position, it.position) }
            ?.takeIf { distance(vehicle.position, it.position) <= 45 }
        return Progress(at, previous, next, index)
    }
    private fun distance(a: GeoPoint, b: GeoPoint): Double {
        val latitude = Math.toRadians((a.lat + b.lat) / 2)
        return hypot(Math.toRadians(a.lon - b.lon) * cos(latitude), Math.toRadians(a.lat - b.lat)) * 6_371_000
    }
}
