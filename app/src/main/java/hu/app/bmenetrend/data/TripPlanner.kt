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
data class PlannerEndpoint(
    val id: String,
    val name: String,
    val detail: String,
    val position: GeoPoint,
    /** Platforms represented by the selected search result, not walking connections. */
    val sourceStopIds: List<String> = listOf(id),
    val isStation: Boolean = false,
    val routeIds: List<String> = emptyList(),
    val providerIds: List<String> = emptyList(),
    val transportMode: String? = null,
    /** Feed of a station suggestion; null for the local index and geocoded places. */
    val source: DataSource? = null,
) {
    companion object {
        fun fromStop(stop: TransitStop, source: DataSource? = null) = PlannerEndpoint(
            id = stop.id,
            name = stop.name,
            detail = BkkPlatformIndex.resolve(stop.id, stop.platformCode).orEmpty(),
            position = stop.position,
            sourceStopIds = stop.sourceStopIds,
            isStation = true,
            routeIds = stop.routeIds,
            providerIds = stop.providerIds,
            transportMode = stop.transportMode,
            source = source,
        )
    }
}
@Immutable
data class PlanPoint(val name: String, val position: GeoPoint, val platform: String? = null)
@Immutable
data class PlanLeg(
    val mode: String, val start: Instant, val end: Instant, val distance: Double,
    val from: PlanPoint, val to: PlanPoint, val route: TransitRoute?, val headsign: String?,
    val realtime: Boolean, val stayOnBoard: Boolean, val stops: List<PlanPoint>,
    val trainNumber: String? = null,
    val restrictedService: Boolean = false,
    /** Planner that produced this leg; survives local/national joins. Null for local timetables. */
    val source: DataSource? = null,
    /** Router provenance for this exact sequence; hybrid joins cannot inherit its slack policy. */
    val routerJourney: String? = null,
    val routerPrevious: String? = null,
) { val walking: Boolean get() = mode == "WALK" }
@Immutable
data class TripItinerary(
    val id: String, val start: Instant, val end: Instant, val legs: List<PlanLeg>,
    /** When the source planner answered, if known; a reused answer keeps its original time. */
    val fetchedAt: Instant? = null,
) {
    /** Planners that produced the vehicle legs (walking-only trips report their walking source). */
    val sources: Set<DataSource> get() = (legs.filterNot { it.walking }.ifEmpty { legs }).mapNotNullTo(LinkedHashSet()) { it.source }
    val durationSeconds: Long get() = Duration.between(start, end).seconds
    val transfers: Int get() = (legs.count { !it.walking && !it.stayOnBoard } - 1).coerceAtLeast(0)
    val walkMeters: Double get() = legs.filter { it.walking }.sumOf { it.distance }
    val walkSeconds: Long get() = legs.filter { it.walking }.sumOf { Duration.between(it.start, it.end).seconds }
    /** Door-to-door time from the chosen departure, including the initial wait. */
    fun totalSeconds(ready: Instant): Long = Duration.between(minOf(ready, start), end).seconds

    fun segments(ready: Instant = start): List<PlanSegment> = buildList {
        var cursor = minOf(ready, start)
        legs.forEach { leg ->
            if (leg.start > cursor) add(PlanSegment(cursor, leg.start, null))
            if (leg.end > leg.start) add(PlanSegment(leg.start, leg.end, leg))
            cursor = leg.end
        }
    }

    /** Movement only; idle gaps remain in total time and in the detailed step list. */
    fun travelLegs(): List<PlanLeg> = buildList {
        for (leg in legs) {
            if (leg.end <= leg.start) continue
            val previous = lastOrNull()
            if (leg.walking && previous?.walking == true && previous.end == leg.start) {
                this[lastIndex] = previous.copy(end = leg.end, distance = previous.distance + leg.distance, to = leg.to,
                    stops = previous.stops + leg.stops, routerJourney = null, routerPrevious = null)
            } else add(leg)
        }
    }
}
@Immutable
data class PlanSegment(val start: Instant, val end: Instant, val leg: PlanLeg?)
@Immutable
data class TripPlan(val origin: PlannerEndpoint, val destination: PlannerEndpoint, val requestedAt: Instant, val readyAt: Instant, val itineraries: List<TripItinerary>, val partial: Boolean = false, val offlinePack: String? = null, val offlineValidUntil: String? = null)

