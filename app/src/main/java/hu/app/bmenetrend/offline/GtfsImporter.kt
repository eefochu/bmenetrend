package hu.app.bmenetrend.offline

import android.database.sqlite.SQLiteDatabase
import hu.app.bmenetrend.data.TransitSearch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile

/** Stream directly from the archive into an indexed database; never extract untrusted ZIP paths. */
class GtfsImporter(private val db: SQLiteDatabase, private val from: LocalDate, private val until: LocalDate,
    private val onProgress: suspend (OfflineImportProgress) -> Unit = {},
) {
    private var rowCount = 0
    private val pendingValidation = mutableSetOf<Long>()
    val warnings = linkedSetOf<String>()
    suspend fun import(file: File, source: OfflineSource) {
        require(file.length() in 1..OFFLINE_MAX_BYTES)
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
            require(entries.size < 1000 && entries.sumOf { it.size.coerceAtLeast(0) } < 8_000_000_000L) { "Oversized GTFS archive" }
            require(entries.groupBy { it.name.substringAfterLast('/').lowercase() }.filterKeys { it.endsWith(".txt") }.values.none { it.size > 1 }) { "Duplicate GTFS files" }
            val byName = entries.associateBy { it.name.substringAfterLast('/').lowercase() }
            suspend fun rows(name: String, required: Boolean = false, action: (Map<String, String>) -> Unit) {
                val entry = byName[name] ?: if (required) error("Missing $name") else return
                var consumed = 0L
                var records = 0L
                var lastReport = System.nanoTime()
                val step = when (name) {
                    "stops.txt" -> OfflineImportStep.STOPS; "routes.txt" -> OfflineImportStep.ROUTES
                    "trips.txt" -> OfflineImportStep.TRIPS; "stop_times.txt" -> OfflineImportStep.STOP_TIMES
                    "transfers.txt", "pathways.txt" -> OfflineImportStep.TRANSFERS
                    else -> OfflineImportStep.CALENDARS
                }
                onProgress(OfflineImportProgress(step, total = entry.size.coerceAtLeast(0)))
                val checksum = java.util.zip.CRC32()
                zip.getInputStream(entry).use { raw ->
                    val input = java.util.zip.CheckedInputStream(raw, checksum)
                    // Bound expansion even when an archive has no trustworthy size metadata.
                    val bounded = object : java.io.FilterInputStream(input) {
                        override fun read(): Int = super.read().also { if (it >= 0) { consumed++; require(consumed <= 4_000_000_000L) } }
                        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { consumed += it; require(consumed <= 4_000_000_000L) } }
                    }
                    GtfsCsv(bounded.reader(Charsets.UTF_8)).use { csv ->
                        while (true) {
                            val row = csv.next() ?: break
                            records++
                            if (++rowCount % 1024 == 0) {
                                currentCoroutineContext().ensureActive()
                                require(File(db.path).length() <= OFFLINE_DATABASE_MAX_BYTES) { "Installed pack exceeds 500 MB" }
                                val now = System.nanoTime()
                                if (now - lastReport >= 750_000_000L) {
                                    onProgress(OfflineImportProgress(step, records, consumed, entry.size.coerceAtLeast(0)))
                                    lastReport = now
                                }
                            }
                            action(row)
                        }
                    }
                }
                require(entry.crc < 0 || checksum.value == entry.crc) { "Corrupted GTFS entry" }
                onProgress(OfflineImportProgress(step, records, consumed, consumed))
            }
            fun key(id: String) = "${source.id}:$id"
            var coverageFirst: LocalDate? = null
            var coverageLast: LocalDate? = null
            rows("feed_info.txt") { r ->
                coverageFirst = r["feed_start_date"]?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }
                coverageLast = r["feed_end_date"]?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }
            }
            val services = mutableMapOf<String, MutableSet<LocalDate>>()
            val dateFormat = DateTimeFormatter.BASIC_ISO_DATE
            val weekdays = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
            rows("agency.txt", required = true) { row ->
                require(row["agency_timezone"] == "Europe/Budapest") { "Unsupported GTFS timezone" }
            }
            rows("calendar.txt") { r ->
                val first = LocalDate.parse(r.getValue("start_date"), dateFormat)
                val last = LocalDate.parse(r.getValue("end_date"), dateFormat)
                val days = services.getOrPut(r.getValue("service_id")) { linkedSetOf() }
                var day = maxOf(first, from)
                while (day <= minOf(last, until)) {
                    if (r[weekdays[day.dayOfWeek.value - 1]] == "1") days += day
                    day = day.plusDays(1)
                }
            }
            rows("calendar_dates.txt") { r ->
                val day = LocalDate.parse(r.getValue("date"), dateFormat)
                if (day in from..until) {
                    val days = services.getOrPut(r.getValue("service_id")) { linkedSetOf() }
                    when (r.getValue("exception_type")) { "1" -> days += day; "2" -> days -= day; else -> error("Invalid service exception") }
                }
            }
            val serviceIds = mutableMapOf<String, Long>()
            val insertService = db.compileStatement("INSERT INTO services(source_id) VALUES(?)")
            val insertDay = db.compileStatement("INSERT INTO service_days(service,day) VALUES(?,?)")
            services.filterValues { it.isNotEmpty() }.forEach { (id, dates) ->
                insertService.bindString(1, key(id)); val number = insertService.executeInsert(); serviceIds[id] = number
                dates.forEach { date -> insertDay.bindLong(1, number); insertDay.bindLong(2, date.toEpochDay()); insertDay.executeInsert() }
            }
            require(serviceIds.isNotEmpty()) { "GTFS has no service in downloaded date range: ${source.name}" }
            val stops = mutableMapOf<String, Long>()
            val insertStop = db.compileStatement("INSERT INTO stops(source_id,name,search,lat,lon,platform,parent,station,feed) VALUES(?,?,?,?,?,?,?,?,?)")
            rows("stops.txt", required = true) { r ->
                val lat = r["stop_lat"]?.toDoubleOrNull(); val lon = r["stop_lon"]?.toDoubleOrNull()
                if (lat != null && lon != null && lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
                    val name = r["stop_name"].orEmpty().ifBlank { r.getValue("stop_id") }
                    listOf(key(r.getValue("stop_id")), name, TransitSearch.normalize(name)).forEachIndexed { i, s -> insertStop.bindString(i + 1, s) }
                    insertStop.bindDouble(4, lat); insertStop.bindDouble(5, lon)
                    insertStop.bindString(6, r["platform_code"].orEmpty()); insertStop.bindString(7, r["parent_station"]?.takeIf { it.isNotBlank() }?.let(::key).orEmpty())
                    insertStop.bindLong(8, if (r["location_type"] == "1") 1 else 0); insertStop.bindString(9, source.id)
                    stops[r.getValue("stop_id")] = insertStop.executeInsert()
                }
            }
            val routes = mutableMapOf<String, Long>()
            val insertRoute = db.compileStatement("INSERT INTO routes(source_id,number,name,mode,color,feed) VALUES(?,?,?,?,?,?)")
            rows("routes.txt", required = true) { r ->
                val type = r.getValue("route_type").toInt()
                val mode = when { type == 0 || type in 900..999 -> "TRAM"; type == 1 || type in 400..499 -> "SUBWAY"; type == 2 || type in 100..199 -> "RAIL"; type == 4 || type in 1000..1099 -> "FERRY"; type == 11 || type in 800..899 -> "TROLLEYBUS"; type == 3 || type in 200..299 || type in 700..799 -> "BUS"; type == 5 -> "CABLE_CAR"; type == 6 -> "GONDOLA"; type == 7 -> "FUNICULAR"; else -> error("Unsupported GTFS route type: $type") }
                listOf(key(r.getValue("route_id")), r["route_short_name"].orEmpty().ifBlank { r["route_long_name"].orEmpty() }, r["route_long_name"].orEmpty(), mode, r["route_color"].orEmpty(), source.id)
                    .forEachIndexed { i, s -> insertRoute.bindString(i + 1, s) }
                routes[r.getValue("route_id")] = insertRoute.executeInsert()
            }
            val frequencyTrips = mutableSetOf<String>()
            rows("frequencies.txt") { frequencyTrips += it.getValue("trip_id") }
            require(frequencyTrips.isEmpty()) { "Frequency-based GTFS needs expansion before import" }
            val trips = mutableMapOf<String, Long>()
            val insertTrip = db.compileStatement("INSERT INTO trips(source_id,route,service,headsign,direction,restricted) VALUES(?,?,?,?,?,0)")
            rows("trips.txt", required = true) { r ->
                val service = serviceIds[r.getValue("service_id")]
                val route = routes[r.getValue("route_id")]
                if (service != null && route != null) {
                    insertTrip.bindString(1, key(r.getValue("trip_id"))); insertTrip.bindLong(2, route); insertTrip.bindLong(3, service)
                    insertTrip.bindString(4, r["trip_headsign"].orEmpty()); insertTrip.bindString(5, r["direction_id"].orEmpty())
                    trips[r.getValue("trip_id")] = insertTrip.executeInsert()
                }
            }
            val firstTrip = trips.values.minOrNull() ?: 0L
            val lastTrip = trips.values.maxOrNull() ?: -1L
            val sequenceByTrip = LongArray((lastTrip - firstTrip + 1).toInt()) { -1L }
            val departureByTrip = IntArray(sequenceByTrip.size)
            val invalidTrips = mutableSetOf<Long>()
            val restrictedTrips = mutableSetOf<Long>()
            val insertTime = db.compileStatement("INSERT INTO stop_times(trip,seq,stop,arrival,departure,pickup,dropoff) VALUES(?,?,?,?,?,?,?)")
            rows("stop_times.txt", required = true) { r ->
                val trip = trips[r.getValue("trip_id")]
                if (trip != null) {
                    val stop = stops[r.getValue("stop_id")]
                    val arr = seconds(r["arrival_time"].orEmpty()); val dep = seconds(r["departure_time"].orEmpty())
                    if (stop == null || arr == null || dep == null || dep < arr || !r["start_pickup_drop_off_window"].isNullOrBlank()) invalidTrips += trip
                    else {
                        val sequence = r.getValue("stop_sequence").toLong()
                        require(sequence >= 0) { "Negative stop sequence" }
                        val index = (trip - firstTrip).toInt()
                        val previous = sequenceByTrip[index]
                        if (previous >= 0 && sequence < previous) pendingValidation += trip
                        if (previous >= 0 && sequence > previous && arr < departureByTrip[index]) invalidTrips += trip
                        sequenceByTrip[index] = sequence; departureByTrip[index] = dep
                        val pickup = r["pickup_type"]?.toLongOrNull() ?: 0L; val dropoff = r["drop_off_type"]?.toLongOrNull() ?: 0L
                        insertTime.bindLong(1, trip); insertTime.bindLong(2, sequence); insertTime.bindLong(3, stop)
                        insertTime.bindLong(4, arr.toLong()); insertTime.bindLong(5, dep.toLong()); insertTime.bindLong(6, pickup); insertTime.bindLong(7, dropoff)
                        insertTime.executeInsert()
                        if ((pickup > 1 || dropoff > 1) && restrictedTrips.add(trip)) db.execSQL("UPDATE trips SET restricted=1 WHERE id=?", arrayOf(trip))
                    }
                }
            }
            pendingValidation.removeAll(invalidTrips)
            invalidTrips.forEach { db.execSQL("DELETE FROM stop_times WHERE trip=?", arrayOf(it)); db.execSQL("DELETE FROM trips WHERE id=?", arrayOf(it)) }
            if (invalidTrips.isNotEmpty()) warnings += "${source.name}: ${invalidTrips.size} incomplete trips excluded"
            val insertTransfer = db.compileStatement("INSERT INTO transfers(from_stop,to_stop,seconds,forbidden,from_route,to_route,from_trip,to_trip) VALUES(?,?,?,?,?,?,?,?)")
            rows("transfers.txt") { r ->
                val a = stops[r["from_stop_id"]]; val b = stops[r["to_stop_id"]]
                if (a != null && b != null) {
                    val type = r["transfer_type"]?.toIntOrNull() ?: 0
                    if (listOf("from_route_id", "to_route_id").any { !r[it].isNullOrBlank() && r[it] !in routes } ||
                        listOf("from_trip_id", "to_trip_id").any { !r[it].isNullOrBlank() && r[it] !in trips }) return@rows
                    // In-seat transfers require a through-trip model; do not guess their semantics.
                    require(type !in 4..5) { "In-seat GTFS transfers require preprocessing" }
                    insertTransfer.bindLong(1, a); insertTransfer.bindLong(2, b); insertTransfer.bindLong(3, (r["min_transfer_time"]?.toLongOrNull() ?: if (a == b) 0 else 180).coerceAtLeast(0))
                    insertTransfer.bindLong(4, if (type == 3) 1 else 0)
                    insertTransfer.bindLong(5, routes[r["from_route_id"]] ?: 0); insertTransfer.bindLong(6, routes[r["to_route_id"]] ?: 0)
                    insertTransfer.bindLong(7, trips[r["from_trip_id"]] ?: 0); insertTransfer.bindLong(8, trips[r["to_trip_id"]] ?: 0)
                    insertTransfer.executeInsert()
                }
            }
            rows("pathways.txt") { r ->
                val a = stops[r["from_stop_id"]]; val b = stops[r["to_stop_id"]]
                val time = r["traversal_time"]?.toLongOrNull() ?: r["length"]?.toDoubleOrNull()?.let { kotlin.math.ceil(it / 1.15).toLong() }
                if (a != null && b != null && time != null && time >= 0) {
                    db.execSQL("INSERT INTO paths VALUES(?,?,?)", arrayOf(a, b, time))
                    if (r["is_bidirectional"] == "1") db.execSQL("INSERT INTO paths VALUES(?,?,?)", arrayOf(b, a, time))
                }
            }
            val days = services.values.flatten()
            db.execSQL("INSERT INTO feeds VALUES(?,?,?,?,?)", arrayOf<Any>(source.id, source.name, source.url, (coverageFirst ?: days.minOrNull()!!).toEpochDay(), (coverageLast ?: days.maxOrNull()!!).toEpochDay()))
        }
    }

    suspend fun importLocal(network: hu.app.bmenetrend.data.TransitNetwork): List<String> {
        onProgress(OfflineImportProgress(OfflineImportStep.LOCAL_TIMETABLES))
        val validRoutes = network.routes.filter { it.providerId in network.verifiedProviderIds }
        val routeIds = validRoutes.map { it.id }.toSet()
        val validStops = network.stops.filter { stop -> stop.routeIds.any { it in routeIds } }
        val stopIds = mutableMapOf<String, Long>()
        validStops.forEach { stop ->
            val statement = db.compileStatement("INSERT INTO stops(source_id,name,search,lat,lon,platform,parent,station,feed) VALUES(?,?,?,?,?,?,?,0,?)")
            statement.bindString(1, "local:${stop.id}"); statement.bindString(2, stop.name); statement.bindString(3, TransitSearch.normalize(stop.name))
            statement.bindDouble(4, stop.position.lat); statement.bindDouble(5, stop.position.lon); statement.bindString(6, stop.platformCode.orEmpty()); statement.bindString(7, "")
            statement.bindString(8, stop.providerId); stopIds[stop.id] = statement.executeInsert(); statement.close()
        }
        val names = mutableListOf<String>()
        for (provider in validRoutes.map { it.providerId }.distinct()) {
            var first: LocalDate? = null; var last: LocalDate? = null
            val providerRoutes = validRoutes.filter { it.providerId == provider }
            for (route in providerRoutes) {
                currentCoroutineContext().ensureActive()
                val rs = db.compileStatement("INSERT INTO routes(source_id,number,name,mode,color,feed) VALUES(?,?,?,?,?,?)")
                listOf("local:${route.id}", route.number, route.name, "BUS", route.color.orEmpty(), route.providerId).forEachIndexed { i, value -> rs.bindString(i+1, value) }
                val routeId = rs.executeInsert(); rs.close()
                for (trip in network.trips.filter { it.routeId == route.id }) {
                    val dates = mutableListOf<LocalDate>()
                    var day = from
                    while (day <= until && day <= LocalDate.of(2026, 12, 31)) {
                        if (hu.app.bmenetrend.data.LocalTimetableRouter.calendar(provider, day) == trip.calendar) dates += day
                        day = day.plusDays(1)
                    }
                    if (dates.isEmpty() || trip.stopTimes.any { it.stopId !in stopIds } || trip.stopTimes.zipWithNext().any { (a, b) -> b.minuteOfDay < a.minuteOfDay }) continue
                    val ss = db.compileStatement("INSERT INTO services(source_id) VALUES(?)"); ss.bindString(1, "local:${trip.id}")
                    val service = ss.executeInsert(); ss.close()
                    dates.forEach { db.execSQL("INSERT INTO service_days VALUES(?,?)", arrayOf(service, it.toEpochDay())) }
                    first = minOf(first ?: dates.first(), dates.first()); last = maxOf(last ?: dates.last(), dates.last())
                    val direction = route.directions.find { it.id == trip.directionId }
                    val ts = db.compileStatement("INSERT INTO trips(source_id,route,service,headsign,direction,restricted) VALUES(?,?,?,?,?,?)")
                    ts.bindString(1, "local:${trip.id}"); ts.bindLong(2, routeId); ts.bindLong(3, service); ts.bindString(4, direction?.name.orEmpty()); ts.bindString(5, trip.directionId)
                    ts.bindLong(6, if (direction?.restrictedService == true) 1 else 0); val tripId = ts.executeInsert(); ts.close()
                    trip.stopTimes.forEachIndexed { i, time -> db.execSQL("INSERT INTO stop_times VALUES(?,?,?,?,?,0,0)", arrayOf(tripId, i, stopIds.getValue(time.stopId), time.minuteOfDay * 60, time.minuteOfDay * 60)) }
                }
            }
            if (first != null && last != null) {
                db.execSQL("INSERT INTO feeds VALUES(?,?,?,?,?)", arrayOf<Any>(provider, provider, network.source, from.toEpochDay(), minOf(until, LocalDate.of(2026, 12, 31)).toEpochDay()))
                names += provider
            }
        }
        return names
    }

    suspend fun finish() = finishDatabase(db, onProgress, pendingValidation.toList())

    companion object {
        fun seconds(value: String): Int? {
            val p = value.trim().split(':').map { it.toIntOrNull() ?: return null }
            if (p.size != 3 || p[0] !in 0..71 || p[1] !in 0..59 || p[2] !in 0..59) return null
            return p[0] * 3600 + p[1] * 60 + p[2]
        }
        fun create(file: File): SQLiteDatabase = SQLiteDatabase.openOrCreateDatabase(file, null).apply {
            // Staging build: journal to memory and skip fsyncs. A torn staging file is disposable
            // (staging() deletes it; activation only promotes a verified digest + quick_check pass).
            // This removes rollback-journal double-writes and full-durability syncs from the bulk load.
            rawQuery("PRAGMA journal_mode=MEMORY", null).use { it.moveToFirst() }; execSQL("PRAGMA synchronous=OFF"); version = OFFLINE_FORMAT
            execSQL("CREATE TABLE stops(id INTEGER PRIMARY KEY,source_id TEXT UNIQUE NOT NULL,name TEXT NOT NULL,search TEXT NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,platform TEXT,parent TEXT,station INTEGER,feed TEXT)")
            execSQL("CREATE TABLE routes(id INTEGER PRIMARY KEY,source_id TEXT UNIQUE NOT NULL,number TEXT,name TEXT,mode TEXT,color TEXT,feed TEXT)")
            execSQL("CREATE TABLE services(id INTEGER PRIMARY KEY,source_id TEXT UNIQUE NOT NULL)")
            execSQL("CREATE TABLE service_days(service INTEGER,day INTEGER,PRIMARY KEY(service,day)) WITHOUT ROWID")
            execSQL("CREATE TABLE trips(id INTEGER PRIMARY KEY,source_id TEXT UNIQUE NOT NULL,route INTEGER,service INTEGER,headsign TEXT,direction TEXT,restricted INTEGER)")
            execSQL("CREATE TABLE stop_times(trip INTEGER,seq INTEGER,stop INTEGER,arrival INTEGER,departure INTEGER,pickup INTEGER,dropoff INTEGER,PRIMARY KEY(trip,seq)) WITHOUT ROWID")
            execSQL("CREATE TABLE transfers(from_stop INTEGER,to_stop INTEGER,seconds INTEGER,forbidden INTEGER,from_route INTEGER,to_route INTEGER,from_trip INTEGER,to_trip INTEGER)")
            execSQL("CREATE TABLE paths(from_stop INTEGER,to_stop INTEGER,seconds INTEGER)")
            execSQL("CREATE TABLE feeds(id TEXT PRIMARY KEY,name TEXT,url TEXT,first_day INTEGER,last_day INTEGER)")
        }
        suspend fun finish(db: SQLiteDatabase, onProgress: suspend (OfflineImportProgress) -> Unit = {}) = finishDatabase(db, onProgress, null)

        private suspend fun finishDatabase(db: SQLiteDatabase, onProgress: suspend (OfflineImportProgress) -> Unit, pending: List<Long>?) {
            currentCoroutineContext().ensureActive()
            onProgress(OfflineImportProgress(OfflineImportStep.VALIDATING))
            // Ordered records were already validated during import without another SQLite cursor
            // pass. Retain native indexed validation for unordered trips (or untracked callers).
            val batches: List<List<Long>?> = pending?.chunked(500) ?: listOf(null)
            for (batch in batches) {
                currentCoroutineContext().ensureActive()
                val clause = if (batch == null) "" else " AND a.trip IN (${batch.joinToString(",") { "?" }})"
                db.execSQL("DELETE FROM trips WHERE id IN (SELECT a.trip FROM stop_times a JOIN stop_times b ON a.trip=b.trip AND b.seq=(SELECT min(c.seq) FROM stop_times c WHERE c.trip=a.trip AND c.seq>a.seq) WHERE b.arrival<a.departure$clause)", batch?.toTypedArray() ?: emptyArray<Any>())
                if (batch != null) {
                    val marks = batch.joinToString(",") { "?" }
                    db.execSQL("DELETE FROM stop_times WHERE trip IN ($marks) AND trip NOT IN (SELECT id FROM trips)", batch.toTypedArray())
                } else db.execSQL("DELETE FROM stop_times WHERE trip NOT IN (SELECT id FROM trips)")
            }
            val total = db.rawQuery("SELECT count(*) FROM stop_times", null).use { it.moveToFirst(); it.getLong(0) }
            onProgress(OfflineImportProgress(OfflineImportStep.VALIDATING, total, total, total))
            currentCoroutineContext().ensureActive()
            onProgress(OfflineImportProgress(OfflineImportStep.CATALOGUE))
            db.execSQL("CREATE TABLE stop_routes(stop INTEGER,route INTEGER,PRIMARY KEY(stop,route)) WITHOUT ROWID")
            db.execSQL("INSERT INTO stop_routes SELECT DISTINCT s.stop,t.route FROM stop_times s JOIN trips t ON t.id=s.trip")
            val indexes = listOf(
                "CREATE INDEX boarding ON stop_times(stop,departure,trip)",
                "CREATE INDEX trip_service ON trips(service)",
                "CREATE INDEX service_date ON service_days(day,service)",
                "CREATE INDEX transfers_from ON transfers(from_stop)",
                "CREATE INDEX paths_from ON paths(from_stop)",
                "CREATE INDEX stops_search ON stops(search)",
            )
            indexes.forEachIndexed { index, sql ->
                currentCoroutineContext().ensureActive()
                onProgress(OfflineImportProgress(OfflineImportStep.INDEXING, completed = index.toLong(), total = indexes.size.toLong()))
                db.execSQL(sql)
            }
            currentCoroutineContext().ensureActive()
            onProgress(OfflineImportProgress(OfflineImportStep.OPTIMIZING))
            db.execSQL("ANALYZE")
        }
    }
}
