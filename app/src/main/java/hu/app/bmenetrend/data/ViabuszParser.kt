package hu.app.bmenetrend.data

import org.jsoup.Jsoup
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ViabuszParser {
    val zone: ZoneId = ZoneId.of("Europe/Budapest")
    val routeNumbers = linkedMapOf("10" to "1", "11" to "1P", "12" to "2", "13" to "3", "14" to "4")
    private val coordinates = Regex("zoomMapToPoint\\(([-\\d.]+),\\s*([-\\d.]+)")
    private val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    data class RoutePage(val route: TransitRoute, val stops: List<TransitStop>, val trips: List<ScheduledTrip>, val calendarLabel: String)

    fun parseRoutePage(html: String, routeId: String): RoutePage {
        val number = requireNotNull(routeNumbers[routeId]) { "Unsupported route" }
        val document = Jsoup.parse(html)
        val option = requireNotNull(document.selectFirst("#routeSelect option[value=$routeId]")) { "Missing route selector" }
        require(option.text().substringBefore(" - ").trim() == number) { "Route number changed" }
        val name = option.text().substringAfter(" - ").trim()
        val rows = document.select("#timeTableData tr[routeId=$routeId]")
        require(rows.isNotEmpty()) { "Missing stops for route $number" }
        val stops = rows.map { row ->
            val id = requireNotNull(row.selectFirst(".digitalClock")).id().removePrefix("bsClock")
            require(id.toIntOrNull() != null) { "Invalid stop ID" }
            val cells = row.select("td")
            require(cells.size >= 2) { "Incomplete stop row" }
            val stopName = cells[1].text().trim()
            require(stopName.isNotBlank()) { "Empty stop name" }
            val match = row.select("img[onclick]").firstNotNullOfOrNull { coordinates.find(it.attr("onclick")) }
            requireNotNull(match) { "Missing coordinates for $stopName" }
            val lat = match.groupValues[2].toDouble(); val lon = match.groupValues[1].toDouble()
            require(lat in 47.35..47.60 && lon in 18.65..19.0) { "Stop outside service area" }
            TransitStop(id, stopName, GeoPoint(lat, lon), listOf(routeId))
        }
        val directionId = rows.first()!!.attr("directionId")
        val direction = RouteDirection(directionId, name, stops.map { it.id })
        require(stops.groupBy { it.name }.all { (_, items) -> items.map { it.id }.distinct().size == 1 }) { "Ambiguous stop names" }
        val nameToId = stops.associate { it.name to it.id }
        val trips = document.select("div[id^=busStopPopupBoxMPST]").mapNotNull { popup ->
            if (popup.selectFirst("h3")?.text() != number) return@mapNotNull null
            val times = popup.select("tr").mapNotNull row@ { row ->
                val cells = row.select("td")
                if (cells.size != 2) return@row null
                val id = requireNotNull(nameToId[cells[0].text().trim()]) { "Unmapped scheduled stop" }
                val parts = cells[1].text().trim().split(":")
                require(parts.size == 2) { "Invalid time" }
                val hour = parts[0].toInt(); val minute = parts[1].toInt()
                require(hour in 0..47 && minute in 0..59) { "Invalid clock time" }
                StopTime(id, hour * 60 + minute)
            }
            // Repeated stop occurrences (e.g. Vasútállomás at both ends) are kept.
            require(times.map { it.stopId } == direction.stopIds) { "Trip stop sequence changed" }
            require(times.zipWithNext().all { (a,b) -> a.minuteOfDay <= b.minuteOfDay }) { "Non-monotonic times" }
            ScheduledTrip(popup.id().removePrefix("busStopPopupBoxMPST"), routeId, directionId, times)
        }
        require(trips.isNotEmpty()) { "Missing timetable for route $number" }
        val calendarLabel = document.selectFirst("#BusStopsDiv h3")?.text().orEmpty()
        return RoutePage(TransitRoute(routeId, number, name, listOf(direction)), stops.distinctBy { it.id }, trips, calendarLabel)
    }

    fun mergeRoutePages(pages: List<RoutePage>, now: Instant): TransitNetwork {
        require(pages.map { it.route.id }.toSet() == routeNumbers.keys) { "Incomplete transit network" }
        val stops = pages.flatMap { it.stops }.groupBy { it.id }.map { (_, matches) ->
            val first = matches.first()
            require(matches.all { it.name == first.name && it.position == first.position }) { "Conflicting stop metadata" }
            first.copy(routeIds = matches.flatMap { it.routeIds }.distinct())
        }
        val label = pages.map { it.calendarLabel }.distinct().singleOrNull().orEmpty()
        val date = ServiceCalendar.serviceDate(now.atZone(zone).toLocalDate(), label)
        return TransitNetwork(stops, pages.map { it.route }, pages.flatMap { it.trips }, now,
            "https://viabusz.biatorbagy.hu/", date, label)
    }

    fun parseVehicles(body: String, now: Instant): List<Vehicle> = body.split('#').mapNotNull { record ->
        val fields = record.split(';')
        if (fields.size < 5 || fields[0] == "Error") return@mapNotNull null
        val lat = fields[2].toDoubleOrNull() ?: return@mapNotNull null
        val lon = fields[3].toDoubleOrNull() ?: return@mapNotNull null
        val observed = runCatching { LocalDateTime.parse(fields[1], timestamp).atZone(zone).toInstant() }.getOrNull()
            ?: return@mapNotNull null
        // Never display stale depots/intercity positions as live local buses.
        val age = Duration.between(observed, now).seconds
        if (age !in -60..180 || lat !in 47.40..47.53 || lon !in 18.75..18.89) return@mapNotNull null
        Vehicle(fields[0], GeoPoint(lat, lon), observed, fields.getOrNull(7)?.takeIf { it.isNotBlank() })
    }.distinctBy { it.id }

    fun parseAggregateArrivals(body: String): List<LiveArrival> = body.split('#').mapNotNull { record ->
        val fields = record.split(';')
        if (fields.size != 3 || fields[0].toIntOrNull() == null) return@mapNotNull null
        val minutes = fields[1].trim().trim('\'').toIntOrNull() ?: return@mapNotNull null
        if (minutes !in 1..180) return@mapNotNull null
        LiveArrival(fields[0], minutes, fields[2], isVerifiedRealtime = false)
    }.distinctBy { it.stopId to it.directionId }

    fun parseVehicleArrivals(body: String, validVehicles: List<Vehicle>): List<LiveArrival> {
        val ids = validVehicles.map { it.id }.toSet()
        return body.split('#').flatMap { record ->
            val fields = record.split(';')
            if (fields.size < 7 || fields[0] !in ids || fields[5].isBlank()) return@flatMap emptyList()
            val stops = fields[5].split('|'); val minutes = fields[6].split('|')
            if (stops.size != minutes.size) return@flatMap emptyList()
            stops.zip(minutes).mapNotNull { (stop, minute) ->
                val value = minute.trim().trim('\'').toIntOrNull()
                if (stop.toIntOrNull() == null || value == null || value !in 1..180) null
                else LiveArrival(stop, value, "", vehicleId = fields[0], isVerifiedRealtime = true)
            }
        }
    }
}
