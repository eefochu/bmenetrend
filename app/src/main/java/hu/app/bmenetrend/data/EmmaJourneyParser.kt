package hu.app.bmenetrend.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

@Immutable
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
        trip(id:${'$'}id) { gtfsId tripShortName tripHeadsign geometry pattern { code } route { gtfsId shortName longName mode }
            stops { gtfsId name lat lon vehicleMode platformCode parentStation { gtfsId } }
        }
    }""".trimIndent()

    fun parse(text: String, expectedTripId: String): VehicleJourney {
        val trip = EmmaGraphqlClient.data(text).getJSONObject("trip")
        require(trip.getString("gtfsId") == expectedTripId) { "Wrong trip returned" }
        val route = trip.optJSONObject("route") ?: error("Missing route")
        val parsedRoute = EmmaStopsParser.route(route) ?: run {
            val namespace = EmmaStopsParser.provider(route.getString("gtfsId"))
            require(route.text("mode") == null && namespace in setOf("volan", "bkk")) { "Unsupported route" }
            TransitRoute("$namespace:${route.getString("gtfsId")}", route.text("shortName") ?: "?", route.text("longName").orEmpty(), emptyList(), namespace!!)
        }
        val provider = parsedRoute.providerId
        require(EmmaStopsParser.provider(expectedTripId) == EmmaStopsParser.provider(route.getString("gtfsId"))) { "Wrong trip operator" }
        val rows = trip.getJSONArray("stops")
        val stops = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val source = row.getString("gtfsId")
            require(EmmaStopsParser.provider(source) == if (provider == "hev") "mav" else provider) { "Wrong stop operator" }
            val latitude = row.getDouble("lat")
            val longitude = row.getDouble("lon")
            require(latitude.isFinite() && longitude.isFinite() && if (provider == "mav") latitude in -90.0..90.0 && longitude in -180.0..180.0 && !(latitude == 0.0 && longitude == 0.0) else latitude in 45.7..48.6 && longitude in 16.1..22.9) { "Invalid trip stop" }
            TransitStop("emma:$source", row.getString("name"), GeoPoint(latitude, longitude), emptyList(), provider,
                sourceStopIds = listOf(source), platformCode = row.text("platformCode"), transportMode = if (provider == "hev") "SUBURBAN_RAILWAY" else row.text("vehicleMode") ?: route.text("mode"),
                parentStationId = row.optJSONObject("parentStation").text("gtfsId")?.let { "emma:$it" })
        }
        // Repeated stop occurrences matter on loop routes, so keep their exact source order.
        return VehicleJourney(expectedTripId, parsedRoute.number, route.text("longName"), trip.text("tripHeadsign"), stops, runCatching { EmmaRouteParser.tripGeometry(trip.optJSONArray("geometry")) }.getOrDefault(emptyList()), trip.optJSONObject("pattern").text("code"))
    }
    private fun JSONObject?.text(key: String) = this?.optString(key)?.takeUnless { it.isBlank() || it == "null" }
}
