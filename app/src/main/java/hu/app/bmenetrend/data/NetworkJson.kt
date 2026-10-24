package hu.app.bmenetrend.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

object NetworkJson {
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONObject.date(key: String) = optString(key).takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse)

    fun decode(text: String): TransitNetwork {
        val root = JSONObject(text)
        require(root.getInt("schemaVersion") in 1..3) { "Unsupported cached network" }
        val stops = root.getJSONArray("stops").objects().map { stop ->
            TransitStop(stop.getString("id"), stop.getString("name"), GeoPoint(stop.getDouble("lat"), stop.getDouble("lon")),
                stop.getJSONArray("routeIds").strings(), stop.optString("providerId", "viabusz"),
                stop.optJSONArray("providerIds")?.strings() ?: listOf(stop.optString("providerId", "viabusz")),
                stop.optJSONArray("aliases")?.strings().orEmpty(),
                stop.optJSONArray("sourceStopIds")?.strings() ?: listOf(stop.getString("id")))
        }
        val routes = root.getJSONArray("routes").objects().map { route ->
            TransitRoute(route.getString("id"), route.getString("number"), route.getString("name"),
                route.getJSONArray("directions").objects().map { direction ->
                    RouteDirection(direction.getString("id"), direction.getString("name"), direction.getJSONArray("stopIds").strings(),
                        direction.optBoolean("restrictedService", false))
                }, route.optString("providerId", "viabusz"))
        }
        val trips = root.getJSONArray("trips").objects().map { trip ->
            ScheduledTrip(trip.getString("id"), trip.getString("routeId"), trip.getString("directionId"),
                trip.getJSONArray("stopTimes").objects().map { StopTime(it.getString("stopId"), it.getInt("minuteOfDay")) },
                trip.optString("calendar", "working_days"))
        }
        val routeIds = routes.map { it.id }.toSet()
        val stopIds = stops.map { it.id }.toSet()
        require(stops.isNotEmpty() && routes.isNotEmpty()) { "Empty network" }
        require(routeIds.size == routes.size && stopIds.size == stops.size) { "Duplicate network IDs" }
        require(routes.all { route -> route.directions.isNotEmpty() && route.directions.all { it.stopIds.size > 1 && it.stopIds.all(stopIds::contains) } })
        require(stops.all { it.routeIds.all(routeIds::contains) })
        require(trips.all { trip ->
            trip.routeId in routeIds && trip.stopTimes.size > 1 && trip.stopTimes.all { it.stopId in stopIds && it.minuteOfDay in 0..2879 } &&
                trip.stopTimes.zipWithNext().all { (a,b) -> a.minuteOfDay <= b.minuteOfDay } &&
                routes.first { it.id == trip.routeId }.directions.any { it.id == trip.directionId && it.stopIds == trip.stopTimes.map(StopTime::stopId) }
        }) { "Invalid trip sequence" }
        val dates = root.optJSONObject("providerServiceDates")?.let { obj -> obj.keys().asSequence().associateWith { obj.date(it) } }.orEmpty()
        return TransitNetwork(stops, routes, trips, Instant.parse(root.getString("fetchedAt")), root.getString("source"),
            root.date("serviceDate"), root.optString("calendarLabel", "Munkanapokon"), providerServiceDates = dates,
            verifiedProviderIds = root.optJSONArray("verifiedProviderIds")?.strings()?.toSet().orEmpty())
    }

    fun encode(network: TransitNetwork): String = JSONObject().apply {
        put("schemaVersion", 3); put("fetchedAt", network.fetchedAt.toString()); put("source", network.source)
        put("serviceDate", network.serviceDate?.toString() ?: JSONObject.NULL); put("calendarLabel", network.calendarLabel)
        put("verifiedProviderIds", JSONArray(network.verifiedProviderIds.toList()))
        put("providerServiceDates", JSONObject().apply { network.providerServiceDates.forEach { (provider, date) -> put(provider, date?.toString() ?: JSONObject.NULL) } })
        put("stops", JSONArray(network.stops.map { stop -> JSONObject().apply {
            put("id", stop.id); put("name", stop.name); put("lat", stop.position.lat); put("lon", stop.position.lon)
            put("routeIds", JSONArray(stop.routeIds)); put("providerId", stop.providerId)
            put("providerIds", JSONArray(stop.providerIds)); put("aliases", JSONArray(stop.aliases))
            put("sourceStopIds", JSONArray(stop.sourceStopIds))
        } }))
        put("routes", JSONArray(network.routes.map { route -> JSONObject().apply {
            put("id", route.id); put("number", route.number); put("name", route.name); put("providerId", route.providerId)
            put("directions", JSONArray(route.directions.map { direction -> JSONObject().apply {
                put("id", direction.id); put("name", direction.name); put("stopIds", JSONArray(direction.stopIds)); put("restrictedService", direction.restrictedService)
            } }))
        } }))
        put("trips", JSONArray(network.trips.map { trip -> JSONObject().apply {
            put("id", trip.id); put("routeId", trip.routeId); put("directionId", trip.directionId); put("calendar", trip.calendar)
            put("stopTimes", JSONArray(trip.stopTimes.map { JSONObject().apply {
                put("stopId", it.stopId); put("minuteOfDay", it.minuteOfDay)
            } }))
        } }))
    }.toString()
}
