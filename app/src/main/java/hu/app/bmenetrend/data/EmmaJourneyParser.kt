package hu.app.bmenetrend.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Immutable
data class JourneyStopTime(
    val arrivalTime: String,
    val scheduledTime: String? = null,
    val isLive: Boolean = false,
    val delayMinutes: Int = 0,
)

@Immutable
data class VehicleJourney(
    val tripId: String,
    val routeNumber: String?,
    val routeName: String?,
    val destination: String?,
    val stops: List<TransitStop>,
    val geometry: List<GeoPoint> = emptyList(),
    val patternCode: String? = null,
    val stopTimes: List<JourneyStopTime?> = emptyList(),
)

/** The actual vehicle trip's ordered stops, never a guessed route variant or nearest-stop path. */
object EmmaJourneyParser {
    val QUERY = """query VehicleTrip(${'$'}id: String!) {
        trip(id:${'$'}id) { gtfsId tripShortName tripHeadsign geometry pattern { code } route { gtfsId shortName longName mode }
            stops { gtfsId name lat lon vehicleMode platformCode parentStation { gtfsId } }
            stoptimes {
                scheduledArrival realtimeArrival scheduledDeparture realtimeDeparture realtime realtimeState serviceDay
                stop { gtfsId }
            }
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
        val stopTimesRows = trip.optJSONArray("stoptimes")
        val stopTimes = if (stopTimesRows != null) {
            (0 until stopTimesRows.length()).map { index ->
                val row = stopTimesRows.optJSONObject(index) ?: return@map null
                val serviceDay = row.optLong("serviceDay", -1L)
                val scheduledSec = if (index == 0) {
                    (row.opt("scheduledDeparture") as? Number)?.toLong()?.takeIf { it in 0..172799 }
                        ?: (row.opt("scheduledArrival") as? Number)?.toLong()?.takeIf { it in 0..172799 }
                } else {
                    (row.opt("scheduledArrival") as? Number)?.toLong()?.takeIf { it in 0..172799 }
                        ?: (row.opt("scheduledDeparture") as? Number)?.toLong()?.takeIf { it in 0..172799 }
                }
                val realtimeSec = if (index == 0) {
                    (row.opt("realtimeDeparture") as? Number)?.toLong()?.takeIf { it in 0..172799 }
                        ?: (row.opt("realtimeArrival") as? Number)?.toLong()?.takeIf { it in 0..172799 }
                        ?: scheduledSec
                } else {
                    (row.opt("realtimeArrival") as? Number)?.toLong()?.takeIf { it in 0..172799 }
                        ?: (row.opt("realtimeDeparture") as? Number)?.toLong()?.takeIf { it in 0..172799 }
                        ?: scheduledSec
                }
                val isRealtime = row.optBoolean("realtime", false) && row.optString("realtimeState") != "SCHEDULED"
                val expectedSec = if (isRealtime && realtimeSec != null) realtimeSec else (scheduledSec ?: return@map null)
                val delaySec = if (isRealtime && scheduledSec != null && realtimeSec != null) (realtimeSec - scheduledSec) else 0L
                val delayMinutes = (delaySec / 60).toInt()
                val arrivalTime = formatTime(serviceDay, expectedSec)
                val scheduledTime = if (isRealtime && scheduledSec != null && formatTime(serviceDay, scheduledSec) != arrivalTime) {
                    formatTime(serviceDay, scheduledSec)
                } else {
                    null
                }
                JourneyStopTime(arrivalTime, scheduledTime, isRealtime, delayMinutes)
            }
        } else {
            emptyList()
        }
        // Repeated stop occurrences matter on loop routes, so keep their exact source order.
        return VehicleJourney(
            expectedTripId,
            parsedRoute.number,
            route.text("longName"),
            trip.text("tripHeadsign"),
            stops,
            runCatching { EmmaRouteParser.tripGeometry(trip.optJSONArray("geometry")) }.getOrDefault(emptyList()),
            trip.optJSONObject("pattern").text("code"),
            stopTimes,
        )
    }

    private fun formatTime(serviceDay: Long, seconds: Long): String {
        if (serviceDay > 0) {
            val instant = runCatching { Instant.ofEpochSecond(serviceDay + seconds) }.getOrNull()
            if (instant != null) {
                return DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Budapest")).format(instant)
            }
        }
        val totalMinutes = ((seconds % 86400 + 86400) % 86400) / 60
        val hour = (totalMinutes / 60).toInt()
        val minute = (totalMinutes % 60).toInt()
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute)
    }

    private fun JSONObject?.text(key: String) = this?.optString(key)?.takeUnless { it.isBlank() || it == "null" }
}
