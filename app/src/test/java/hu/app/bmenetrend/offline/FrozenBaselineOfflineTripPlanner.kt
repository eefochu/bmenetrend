package hu.app.bmenetrend.offline

import android.database.sqlite.SQLiteDatabase
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.offline.OfflineTransitData.Companion.serviceInstant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.PriorityQueue

/**
 * Frozen baseline OfflineTripPlanner implementation verbatim from commit e60a444
 * (prior to R1 indexed departure bounds optimization).
 * Kept exclusively in test source set to measure scientifically rigorous matched
 * warm baseline numbers against the exact same test harness, JVM, DB pack, and host conditions.
 */
class FrozenBaselineOfflineTripPlanner(private val store: OfflinePackStore) {
    suspend fun plan(origin: PlannerEndpoint, destination: PlannerEndpoint, requested: Instant, ready: Instant): TripPlan = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        store.read { db, meta ->
            val date = ready.atZone(TripPlanner.zone).toLocalDate()
            if (date < meta.validFrom || date > meta.validUntil) throw OfflineDateUnavailable()
            fun resolve(endpoint: PlannerEndpoint): Set<Long> {
                val platforms = linkedSetOf<Long>()
                // Search results can represent several platforms/providers. Only expand
                // those explicit IDs (or a source station's children), never nearby stops.
                for (source in (endpoint.sourceStopIds + endpoint.id).map(::sourceStopId).distinct()) {
                    context.ensureActive()
                    db.rawQuery("SELECT id,station FROM stops WHERE source_id=?", arrayOf(source)).use { selected ->
                        if (selected.moveToFirst()) {
                            if (selected.getInt(1) == 0) platforms += selected.getLong(0)
                            else db.rawQuery("SELECT id FROM stops WHERE parent=? AND station=0", arrayOf(source)).use { children ->
                                while (children.moveToNext()) platforms += children.getLong(0)
                            }
                        }
                    }
                }
                return platforms.takeIf { it.isNotEmpty() } ?: throw OfflineEndpointUnavailable()
            }
            val start = resolve(origin); val end = resolve(destination)
            val stops = mutableMapOf<Long, TransitStop>()
            val routes = mutableMapOf<Long, TransitRoute>()
            fun stop(id: Long) = stops.getOrPut(id) { db.rawQuery("SELECT id,source_id,name,lat,lon,platform,parent,station,feed FROM stops WHERE id=?", arrayOf(id.toString())).use { check(it.moveToFirst()); OfflineTransitData.stop(it) } }
            fun route(id: Long) = routes.getOrPut(id) { db.rawQuery("SELECT id,source_id,number,name,mode,color,feed FROM routes WHERE id=?", arrayOf(id.toString())).use { check(it.moveToFirst()); OfflineTransitData.route(it) } }
            fun point(id: Long) = stop(id).let { PlanPoint(it.name, it.position, it.platformCode) }
            var horizon = ready.plusSeconds(86_400)
            val specificTransfers = db.rawQuery("SELECT 1 FROM transfers WHERE forbidden!=0 OR seconds>0 OR from_trip!=0 OR to_trip!=0 OR from_route!=0 OR to_route!=0 LIMIT 1", null).use { it.moveToFirst() }
            data class Label(val stop: Long, val time: Instant, val legs: List<PlanLeg>, val trip: Long = 0, val route: Long = 0,
                val alighted: Long = stop, val arrival: Instant = time, val walking: Long = 0)
            data class LabelKey(val stop: Long, val trip: Long, val alighted: Long, val restricted: Boolean)
            fun key(label: Label) = LabelKey(label.stop, if (specificTransfers) label.trip else 0, if (specificTransfers) label.alighted else 0, label.legs.any { it.restrictedService })
            fun walks(seed: Collection<Label>): List<Label> {
                val queue = PriorityQueue(compareBy<Label> { it.time })
                val best = mutableMapOf<LabelKey, MutableList<Label>>()
                fun offer(label: Label) {
                    if (label.time > horizon) return
                    val bucket = best.getOrPut(key(label)) { mutableListOf() }
                    if (bucket.any { it.time <= label.time && it.walking <= label.walking }) return
                    bucket.removeAll { label.time <= it.time && label.walking <= it.walking }
                    bucket += label; queue += label
                }
                seed.forEach(::offer)
                while (queue.isNotEmpty()) {
                    context.ensureActive()
                    val label = queue.remove()
                    if (best[key(label)]?.any { it === label } != true) continue
                    db.rawQuery("SELECT to_stop,seconds FROM paths WHERE from_stop=? UNION SELECT to_stop,seconds FROM transfers WHERE from_stop=? AND to_stop!=from_stop AND forbidden=0 AND from_route=0 AND to_route=0 AND from_trip=0 AND to_trip=0",
                        arrayOf(label.stop.toString(), label.stop.toString())).use { c ->
                        while (c.moveToNext()) {
                            val to = c.getLong(0); val seconds = c.getLong(1)
                            val walk = PlanLeg("WALK", label.time, label.time.plusSeconds(seconds), StopClusters.distanceMeters(stop(label.stop).position, stop(to).position), point(label.stop), point(to), null, null, false, false, emptyList())
                            offer(label.copy(stop = to, time = walk.end, legs = label.legs + walk, walking = label.walking + seconds))
                        }
                    }
                }
                return best.values.flatten()
            }
            var frontier = walks(start.map { Label(it, ready, emptyList()) })
            val journeys = mutableListOf<TripItinerary>()
            repeat(5) { round ->
                val next = mutableMapOf<LabelKey, MutableList<Label>>()
                for (label in frontier) {
                    context.ensureActive()
                    db.rawQuery("""SELECT s.trip,s.seq,s.departure,t.route,t.headsign,t.restricted,d.day
                        FROM stop_times s JOIN trips t ON t.id=s.trip JOIN service_days d ON d.service=t.service
                        WHERE s.stop=? AND s.pickup!=1 AND d.day BETWEEN ? AND ?""",
                        arrayOf(label.stop.toString(), date.minusDays(2).toEpochDay().toString(), date.plusDays(1).toEpochDay().toString())).use { boarding ->
                        while (boarding.moveToNext()) {
                            context.ensureActive()
                            val trip = boarding.getLong(0); val seq = boarding.getInt(1); val routeId = boarding.getLong(3)
                            val serviceDay = LocalDate.ofEpochDay(boarding.getLong(6))
                            val departure = serviceInstant(serviceDay, boarding.getInt(2))
                            var allowance = if (round == 0) TripPlanner.BOARD_SECONDS else TripPlanner.TRANSFER_SECONDS
                            var forbidden = false
                            if (label.trip != 0L) {
                                db.rawQuery("""SELECT seconds,forbidden FROM transfers WHERE from_stop=? AND to_stop=?
                                    AND (from_trip=0 OR from_trip=?) AND (to_trip=0 OR to_trip=?)
                                    AND (from_route=0 OR from_route=?) AND (to_route=0 OR to_route=?)""",
                                    arrayOf(label.alighted.toString(), label.stop.toString(), label.trip.toString(), trip.toString(), label.route.toString(), routeId.toString())).use { rules ->
                                    while (rules.moveToNext()) {
                                        forbidden = forbidden || rules.getInt(1) != 0
                                        // GTFS min_transfer_time is total since alighting, including walking.
                                        val remaining = rules.getLong(0) - Duration.between(label.arrival, label.time).seconds
                                        allowance = maxOf(allowance, remaining)
                                    }
                                }
                            }
                            if (forbidden || departure < label.time.plusSeconds(allowance) || departure > horizon) continue
                            val rideRoute = route(routeId)
                            val intermediate = mutableListOf<PlanPoint>()
                            db.rawQuery("SELECT stop,arrival,dropoff FROM stop_times WHERE trip=? AND seq>? ORDER BY seq", arrayOf(trip.toString(), seq.toString())).use { times ->
                                while (times.moveToNext()) {
                                    val to = times.getLong(0)
                                    val arrival = serviceInstant(serviceDay, times.getInt(1))
                                    val candidateKey = LabelKey(to, if (specificTransfers) trip else 0L, if (specificTransfers) to else 0L, label.legs.any { it.restrictedService } || boarding.getInt(5) != 0)
                                    if (times.getInt(2) != 1 && arrival >= departure && arrival <= horizon && next[candidateKey]?.any { it.time <= arrival && it.walking <= label.walking } != true) {
                                        val ride = PlanLeg(rideRoute.transportMode ?: "BUS", departure, arrival, 0.0, point(label.stop), point(to), rideRoute, boarding.getString(4), false, false,
                                            intermediate.toList(), restrictedService = boarding.getInt(5) != 0)
                                        val updated = Label(to, arrival, label.legs + ride, trip, routeId, to, arrival, label.walking)
                                        val bucket = next.getOrPut(candidateKey) { mutableListOf() }
                                        bucket.removeAll { arrival <= it.time && label.walking <= it.walking }
                                        bucket += updated
                                    }
                                    intermediate += point(to)
                                }
                            }
                        }
                    }
                }
                frontier = walks(next.values.flatten())
                frontier.filter { it.stop in end }.forEach { label -> TripPlanner.itinerary(label.legs, ready)?.let(journeys::add) }
                journeys.minOfOrNull { it.end }?.plusSeconds(PlannerAlternatives.MAX_ARRIVAL_DELAY_SECONDS)?.let { horizon = minOf(horizon, it) }
                frontier = frontier.filter { it.time <= horizon }
            }
            TripPlan(origin, destination, requested, ready, TripPlanner.alternatives(journeys, ready), offlinePack = meta.id,
                offlineValidUntil = meta.validUntil.toString())
        }
    }
}
