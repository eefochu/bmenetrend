package hu.app.bmenetrend.offline

import hu.app.bmenetrend.data.EmmaGraphqlClient
import hu.app.bmenetrend.data.RailSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bounded EMMA batches become a scheduled GTFS snapshot, consumed by the normal importer. */
internal class EmmaRailSnapshot(
    private val execute: suspend (String, JSONObject) -> String = { query, variables ->
        EmmaGraphqlClient(EmmaGraphqlClient.plannerHttpClient()).execute(query, variables)
    },
) {
    suspend fun download(target: File, from: LocalDate, until: LocalDate,
        onProgress: suspend (Long, Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val work = File(target.parentFile, "emma-rail-staging")
        work.deleteRecursively()
        check(work.mkdirs())
        val partial = File(target.parentFile, "${target.name}.part")
        try {
            val catalogue = EmmaGraphqlClient.data(execute(CATALOGUE_QUERY, JSONObject())).getJSONArray("routes")
            val ids = (0 until catalogue.length()).map { catalogue.getJSONObject(it) }
                .filter { isRail(it) }.map { it.getString("gtfsId") }.distinct()
            require(ids.isNotEmpty()) { "Missing EMMA rail catalogue" }
            SnapshotWriter(work, from, until).use { writer ->
                onProgress(0, ids.size.toLong())
                var completed = 0L
                for (batch in ids.chunked(BATCH_SIZE)) {
                    currentCoroutineContext().ensureActive()
                    // Serial requests alone can still burst fast enough to be denied by EMMA.
                    // Pace bulk downloads independently of the shared denial backoff.
                    delay(1_000)
                    val routes = EmmaGraphqlClient.data(execute(ROUTES_QUERY, JSONObject().put("ids", JSONArray(batch)))).getJSONArray("routes")
                    // Never activate a silently truncated or partial network snapshot.
                    val returned = (0 until routes.length()).map { routes.getJSONObject(it).getString("gtfsId") }
                    require(returned.size == batch.size && returned.toSet() == batch.toSet()) { "Incomplete EMMA rail batch" }
                    for (i in 0 until routes.length()) writer.route(routes.getJSONObject(i))
                    require(work.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= OFFLINE_DATABASE_MAX_BYTES) { "size" }
                    completed += batch.size
                    onProgress(completed, ids.size.toLong())
                }
                require(writer.tripCount > 0) { "No EMMA rail service in downloaded date range" }
            }
            ZipOutputStream(partial.outputStream().buffered()).use { zip ->
                for (file in work.listFiles().orEmpty()) {
                    currentCoroutineContext().ensureActive()
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            require(partial.length() in 1..OFFLINE_MAX_BYTES) { "size" }
            check(partial.renameTo(target)) { "Cannot store EMMA rail snapshot" }
        } finally {
            partial.delete()
            work.deleteRecursively()
        }
    }

    companion object {
        internal const val BATCH_SIZE = 256
        internal const val CATALOGUE_QUERY = "{routes(feeds:[\"1\"]){gtfsId mode}}"
        internal val ROUTES_QUERY = """query OfflineRail(${'$'}ids:[String!]) {
            routes(ids:${'$'}ids) { gtfsId shortName longName mode color
                stops { gtfsId name lat lon platformCode locationType parentStation { gtfsId name lat lon } }
                trips { gtfsId activeDates tripHeadsign directionId
                    stoptimes { scheduledArrival scheduledDeparture pickupType dropoffType
                        stop { gtfsId }
                    }
                }
            }
        }""".trimIndent()
        internal fun isRail(route: JSONObject) = route.getString("gtfsId").startsWith("1:") &&
            (RailSupport.isTrain(route.optString("mode")) || route.optString("mode") == "RAIL_REPLACEMENT_BUS")
    }
}

/** Keeps only stop/trip identities in memory; large timetable rows are streamed to disk. */
internal class SnapshotWriter(private val directory: File, private val from: LocalDate, private val until: LocalDate) : AutoCloseable {
    private val writers = mutableListOf<BufferedWriter>()
    private fun table(name: String, header: String) = File(directory, name).bufferedWriter().also {
        writers += it
        it.write(header); it.newLine()
    }
    private val stops = table("stops.txt", "stop_id,stop_name,stop_lat,stop_lon,platform_code,location_type,parent_station")
    private val routes = table("routes.txt", "route_id,route_short_name,route_long_name,route_type,route_color")
    private val trips = table("trips.txt", "trip_id,route_id,service_id,trip_headsign,direction_id")
    private val dates = table("calendar_dates.txt", "service_id,date,exception_type")
    private val times = table("stop_times.txt", "trip_id,stop_sequence,stop_id,arrival_time,departure_time,pickup_type,drop_off_type")
    private val seenStops = hashSetOf<String>()
    private val seenTrips = hashSetOf<String>()
    private val services = hashMapOf<List<LocalDate>, String>()
    var tripCount = 0
        private set

    init {
        table("agency.txt", "agency_id,agency_name,agency_url,agency_timezone").row("mav", "MÁV – EMMA", "https://mavplusz.hu/", "Europe/Budapest")
    }

    fun route(route: JSONObject) {
        require(EmmaRailSnapshot.isRail(route)) { "Unexpected EMMA rail route" }
        val routeId = route.getString("gtfsId")
        // Route-level stop metadata avoids repeating coordinates/names for every train call.
        // Embedded stop-time metadata can also supply missing catalogue records.
        route.optJSONArray("stops")?.let { rows ->
            for (i in 0 until rows.length()) stop(rows.getJSONObject(i))
        }
        routes.row(routeId, RailSupport.number(route.text("shortName"), route.text("longName")), route.text("longName").orEmpty(),
            if (route.optString("mode") == "RAIL_REPLACEMENT_BUS") 3 else if (route.optString("mode") == "TRAMTRAIN") 900 else 2, route.text("color").orEmpty())
        val rows = route.getJSONArray("trips")
        for (i in 0 until rows.length()) {
            val trip = rows.getJSONObject(i)
            val active = trip.getJSONArray("activeDates")
            val days = (0 until active.length()).map { LocalDate.parse(active.getString(it), DateTimeFormatter.BASIC_ISO_DATE) }
                .filter { it in from..until }.distinct().sorted()
            if (days.isEmpty()) continue
            val id = trip.getString("gtfsId")
            require(id.startsWith("1:") && seenTrips.add(id)) { "Duplicate or invalid EMMA rail trip" }
            val stopTimes = trip.getJSONArray("stoptimes")
            require(stopTimes.length() >= 2) { "Missing EMMA rail stop times" }
            val service = services.getOrPut(days) {
                "service-${services.size}".also { service ->
                    days.forEach { dates.row(service, it.format(DateTimeFormatter.BASIC_ISO_DATE), 1) }
                }
            }
            trips.row(id, routeId, service, trip.text("tripHeadsign").orEmpty(), trip.text("directionId").orEmpty())
            for (j in 0 until stopTimes.length()) {
                val time = stopTimes.getJSONObject(j)
                val stop = time.getJSONObject("stop")
                stop(stop)
                times.row(id, j, stop.getString("gtfsId"), clock(time.getInt("scheduledArrival")), clock(time.getInt("scheduledDeparture")),
                    pickup(time.getString("pickupType")), pickup(time.getString("dropoffType")))
            }
            tripCount++
        }
    }

    private fun stop(stop: JSONObject, station: Boolean = false) {
        val id = stop.getString("gtfsId")
        require(id.startsWith("1:")) { "Invalid EMMA rail stop" }
        if (!seenStops.add(id)) return
        val parent = stop.optJSONObject("parentStation")
        stops.row(id, stop.getString("name"), stop.getDouble("lat"), stop.getDouble("lon"), stop.text("platformCode").orEmpty(),
            if (station || stop.optString("locationType") == "STATION") 1 else 0, parent?.getString("gtfsId").orEmpty())
        if (parent != null) stop(parent, station = true)
    }

    private fun clock(seconds: Int): String {
        require(seconds in 0..259199) { "Invalid EMMA scheduled time" }
        return "%02d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
    private fun pickup(value: String) = when (value) {
        "SCHEDULED" -> 0; "NONE" -> 1; "CALL_AGENCY" -> 2; "COORDINATE_WITH_DRIVER" -> 3
        else -> error("Unsupported EMMA pickup/dropoff: $value")
    }
    private fun JSONObject.text(key: String) = optString(key).takeUnless { it.isBlank() || it == "null" }
    private fun BufferedWriter.row(vararg values: Any) {
        write(values.joinToString(",") { "\"${it.toString().replace("\"", "\"\"")}\"" }); newLine()
    }
    override fun close() { writers.forEach { it.close() } }
}
