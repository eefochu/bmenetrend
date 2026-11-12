package hu.app.bmenetrend.data

import java.time.Duration
import java.time.Instant

/** Presentation selection only: every operator still participates in routing. */
internal object PlannerAlternatives {
    const val MAX_ARRIVAL_DELAY_SECONDS = 1200L
    /** Extra waiting seconds a rider accepts to avoid one second of walking. */
    const val WAIT_PER_WALK = 2L
    /** At most this many route cards: the recommendation and two useful backups. */
    const val MAX_CHOICES = 3
    /** A journey walking at least this long triggers the walk-averse search. */
    const val LONG_WALK_SECONDS = 600L
    const val WALK_AVERSE_TIMEOUT_MILLIS = 4_000L
    /** Latest a ride that spares walking may arrive after the fastest journey (e.g. vs. a 38-minute walk). */
    const val LESS_WALK_MAX_DELAY_SECONDS = 2700L
    /** Walking a backup must save to earn the reserved "wait for a ride" slot. */
    private const val LESS_WALK_SECONDS = 120L
    /** A backup this much later than a shown one, with no other advantage, is not shown. */
    private const val OUTCLASSED_DELAY_SECONDS = 300L
    private val LOCAL_PROVIDERS = setOf("viabusz", "kanyargo", "homm")
    /** Extra waiting weight per walking second beyond [LONG_WALK_SECONDS]. */
    private const val LONG_WALK_EXTRA = 2L
    /** Walking that is always acceptable to offer (15 min in total). */
    private const val SENSIBLE_WALK_SECONDS = 900L
    /** Beyond the least-walking journey, at most this much more walking is offered. */
    private const val EXTRA_WALK_SECONDS = 600L
    /** Boarding the "same" vehicle: within this many seconds at a nearby stop. */
    private const val SAME_RIDE_SECONDS = 120L
    fun select(journeys: List<TripItinerary>, ready: Instant? = null): List<TripItinerary> {
        val sorted = journeys.distinctBy { it.id }.sortedWith(TripPlanner.order)
        val distinct = mutableListOf<TripItinerary>()
        for (trip in sorted) if (distinct.none { equivalent(it,trip) }) distinct += trip
        // Keep a genuinely different backup, even when it arrives a little later. Public
        // services take precedence over trips requiring registration the app cannot confirm.
        val public = distinct.filterNot(::restricted)
        val usable = public.ifEmpty { distinct }
        if (usable.isEmpty()) return emptyList()
        val baseline = ready ?: sorted.minOf { it.start }
        val practicalOrder = compareBy<TripItinerary> { inconvenience(it, baseline) }
            .thenBy { it.transfers }.thenBy { it.walkSeconds }.then(TripPlanner.order)
        // People use the app for transport, not walks: when some journey walks a sensible
        // amount, journeys walking far more than that are never offered.
        val walkLimit = maxOf(SENSIBLE_WALK_SECONDS, usable.minOf { it.walkSeconds } + EXTRA_WALK_SECONDS)
        // The same vehicles, just getting off somewhere else, is one choice: keep the better one.
        val candidates = mutableListOf<TripItinerary>()
        for (trip in usable.filter { it.walkSeconds <= walkLimit }.sortedWith(practicalOrder))
            if (candidates.none { sameRide(it, trip) }) candidates += trip
        val fastest = candidates.minWith(TripPlanner.order)
        val elapsed = Duration.between(baseline, fastest.end).seconds.coerceAtLeast(0)
        val recommendationDelay = (elapsed / 4).coerceIn(300L, 600L)
        val alternativeDelay = (elapsed / 3).coerceIn(900L, MAX_ARRIVAL_DELAY_SECONDS)
        // Waiting at a nearby stop beats walking to catch something earlier: every walking
        // minute saved buys up to WAIT_PER_WALK minutes of later arrival, up to LESS_WALK_MAX_DELAY_SECONDS.
        val recommended = candidates.filter { trip ->
            trip.end <= fastest.end.plusSeconds(maxOf(recommendationDelay, minOf(LESS_WALK_MAX_DELAY_SECONDS, walkRelief(fastest, trip))))
        }.minWith(practicalOrder)
        // Backups may also wait longer for a ride that spares walking, within the search horizon.
        val backups = candidates.filter { trip ->
            trip.id != recommended.id && trip.end <= fastest.end.plusSeconds(
                maxOf(alternativeDelay, minOf(LESS_WALK_MAX_DELAY_SECONDS, walkRelief(fastest, trip))))
        }.sortedWith(practicalOrder)
        val shown = mutableListOf(recommended)
        // Reserve a slot for "wait for a ride instead of walking" when it really spares walking.
        backups.minWithOrNull(compareBy<TripItinerary> { it.walkSeconds }.then(practicalOrder))
            ?.takeIf { it.walkSeconds + LESS_WALK_SECONDS <= recommended.walkSeconds }
            ?.let { shown += it }
        // Offer variety: the best viable Volán, local (ViaBusz/KanyarGO/HOMM), BKK or rail
        // journey each get a card when their kind is not shown yet, cheapest kind first.
        backups.filter { families(it).isNotEmpty() }.groupBy { families(it).first() }.values
            .map { group -> group.minWith(practicalOrder) }.sortedWith(practicalOrder).forEach { trip ->
                val shownFamilies = shown.flatMapTo(HashSet(), ::families)
                if (shown.size < MAX_CHOICES && shown.none { it.id == trip.id } && families(trip).any { it !in shownFamilies }) shown += trip
            }
        for (trip in backups) {
            if (shown.size >= MAX_CHOICES) break
            if (shown.any { it.id == trip.id }) continue
            // Never show a card that is no faster and strictly worse (an extra change or more walking).
            if (shown.any { strictlyWorse(trip, it) }) continue
            // The first backup is the next-best route (e.g. the next departure if you miss one);
            // further cards must offer something the shown ones do not.
            if (shown.size == 1 || shown.none { outclassed(trip, it) }) shown += trip
        }
        // Never pad: a slot stays empty when no remaining journey is a useful choice.
        return listOf(recommended) + shown.drop(1).sortedWith(practicalOrder)
    }

