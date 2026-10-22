package hu.viabusz.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant
import java.time.Duration

object EmmaStopsParser {
    const val ROUTE_FIELDS = "gtfsId shortName longName mode"
    const val STOP_FIELDS = "gtfsId name lat lon platformCode locationType parentStation { gtfsId name } routes { $ROUTE_FIELDS }"
    const val INDEX_QUERY = "{stops {gtfsId name lat lon platformCode locationType parentStation {gtfsId name}}}"
    val AREA_QUERY = """query StopsInArea(${'$'}south: Float!, ${'$'}west: Float!, ${'$'}north: Float!, ${'$'}east: Float!) {
        stopsByBbox(minLat:${'$'}south,minLon:${'$'}west,maxLat:${'$'}north,maxLon:${'$'}east) { $STOP_FIELDS }
    }""".trimIndent()
    val SEARCH_QUERY = """query FindStops(${'$'}name: String!) { stops(name:${'$'}name) { $STOP_FIELDS } }"""
    val BOARD_QUERY = """query StopBoard(${'$'}id: String!, ${'$'}start: Long!, ${'$'}range: Int!) {
        stop(id:${'$'}id) { $STOP_FIELDS
            stoptimesWithoutPatterns(startTime:${'$'}start,timeRange:${'$'}range,numberOfDepartures:100,omitNonPickups:true) {
                scheduledDeparture realtimeDeparture realtime realtimeState serviceDay headsign pickupType
                trip { gtfsId tripHeadsign route { $ROUTE_FIELDS } }
            }
        }
    }""".trimIndent()
    data class Catalogue(val stops: List<TransitStop>, val routes: List<TransitRoute>)
    data class Board(val stopId: String, val departures: List<Departure>, val catalogue: Catalogue, val fetchedAt: Instant, val limitReached: Boolean)
    private fun JSONObject.text(key: String) = optString(key).takeUnless { it.isBlank() || it == "null" }
    private fun JSONObject.number(key: String) = (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
    fun provider(sourceId: String): String? = when {
        sourceId.startsWith("hkir:") && sourceId.length > 5 -> "volan"
        sourceId.startsWith("BKK:") && sourceId.length > 4 -> "bkk"
        else -> null
    }
    fun sourceId(appId: String): String = appId.removePrefix("emma:").also { require(provider(it) != null) }
    private fun route(row: JSONObject): TransitRoute? {
        val id = row.text("gtfsId") ?: return null
        val provider = provider(id) ?: return null
        val mode = row.text("mode") ?: return null
        if (mode !in if (provider == "volan") setOf("BUS", "COACH") else setOf("BUS", "TRAM", "TROLLEYBUS", "SUBWAY")) return null
        val number = row.text("shortName") ?: return null
        return TransitRoute("$provider:$id", number, row.text("longName") ?: number, emptyList(), provider, transportMode = mode)
    }
    private fun stops(rows: JSONArray): Catalogue {
        val routes = linkedMapOf<String, TransitRoute>()
        val stops = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val source = row.text("gtfsId") ?: return@mapNotNull null
            val provider = provider(source) ?: return@mapNotNull null
            val name = row.text("name") ?: return@mapNotNull null
            val lat = row.number("lat") ?: return@mapNotNull null
            val lon = row.number("lon") ?: return@mapNotNull null
            if (lat !in 45.7..48.6 || lon !in 16.1..22.9) return@mapNotNull null
            val rawRoutes = row.optJSONArray("routes")
            val serving = (0 until (rawRoutes?.length() ?: 0)).mapNotNull { rawRoutes?.optJSONObject(it)?.let(::route) }
            val station = row.text("locationType") == "STATION"
            serving.forEach { routes[it.id] = it }
            TransitStop("emma:$source", name, GeoPoint(lat, lon), serving.map { it.id }, provider,
                sourceStopIds = listOf(source), platformCode = row.text("platformCode"), station = station,
                parentStationId = row.optJSONObject("parentStation")?.text("gtfsId")?.let { "emma:$it" })
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
        require(row.getString("gtfsId") == sourceId(expectedId)) { "Wrong stop returned" }
        val catalogue = stops(JSONArray().put(row))
        require(catalogue.stops.singleOrNull()?.id == expectedId) { "Unsupported stop" }
        val rows = row.getJSONArray("stoptimesWithoutPatterns")
        val routes = linkedMapOf<String, TransitRoute>().apply { catalogue.routes.forEach { put(it.id, it) } }
        val departures = (0 until rows.length()).mapNotNull { index ->
            val time = rows.optJSONObject(index) ?: return@mapNotNull null
            if (time.text("realtimeState") == "CANCELED" || time.text("pickupType") == "NONE") return@mapNotNull null
            val trip = time.optJSONObject("trip") ?: return@mapNotNull null
            val tripId = trip.text("gtfsId") ?: return@mapNotNull null
            val route = trip.optJSONObject("route")?.let(::route) ?: return@mapNotNull null
            val stopProvider = catalogue.stops.single().providerId
            if (route.providerId != stopProvider) return@mapNotNull null
            val day = time.number("serviceDay")?.toLong() ?: return@mapNotNull null
            val scheduledSeconds = time.number("scheduledDeparture")?.toLong()?.takeIf { it in 0..172799 } ?: return@mapNotNull null
            val realtimeSeconds = time.number("realtimeDeparture")?.toLong()?.takeIf { it in 0..172799 }
            val realtime = time.optBoolean("realtime", false) && realtimeSeconds != null && time.text("realtimeState") != "SCHEDULED"
            val scheduled = runCatching { Instant.ofEpochSecond(day + scheduledSeconds) }.getOrNull() ?: return@mapNotNull null
            val expected = if (realtime) Instant.ofEpochSecond(day + realtimeSeconds!!) else scheduled
            if (expected < now || expected > now.plusSeconds(windowMinutes * 60L)) return@mapNotNull null
            routes[route.id] = route
            Departure(route.id, route.number, time.text("headsign") ?: trip.text("tripHeadsign") ?: route.name,
                expectedId, scheduled, expected, if (realtime) ArrivalKind.LIVE else ArrivalKind.SCHEDULED,
                // The full trip ID distinguishes departures with identical route/headsign/time.
                directionId = tripId)
        }.distinctBy { listOf(it.routeId, it.directionId, it.expectedAt) }.sortedBy { it.expectedAt }
        return Board(expectedId, departures, catalogue.copy(routes = routes.values.toList()), now, rows.length() >= 100)
    }
    fun freshDepartures(board: Board?, stopId: String?, now: Instant, windowMinutes: Int): List<Departure> {
        if (board == null || board.stopId != stopId || Duration.between(board.fetchedAt, now).seconds !in 0..60) return emptyList()
        return board.departures.filter { it.expectedAt >= now && it.expectedAt <= now.plusSeconds(windowMinutes * 60L) }
    }

}
