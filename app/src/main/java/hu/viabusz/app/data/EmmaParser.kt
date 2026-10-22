package hu.viabusz.app.data

import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/** The same MÁVPlusz OTP GraphQL protocol as vonatpervolan; no direct BKK API. */
object EmmaParser {
    const val ENDPOINT = "https://mavplusz.hu/otp2-backend/otp/routers/default/index/graphql"
    val QUERY = """
        query LiveVehicles {
          vehicles: vehiclePositions(swLat:45.7, swLon:16.1, neLat:48.6, neLon:22.9,
            modes:[BUS,COACH,TRAM,TROLLEYBUS,SUBWAY]) {
            vehicleId lat lon heading label lastUpdated speed
            nextStop { stopPosition stop { gtfsId name lat lon } }
            trip { gtfsId tripShortName tripHeadsign
              route { gtfsId shortName longName mode color textColor }
            }
          }
        }
    """.trimIndent()
    private val bkkModes = setOf("BUS", "TRAM", "TROLLEYBUS", "SUBWAY")

    data class Route(val number: String, val name: String, val domainCode: Int, val url: String) {
        // All 36 local labels were checked against the reference app's Volán GTFS routes.txt.
        val sourceId: String get() = "hkir:hkir_V-R-200-$number"
    }
    fun catalogue(text: String): List<Route> {
        val rows = JSONObject(text).getJSONArray("routes")
        return (0 until rows.length()).map { index -> rows.getJSONObject(index).let {
            Route(it.getString("number"), it.getString("name"), it.getInt("domainCode"), it.getString("url"))
        } }
    }
    fun request(): String = JSONObject().put("query", QUERY).put("variables", JSONObject()).toString()
    private fun JSONObject.text(key: String): String? = optString(key).takeUnless { it.isBlank() || it == "null" }
    private fun JSONObject.number(key: String): Double? = (opt(key) as? Number)?.toDouble()?.takeIf(Double::isFinite)

    fun parse(text: String, now: Instant): List<Vehicle> {
        val root = JSONObject(text)
        require((root.optJSONArray("errors")?.length() ?: 0) == 0) { "EMMA GraphQL error" }
        val rows = root.getJSONObject("data").getJSONArray("vehicles")
        val vehicles = linkedMapOf<String, Vehicle>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val sourceId = row.text("vehicleId") ?: continue
            val trip = row.optJSONObject("trip")
            val route = trip?.optJSONObject("route")
            val mode = route?.text("mode")
            val provider = when {
                sourceId.startsWith("hkir:") && sourceId.length > 5 && (mode == null || mode in setOf("BUS", "COACH")) -> "volan"
                sourceId.startsWith("BKK:") && sourceId.length > 4 && mode in bkkModes -> "bkk"
                else -> continue
            }
            val lat = row.number("lat") ?: continue
            val lon = row.number("lon") ?: continue
            if (lat !in 45.7..48.6 || lon !in 16.1..22.9) continue
            // OTP reports Unix seconds, not milliseconds. Never substitute the HTTP fetch time.
            val seconds = row.number("lastUpdated") ?: continue
            val observed = runCatching { Instant.ofEpochSecond(seconds.toLong()) }.getOrNull() ?: continue
            if (Duration.between(observed, now).seconds !in -30..180) continue
            val sourceRouteId = route?.text("gtfsId")
            if (sourceRouteId != null && !sourceRouteId.startsWith(if (provider == "volan") "hkir:" else "BKK:")) continue
            val routeNumber = route?.text("shortName")
            val color = route?.text("color")?.takeIf { it.matches(Regex("[a-fA-F0-9]{6}")) }
            val nextTime = row.optJSONObject("nextStop")
            val nextStop = nextTime?.optJSONObject("stop")
            val vehicle = Vehicle(
                id = "emma:$sourceId", position = GeoPoint(lat, lon), observedAt = observed,
                routeId = sourceRouteId?.let { "$provider:$it" }, providerId = provider,
                routeNumber = routeNumber, destination = trip?.text("tripHeadsign") ?: route?.text("longName"),
                registration = row.text("label") ?: sourceId.substringAfter(":").removePrefix("hkir_"),
                tripId = trip?.text("gtfsId"), routeName = route?.text("longName"), transportMode = mode,
                heading = row.number("heading")?.takeIf { it in 0.0..360.0 },
                speedKmh = row.number("speed")?.takeIf { it >= 0 }?.times(3.6),
                markerColor = if (provider == "bkk") color else null,
                nextStopId = nextStop?.text("gtfsId")?.let { "emma:$it" },
                nextStopName = nextStop?.text("name"),
                nextStopPosition = nextTime?.number("stopPosition")?.toInt()?.takeIf { it >= 0 },
                nextStopPoint = nextStop?.number("lat")?.let { latitude -> nextStop.number("lon")?.let { longitude ->
                    if (latitude in 45.7..48.6 && longitude in 16.1..22.9) GeoPoint(latitude, longitude) else null
                } },
            )
            if (vehicles[vehicle.id]?.observedAt?.let { it >= observed } != true) vehicles[vehicle.id] = vehicle
        }
        return vehicles.values.toList()
    }

    fun localVehicles(vehicles: List<Vehicle>, routes: List<Route>): List<Vehicle> {
        val localIds = routes.map { "volan:${it.sourceId}" }.toSet()
        return vehicles.filter { vehicle ->
            if (vehicle.providerId == "volan") vehicle.routeId in localIds
            else vehicle.providerId == "bkk" && vehicle.position.lon in 18.85..19.4 && vehicle.position.lat in 47.3..47.7
        }
    }

    fun routeCatalogue(vehicles: List<Vehicle>, local: List<TransitRoute>): List<TransitRoute> =
        (local + vehicles.mapNotNull { vehicle ->
            val id = vehicle.routeId ?: return@mapNotNull null
            TransitRoute(id, vehicle.routeNumber ?: "?", vehicle.routeName ?: vehicle.destination.orEmpty(), emptyList(), vehicle.providerId, transportMode = vehicle.transportMode)
        }).distinctBy { it.id }
}
