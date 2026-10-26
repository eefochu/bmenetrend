package hu.app.bmenetrend.data

import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import kotlin.math.*

/** Reviewed GPX roads split at ordered timetable stops. Never connect across GPX track gaps. */
internal data class RouteRoads(val routeId: String, val directionId: String, val stopIds: List<String>, val segments: List<List<GeoPoint>>) {
    companion object {
        fun decode(text: String): List<RouteRoads> {
            val root = JSONObject(text)
            require(root.getInt("schemaVersion") == 1)
            val routes = root.getJSONArray("routes")
            return (0 until routes.length()).map { index ->
                val row = routes.getJSONObject(index)
                val ids = row.getJSONArray("stopIds")
                val segments = row.getJSONArray("segments")
                RouteRoads(row.getString("routeId"), row.getString("directionId"),
                    (0 until ids.length()).map(ids::getString), (0 until segments.length()).map { s ->
                        val points = segments.getJSONArray(s)
                        (0 until points.length()).map { p -> points.getJSONArray(p).let { GeoPoint(it.getDouble(0), it.getDouble(1)) } }
                    }).also { require(it.segments.size == it.stopIds.size - 1 && it.segments.all { points -> points.size >= 2 }) }
            }
        }
    }
}

/** Estimates only. Observed route/trip identities and departure predictions are never modified.
 * One instance per ViewModel, called once per feed refresh; history stays on device and in memory.
 */
internal class ViabuszRouteEstimator(roads: List<RouteRoads>) {
    private data class Edge(val a: GeoPoint, val b: GeoPoint, val start: Double, val length: Double, val stop: Int, val fractionStart: Double, val segmentLength: Double)
    private data class Projection(val distance: Double, val along: Double, val stop: Int, val fraction: Double)
    private data class Path(val projection: Projection, val delay: Double, val cost: Double, val firstAlong: Double, val errors: List<Double>, val delays: List<Double>)
    private data class Match(val route: TransitRoute, val score: Double)
    private class Track {
        val samples = ArrayDeque<Vehicle>()
        var pending: String? = null
        var confirmations = 0
        var estimate: VehicleRouteEstimate? = null
    }
    private val tracks = mutableMapOf<String, Track>()
    private val geometry = roads.associateWith { road ->
        var along = 0.0
        road.segments.flatMapIndexed { stop, points ->
            val lengths = points.zipWithNext { a,b -> distance(a,b) }
            val segmentLength = lengths.sum()
            var within = 0.0
            points.zipWithNext().mapIndexed { i, (a,b) ->
                Edge(a,b,along,lengths[i],stop,within,segmentLength).also { along += lengths[i]; within += lengths[i] }
            }
        }
    }

    fun update(vehicles: List<Vehicle>, network: TransitNetwork?, now: Instant): List<Vehicle> {
        val date = now.atZone(ViabuszParser.zone).toLocalDate()
        val calendar = ViabuszTimetableParser.activeCalendar(date)
        val usable = network != null && "viabusz" in network.verifiedProviderIds &&
            network.fetchedAt.atZone(ViabuszParser.zone).toLocalDate() == date && calendar != null
        if (!usable) { tracks.clear(); return vehicles.map { it.copy(routeEstimate = null) } }
        val activeIds = vehicles.filter { it.providerId == "viabusz" }.map { it.id }.toSet()
        tracks.keys.retainAll(activeIds)
        val routes = network!!.routes.filter { it.providerId == "viabusz" }.associateBy { it.id }
        val trips = network.trips.filter { it.routeId in routes && it.calendar == calendar }
        return vehicles.map { vehicle ->
            if (vehicle.providerId != "viabusz" || vehicle.routeNumber != null || vehicle.routeId != null) {
                tracks.remove(vehicle.id)
                return@map vehicle.copy(routeEstimate = null)
            }
            if (Duration.between(vehicle.observedAt,now).seconds !in -30..90) {
                tracks.remove(vehicle.id)
                return@map vehicle.copy(routeEstimate = null)
            }
            var track = tracks.getOrPut(vehicle.id) { Track() }
            val previous = track.samples.lastOrNull()
            if (previous != null && vehicle.observedAt < previous.observedAt) {
                tracks.remove(vehicle.id)
                return@map vehicle.copy(routeEstimate = null)
            }
            if (previous != null && vehicle.observedAt == previous.observedAt) {
                // Repeated server packets are not new evidence; changed coordinates on the same timestamp are invalid.
                if (distance(previous.position,vehicle.position) > 1) {
                    tracks.remove(vehicle.id)
                    return@map vehicle.copy(routeEstimate = null)
                }
                return@map vehicle.copy(routeEstimate = track.estimate)
            }
            if (previous != null) {
                val seconds = Duration.between(previous.observedAt,vehicle.observedAt).seconds
                if (seconds > 90 || previous.observedAt.atZone(ViabuszParser.zone).toLocalDate() != date ||
                    distance(previous.position,vehicle.position) > seconds * 25 + 100) {
                    track = Track(); tracks[vehicle.id] = track
                }
            }
            track.samples.addLast(vehicle)
            while (track.samples.size > 12 || Duration.between(track.samples.first().observedAt,vehicle.observedAt).seconds > 300) track.samples.removeFirst()
            val samples = track.samples.toList()
            val stationary = samples.filter { Duration.between(it.observedAt,vehicle.observedAt).seconds <= 90 }
                .takeIf { it.size >= 3 && Duration.between(it.first().observedAt,vehicle.observedAt).seconds >= 60 }
                ?.all { distance(it.position,vehicle.position) < 25 } == true
            val terminal = network.stops.any { stop ->
                routes.values.any { route -> route.directions.any { it.stopIds.firstOrNull() == stop.id || it.stopIds.lastOrNull() == stop.id } } &&
                    distance(stop.position,vehicle.position) < 100
            }
            val candidate = if (samples.size < 3 || Duration.between(samples.first().observedAt,vehicle.observedAt).seconds < 20 || stationary || terminal ||
                distance(samples.first().position,vehicle.position) < 50) null else estimate(samples,trips,routes,date)
            if (candidate == null) {
                track.pending = null; track.confirmations = 0; track.estimate = null
                // Forget the previous journey at a terminus/layover instead of carrying its identity onto the next run.
                if (terminal || stationary) { track.samples.clear(); track.samples.add(vehicle) }
            } else {
                if (track.pending == candidate.id) track.confirmations++ else { track.pending = candidate.id; track.confirmations = 1; track.estimate = null }
                if (track.confirmations >= 2) track.estimate = VehicleRouteEstimate(candidate.id,candidate.number)
            }
            vehicle.copy(routeEstimate = track.estimate)
        }
    }

