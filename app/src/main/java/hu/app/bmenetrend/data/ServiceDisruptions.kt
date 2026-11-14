package hu.app.bmenetrend.data

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.time.Instant

@Immutable
data class AlertText(val language: String, val text: String)
@Immutable
data class AlertPeriod(val start: Long?, val end: Long?) {
    fun overlaps(from: Instant, until: Instant) = (end == null || from.epochSecond < end) && (start == null || until.epochSecond >= start)
}
@Immutable
data class AlertTrip(val id: String?, val date: String?, val time: String?, val route: String?, val direction: Int?)
@Immutable
data class AlertSelector(val agency: String?, val route: String?, val type: Int?, val stop: String?, val direction: Int?, val trip: AlertTrip?) {
    // All supplied fields within a selector must match; selectors themselves are alternatives.
    fun matches(c: DisruptionContext): Boolean = c.bkk &&
        (agency == null || agency == "BKK") && (route == null || route == c.route) &&
        (type == null || type == c.type) && (stop == null || stop in c.stops) &&
        (direction == null || direction == c.direction) && (trip == null ||
            (trip.id == null || trip.id == c.trip) && (trip.date == null || trip.date == c.date) &&
            (trip.time == null || trip.time == c.time) && (trip.route == null || trip.route == c.route) &&
            (trip.direction == null || trip.direction == c.direction))
}
@Immutable
data class ServiceAlert(val id: String, val periods: List<AlertPeriod>, val selectors: List<AlertSelector>,
    val title: List<AlertText>, val description: List<AlertText>, val urls: List<AlertText>, val effect: Int, val severity: Int) {
    val important get() = severity >= 3 || effect in setOf(1, 2, 3, 4, 6, 9, 11)
}
@Immutable
data class TripChange(val id: String, val trip: AlertTrip, val cancelled: Boolean, val skipped: Set<String>, val updatedAt: Instant?)
@Immutable
data class DisruptionContext(val bkk: Boolean, val from: Instant, val until: Instant = from,
    val route: String? = null, val type: Int? = null, val stops: Set<String> = emptySet(),
    val trip: String? = null, val date: String? = null, val time: String? = null, val direction: Int? = null, val label: String? = null)
@Immutable
data class DisruptionWarning(val id: String, val alert: ServiceAlert? = null, val change: TripChange? = null, val stale: Boolean = false, val serviceLabel: String? = null) {
    val important get() = change != null || alert?.important == true
}
@Immutable
data class DisruptionSnapshot(
    val alerts: List<ServiceAlert> = emptyList(), val changes: List<TripChange> = emptyList(),
    val alertsAt: Instant? = null, val changesAt: Instant? = null,
    val alertsUnavailable: Boolean = false, val changesUnavailable: Boolean = false,
    val attempted: Boolean = false,
) {
    fun alertsStale(now: Instant) = alertsUnavailable || alertsAt == null || alertsAt < now.minusSeconds(300)
    fun changesStale(now: Instant) = changesUnavailable || changesAt == null || changesAt < now.minusSeconds(180)
    fun warnings(contexts: List<DisruptionContext>, now: Instant, offline: Boolean = false): List<DisruptionWarning> {
        val scope = contexts.filter { it.bkk }
        if (scope.isEmpty()) return emptyList()
        val alertsUsable = alertsAt?.let { it >= now.minusSeconds(21600) } == true
        val result = if (!alertsUsable) emptyList() else alerts.filter { a -> scope.any { c ->
            (a.periods.isEmpty() || a.periods.any { it.overlaps(c.from, c.until) }) &&
                a.selectors.any { it.matches(c) }
        } }.map { DisruptionWarning(it.id, alert = it, stale = offline || alertsStale(now)) }
        // A reused daily trip ID is insufficient: require the source's service date too.
        // Old cancellations/skips must never masquerade as a current live status.
        val tripWarnings = if (offline || changesStale(now)) emptyList() else changes.mapNotNull { change ->
            if (change.trip.id == null || change.trip.date == null) return@mapNotNull null
            val matching = scope.firstOrNull { c ->
                c.trip == change.trip.id && c.date == change.trip.date &&
                    (change.trip.time == null || c.time == change.trip.time) &&
                    (change.trip.route == null || c.route == change.trip.route) &&
                    (change.trip.direction == null || c.direction == change.trip.direction) &&
                    (change.cancelled || change.skipped.any { it in c.stops })
            } ?: return@mapNotNull null
            DisruptionWarning(change.id, change = change, serviceLabel = matching.label)
        }
        return (tripWarnings + result).distinctBy { (it.alert != null) to it.id }.sortedByDescending { it.important }
    }
}

