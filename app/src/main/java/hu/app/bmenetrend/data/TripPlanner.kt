package hu.app.bmenetrend.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject
import java.time.Instant
import java.time.Duration
import java.time.ZoneId
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Immutable
data class PlannerEndpoint(val id: String, val name: String, val detail: String, val position: GeoPoint)
@Immutable
data class PlanPoint(val name: String, val position: GeoPoint, val platform: String? = null)
@Immutable
data class PlanLeg(
    val mode: String, val start: Instant, val end: Instant, val distance: Double,
    val from: PlanPoint, val to: PlanPoint, val route: TransitRoute?, val headsign: String?,
    val realtime: Boolean, val stayOnBoard: Boolean, val stops: List<PlanPoint>,
    val trainNumber: String? = null,
    val restrictedService: Boolean = false,
) { val walking: Boolean get() = mode == "WALK" }
@Immutable
data class TripItinerary(val id: String, val start: Instant, val end: Instant, val legs: List<PlanLeg>) {
    val durationSeconds: Long get() = Duration.between(start, end).seconds
    val transfers: Int get() = (legs.count { !it.walking && !it.stayOnBoard } - 1).coerceAtLeast(0)
    val walkMeters: Double get() = legs.filter { it.walking }.sumOf { it.distance }
    val walkSeconds: Long get() = legs.filter { it.walking }.sumOf { Duration.between(it.start, it.end).seconds }
    /** Includes the gaps supplied by the router, so a journey bar accounts for waiting too. */
    fun segments(): List<PlanSegment> = buildList {
        var cursor = start
        legs.forEach { leg ->
            if (leg.start > cursor) add(PlanSegment(cursor, leg.start, null))
            if (leg.end > leg.start) add(PlanSegment(leg.start, leg.end, leg))
            cursor = leg.end
        }
    }
}
@Immutable
data class PlanSegment(val start: Instant, val end: Instant, val leg: PlanLeg?)
@Immutable
data class TripPlan(val origin: PlannerEndpoint, val destination: PlannerEndpoint, val requestedAt: Instant, val readyAt: Instant, val itineraries: List<TripItinerary>, val partial: Boolean = false)

object TripPlanner {
    const val WALK_SPEED = 1.15
    const val PREPARATION_SECONDS = 120L
    const val BOARD_SECONDS = 60L
    const val TRANSFER_SECONDS = 180L
    val zone: ZoneId = ZoneId.of("Europe/Budapest")

    /** The API accepts local wall time only, so ambiguous DST times cannot be requested reliably. */
    fun departure(date: LocalDate, time: LocalTime, now: Instant): Instant? {
        val local = date.atTime(time.withSecond(0).withNano(0))
        val offsets = zone.rules.getValidOffsets(local)
        if (offsets.size != 1) return null
        return local.toInstant(offsets.single()).takeUnless { it < now }
    }
    val QUERY get() = query()
    fun query(busesOnly: Boolean = false) = """query TripPlan(${'$'}from:String!,${'$'}to:String!,${'$'}date:String!,${'$'}time:String!) {
        plan(fromPlace:${'$'}from,toPlace:${'$'}to,date:${'$'}date,time:${'$'}time,arriveBy:false,
            walkSpeed:1.15,minTransferTime:180,boardSlack:60,alightSlack:30,numItineraries:12,maxTransfers:4,omitCanceled:true,
            transportModes:[{mode:WALK},${if (busesOnly) "{mode:BUS},{mode:COACH}" else "{mode:TRANSIT}"}],ignoreRealtimeUpdates:false) {
            itineraries { startTime endTime legs {
                mode startTime endTime distance realTime transitLeg interlineWithPreviousLeg
                from { name lat lon stop { gtfsId name platformCode } }
                to { name lat lon stop { gtfsId name platformCode } }
                route { gtfsId shortName longName mode color }
                trip { gtfsId tripShortName tripHeadsign }
                intermediateStops { name lat lon }
            } }
            routingErrors { code description }
        }
    }""".trimIndent()