    private fun estimate(samples: List<Vehicle>, trips: List<ScheduledTrip>, routes: Map<String,TransitRoute>, date: java.time.LocalDate): TransitRoute? {
        val matches = mutableListOf<Match>()
        for ((road, edges) in geometry) {
            val route = routes[road.routeId] ?: continue
            val projections = samples.map { sample -> edges.map { project(sample.position,it) }.filter { it.distance <= 110 }
                .sortedBy { it.distance }.distinctBy { (it.along / 15).toInt() }.take(8) }
            if (projections.any { it.isEmpty() }) continue
            for (trip in trips.filter { it.routeId == road.routeId && it.directionId == road.directionId && it.stopTimes.map { t -> t.stopId } == road.stopIds }) {
                val midnight = date.atStartOfDay(ViabuszParser.zone).toInstant()
                var paths = emptyList<Path>()
                for ((index,sample) in samples.withIndex()) {
                    val next = mutableListOf<Path>()
                    for (p in projections[index]) {
                        val a = trip.stopTimes[p.stop].minuteOfDay * 60.0
                        val b = trip.stopTimes[p.stop+1].minuteOfDay * 60.0
                        val planned = a + p.fraction * (b-a)
                        val delay = Duration.between(midnight,sample.observedAt).seconds - planned
                        if (delay !in -300.0..1800.0) continue
                        val emission = p.distance.pow(2) + (max(0.0,abs(delay)-120) * .015).pow(2)
                        if (index == 0) next += Path(p,delay,emission,p.along,listOf(p.distance),listOf(delay))
                        else {
                            val elapsed = Duration.between(samples[index-1].observedAt,sample.observedAt).seconds.toDouble()
                            val previous = paths.filter { p.along-it.projection.along in -30.0..(elapsed*22+70) && abs(delay-it.delay) <= max(100.0,elapsed*1.5) }
                                .minByOrNull { it.cost + (delay-it.delay).pow(2)*.03 } ?: continue
                            next += Path(p,delay,previous.cost+emission+(delay-previous.delay).pow(2)*.03,previous.firstAlong,previous.errors+p.distance,previous.delays+delay)
                        }
                    }
                    paths = next
                    if (paths.isEmpty()) break
                }
                val best = paths.filter { it.errors.size == samples.size && it.projection.along-it.firstAlong >= 60 }.minByOrNull { it.cost } ?: continue
                val rms = sqrt(best.errors.sumOf { it*it } / samples.size)
                if (rms > 55) continue
                val delay = best.delays.average()
                val deviation = sqrt(best.delays.sumOf { (it-delay).pow(2) } / samples.size)
                matches += Match(route,rms + max(0.0,abs(delay)-120)*.015 + deviation*.2)
            }
        }
        val ranked = matches.groupBy { it.route.id }.values.map { group -> group.minBy { it.score } }.sortedBy { it.score }
        val best = ranked.firstOrNull() ?: return null
        if (best.score > 70 || ranked.getOrNull(1)?.let { it.score-best.score < 25 } == true) return null
        return best.route
    }

    private fun project(p: GeoPoint, edge: Edge): Projection {
        val scale = 111195.0
        val cosLat = cos(Math.toRadians(p.lat))
        val ax = (edge.a.lon-p.lon)*scale*cosLat; val ay = (edge.a.lat-p.lat)*scale
        val bx = (edge.b.lon-p.lon)*scale*cosLat; val by = (edge.b.lat-p.lat)*scale
        val dx = bx-ax; val dy = by-ay
        val t = if (dx*dx+dy*dy < .001) 0.0 else (-(ax*dx+ay*dy)/(dx*dx+dy*dy)).coerceIn(0.0,1.0)
        return Projection(hypot(ax+t*dx,ay+t*dy),edge.start+t*edge.length,edge.stop,
            if (edge.segmentLength <= 0) 0.0 else (edge.fractionStart+t*edge.length)/edge.segmentLength)
    }
    companion object { private fun distance(a: GeoPoint,b: GeoPoint) = StopClusters.distanceMeters(a,b) }
}
