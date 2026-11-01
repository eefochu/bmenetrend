package hu.app.bmenetrend.offline

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import hu.app.bmenetrend.data.*
import java.time.Instant
import java.time.LocalDate

/** App IDs deliberately retain exact EMMA namespaces for the two official feeds. */
internal fun stopAppId(source: String): String = when (source.substringBefore(':')) {
    "local" -> source.substringAfter(':')
    "volan" -> "emma:hkir:${source.substringAfter(':')}"
    "bkk" -> "emma:BKK:${source.substringAfter(':')}"
    else -> "offline:$source"
}
internal fun sourceStopId(appId: String): String = when {
    appId.startsWith("emma:hkir:") -> "volan:${appId.removePrefix("emma:hkir:")}"
    appId.startsWith("emma:BKK:") -> "bkk:${appId.removePrefix("emma:BKK:")}"
    appId.startsWith("offline:") -> appId.removePrefix("offline:")
    else -> "local:$appId"
}
internal fun routeAppId(source: String): String = when (source.substringBefore(':')) {
    "local" -> source.substringAfter(':')
    "volan" -> "volan:hkir:${source.substringAfter(':')}"
    "bkk" -> "bkk:BKK:${source.substringAfter(':')}"
    else -> "offline:$source"
}

private fun sourceRouteId(appId: String): String = when {
    appId.startsWith("volan:hkir:") -> "volan:${appId.removePrefix("volan:hkir:")}"
    appId.startsWith("bkk:BKK:") -> "bkk:${appId.removePrefix("bkk:BKK:")}"
    appId.startsWith("offline:") -> appId.removePrefix("offline:")
    else -> "local:$appId"
}

private val routeHexColor = Regex("[0-9a-fA-F]{6}")
internal data class OfflineCatalogue(val metadata: OfflinePackMetadata, val stops: List<TransitStop>, val routes: List<TransitRoute>)

class OfflineTransitData(private val store: OfflinePackStore) {
    fun catalogue(): Pair<List<TransitStop>, List<TransitRoute>> = catalogueSnapshot().let { it.stops to it.routes }
    internal fun catalogueSnapshot(): OfflineCatalogue = store.read { db, meta ->
        val servingRoutes = mutableMapOf<Long, MutableList<String>>()
        db.rawQuery("SELECT s.stop,r.source_id FROM stop_routes s JOIN routes r ON r.id=s.route", null).use { c ->
            while (c.moveToNext()) servingRoutes.getOrPut(c.getLong(0)) { mutableListOf() }.add(routeAppId(c.getString(1)))
        }
        val routes = db.rawQuery("SELECT id,source_id,number,name,mode,color,feed FROM routes", null).use { c -> buildList { while (c.moveToNext()) add(route(c)) } }
        val byId = routes.associateBy { it.id }
        val platforms = db.rawQuery("SELECT id,source_id,name,lat,lon,platform,parent,station,feed FROM stops", null).use { c -> buildList { while (c.moveToNext()) add(stop(c).copy(routeIds = servingRoutes[c.getLong(0)].orEmpty())) } }
        val parentRoutes = mutableMapOf<String, MutableSet<String>>()
        platforms.forEach { stop -> stop.parentStationId?.let { parent -> parentRoutes.getOrPut(parent) { linkedSetOf() }.addAll(stop.routeIds) } }
        val stops = platforms.map { stop ->
            val ids = if (stop.station) (stop.routeIds + parentRoutes[stop.id].orEmpty()).distinct() else stop.routeIds
            val modes = ids.mapNotNull { byId[it]?.transportMode }.distinct()
            stop.copy(routeIds = ids, transportMode = modePriority.firstOrNull { it in modes })
        }
        OfflineCatalogue(meta, stops, routes)
    }
    fun board(ids: List<String>, now: Instant, minutes: Int): List<Departure> = boards(listOf(ids), now, minutes).single()

    /** One database handle/snapshot serves the selected, nearby and favourite boards. */
    fun boards(scopes: List<List<String>>, now: Instant, minutes: Int): List<List<Departure>> = store.read { db, meta ->
        val day = now.atZone(TripPlanner.zone).toLocalDate()
        if (day < meta.validFrom || day > meta.validUntil) return@read scopes.map { emptyList() }
        val cache = mutableMapOf<List<String>, List<Departure>>()
        scopes.map { ids -> cache.getOrPut(ids.distinct().sorted()) { board(db, ids.distinct(), now, minutes) } }
    }

