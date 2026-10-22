package hu.viabusz.app.data

import org.json.JSONArray
import org.json.JSONObject

data class LinePattern(val id: String, val codes: Set<String>, val headsign: String, val stops: List<TransitStop>, val geometry: List<GeoPoint>, val serviceCount: Int = 0, val directionId: String? = null) {
    val familyKey: List<String> get() = if (directionId == null) listOf(id) else listOf(directionId, headsign, stops.last().id)
}
data class LineDetails(val route: TransitRoute, val patterns: List<LinePattern>) {
    fun boardingPattern(patternId: String, stopId: String): LinePattern? {
        val selected = patterns.find { it.id == patternId } ?: return null
        val occurrence = selected.stops.indices.filter { selected.stops[it].id == stopId }.singleOrNull() ?: return selected.takeIf { it.stops.any { stop -> stop.id == stopId } }
        val suffix = selected.stops.drop(occurrence).map { it.id }
        val codes = patterns.filter { it.familyKey == selected.familyKey }.filter { variant ->
            val index = variant.stops.indices.filter { variant.stops[it].id == stopId }.singleOrNull()
            index != null && variant.stops.drop(index).map { it.id } == suffix
        }.flatMap { it.codes }.toSet()
        return selected.copy(codes = codes.ifEmpty { selected.codes })
    }
}
data class BoardingSelection(val routeId: String, val title: String, val pattern: LinePattern? = null, val localDirectionId: String? = null) {
    fun matches(departure: Departure): Boolean = departure.routeId == routeId && when {
        pattern != null -> departure.patternCode in pattern.codes
        localDirectionId != null -> departure.directionId == localDirectionId
        else -> true
    }
}

object EmmaRouteParser {
    const val CATALOGUE_QUERY = "{ routes { gtfsId shortName longName mode color } }"
    fun catalogue(text: String): List<TransitRoute> {
        val rows = EmmaGraphqlClient.data(text).getJSONArray("routes")
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            val source = row.text("gtfsId") ?: return@mapNotNull null
            val provider = EmmaStopsParser.provider(source) ?: return@mapNotNull null
            val mode = row.text("mode") ?: return@mapNotNull null
            if (mode !in if (provider == "volan") setOf("BUS", "COACH") else setOf("BUS", "TRAM", "TROLLEYBUS", "SUBWAY")) return@mapNotNull null
            val number = row.text("shortName") ?: return@mapNotNull null
            TransitRoute("$provider:$source", number, row.text("longName") ?: number, emptyList(), provider,
                transportMode = mode, color = row.text("color")?.takeIf { it.matches(Regex("[a-fA-F0-9]{6}")) })
        }.distinctBy { it.id }.also { require(it.isNotEmpty()) { "Missing line catalogue" } }
    }
    val QUERY = """query LineDetails(${'$'}id:String!, ${'$'}date:String!) {
        route(id:${'$'}id) { gtfsId shortName longName mode color
            patterns { code name directionId headsign tripsForDate(serviceDate:${'$'}date) { gtfsId } stops { gtfsId name lat lon vehicleMode platformCode parentStation { gtfsId } } geometry { lat lon } }
        }
    }""".trimIndent()
    fun parse(text: String, expectedRouteId: String): LineDetails {
        val row = EmmaGraphqlClient.data(text).getJSONObject("route")
        val source = row.getString("gtfsId")
        val provider = EmmaStopsParser.provider(source) ?: error("Unsupported route")
        require("$provider:$source" == expectedRouteId) { "Wrong route returned" }
        val number = row.text("shortName") ?: error("Missing line number")
        val mode = row.text("mode") ?: error("Missing mode")
        require(mode in if (provider == "volan") setOf("BUS", "COACH") else setOf("BUS", "TRAM", "TROLLEYBUS", "SUBWAY"))
        val route = TransitRoute(expectedRouteId, number, row.text("longName") ?: number, emptyList(), provider,
            transportMode = mode, color = row.text("color")?.takeIf { it.matches(Regex("[a-fA-F0-9]{6}")) })
        val patterns = row.getJSONArray("patterns")
        val parsed = (0 until patterns.length()).mapNotNull { i ->
            val pattern = patterns.getJSONObject(i)
            val code = pattern.text("code") ?: return@mapNotNull null
            if (!code.startsWith(if (provider == "volan") "hkir:" else "BKK:")) return@mapNotNull null
            val rows = pattern.getJSONArray("stops")
            val stops = (0 until rows.length()).map { index ->
                val stop = rows.getJSONObject(index)
                val id = stop.getString("gtfsId")
                require(EmmaStopsParser.provider(id) == provider)
                val point = point(stop.getDouble("lat"), stop.getDouble("lon"))
                TransitStop("emma:$id", stop.getString("name"), point, listOf(expectedRouteId), provider,
                    sourceStopIds = listOf(id), platformCode = stop.text("platformCode"), transportMode = stop.text("vehicleMode") ?: mode,
                    parentStationId = stop.optJSONObject("parentStation")?.text("gtfsId")?.let { "emma:$it" })
            }
            if (stops.size < 2) return@mapNotNull null
            val geometry = pattern.optJSONArray("geometry")?.let { points -> (0 until points.length()).map { index ->
                val p = points.getJSONObject(index); point(p.getDouble("lat"), p.getDouble("lon"))
            }}.orEmpty()
            LinePattern(code, setOf(code), pattern.text("headsign") ?: stops.last().name, stops, geometry, pattern.optJSONArray("tripsForDate")?.length() ?: 0, pattern.text("directionId"))
        }
        // Different service calendars can share one physical stop sequence; keep all their codes for board filtering.
        val grouped = parsed.groupBy { it.headsign to it.stops.map(TransitStop::id) }.values.map { aliases ->
            aliases.first().copy(codes = aliases.flatMap { it.codes }.toSet(), serviceCount = aliases.sumOf { it.serviceCount })
        }.sortedWith(compareByDescending<LinePattern> { it.serviceCount }.thenBy { it.headsign }.thenByDescending { it.stops.size })
        return LineDetails(route, grouped)
    }
    fun tripGeometry(rows: JSONArray?): List<GeoPoint> = rows?.let { array -> (0 until array.length()).map { i ->
        val pair = array.getJSONArray(i)
        // OTP trip.geometry is GeoJSON coordinate order [longitude, latitude].
        point(pair.getDouble(1), pair.getDouble(0))
    }}.orEmpty()
    private fun point(lat: Double, lon: Double): GeoPoint {
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0))
        return GeoPoint(lat, lon)
    }
    private fun JSONObject.text(key: String) = optString(key).takeUnless { it.isBlank() || it == "null" }
}