    /** Kinds of service a journey rides, in boarding order; empty for walking only. */
    internal fun families(trip: TripItinerary): List<String> = trip.legs.filterNot { it.walking }.map { leg ->
        val provider = leg.route?.providerId
        when {
            provider in LOCAL_PROVIDERS -> "local"
            provider == "volan" -> "volan"
            provider == "mav" || provider == "hev" || leg.mode == "RAIL" || leg.mode == "SUBURBAN_RAILWAY" -> "rail"
            else -> provider ?: leg.mode
        }
    }.distinct()

    /** [by] arrives no later, walks no more (30 s tolerance), changes no more and is not less accessible. */
    private fun noBetter(trip: TripItinerary, by: TripItinerary): Boolean =
        !by.end.isAfter(trip.end) && by.walkSeconds <= trip.walkSeconds + 30 &&
            by.transfers <= trip.transfers && (restricted(trip) || !restricted(by))

    /** Arrives clearly later with no less walking, no fewer changes and no public-access gain. */
    private fun outclassed(trip: TripItinerary, by: TripItinerary): Boolean =
        noBetter(trip, by) && !by.end.plusSeconds(OUTCLASSED_DELAY_SECONDS).isAfter(trip.end)

    /** No earlier, and an extra change or at least a minute more walking: e.g. the same bus reached the hard way. */
    private fun strictlyWorse(trip: TripItinerary, by: TripItinerary): Boolean =
        noBetter(trip, by) && (by.transfers < trip.transfers || by.walkSeconds + 60 <= trip.walkSeconds)

    private fun restricted(trip: TripItinerary) = trip.legs.any { it.restrictedService }

    /**
     * Ranking weight of walking, in waiting seconds on top of the walk itself. Short walks cost
     * WAIT_PER_WALK; every second beyond LONG_WALK_SECONDS costs LONG_WALK_EXTRA more, because a
     * 20-minute walk is far worse than twice a 10-minute one.
     */
    internal fun walkPenalty(walkSeconds: Long): Long =
        walkSeconds * WAIT_PER_WALK + (walkSeconds - LONG_WALK_SECONDS).coerceAtLeast(0) * LONG_WALK_EXTRA

    /** How much later [trip] may arrive than [fastest] because it spares walking. */
    private fun walkRelief(fastest: TripItinerary, trip: TripItinerary): Long =
        (walkPenalty(fastest.walkSeconds) - walkPenalty(trip.walkSeconds)).coerceAtLeast(0)

    /** Boards the same vehicles (line, time, place) and differs only in where it gets off or how it walks. */
    private fun sameRide(a: TripItinerary, b: TripItinerary): Boolean {
        val first = a.legs.filterNot { it.walking }
        val second = b.legs.filterNot { it.walking }
        if (first.isEmpty() || first.size != second.size) return false
        return first.zip(second).all { (x, y) ->
            x.route?.providerId == y.route?.providerId && x.route?.number == y.route?.number && x.mode == y.mode &&
                kotlin.math.abs(Duration.between(x.start, y.start).seconds) <= SAME_RIDE_SECONDS && near(x.from, y.from)
        }
    }

    /** A trip whose last step is a walk of at least this long may be improved by a connecting ride. */
    const val EGRESS_WALK_SECONDS = 480L

    /** Seconds of the final walk after the last ride; 0 when the trip ends on a vehicle or walks only. */
    fun finalWalkSeconds(trip: TripItinerary): Long {
        if (trip.legs.none { !it.walking }) return 0
        return trip.legs.takeLastWhile { it.walking }.sumOf { Duration.between(it.start, it.end).seconds }
    }

    /** True when a planner may still improve these trips with a slower, walk-sparing search. */
    fun mayImprove(trips: List<TripItinerary>): Boolean = trips.isNotEmpty() &&
        (trips.minOf { it.walkSeconds } >= LONG_WALK_SECONDS || trips.any { finalWalkSeconds(it) >= EGRESS_WALK_SECONDS })

    /**
     * Ranking cost only, never added to the displayed timetable or walking duration. A walking
     * second costs 1 + WAIT_PER_WALK waiting seconds: people prefer to wait at the closest stop.
     */
    private fun inconvenience(trip: TripItinerary, ready: Instant): Long = trip.totalSeconds(ready) + walkPenalty(trip.walkSeconds) +
        trip.transfers * 300L + trip.legs.filterNot { it.walking || it.stayOnBoard }.sumOf { leg ->
            // Railway platforms/boarding generally require more preparation than urban
            // turn-up-and-go modes. A substantial real time saving still wins.
            when (leg.mode) { "RAIL" -> 180L; "COACH" -> 60L; else -> 0L }
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
