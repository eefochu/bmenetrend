package hu.app.bmenetrend.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant
import java.time.Duration

object EmmaStopsParser {
    const val BOARD_LIMIT = 300
    const val ROUTE_FIELDS = "gtfsId shortName longName mode color"
    const val STOP_FIELDS = "gtfsId name lat lon vehicleMode platformCode locationType parentStation { gtfsId name } routes { $ROUTE_FIELDS }"
    const val INDEX_QUERY = "{stops {gtfsId name lat lon vehicleMode platformCode locationType parentStation {gtfsId name}}}"
    val AREA_QUERY = """query StopsInArea(${'$'}south: Float!, ${'$'}west: Float!, ${'$'}north: Float!, ${'$'}east: Float!) {
        stopsByBbox(minLat:${'$'}south,minLon:${'$'}west,maxLat:${'$'}north,maxLon:${'$'}east) { $STOP_FIELDS }
    }""".trimIndent()
    val SEARCH_QUERY = """query FindStops(${'$'}name: String!) { stops(name:${'$'}name) { $STOP_FIELDS } }"""
    val BOARD_FIELDS = """$STOP_FIELDS
        stoptimesForPatterns(startTime:${'$'}start,timeRange:${'$'}range,numberOfDepartures:$BOARD_LIMIT,omitNonPickups:true) {
            pattern { code headsign }
            stoptimes {
                scheduledDeparture realtimeDeparture realtime realtimeState serviceDay headsign pickupType
                stop { gtfsId platformCode }
                trip { gtfsId tripShortName tripHeadsign directionId pattern { code } wheelchairAccessible bikesAllowed route { $ROUTE_FIELDS } }
            }
        }
    """.trimIndent()
    val BOARD_QUERY = """query StopBoard(${'$'}id:String!,${'$'}start:Long!,${'$'}range:Int!) {
        stop(id:${'$'}id) { $BOARD_FIELDS }
    }""".trimIndent()
    fun batchQuery(count: Int): String {
        require(count in 1..6)
        val declarations = (0 until count).joinToString(",") { "${'$'}id$it:String!" }
        return "query StationBoards(${'$'}start:Long!,${'$'}range:Int!,$declarations){" +
            (0 until count).joinToString("\n") { "board$it:stop(id:${'$'}id$it){$BOARD_FIELDS}" } + "}"
    }
    data class Catalogue(val stops: List<TransitStop>, val routes: List<TransitRoute>)
    data class Board(val stopId: String, val departures: List<Departure>, val catalogue: Catalogue, val fetchedAt: Instant, val limitReached: Boolean)
    private fun JSONObject.text(key: String) = optString(key).takeUnless { it.isBlank() || it == "null" }
    private fun JSONObject.number(key: String) = (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
    fun provider(sourceId: String): String? = when {
        sourceId.startsWith("hkir:") && sourceId.length > 5 -> "volan"
        sourceId.startsWith("BKK:") && sourceId.length > 4 -> "bkk"
        sourceId.startsWith("1:") && sourceId.length > 2 -> "mav"
        else -> null
    }
    fun sourceId(appId: String): String = appId.removePrefix("emma:").also { require(provider(it) != null) }
    internal fun route(row: JSONObject): TransitRoute? {
        val id = row.text("gtfsId") ?: return null
        val namespace = provider(id) ?: return null
        val mode = row.text("mode") ?: return null
        val provider = if (namespace == "mav") when {
            HevSupport.accepts(mode, row.text("shortName"), row.text("longName")) -> "hev"
            RailSupport.isTrain(mode) -> "mav"
            else -> return null
        } else namespace
        if (provider !in setOf("hev", "mav") && mode !in if (provider == "volan") setOf("BUS", "COACH") else setOf("BUS", "TRAM", "TROLLEYBUS", "SUBWAY")) return null
        val number = when (provider) {
            "hev" -> HevSupport.number(row.text("shortName"), row.text("longName")) ?: return null
            "mav" -> RailSupport.number(row.text("shortName"), row.text("longName"))
            else -> row.text("shortName") ?: return null
        }
        return TransitRoute("$provider:$id", number, row.text("longName") ?: number, emptyList(), provider, transportMode = mode, color = if (provider == "hev") HevSupport.color(number) else row.text("color")?.takeIf { it.matches(Regex("[a-fA-F0-9]{6}")) })
    }
    private fun stops(rows: JSONArray): Catalogue {
        val routes = linkedMapOf<String, TransitRoute>()
        val stops = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val source = row.text("gtfsId") ?: return@mapNotNull null
            val namespace = provider(source) ?: return@mapNotNull null
            val name = row.text("name") ?: return@mapNotNull null
            val lat = row.number("lat") ?: return@mapNotNull null
            val lon = row.number("lon") ?: return@mapNotNull null
            if (lat !in 45.7..48.6 || lon !in 16.1..22.9) return@mapNotNull null
            val rawRoutes = row.optJSONArray("routes")
            val serving = (0 until (rawRoutes?.length() ?: 0)).mapNotNull { rawRoutes?.optJSONObject(it)?.let(::route) }
            val mode = row.text("vehicleMode") ?: serving.mapNotNull { it.transportMode }.distinct().singleOrNull()
            val provider = if (namespace == "mav") when {
                source.startsWith("1:0036") && mode == "SUBURBAN_RAILWAY" || name.endsWith(" H") || serving.any { it.providerId == "hev" } -> "hev"
                RailSupport.isTrain(mode) -> "mav"
                else -> return@mapNotNull null
            } else namespace
            val station = row.text("locationType") == "STATION"
            serving.forEach { routes[it.id] = it }
            TransitStop("emma:$source", name, GeoPoint(lat, lon), serving.map { it.id }, provider,
                sourceStopIds = listOf(source), platformCode = row.text("platformCode"), station = station,
                parentStationId = row.optJSONObject("parentStation")?.text("gtfsId")?.let { "emma:$it" },
                transportMode = if (provider == "hev") "SUBURBAN_RAILWAY" else mode)
        }.distinctBy { it.id }
        return Catalogue(stops, routes.values.toList())
    }
    fun index(text: String): Catalogue {
        val tokens = JSONTokener(text)
        val result = mutableListOf<TransitStop>()
        var found = false
        fun expect(char: Char) { require(tokens.nextClean() == char) { "Invalid EMMA stop index" } }
        fun fields(action: (String) -> Unit) {
            expect('{')
            if (tokens.nextClean() == '}') return
            tokens.back()
            while (true) {
                val key = tokens.nextValue() as? String ?: error("Invalid EMMA key")
                expect(':'); action(key)
                when (tokens.nextClean()) { '}' -> return; ',' -> Unit; else -> error("Invalid EMMA object") }
            }
        }
        fields { key ->
            when (key) {
                "errors" -> require((tokens.nextValue() as? JSONArray)?.length().let { it == null || it == 0 }) { "EMMA GraphQL error" }
                "data" -> fields { dataKey ->
                    if (dataKey != "stops") tokens.nextValue() else {
                        expect('['); found = true
                        if (tokens.nextClean() != ']') {
                            tokens.back()
                            while (true) {
                                val row = tokens.nextValue() as? JSONObject ?: error("Invalid EMMA stop")
                                // Each temporary JSONObject is released before parsing the next record.
                                result.addAll(stops(JSONArray().put(row)).stops)
                                when (tokens.nextClean()) { ']' -> break; ',' -> Unit; else -> error("Invalid EMMA array") }
                            }
                        }
                    }
                }
                else -> tokens.nextValue()
            }
        }
        require(found && result.isNotEmpty()) { "Missing national stop index" }
        return Catalogue(result.distinctBy { it.id }, emptyList())
    }
    fun area(text: String) = stops(EmmaGraphqlClient.data(text).getJSONArray("stopsByBbox"))
    fun search(text: String) = stops(EmmaGraphqlClient.data(text).getJSONArray("stops"))
    fun board(text: String, expectedId: String, now: Instant, windowMinutes: Int): Board {
        val row = EmmaGraphqlClient.data(text).getJSONObject("stop")
        return boardRow(row, expectedId, now, windowMinutes)
    }
    internal fun boardRow(row: JSONObject, expectedId: String, now: Instant, windowMinutes: Int): Board {
        require(row.getString("gtfsId") == sourceId(expectedId)) { "Wrong stop returned" }
        val catalogue = stops(JSONArray().put(row))
        require(catalogue.stops.singleOrNull()?.id == expectedId) { "Unsupported stop" }
        val groups = row.optJSONArray("stoptimesForPatterns")
        var limitReached = false
        val rows = if (groups == null) row.getJSONArray("stoptimesWithoutPatterns") else JSONArray().also { flat ->
            for (i in 0 until groups.length()) {
                val group = groups.getJSONObject(i)
                val times = group.getJSONArray("stoptimes")
                if (times.length() >= BOARD_LIMIT) limitReached = true
                for (j in 0 until times.length()) flat.put(times.getJSONObject(j).put("_sourcePatternCode", group.optJSONObject("pattern")?.text("code"))
                    .put("_sourceHeadsign", group.optJSONObject("pattern")?.text("headsign")))
            }
        }
        val routes = linkedMapOf<String, TransitRoute>().apply { catalogue.routes.forEach { put(it.id, it) } }
        val departures = (0 until rows.length()).mapNotNull { index ->
            val time = rows.optJSONObject(index) ?: return@mapNotNull null
            if (time.text("realtimeState") == "CANCELED" || time.text("pickupType") == "NONE") return@mapNotNull null
            val trip = time.optJSONObject("trip") ?: return@mapNotNull null
            val tripId = trip.text("gtfsId") ?: return@mapNotNull null
            val route = trip.optJSONObject("route")?.let(::route) ?: return@mapNotNull null
            val selectedStop = catalogue.stops.single()
            if (!StopBoardScope.acceptsStop(selectedStop, time.optJSONObject("stop")?.text("gtfsId"))) return@mapNotNull null
            val stopProvider = selectedStop.providerId
            if (route.providerId != stopProvider) return@mapNotNull null
            val stopMode = catalogue.stops.single().transportMode
            if (!StopBoardScope.acceptsMode(stopMode, route.transportMode)) return@mapNotNull null
            val day = time.number("serviceDay")?.toLong() ?: return@mapNotNull null
            val scheduledSeconds = time.number("scheduledDeparture")?.toLong()?.takeIf { it in 0..172799 }
            val realtimeSeconds = time.number("realtimeDeparture")?.toLong()?.takeIf { it in 0..172799 }
            val realtime = time.optBoolean("realtime", false) && realtimeSeconds != null && time.text("realtimeState") != "SCHEDULED"
            val scheduled = scheduledSeconds?.let { seconds -> runCatching { Instant.ofEpochSecond(day + seconds) }.getOrNull() }
            val expected = if (realtime) Instant.ofEpochSecond(day + realtimeSeconds!!) else scheduled ?: return@mapNotNull null
            if (expected < now || expected > now.plusSeconds(windowMinutes * 60L)) return@mapNotNull null
            routes[route.id] = route
            Departure(route.id, route.number, time.text("headsign") ?: trip.text("tripHeadsign") ?: time.text("_sourceHeadsign").orEmpty(),
                expectedId, scheduled, expected, if (realtime) ArrivalKind.LIVE else ArrivalKind.SCHEDULED,
                // The full trip ID distinguishes departures with identical route/headsign/time.
                directionId = tripId,
                patternCode = trip.optJSONObject("pattern")?.text("code") ?: time.text("_sourcePatternCode"), sourceDirectionId = trip.text("directionId"),
                wheelchairAccessible = trip.text("wheelchairAccessible"), bikesAllowed = trip.text("bikesAllowed"),
                realtimeState = time.text("realtimeState"),
                trainNumber = if (route.providerId == "mav") RailSupport.serviceNumber(trip.text("tripShortName")) else null,
                serviceName = if (route.providerId == "mav") RailSupport.clean(trip.text("tripShortName")) else null,
                platformCode = time.optJSONObject("stop")?.text("platformCode"))
        }.distinctBy { listOf(it.routeId, it.directionId, it.expectedAt) }.sortedBy { it.expectedAt }
        return Board(expectedId, departures, catalogue.copy(routes = routes.values.toList()), now, limitReached || groups == null && rows.length() >= BOARD_LIMIT)
    }
    /** A failed alias must not discard other successful boards; unscoped source errors fail the batch. */
    fun batch(text: String, stopIds: List<String>, now: Instant, windowMinutes: Int): Map<String, Board?> {
        val root = JSONObject(text)
        val errors = root.optJSONArray("errors")
        val failed = (0 until (errors?.length() ?: 0)).map { index ->
            val alias = errors!!.getJSONObject(index).optJSONArray("path")?.optString(0)
            require(alias in stopIds.indices.map { "board$it" }) { "Unscoped EMMA batch error" }
            alias
        }.toSet()
        val data = root.getJSONObject("data")
        return stopIds.mapIndexed { index, id ->
            val alias = "board$index"
            id to if (alias in failed) null else data.optJSONObject(alias)?.let { row -> runCatching { boardRow(row, id, now, windowMinutes) }.getOrNull() }
        }.toMap()
    }
    fun combineBoards(clusterStopId: String, boards: List<Board>): Board {
        require(boards.isNotEmpty()) { "Cannot combine empty boards" }
        if (boards.size == 1) {
            val single = boards.single()
            return if (single.stopId == clusterStopId) single else single.copy(stopId = clusterStopId)
        }
        val allDepartures = boards.flatMap { it.departures }
            .distinctBy { listOf(it.routeId, it.directionId, it.expectedAt) }
            .sortedBy { it.expectedAt }
        val allStops = boards.flatMap { it.catalogue.stops }.distinctBy { it.id }
        val allRoutes = boards.flatMap { it.catalogue.routes }.distinctBy { it.id }
        val maxFetchedAt = boards.maxOf { it.fetchedAt }
        val limit = boards.any { it.limitReached }
        return Board(clusterStopId, allDepartures, Catalogue(allStops, allRoutes), maxFetchedAt, limit)
    }
    fun freshDepartures(board: Board?, stopId: String?, now: Instant, windowMinutes: Int): List<Departure> {
        if (board == null || board.stopId != stopId || Duration.between(board.fetchedAt, now).seconds !in 0..60) return emptyList()
        return board.departures.filter { it.expectedAt >= now && it.expectedAt <= now.plusSeconds(windowMinutes * 60L) }
    }

}