    fun variables(origin: PlannerEndpoint, destination: PlannerEndpoint, readyAt: Instant): JSONObject {
        require(supported(origin.position) && supported(destination.position))
        val local = readyAt.atZone(zone)
        return JSONObject().put("from", "${origin.position.lat},${origin.position.lon}")
            .put("to", "${destination.position.lat},${destination.position.lon}")
            .put("date", local.toLocalDate().toString()).put("time", local.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
    }

    fun parse(text: String, origin: PlannerEndpoint, destination: PlannerEndpoint, requestedAt: Instant,
              ready: Instant = requestedAt.plusSeconds(PREPARATION_SECONDS)): TripPlan {
        val plan = EmmaGraphqlClient.data(text).optJSONObject("plan") ?: error("Missing plan")
        val itineraries = plan.optJSONArray("itineraries") ?: error("Missing itineraries")
        val parsed = (0 until itineraries.length()).mapNotNull { index ->
            runCatching {
                val row = itineraries.getJSONObject(index)
                val rows = row.getJSONArray("legs")
                require(rows.length() in 1..40)
                val legs = (0 until rows.length()).map { i ->
                    val leg = rows.getJSONObject(i)
                    val mode = leg.getString("mode")
                    require(mode in setOf("WALK", "BUS", "COACH", "TRAM", "TROLLEYBUS", "SUBWAY", "RAIL", "SUBURBAN_RAILWAY", "TRAMTRAIN", "FERRY", "CABLE_CAR", "FUNICULAR"))
                    val start = Instant.ofEpochMilli(leg.getLong("startTime"))
                    val end = Instant.ofEpochMilli(leg.getLong("endTime"))
                    val distance = leg.getDouble("distance")
                    require(!end.isBefore(start) && distance.isFinite() && distance >= 0)
                    // The street router accounts for grades/escalators; do not override those times.
                    if (mode == "WALK") require(Duration.between(start, end).seconds + 10 >= distance / 2.2)
                    val rawRoute = leg.optJSONObject("route")
                    val route = rawRoute?.let { route ->
                        val source = route.getString("gtfsId")
                        val provider = if (mode == "SUBURBAN_RAILWAY" && HevSupport.number(route.text("shortName"), route.text("longName")) != null) "hev"
                            else EmmaStopsParser.provider(source)?.takeUnless { it == "hev" } ?: "mav"
                        val number = if (provider == "hev") HevSupport.number(route.text("shortName"), route.text("longName"))!!
                            else if (RailSupport.isTrain(mode)) RailSupport.number(route.text("shortName"), route.text("longName"))
                            else route.text("shortName")?.takeUnless { name -> '<' in name || name.any { Character.getType(it) == Character.PRIVATE_USE.toInt() } } ?: route.text("longName") ?: mode
                        TransitRoute("$provider:$source", number, route.text("longName") ?: number, emptyList(), provider,
                            transportMode = mode, color = if (provider == "hev") HevSupport.color(number) else route.text("color"))
                    }
                    require(mode == "WALK" || route != null)
                    val stops = leg.optJSONArray("intermediateStops")
                    PlanLeg(mode, start, end, distance, point(leg.getJSONObject("from")), point(leg.getJSONObject("to")), route,
                        leg.optJSONObject("trip")?.text("tripHeadsign"), leg.optBoolean("realTime"), leg.optBoolean("interlineWithPreviousLeg"),
                        (0 until (stops?.length() ?: 0)).map { point(stops!!.getJSONObject(it)) },
                        if (RailSupport.isTrain(mode)) RailSupport.serviceNumber(leg.optJSONObject("trip")?.text("tripShortName")) else null)
                }
                require(feasible(legs, ready))
                val start = Instant.ofEpochMilli(row.getLong("startTime")); val end = Instant.ofEpochMilli(row.getLong("endTime"))
                require(start == legs.first().start && end == legs.last().end)
                TripItinerary(legs.joinToString("|") { "${it.route?.id ?: it.mode}:${it.start.toEpochMilli()}:${it.from.name}:${it.to.name}" }, start, end, legs)
            }.getOrNull()
        }.distinctBy { it.id }.sortedWith(order).take(12)
        return TripPlan(origin, destination, requestedAt, ready, parsed)
    }

    /** Verify source times; never move a leg or invent a connection to make it fit. */
    internal fun feasible(legs: List<PlanLeg>, ready: Instant): Boolean {
        if (legs.isEmpty() || legs.first().start.isBefore(ready.minusSeconds(1))) return false
        if (legs.count { !it.walking && !it.stayOnBoard } > 5) return false
        if (legs.zipWithNext().any { (a,b) -> b.start.isBefore(a.end) }) return false
        var previousTransit: PlanLeg? = null
        var walkingSinceTransit = 0L
        for (leg in legs) {
            if (leg.walking) {
                if (previousTransit != null) walkingSinceTransit += Duration.between(leg.start, leg.end).seconds
            } else {
                if (leg.stayOnBoard && previousTransit == null) return false
                if (!leg.stayOnBoard) {
                    val previous = previousTransit
                    if (previous != null && Duration.between(previous.end, leg.start).seconds < walkingSinceTransit + TRANSFER_SECONDS) return false
                    if (previous == null) {
                        val precedingWalk = legs.takeWhile { it !== leg }.lastOrNull()
                        val arrived = precedingWalk?.end ?: ready
                        if (Duration.between(arrived, leg.start).seconds < BOARD_SECONDS) return false
                    }
                }
                previousTransit = leg; walkingSinceTransit = 0
            }
        }
        return true
    }

    val order = compareBy<TripItinerary> { it.end }.thenBy { it.transfers }.thenBy { it.walkSeconds }.thenBy { it.walkMeters }.thenBy { it.durationSeconds }.thenBy { it.id }

    fun itinerary(legs: List<PlanLeg>, ready: Instant): TripItinerary? {
        if (!feasible(legs, ready)) return null
        return TripItinerary(legs.joinToString("|") { "${it.route?.id ?: it.mode}:${it.start.toEpochMilli()}:${it.from.name}:${it.to.name}" },
            legs.first().start, legs.last().end, legs)
    }

    /** Up to three distinct, non-dominated choices; operator identity never reserves a slot. */
    fun alternatives(journeys: List<TripItinerary>, ready: Instant? = null): List<TripItinerary> =
        PlannerAlternatives.select(journeys,ready)
    private fun point(row: JSONObject): PlanPoint {
        val point = GeoPoint(row.getDouble("lat"), row.getDouble("lon"))
        require(point.lat.isFinite() && point.lon.isFinite() && point.lat in -90.0..90.0 && point.lon in -180.0..180.0)
        return PlanPoint(row.text("name") ?: error("Unnamed point"), point, row.optJSONObject("stop")?.text("platformCode"))
    }
    fun supported(p: GeoPoint) = p.lat.isFinite() && p.lon.isFinite() && p.lat in 45.7..48.6 && p.lon in 16.1..22.9
    private fun JSONObject.text(key: String) = optString(key).takeUnless { it.isBlank() || it == "null" }
}