    private fun board(db: SQLiteDatabase, ids: List<String>, now: Instant, minutes: Int): List<Departure> {
        val result = mutableListOf<Departure>()
        val day = now.atZone(TripPlanner.zone).toLocalDate()
        val end = now.plusSeconds(minutes * 60L)
        val lastDay = end.atZone(TripPlanner.zone).toLocalDate()
        val boardingStops = linkedMapOf<Long, String>()
        for (id in ids) {
            db.rawQuery("SELECT id,source_id,station FROM stops WHERE source_id=?", arrayOf(sourceStopId(id))).use { selected ->
                if (selected.moveToFirst()) {
                    if (selected.getInt(2) == 0) boardingStops[selected.getLong(0)] = id
                    else db.rawQuery("SELECT id,source_id FROM stops WHERE parent=? AND station=0", arrayOf(selected.getString(1))).use { children ->
                        while (children.moveToNext()) boardingStops[children.getLong(0)] = stopAppId(children.getString(1))
                    }
                }
            }
        }
        for ((number, id) in boardingStops) {
            var serviceDay = day.minusDays(2)
            while (serviceDay <= lastDay) {
                val base = serviceInstant(serviceDay, 0)
                val lower = java.time.Duration.between(base, now).seconds.coerceAtLeast(0)
                val upper = java.time.Duration.between(base, end).seconds
                if (lower <= upper) {
                    db.rawQuery("""SELECT r.source_id,r.number,t.headsign,s.departure,d.day,t.direction,t.restricted,st.platform
                        FROM stop_times s JOIN trips t ON t.id=s.trip JOIN routes r ON r.id=t.route
                        JOIN service_days d ON d.service=t.service JOIN stops st ON st.id=s.stop
                        WHERE s.stop=? AND s.pickup!=1 AND s.departure BETWEEN ? AND ? AND d.day=?""",
                        arrayOf(number.toString(), lower.toString(), upper.toString(), serviceDay.toEpochDay().toString())).use { c ->
                        while (c.moveToNext()) {
                            val time = serviceInstant(LocalDate.ofEpochDay(c.getLong(4)), c.getInt(3))
                            if (time >= now && time <= end) result += Departure(routeAppId(c.getString(0)), c.getString(1), c.getString(2), id,
                                time, time, ArrivalKind.SCHEDULED, directionId = c.getString(5), restrictedService = c.getInt(6) != 0, platformCode = c.getString(7))
                        }
                    }
                }
                serviceDay = serviceDay.plusDays(1)
            }
        }
        return result.sortedBy { it.expectedAt }.take(300)
    }
    fun line(appId: String): LineDetails? = store.read { db, _ ->
        val route = db.rawQuery("SELECT id,source_id,number,name,mode,color,feed FROM routes WHERE source_id=?", arrayOf(sourceRouteId(appId))).use { c ->
            if (c.moveToFirst()) c.getLong(0) to route(c) else null
        } ?: return@read null
        val patterns = linkedMapOf<List<String>, LinePattern>()
        // Collapse repeated timetable trips by exact ordered stop sequence in SQLite.
        // Only materialize stop objects for distinct patterns, rather than every trip.
        db.rawQuery("""SELECT min(t.id),t.headsign,t.direction FROM trips t JOIN (
            SELECT trip,group_concat(stop, ',') AS sequence FROM (
                SELECT s.trip,s.stop FROM stop_times s JOIN trips r ON r.id=s.trip
                WHERE r.route=? ORDER BY s.trip,s.seq
            ) GROUP BY trip
        ) p ON p.trip=t.id GROUP BY t.headsign,t.direction,p.sequence""", arrayOf(route.first.toString())).use { trips ->
            while (trips.moveToNext()) {
                val stops = db.rawQuery("SELECT st.id,st.source_id,st.name,st.lat,st.lon,st.platform,st.parent,st.station,st.feed FROM stop_times s JOIN stops st ON st.id=s.stop WHERE s.trip=? ORDER BY s.seq", arrayOf(trips.getLong(0).toString())).use { c -> buildList { while (c.moveToNext()) add(stop(c)) } }
                val key = listOf(trips.getString(1), trips.getString(2)) + stops.map { it.id }
                if (key !in patterns && stops.size > 1) {
                    val id = "offline-pattern:${trips.getLong(0)}"
                    patterns[key] = LinePattern(id, setOf(id), trips.getString(1), stops, emptyList(), directionId = trips.getString(2))
                }
            }
        }
        LineDetails(route.second, patterns.values.toList())
    }
    companion object {
        private val modePriority = listOf("SUBWAY", "SUBURBAN_RAILWAY", "RAIL", "TRAMTRAIN", "TRAM", "TROLLEYBUS", "BUS", "COACH", "FERRY", "CABLE_CAR", "GONDOLA", "FUNICULAR")
        internal fun stop(c: Cursor): TransitStop = TransitStop(stopAppId(c.getString(1)), c.getString(2), GeoPoint(c.getDouble(3), c.getDouble(4)), emptyList(),
            providerId = c.getString(8), platformCode = c.getString(5).takeIf { it.isNotBlank() }, parentStationId = c.getString(6).takeIf { it.isNotBlank() }?.let(::stopAppId), station = c.getInt(7) == 1)
        internal fun route(c: Cursor): TransitRoute {
            val number = c.getString(2)
            val name = c.getString(3)
            val mode = c.getString(4)
            val hev = c.getString(6) == "bkk" && mode == "RAIL" && HevSupport.number(number, name) != null
            return TransitRoute(routeAppId(c.getString(1)), number, name, emptyList(), c.getString(6),
                transportMode = if (hev) "SUBURBAN_RAILWAY" else mode,
                color = c.getString(5).takeIf { it.matches(routeHexColor) } ?: if (hev) HevSupport.color(HevSupport.number(number, name)!!) else null)
        }
        // GTFS service-day origin is local noon minus twelve hours, including DST transition days.
        internal fun serviceInstant(date: LocalDate, seconds: Int): Instant = date.atTime(12, 0).atZone(TripPlanner.zone).toInstant().minusSeconds(43_200).plusSeconds(seconds.toLong())
    }
}
