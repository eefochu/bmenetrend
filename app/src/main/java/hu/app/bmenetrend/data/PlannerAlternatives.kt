package hu.app.bmenetrend.data

import java.time.Duration
import java.time.Instant

/** Presentation selection only: every operator still participates in routing. */
internal object PlannerAlternatives {
    const val MAX_ARRIVAL_DELAY_SECONDS = 1200L
    private const val SIMILAR_ARRIVAL_SECONDS = 120L
    fun select(journeys: List<TripItinerary>, ready: Instant? = null): List<TripItinerary> {
        val sorted = journeys.distinctBy { it.id }.sortedWith(TripPlanner.order)
        val distinct = mutableListOf<TripItinerary>()
        for (trip in sorted) if (distinct.none { equivalent(it,trip) }) distinct += trip
        val frontier = distinct.filter { trip -> distinct.none { it !== trip && dominates(it,trip) } }
        val fastest = frontier.firstOrNull() ?: return emptyList()
        val baseline = ready ?: sorted.minOf { it.start }
        // A small walking/transfer saving must not keep a journey that arrives much later.
        val delayBudget = (Duration.between(baseline,fastest.end).seconds / 4).coerceIn(600L,MAX_ARRIVAL_DELAY_SECONDS)
        val useful = frontier.filter { trip ->
            val delay = Duration.between(fastest.end,trip.end).seconds
            trip === fastest || delay <= delayBudget && (
                delay == 0L || trip.transfers < fastest.transfers ||
                fastest.walkMeters-trip.walkMeters >= 200 || fastest.walkSeconds-trip.walkSeconds >= 180 ||
                restricted(fastest) && !restricted(trip))
        }
        // With almost the same arrival, waiting for a reachable service is preferable
        // to several extra minutes on foot. Keep the actual fastest alternative too.
        val recommended = useful.filter { trip ->
            trip === fastest || Duration.between(fastest.end, trip.end).seconds <= SIMILAR_ARRIVAL_SECONDS &&
                (!restricted(trip) || restricted(fastest)) && trip.transfers <= fastest.transfers &&
                fastest.walkSeconds - trip.walkSeconds >= 180
        }.minWith(compareBy<TripItinerary> { restricted(it) }.thenBy { it.walkSeconds }.then(TripPlanner.order))
        // Preserve meaningful choices for arrival, connections and walking.
        val easy = compareBy<TripItinerary> { it.transfers }.then(TripPlanner.order)
        val walking = compareBy<TripItinerary> { it.walkSeconds }.thenBy { it.walkMeters }.then(TripPlanner.order)
        return (listOf(recommended,fastest,useful.minWith(easy),useful.minWith(walking)) + useful)
            .distinctBy { it.id }.take(3).let { choices ->
                listOf(recommended) + choices.filterNot { it.id == recommended.id }.sortedWith(TripPlanner.order)
            }
    }

    private fun restricted(trip: TripItinerary) = trip.legs.any { it.restrictedService }

    private fun dominates(a: TripItinerary,b: TripItinerary): Boolean {
        // A registration-only school service cannot suppress a publicly available journey.
        if (restricted(a) && !restricted(b)) return false
        return a.end <= b.end && a.transfers <= b.transfers &&
            a.walkMeters <= b.walkMeters+30 && a.walkSeconds <= b.walkSeconds+30 && (
                a.end < b.end || a.transfers < b.transfers || a.walkMeters+30 < b.walkMeters ||
                a.walkSeconds+30 < b.walkSeconds || !restricted(a) && restricted(b))
    }

    private fun equivalent(a: TripItinerary,b: TripItinerary): Boolean {
        if (restricted(a) != restricted(b) || a.transfers != b.transfers ||
            kotlin.math.abs(a.walkMeters-b.walkMeters) > 200 || kotlin.math.abs(a.walkSeconds-b.walkSeconds) > 180) return false
        val first = a.legs.filterNot { it.walking }
        val second = b.legs.filterNot { it.walking }
        if (first.size != second.size) return false
        // Walking-only suggestions share the same requested endpoints.
        if (first.isEmpty()) return true
        return first.zip(second).all { (x,y) ->
            x.route?.providerId == y.route?.providerId && x.route?.number == y.route?.number && x.mode == y.mode &&
                x.stayOnBoard == y.stayOnBoard && near(x.from,y.from) && near(x.to,y.to)
        }
    }

    private fun near(a: PlanPoint,b: PlanPoint) = StopClusters.distanceMeters(a.position,b.position) <= 200
}