object TripPlanner {
    const val WALK_SPEED = 1.15
    /** EMMA reluctance for the walk-averse query; 5.0 found Biatorbágy 762→767 transfers the default hides. */
    const val WALK_AVERSE_RELUCTANCE = 5.0
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
    /** [avoidWalking] makes the router prefer waiting and changing over long walks (default reluctance is 2). */
    fun query(busesOnly: Boolean = false, avoidWalking: Boolean = false) = """query TripPlan(${'$'}from:String!,${'$'}to:String!,${'$'}date:String!,${'$'}time:String!) {
        plan(fromPlace:${'$'}from,toPlace:${'$'}to,date:${'$'}date,time:${'$'}time,arriveBy:false,
            ${if (avoidWalking) "walkReluctance:$WALK_AVERSE_RELUCTANCE," else ""}walkSpeed:1.15,minTransferTime:180,boardSlack:60,alightSlack:30,numItineraries:12,maxTransfers:4,omitCanceled:true,
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
              ready: Instant = requestedAt): TripPlan {
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
                        if (RailSupport.isTrain(mode)) RailSupport.serviceNumber(leg.optJSONObject("trip")?.text("tripShortName")) else null,
                        source = DataSource.EMMA)
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
        var previousLeg: PlanLeg? = null
        var routerChainSinceTransit = false
        for (leg in legs) {
            val routerLink = routerLink(previousLeg, leg)
            if (leg.walking) {
                if (previousTransit != null) {
                    walkingSinceTransit += Duration.between(leg.start, leg.end).seconds
                    routerChainSinceTransit = routerChainSinceTransit && routerLink
                }
            } else {
                if (leg.stayOnBoard && previousTransit == null) return false
                if (!leg.stayOnBoard) {
                    val previous = previousTransit
                    if (previous != null && Duration.between(previous.end, leg.start).seconds < walkingSinceTransit + TRANSFER_SECONDS &&
                        !(routerChainSinceTransit && routerLink)) return false
                    if (previous == null) {
                        val precedingWalk = previousLeg?.takeIf { it.walking }
                        val arrived = precedingWalk?.end ?: ready
                        // OTP already computed this walk-to-vehicle connection. Do not add
                        // another boarding minute after the walk. At-stop and hybrid joins
                        // retain the normal buffer; times are never shifted to make them fit.
                        val routedWalk = routerLink && precedingWalk != null && precedingWalk.end > precedingWalk.start
                        if (Duration.between(arrived, leg.start).seconds < BOARD_SECONDS && !routedWalk) return false
                    }
                }
                previousTransit = leg; walkingSinceTransit = 0
                routerChainSinceTransit = leg.source == DataSource.BKK && leg.routerJourney != null
            }
            previousLeg = leg
        }
        return true
    }

    internal fun routerLegKey(leg: PlanLeg): String = listOf(leg.mode, leg.start.toEpochMilli(), leg.end.toEpochMilli(),
        leg.from.position.lat, leg.from.position.lon, leg.to.position.lat, leg.to.position.lon).joinToString(":")

    private fun routerLink(previous: PlanLeg?, next: PlanLeg): Boolean {
        if (previous == null || previous.source != DataSource.BKK || next.source != DataSource.BKK ||
            next.routerJourney == null || next.routerJourney != previous.routerJourney ||
            next.routerPrevious != routerLegKey(previous)) return false
        val seconds = Duration.between(previous.end, next.start).seconds
        // A claimed direct router connection still cannot jump between distant points.
        return seconds >= 0 && StopClusters.distanceMeters(previous.to.position, next.from.position) <= 20.0 + seconds * 2.2
    }

    val order = compareBy<TripItinerary> { it.end }.thenBy { it.walkSeconds }.thenBy { it.transfers }.thenBy { it.walkMeters }.thenBy { it.durationSeconds }.thenBy { it.id }

    /** A leave-at-requested-time suggestion must not wait hours for a later service. */
    internal fun bestTimely(journeys: List<TripItinerary>, ready: Instant): TripItinerary? = timelyChoices(journeys, ready).firstOrNull()

    internal fun timelyChoices(journeys: List<TripItinerary>, ready: Instant): List<TripItinerary> = PlannerAlternatives.select(journeys.asSequence()
        .filter { feasible(it.legs, ready) }
        .filter { trip ->
            var arrived = ready
            trip.legs.all { leg ->
                // Include waiting before the first walk: OTP may delay that walk until
                // just before a much later bus. Walking duration itself is not waiting.
                val acceptable = leg.stayOnBoard || Duration.between(arrived, leg.start).seconds <= MAX_BOARDING_WAIT_SECONDS
                arrived = leg.end
                acceptable
            }
        }.toList(), ready)

    private const val MAX_BOARDING_WAIT_SECONDS = 60 * 60L

    fun itinerary(legs: List<PlanLeg>, ready: Instant): TripItinerary? {
        if (!feasible(legs, ready)) return null
        return TripItinerary(legs.joinToString("|") { "${it.route?.id ?: it.mode}:${it.start.toEpochMilli()}:${it.from.name}:${it.to.name}" },
            legs.first().start, legs.last().end, legs)
    }

    /** A practical recommendation and one distinct, useful alternative. */
    fun alternatives(journeys: List<TripItinerary>, ready: Instant? = null): List<TripItinerary> =
        PlannerAlternatives.select(journeys,ready)
    private fun point(row: JSONObject): PlanPoint {
        val point = GeoPoint(row.getDouble("lat"), row.getDouble("lon"))
        require(point.lat.isFinite() && point.lon.isFinite() && point.lat in -90.0..90.0 && point.lon in -180.0..180.0)
        val stopObj = row.optJSONObject("stop")
        val platform = BkkPlatformIndex.resolve(stopObj?.text("gtfsId"), stopObj?.text("platformCode"))
        return PlanPoint(row.text("name") ?: error("Unnamed point"), point, platform)
    }
    fun supported(p: GeoPoint) = p.lat.isFinite() && p.lon.isFinite() && p.lat in 45.7..48.6 && p.lon in 16.1..22.9
    private fun JSONObject.text(key: String) = optString(key).takeUnless { it.isBlank() || it == "null" }
}