/** Exact feed identities only; a displayed line number is not a GTFS route ID. */
object DisruptionScope {
    fun route(id: String?): String? = id?.let(BkkWorkerParser::workerRouteId)?.takeIf { it.startsWith("BKK_") }?.removePrefix("BKK_")
    fun stop(id: String?): String? = when {
        id == null -> null
        id.startsWith("emma:BKK:") -> id.removePrefix("emma:BKK:")
        id.startsWith("BKK:") -> id.removePrefix("BKK:")
        id.startsWith("BKK_") -> id.removePrefix("BKK_")
        else -> null
    }
    fun trip(id: String?): String? = id?.takeIf { it.startsWith("BKK:") }?.removePrefix("BKK:")
    private fun type(mode: String?) = when (mode) { "TRAM" -> 0; "SUBWAY" -> 1; "RAIL", "SUBURBAN_RAILWAY" -> 2; "BUS", "COACH" -> 3; "FERRY" -> 4; "TROLLEYBUS" -> 11; else -> null }
    fun line(route: TransitRoute, at: Instant) = DisruptionContext(route(route.id) != null, at,
        route = route(route.id), type = type(route.transportMode), label = route.number)
    fun station(stops: List<TransitStop>, routes: Map<String, TransitRoute>, at: Instant, departures: List<Departure> = emptyList()): List<DisruptionContext> {
        val ids = stops.flatMapTo(linkedSetOf()) { s -> (s.sourceStopIds + s.id).mapNotNull(::stop) }
        val routeIds = stops.flatMapTo(hashSetOf()) { it.routeIds }.apply { addAll(departures.map { it.routeId }) }
        val serving = routeIds.mapNotNull(routes::get)
        return listOf(DisruptionContext(ids.isNotEmpty(), at, stops = ids)) +
            serving.map { line(it, at).copy(stops = ids) } + departures.mapNotNull { d ->
                val rawRoute = route(d.routeId) ?: return@mapNotNull null
                DisruptionContext(true, d.expectedAt, route = rawRoute, stops = setOfNotNull(stop(d.stopId)),
                    trip = trip(d.directionId), date = d.serviceDate, label = "${d.routeNumber} → ${d.destination}")
            }
    }
    fun itinerary(trip: TripItinerary) = trip.legs.filterNot { it.walking }.map { leg ->
        val raw = leg.route?.id?.let(::route)
        DisruptionContext(raw != null, leg.start, leg.end, raw, type(leg.mode),
            (listOf(leg.from, leg.to) + leg.stops).mapNotNullTo(hashSetOf()) { stop(it.stopId) },
            trip(leg.tripId), leg.serviceDate, leg.tripStartTime, leg.directionId, leg.route?.number?.let { "$it → ${leg.headsign ?: leg.to.name}" })
    }
}

object ServiceDisruptionsParser {
    fun parse(text: String, previous: DisruptionSnapshot = DisruptionSnapshot()): DisruptionSnapshot {
        val root = JSONObject(text)
        require(root.getString("status") == "OK")
        val a = root.optJSONObject("alerts"); val t = root.optJSONObject("tripChanges")
        val hasAlerts = a?.optJSONArray("items") != null && a.long("timestamp") != null
        val hasChanges = t?.optJSONArray("items") != null && t.long("timestamp") != null
        return DisruptionSnapshot(
            alerts = if (!hasAlerts) previous.alerts else a!!.optJSONArray("items").objects().take(2000).map { row ->
                ServiceAlert(row.getString("id"), row.optJSONArray("periods").objects().map { AlertPeriod(it.long("start"), it.long("end")) },
                    row.optJSONArray("selectors").objects().map { s -> AlertSelector(s.text("agencyId"), s.text("routeId"), s.int("routeType"),
                        s.text("stopId"), s.int("directionId"), s.optJSONObject("trip")?.trip()) },
                    row.texts("title"), row.texts("description"), row.texts("url"), row.optInt("effect", 8), row.optInt("severity", 1))
            },
            changes = if (!hasChanges) previous.changes else t!!.optJSONArray("items").objects().take(10000).map { row ->
                TripChange(row.getString("id"), row.getJSONObject("trip").trip(), row.optBoolean("cancelled"),
                    row.optJSONArray("skippedStops").strings().toSet(), row.long("updatedAt")?.let(Instant::ofEpochSecond))
            },
            alertsAt = if (hasAlerts) Instant.ofEpochSecond(a!!.getLong("timestamp")) else previous.alertsAt,
            changesAt = if (hasChanges) Instant.ofEpochSecond(t!!.getLong("timestamp")) else previous.changesAt,
            alertsUnavailable = !hasAlerts || a!!.optBoolean("unavailable"),
            changesUnavailable = !hasChanges || t!!.optBoolean("unavailable"), attempted = true)
    }
    /** Feed HTML is display text, never executable content or an arbitrary clickable link. */
    fun plain(text: String) = Jsoup.parse(text).text()
    fun localized(texts: List<AlertText>, language: String): String = texts.firstOrNull { it.language.substringBefore('-') == language }?.text
        ?: texts.firstOrNull { it.language == "hu" }?.text ?: texts.firstOrNull()?.text.orEmpty()
    fun safeUrl(url: String): String? = runCatching { java.net.URI(url).takeIf {
        it.scheme == "https" && it.port in setOf(-1, 443) && it.host in setOf("bkk.hu", "www.bkk.hu") && it.userInfo == null
    }?.toASCIIString() }.getOrNull()
    private fun JSONObject.trip() = AlertTrip(text("tripId"), text("startDate"), text("startTime"), text("routeId"), int("directionId"))
    private fun JSONObject.texts(key: String) = optJSONArray(key).objects().map { AlertText(it.optString("language"), plain(it.optString("text"))) }
    private fun JSONObject.text(key: String) = optString(key).takeUnless { it.isBlank() || it == "null" }
    private fun JSONObject.long(key: String) = if (has(key) && !isNull(key)) optLong(key).takeIf { it > 0 } else null
    private fun JSONObject.int(key: String) = if (has(key) && !isNull(key)) optInt(key) else null
    private fun JSONArray?.objects() = (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }
    private fun JSONArray?.strings() = (0 until (this?.length() ?: 0)).mapNotNull { this?.optString(it)?.takeIf(String::isNotBlank) }
}
