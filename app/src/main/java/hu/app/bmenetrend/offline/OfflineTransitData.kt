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

class OfflineTransitData(private val store: OfflinePackStore) {
    fun catalogue(): Pair<List<TransitStop>, List<TransitRoute>> = store.read { db, _ ->
        val servingRoutes = mutableMapOf<Long, MutableList<String>>()
        db.rawQuery("SELECT s.stop,r.source_id FROM stop_routes s JOIN routes r ON r.id=s.route", null).use { c ->
            while (c.moveToNext()) servingRoutes.getOrPut(c.getLong(0)) { mutableListOf() }.add(routeAppId(c.getString(1)))
        }
        val stops = db.rawQuery("SELECT id,source_id,name,lat,lon,platform,parent,station,feed FROM stops", null).use { c -> buildList { while (c.moveToNext()) add(stop(c).copy(routeIds = servingRoutes[c.getLong(0)].orEmpty())) } }
        val routes = db.rawQuery("SELECT id,source_id,number,name,mode,color,feed FROM routes", null).use { c -> buildList { while (c.moveToNext()) add(route(c)) } }
        stops to routes
    }
    fun board(ids: List<String>, now: Instant, minutes: Int): List<Departure> = store.read { db, meta ->
        val result = mutableListOf<Departure>()
        val day = now.atZone(TripPlanner.zone).toLocalDate()
        if (day < meta.validFrom || day > meta.validUntil) return@read emptyList()
        for (id in ids) {
            val number = db.rawQuery("SELECT id FROM stops WHERE source_id=?", arrayOf(sourceStopId(id))).use { if (it.moveToFirst()) it.getLong(0) else null } ?: continue
            db.rawQuery("""SELECT r.source_id,r.number,t.headsign,s.departure,d.day,t.direction,t.restricted,st.platform
                FROM stop_times s JOIN trips t ON t.id=s.trip JOIN routes r ON r.id=t.route
                JOIN service_days d ON d.service=t.service JOIN stops st ON st.id=s.stop
                WHERE s.stop=? AND s.pickup!=1 AND d.day BETWEEN ? AND ? ORDER BY d.day,s.departure""",
                arrayOf(number.toString(), day.minusDays(2).toEpochDay().toString(), day.plusDays(1).toEpochDay().toString())).use { c ->
                while (c.moveToNext()) {
                    val time = serviceInstant(LocalDate.ofEpochDay(c.getLong(4)), c.getInt(3))
                    if (time >= now && time <= now.plusSeconds(minutes * 60L)) result += Departure(routeAppId(c.getString(0)), c.getString(1), c.getString(2), id,
                        time, time, ArrivalKind.SCHEDULED, directionId = c.getString(5), restrictedService = c.getInt(6) != 0, platformCode = c.getString(7))
                }
            }
        }
        result.sortedBy { it.expectedAt }.take(300)
    }
    fun line(appId: String): LineDetails? = store.read { db, _ ->
        val route = db.rawQuery("SELECT id,source_id,number,name,mode,color,feed FROM routes", null).use { c ->
            var found: Pair<Long, TransitRoute>? = null
            while (c.moveToNext()) if (routeAppId(c.getString(1)) == appId) { found = c.getLong(0) to route(c); break }
            found
        } ?: return@read null
        val patterns = linkedMapOf<List<String>, LinePattern>()
        db.rawQuery("SELECT id,headsign,direction FROM trips WHERE route=?", arrayOf(route.first.toString())).use { trips ->
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
        internal fun stop(c: Cursor): TransitStop = TransitStop(stopAppId(c.getString(1)), c.getString(2), GeoPoint(c.getDouble(3), c.getDouble(4)), emptyList(),
            providerId = c.getString(8), platformCode = c.getString(5).takeIf { it.isNotBlank() }, parentStationId = c.getString(6).takeIf { it.isNotBlank() }?.let(::stopAppId), station = c.getInt(7) == 1)
        internal fun route(c: Cursor) = TransitRoute(routeAppId(c.getString(1)), c.getString(2), c.getString(3), emptyList(), c.getString(6), transportMode = c.getString(4), color = c.getString(5).takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) })
        // GTFS service-day origin is local noon minus twelve hours, including DST transition days.
        internal fun serviceInstant(date: LocalDate, seconds: Int): Instant = date.atTime(12, 0).atZone(TripPlanner.zone).toInstant().minusSeconds(43_200).plusSeconds(seconds.toLong())
    }
}
