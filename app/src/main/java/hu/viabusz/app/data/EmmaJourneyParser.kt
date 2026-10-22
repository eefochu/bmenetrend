package hu.viabusz.app.data

import org.json.JSONObject

data class VehicleJourney(
    val tripId: String,
    val routeNumber: String?,
    val routeName: String?,
    val destination: String?,
    val stops: List<TransitStop>,
    val geometry: List<GeoPoint> = emptyList(),
    val patternCode: String? = null,
)

/** The actual vehicle trip's ordered stops, never a guessed route variant or nearest-stop path. */
object EmmaJourneyParser {
    val QUERY = """query VehicleTrip(${'$'}id: String!) {
        trip(id:${'$'}id) { gtfsId tripHeadsign geometry pattern { code } route { gtfsId shortName longName mode }
            stops { gtfsId name lat lon platformCode }
        }
    }""".trimIndent()

    fun parse(text: String, expectedTripId: String): VehicleJourney {
        val trip = EmmaGraphqlClient.data(text).getJSONObject("trip")
        require(trip.getString("gtfsId") == expectedTripId) { "Wrong trip returned" }
        val provider = EmmaStopsParser.provider(expectedTripId) ?: error("Unsupported trip")
        val route = trip.optJSONObject("route")
        require(route?.optString("gtfsId")?.let(EmmaStopsParser::provider) == provider) { "Wrong trip operator" }
        val rows = trip.getJSONArray("stops")
        val stops = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val source = row.getString("gtfsId")
            require(EmmaStopsParser.provider(source) == provider) { "Wrong stop operator" }
            val latitude = row.getDouble("lat")
            val longitude = row.getDouble("lon")
            require(latitude in 45.7..48.6 && longitude in 16.1..22.9) { "Invalid trip stop" }
            TransitStop("emma:$source", row.getString("name"), GeoPoint(latitude, longitude), emptyList(), provider,
                sourceStopIds = listOf(source), platformCode = row.text("platformCode"))
        }
        // Repeated stop occurrences matter on loop routes, so keep their exact source order.
        return VehicleJourney(expectedTripId, route.text("shortName"), route.text("longName"), trip.text("tripHeadsign"), stops, runCatching { EmmaRouteParser.tripGeometry(trip.optJSONArray("geometry")) }.getOrDefault(emptyList()), trip.optJSONObject("pattern").text("code"))
    }
    private fun JSONObject?.text(key: String) = this?.optString(key)?.takeUnless { it.isBlank() || it == "null" }
}
