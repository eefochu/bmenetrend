package hu.app.bmenetrend.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/** A Worker vehicle plus the underlying vehicle numbers used to recognise the same train in EMMA. */
data class WorkerVehicle(val vehicle: Vehicle, val numbers: Set<String>, val fallback: Boolean = false)

/**
 * Maps the Worker's BKK-shaped JSON onto the app's existing EMMA-compatible identities, so saved
 * stops, offline timetables, line catalogues and map markers keep one identity whichever feed
 * served them:
 *  - BKK `BKK_X` stops/trips/routes ⇔ EMMA `BKK:X` (app stop `emma:BKK:X`, route `bkk:BKK:X`);
 *  - BKK railway stops `BKK_00ddddddd[_n]` ⇔ EMMA `1:00ddddddd[_n]` (app `emma:1:…`);
 *  - Worker fallback `emma:1:…` IDs are already the app's EMMA railway identities.
 * Volán platform codes and vehicle plates share EMMA's `hkir:hkir_…` identities. Route/trip
 * identifiers with no shared alias retain the raw BKK identifier instead of guessing one.
 */
object BkkWorkerParser {
    fun stopDirections(text: String): Map<String, Double> {
        val rows = data(text).getJSONArray("list")
        return buildMap {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = appStopId(row.text("id")) ?: continue
                val bearing = (row.opt("bearing") as? Number)?.toDouble() ?: continue
                if (bearing.isFinite() && bearing >= 0 && bearing < 360) put(id, bearing)
            }
        }
    }

    private val RAIL_STOP = Regex("^BKK_(00\\d{7})(_\\d+)?$")
    private val BKK_MODES = setOf("BUS", "TRAM", "TROLLEYBUS", "SUBWAY")
    private val VOLAN_STOP = Regex("^volan_(\\d+_\\d+)$")
    private val EMMA_VOLAN_STOP = Regex("^emma:hkir:hkir_(\\d+_\\d+)$")
    private val VOLAN_PLATE = Regex("^volan_([A-Z]{3,4}\\d{3})$")
    private val EMMA_AGGLO_ROUTE = Regex("^volan:hkir:hkir_V-R-200-(\\d{3})$")
    private val VOLAN_ID = Regex("^volan_[A-Za-z0-9_.-]+$")

    fun appStopId(raw: String?): String? = when {
        raw == null -> null
        raw.startsWith("emma:1:") && raw.length > 7 -> raw
        RAIL_STOP.matches(raw) -> "emma:1:" + raw.removePrefix("BKK_")
        VOLAN_STOP.matches(raw) -> "emma:hkir:hkir_" + VOLAN_STOP.matchEntire(raw)!!.groupValues[1]
        raw.startsWith("BKK_") && raw.length > 4 -> "emma:BKK:" + raw.removePrefix("BKK_")
        else -> null
    }

    /** Try BKK wherever a shared source identity is known, including railway station codes. */
    fun workerStopId(stop: TransitStop): String? = when {
        stop.providerId == "volan" && EMMA_VOLAN_STOP.matches(stop.id) -> "volan_" + EMMA_VOLAN_STOP.matchEntire(stop.id)!!.groupValues[1]
        stop.providerId == "bkk" && stop.id.startsWith("emma:BKK:") && stop.id.length > 9 -> "BKK_" + stop.id.removePrefix("emma:BKK:")
        stop.providerId == "mav" && stop.id.startsWith("emma:1:") &&
            RAIL_STOP.matches("BKK_" + stop.id.removePrefix("emma:1:")) -> "BKK_" + stop.id.removePrefix("emma:1:")
        else -> null
    }

    fun appTripId(raw: String?): String? = when {
        raw == null -> null
        raw.startsWith("emma:1:") -> raw.removePrefix("emma:")
        raw.startsWith("BKK_") && raw.length > 4 -> "BKK:" + raw.removePrefix("BKK_")
        VOLAN_ID.matches(raw) -> "volan:$raw"
        else -> null
    }

    /** BKK trips are served by the Worker; MÁV/HÉV `1:` trips keep using direct EMMA. */
    fun workerTripId(appTripId: String?): String? = when {
        appTripId == null -> null
        appTripId.startsWith("BKK:") && appTripId.length > 4 -> "BKK_" + appTripId.removePrefix("BKK:")
        appTripId.startsWith("volan:") && VOLAN_ID.matches(appTripId.removePrefix("volan:")) -> appTripId.removePrefix("volan:")
        else -> null
    }

    /** Stops that may join the shared catalogue: BKK-only HÉV platforms would duplicate EMMA's HÉV stations. */
    fun sharesCatalogueIdentity(stop: TransitStop): Boolean =
        !(stop.id.startsWith("emma:BKK:") && (stop.providerId != "bkk" || stop.transportMode == "SUBURBAN_RAILWAY" || RailSupport.isTrain(stop.transportMode)))

    // ---- envelopes and references ---------------------------------------------------------

    internal fun data(text: String): JSONObject {
        val root = JSONObject(text)
        require(root.optString("status") == "OK") { "BKK proxy error" }
        return root.getJSONObject("data")
    }

    internal fun route(row: JSONObject): TransitRoute? {
        val raw = row.text("id") ?: return null
        val type = row.text("type") ?: return null
        val short = row.text("shortName")
        val description = row.text("description") ?: row.text("longName")
        if (VOLAN_ID.matches(raw)) {
            if (type !in setOf("BUS", "COACH")) return null
            val number = short ?: description ?: return null
            return TransitRoute("volan:$raw", number, description ?: number, emptyList(), "volan", transportMode = type,
                color = row.text("color")?.takeIf { COLOR.matches(it) })
        }
        if (raw.startsWith("emma:1:")) {
            if (!RailSupport.isTrain(type)) return null
            val number = RailSupport.number(short, description)
            return TransitRoute("mav:" + raw.removePrefix("emma:"), number, description ?: number, emptyList(), "mav", transportMode = type)
        }
        if (!raw.startsWith("BKK_") || raw.length <= 4) return null
        val source = "BKK:" + raw.removePrefix("BKK_")
        return when {
            type in BKK_MODES -> TransitRoute("bkk:$source", short ?: return null, description ?: short, emptyList(), "bkk",
                transportMode = type, color = row.text("color")?.takeIf { COLOR.matches(it) })
            type == "SUBURBAN_RAILWAY" -> {
                val number = HevSupport.number(short, description) ?: return null
                TransitRoute("hev:$source", number, description ?: number, emptyList(), "hev", transportMode = type, color = HevSupport.color(number))
            }
            RailSupport.isTrain(type) -> {
                val number = RailSupport.number(short, description)
                TransitRoute("mav:$source", number, description ?: number, emptyList(), "mav", transportMode = type)
            }
            else -> null
        }
    }

    internal fun routes(references: JSONObject?): Map<String, TransitRoute> {
        val rows = references?.optJSONObject("routes") ?: return emptyMap()
        val result = HashMap<String, TransitRoute>(rows.length() * 2)
        rows.keys().forEach { key -> rows.optJSONObject(key)?.let(::route)?.let { result[key] = it } }
        return result
    }

    internal fun stop(row: JSONObject, routes: Map<String, TransitRoute>): TransitStop? {
        val raw = row.text("id") ?: return null
        if (row.optInt("locationType", 0) != 0) return null
        val id = appStopId(raw) ?: return null
        val name = row.text("name") ?: return null
        val lat = row.number("lat") ?: return null
        val lon = row.number("lon") ?: return null
        if (lat !in 45.7..48.6 || lon !in 16.1..22.9) return null
        val type = row.text("type")
        val railway = id.startsWith("emma:1:")
        val provider = when {
            VOLAN_STOP.matches(raw) -> "volan"
            railway && type == "SUBURBAN_RAILWAY" -> "hev"
            railway -> "mav"
            type == "SUBURBAN_RAILWAY" -> "hev"
            RailSupport.isTrain(type) -> "mav"
            else -> "bkk"
        }
        val ids = row.optJSONArray("routeIds")
        val serving = (0 until (ids?.length() ?: 0)).mapNotNull { routes[ids!!.optString(it)] }
            .filter { sharesPlatform(provider, type, it) }
        val source = id.removePrefix("emma:")
        val parent = row.text("parentStationId")?.let { parent ->
            if (railway) appStopId(parent)?.takeIf { it.startsWith("emma:1:") } else appStopId(parent)
        }
        return TransitStop(id, name, GeoPoint(lat, lon), serving.map { it.id }.distinct(), provider,
            providerIds = (listOf(provider) + serving.map { it.providerId }).distinct(),
            sourceStopIds = listOf(source), platformCode = BkkPlatformIndex.resolve(source, row.text("platformCode")),
            parentStationId = parent, transportMode = if (provider == "hev") "SUBURBAN_RAILWAY" else type)
    }

    internal fun stops(references: JSONObject?, routes: Map<String, TransitRoute>): Map<String, TransitStop> {
        val rows = references?.optJSONObject("stops") ?: return emptyMap()
        val result = HashMap<String, TransitStop>(rows.length() * 2)
        rows.keys().forEach { key -> rows.optJSONObject(key)?.let { stop(it, routes) }?.let { result[key] = it } }
        return result
    }

    // ---- vehicles ------------------------------------------------------------------------

    fun vehicles(text: String, now: Instant): List<WorkerVehicle> {
        val data = data(text)
        val routes = routes(data.optJSONObject("references"))
        val rows = data.optJSONArray("list") ?: return emptyList()
        val result = LinkedHashMap<String, WorkerVehicle>(rows.length() * 2)
        for (index in 0 until rows.length()) {
            val parsed = rows.optJSONObject(index)?.let { vehicle(it, routes, now) } ?: continue
            val previous = result[parsed.vehicle.id]
            if (previous == null || previous.vehicle.observedAt < parsed.vehicle.observedAt) result[parsed.vehicle.id] = parsed
        }
        return result.values.toList()
    }

    internal fun vehicle(row: JSONObject, routes: Map<String, TransitRoute>, now: Instant): WorkerVehicle? {
        val raw = row.text("vehicleId") ?: return null
        val volanPlate = VOLAN_PLATE.matchEntire(raw)?.groupValues?.get(1)
        if (volanPlate == null && (!raw.startsWith("BKK_") || raw.length <= 4)) return null
        val fallback = row.text("source") == "emma"
        val location = row.optJSONObject("location") ?: return null
        val lat = location.number("lat") ?: return null
        val lon = location.number("lon") ?: return null
        if (lat !in 45.7..48.6 || lon !in 16.1..22.9) return null
        // BKK vehicle timestamps are Unix seconds (envelope currentTime is milliseconds).
        val seconds = row.number("lastUpdateTime")?.toLong() ?: return null
        val observed = runCatching { Instant.ofEpochSecond(seconds) }.getOrNull() ?: return null
        if (Duration.between(observed, now).seconds !in -30..180) return null
        val route = row.text("routeId")?.let { routes[it] }
        val type = row.text("vehicleRouteType") ?: route?.transportMode
        val provider = when {
            fallback -> if (RailSupport.isTrain(type)) "mav" else return null
            volanPlate != null && type in setOf("BUS", "COACH") -> "volan"
            type in BKK_MODES -> "bkk"
            type == "SUBURBAN_RAILWAY" -> "hev"
            RailSupport.isTrain(type) -> "mav"
            else -> return null
        }
        if (route != null && route.providerId != provider) return null
        val bare = volanPlate ?: raw.removePrefix("BKK_")
        val components = bare.split('-').filter { it.isNotBlank() }
        if (components.isEmpty()) return null
        val plates = row.text("licensePlate")?.split('-', ' ')?.map { it.trim() }?.filter { it.isNotEmpty() && it.all(Char::isDigit) }.orEmpty()
        val numbers = (components + if (provider == "hev") plates else emptyList()).toSet()
        val id = when {
            provider == "volan" -> "emma:hkir:hkir_$bare"
            provider == "bkk" -> "emma:BKK:$bare"
            fallback -> "emma:" + (row.text("sourceId")?.takeIf { it.startsWith("1:") } ?: "1:$bare")
            provider == "hev" -> plates.firstOrNull()?.let { "emma:1:$it" } ?: "emma:BKK:$bare"
            else -> "emma:1:" + components.first()
        }
        val routeNumber = route?.number
        val trainName = if (fallback) RailSupport.clean(row.text("trainName")) else null
        val vehicle = Vehicle(
            id = id, position = GeoPoint(lat, lon), observedAt = observed,
            routeId = route?.id, providerId = provider, routeNumber = routeNumber,
            destination = row.text("label"),
            registration = row.text("licensePlate") ?: components.first(),
            tripId = appTripId(row.text("tripId")), routeName = route?.name, transportMode = type,
            heading = row.number("bearing")?.takeIf { it in 0.0..360.0 },
            speedKmh = row.number("speedKmh")?.takeIf { it >= 0 },
            markerColor = when (provider) { "bkk" -> route?.color; "hev" -> routeNumber?.let(HevSupport::color); else -> null },
            nextStopId = appStopId(row.text("stopId")),
            nextStopPosition = row.number("stopSequence")?.toInt()?.takeIf { it >= 0 },
            trainNumber = if (provider == "mav") row.text("trainNumber") ?: RailSupport.serviceNumber(trainName) else null,
            serviceName = trainName,
            // The Worker's `source: emma` rows are relayed EMMA data, not BKK observations.
            positionSource = if (fallback) DataSource.EMMA else DataSource.BKK,
            detailsSource = if (fallback) DataSource.EMMA else DataSource.BKK,
            bkkTripId = if (fallback) null else appTripId(row.text("tripId"))?.takeIf { workerTripId(it) != null },
        )
        return WorkerVehicle(vehicle, numbers, fallback)
    }

    // ---- departure boards ----------------------------------------------------------------

    /**
     * Splits one stop or station response into per-platform boards for [requested] (app stops).
     * The catalogue keeps each requested stop's app name; only its serving routes are refreshed.
     */
    fun boards(text: String, requested: List<TransitStop>, now: Instant, windowMinutes: Int): Map<String, EmmaStopsParser.Board> {
        val data = data(text)
        // The batch endpoint reports per-stop upstream failures; only those fall back to EMMA.
        // A stop that answered with no rows is a legitimately empty board.
        val failed = failedStopIds(data)
        val succeeded = data.optJSONArray("successfulStopIds")?.let { rows -> (0 until rows.length()).mapTo(HashSet()) { rows.optString(it) } }
        val references = data.optJSONObject("references")
        val routes = routes(references)
        val trips = references?.optJSONObject("trips")
        val refStops = references?.optJSONObject("stops")
        val entry = data.getJSONObject("entry")
        val byRaw = requested.mapNotNull { stop -> workerStopId(stop)?.takeIf { it !in failed && (succeeded == null || it in succeeded) }?.let { it to stop } }.toMap()
        val rows = entry.optJSONArray("stopTimes") ?: JSONArray()
        val departures = HashMap<String, MutableList<Departure>>()
        val boardRoutes = HashMap<String, LinkedHashMap<String, TransitRoute>>()
        val until = now.plusSeconds(windowMinutes * 60L)
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val rawStop = row.text("stopId") ?: entry.text("stopId") ?: continue
            val stop = byRaw[rawStop] ?: continue
            val canceled = row.optBoolean("canceled", false)
            if (!canceled && !row.optBoolean("pickupAllowed", true)) continue
            val rawTrip = row.text("tripId") ?: continue
            val trip = trips?.optJSONObject(rawTrip)
            val route = (trip?.text("routeId") ?: row.text("routeId"))?.let { routes[it] } ?: continue
            if (!sharesPlatform(stop.providerId, stop.transportMode, route)) continue
            // An arrival-only row (terminus) cannot be boarded.
            val scheduledSeconds = row.number("departureTime")?.toLong() ?: continue
            val predictedSeconds = row.number("predictedDepartureTime")?.toLong()
            val realtime = !canceled && predictedSeconds != null
            val scheduled = Instant.ofEpochSecond(scheduledSeconds)
            val expected = if (realtime) Instant.ofEpochSecond(predictedSeconds!!) else scheduled
            if (expected < now || expected > until) continue
            boardRoutes.getOrPut(stop.id) { LinkedHashMap() }[route.id] = route
            val accessible = if (row.has("wheelchairAccessible")) row.optBoolean("wheelchairAccessible") else trip?.takeIf { it.has("wheelchairAccessible") }?.optBoolean("wheelchairAccessible")
            val bikes = trip?.takeIf { it.has("bikesAllowed") }?.optBoolean("bikesAllowed")
            val source = stop.id.removePrefix("emma:")
            departures.getOrPut(stop.id) { mutableListOf() } += Departure(
                route.id, route.number, row.text("stopHeadsign") ?: trip?.text("tripHeadsign").orEmpty(),
                stop.id, scheduled, expected, if (realtime) ArrivalKind.LIVE else ArrivalKind.SCHEDULED,
                directionId = appTripId(rawTrip), sourceDirectionId = trip?.text("directionId"),
                wheelchairAccessible = accessible?.let { if (it) "POSSIBLE" else "NOT_POSSIBLE" },
                bikesAllowed = bikes?.let { if (it) "ALLOWED" else "NOT_ALLOWED" },
                realtimeState = when { canceled -> "CANCELED"; realtime -> "UPDATED"; else -> "SCHEDULED" },
                platformCode = BkkPlatformIndex.resolve(source, refStops?.optJSONObject(rawStop)?.text("platformCode")),
                source = DataSource.BKK,
            )
        }
        val limited = data.optBoolean("limitExceeded", false)
        val routesById = routes.values.associateBy { it.id }
        return byRaw.entries.associate { (raw, target) ->
            val serving = refStops?.optJSONObject(raw)?.let { row -> stop(row, routes) }?.routeIds.orEmpty().mapNotNull { routesById[it] }
            val stopRoutes = LinkedHashMap<String, TransitRoute>().apply {
                serving.forEach { put(it.id, it) }; boardRoutes[target.id]?.let { putAll(it) }
            }
            val catalogued = if (stopRoutes.isEmpty()) target else target.copy(routeIds = (target.routeIds + stopRoutes.keys).distinct())
            val sorted = departures[target.id].orEmpty().distinctBy { listOf(it.routeId, it.directionId, it.expectedAt) }.sortedBy { it.expectedAt }
            target.id to EmmaStopsParser.Board(target.id, sorted, EmmaStopsParser.Catalogue(listOf(catalogued), stopRoutes.values.toList()), now, limited)
        }
    }

    /** Raw BKK stop IDs the batch board endpoint could not answer. */
    internal fun failedStopIds(data: JSONObject): Set<String> {
        val rows = data.optJSONArray("failedStopIds") ?: return emptySet()
        return (0 until rows.length()).mapNotNullTo(HashSet()) { rows.optString(it).takeIf { id -> id.isNotBlank() } }
    }

    // ---- trip details --------------------------------------------------------------------

    fun journey(text: String, expectedTripId: String): VehicleJourney {
        val data = data(text)
        val references = data.optJSONObject("references")
        val routes = routes(references)
        val stops = stops(references, routes)
        val entry = data.getJSONObject("entry")
        val rawTrip = entry.text("tripId")
        require(rawTrip == null || appTripId(rawTrip) == expectedTripId) { "Wrong trip returned" }
        val trip = rawTrip?.let { references?.optJSONObject("trips")?.optJSONObject(it) }
        val route = (trip?.text("routeId") ?: entry.optJSONObject("vehicle")?.text("routeId"))?.let { routes[it] }
            ?: routes.values.singleOrNull() ?: error("Missing route")
        val rows = entry.getJSONArray("stopTimes")
        val journeyStops = ArrayList<TransitStop>(rows.length())
        val times = ArrayList<JourneyStopTime?>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            journeyStops += stops[row.getString("stopId")] ?: error("Unknown trip stop")
            val first = index == 0
            val scheduled = (if (first) row.number("departureTime") ?: row.number("arrivalTime") else row.number("arrivalTime") ?: row.number("departureTime"))?.toLong()
            val predicted = (if (first) row.number("predictedDepartureTime") ?: row.number("predictedArrivalTime") else row.number("predictedArrivalTime") ?: row.number("predictedDepartureTime"))?.toLong()
            val expected = predicted ?: scheduled
            times += expected?.let {
                JourneyStopTime(clock(it), scheduled?.takeIf { s -> predicted != null && s / 60 != it / 60 }?.let(::clock),
                    isLive = predicted != null, delayMinutes = if (predicted != null && scheduled != null) ((predicted - scheduled) / 60).toInt() else 0)
            }
        }
        require(journeyStops.size >= 2) { "Trip has too few stops" }
        val geometry = entry.optJSONObject("polyline")?.text("points")?.let(::decodePolyline)
            ?: entry.optJSONArray("geometry")?.let { array -> (0 until array.length()).mapNotNull { i ->
                array.optJSONArray(i)?.let { pair -> GeoPoint(pair.getDouble(0), pair.getDouble(1)) }
            } }.orEmpty()
        val headsign = trip?.text("tripHeadsign") ?: rows.optJSONObject(0)?.text("stopHeadsign")
        return VehicleJourney(expectedTripId, route.number, route.name, JourneyDirection.destination(journeyStops, headsign),
            journeyStops, geometry.filter { it.lat in 45.0..49.5 && it.lon in 15.5..23.5 }, null, times, trip?.text("directionId"), DataSource.BKK)
    }

    // ---- line details --------------------------------------------------------------------

    /** App line IDs BKK can describe: its own lines and BKK-identified HÉV lines. */
    fun workerRouteId(appRouteId: String): String? {
        if (appRouteId.startsWith("volan:") && VOLAN_ID.matches(appRouteId.removePrefix("volan:"))) return appRouteId.removePrefix("volan:")
        // This agglomeration namespace is shared with FUTÁR (e.g. 735/756/760). National
        // V-O and other Volán catalogues have no proven alias and continue through EMMA.
        EMMA_AGGLO_ROUTE.matchEntire(appRouteId)?.let { return "volan_" + it.groupValues[1] }
        val source = when {
            appRouteId.startsWith("bkk:BKK:") -> appRouteId.removePrefix("bkk:BKK:")
            appRouteId.startsWith("hev:BKK:") -> appRouteId.removePrefix("hev:BKK:")
            appRouteId.startsWith("mav:BKK:") -> appRouteId.removePrefix("mav:BKK:")
            else -> return null
        }
        return if (source.isNotEmpty() && source.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }) "BKK_$source" else null
    }

    /**
     * BKK `route-details` variants as line patterns. BKK has no EMMA pattern codes, so [LinePattern.codes]
     * stays empty and boarding filters use the GTFS direction (as for BKK trip details).
     */
    fun routeDetails(text: String, appRouteId: String): LineDetails {
        val data = data(text)
        val references = data.optJSONObject("references")
        val routes = routes(references)
        val stops = stops(references, routes)
        val entry = data.getJSONObject("entry")
        val raw = entry.text("id") ?: error("Missing route")
        require(workerRouteId(appRouteId) == raw) { "Wrong route returned" }
        val parsed = route(entry) ?: routes[raw] ?: error("Unsupported route")
        val route = parsed.copy(id = appRouteId)
        val variants = entry.optJSONArray("variants") ?: JSONArray()
        val patterns = (0 until variants.length()).mapNotNull { index ->
            val variant = variants.optJSONObject(index) ?: return@mapNotNull null
            val ids = variant.optJSONArray("stopIds") ?: return@mapNotNull null
            val patternStops = (0 until ids.length()).mapNotNull { stops[ids.optString(it)] }
                .map { if (appRouteId in it.routeIds) it else it.copy(routeIds = it.routeIds + appRouteId) }
            // A variant whose stops cannot all be mapped would draw a wrong path; skip it.
            if (patternStops.size < 2 || patternStops.size != ids.length()) return@mapNotNull null
            // GTFS direction when BKK gives one; metro variants report "[multidirection]".
            val direction = variant.text("direction")?.takeIf { it.all(Char::isDigit) }
            val geometry = variant.optJSONObject("polyline")?.text("points")?.let { runCatching { decodePolyline(it) }.getOrNull() }.orEmpty()
                .filter { it.lat in 45.0..49.5 && it.lon in 15.5..23.5 }
            val headsign = JourneyDirection.destination(patternStops, variant.text("headsign")) ?: patternStops.last().name
            LinePattern("bkk:" + (variant.text("patternId") ?: "$raw:${direction ?: "-"}:$index"), emptySet(), headsign,
                patternStops, geometry, directionId = direction)
        }.distinctBy { it.headsign to it.stops.map(TransitStop::id) }
        require(patterns.isNotEmpty()) { "Route has no usable variants" }
        return LineDetails(route, patterns, DataSource.BKK)
    }

    // ---- stops: map area and search ------------------------------------------------------

    fun areaStops(text: String): EmmaStopsParser.Catalogue {
        val data = data(text)
        val routes = routes(data.optJSONObject("references"))
        val rows = data.optJSONArray("list") ?: JSONArray()
        val stops = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let { row -> stop(row, routes) } }
            .filter(::sharesCatalogueIdentity).distinctBy { it.id }
        val used = stops.flatMap { it.routeIds }.toSet()
        return EmmaStopsParser.Catalogue(stops, routes.values.filter { it.id in used }.distinctBy { it.id })
    }

    /** One BKK search: stops (BKK first, Worker-merged EMMA after), matching BKK line IDs and relayed trains. */
    class SearchResult(val catalogue: EmmaStopsParser.Catalogue, val stopSources: Map<String, DataSource>,
                       val routeIds: List<String>, val vehicles: List<WorkerVehicle>)

    fun search(text: String, now: Instant): SearchResult {
        val data = data(text)
        val references = data.optJSONObject("references")
        val routes = routes(references)
        val rawStops = references?.optJSONObject("stops")
        val byRaw = stops(references, routes)
        val entry = data.optJSONObject("entry")
        val ids = entry?.optJSONArray("stopIds")
        val sources = HashMap<String, DataSource>()
        val stops = (0 until (ids?.length() ?: 0)).mapNotNull { index ->
            val raw = ids!!.optString(index)
            byRaw[raw]?.also { stop ->
                // The Worker marks stops it merged in from EMMA; everything else is BKK's own record.
                if (stop.id !in sources) sources[stop.id] = if (rawStops?.optJSONObject(raw)?.text("source") == "emma") DataSource.EMMA else DataSource.BKK
            }
        }.filter(::sharesCatalogueIdentity).distinctBy { it.id }
        val used = stops.flatMap { it.routeIds }.toSet()
        val routeIds = entry?.optJSONArray("routeIds")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
            .filter { routes[it] != null }.distinct()
        val vehicleRows = references?.optJSONObject("vehicles")
        val vehicleIds = entry?.optJSONArray("vehicleIds")
        val vehicles = (0 until (vehicleIds?.length() ?: 0)).mapNotNull { index ->
            vehicleRows?.optJSONObject(vehicleIds!!.optString(index))?.let { vehicle(it, routes, now) }
        }.distinctBy { it.vehicle.id }
        return SearchResult(EmmaStopsParser.Catalogue(stops, routes.values.filter { it.id in used }.distinctBy { it.id }),
            sources.filterKeys { id -> stops.any { it.id == id } }, routeIds, vehicles)
    }

    fun searchStops(text: String): EmmaStopsParser.Catalogue {
        val data = data(text)
        val references = data.optJSONObject("references")
        val routes = routes(references)
        val byRaw = stops(references, routes)
        val ids = data.optJSONObject("entry")?.optJSONArray("stopIds")
        val stops = (0 until (ids?.length() ?: 0)).mapNotNull { byRaw[ids!!.optString(it)] }
            .filter(::sharesCatalogueIdentity).distinctBy { it.id }
        val used = stops.flatMap { it.routeIds }.toSet()
        return EmmaStopsParser.Catalogue(stops, routes.values.filter { it.id in used }.distinctBy { it.id })
    }

    // ---- trip planning -------------------------------------------------------------------

    /**
     * BKK's OTP answer. [rejected] counts itineraries BKK found but the app cannot show faithfully
     * (another agency's leg without a shared identity, malformed data); those need EMMA. Itineraries
     * that are merely infeasible for the requested time are not counted: EMMA would apply the same rule.
     */
    class PlanResult(val itineraries: List<TripItinerary>, val rejected: Int, val offered: Int)

    /** BKK's OTP itineraries, validated by the same feasibility rules as EMMA's planner. */
    fun plan(text: String, ready: Instant): List<TripItinerary> = planResult(text, ready).itineraries

    fun planResult(text: String, ready: Instant, fetchedAt: Instant? = null): PlanResult {
        val data = data(text)
        val entry = data.getJSONObject("entry")
        require(!entry.has("error") || entry.isNull("error")) { "BKK planner error" }
        val itineraries = entry.optJSONObject("plan")?.optJSONArray("itineraries") ?: return PlanResult(emptyList(), 0, 0)
        var rejected = 0
        val parsed = (0 until itineraries.length()).mapNotNull { index ->
            val legs = runCatching {
                val row = itineraries.getJSONObject(index)
                val rows = row.getJSONArray("legs")
                require(rows.length() in 1..40)
                (0 until rows.length()).map { leg(rows.getJSONObject(it)) }
            }.getOrElse { rejected++; return@mapNotNull null }
            val keys = legs.map(TripPlanner::routerLegKey)
            val journey = "bkk:${ready.toEpochMilli()}:$index:" + keys.joinToString("|")
            val routed = legs.mapIndexed { i, leg -> leg.copy(routerJourney = journey, routerPrevious = keys.getOrNull(i - 1)) }
            TripPlanner.itinerary(routed, ready)?.copy(fetchedAt = fetchedAt)
        }.distinctBy { it.id }
        return PlanResult(parsed, rejected, itineraries.length())
    }

    /** A BKK `mode=WALK` plan as a street walking link, or null when BKK found no walk-only itinerary. */
    fun walking(text: String, from: PlanPoint, to: PlanPoint, ready: Instant): WalkingLink? {
        val trip = planResult(text, ready).itineraries.firstOrNull { it.legs.all { leg -> leg.walking } } ?: return null
        return WalkingLink(from, to, Duration.between(trip.start, trip.end).seconds, trip.walkMeters)
    }

    private val PLAN_MODES = setOf("WALK", "BUS", "COACH", "TRAM", "TROLLEYBUS", "SUBWAY", "RAIL", "SUBURBAN_RAILWAY", "TRAMTRAIN",
        "FERRY", "CABLE_CAR", "FUNICULAR")
    private val BKK_SPECIAL_MODES = setOf("COACH", "FERRY", "CABLE_CAR", "FUNICULAR")

    private fun sharesPlatform(provider: String, mode: String?, route: TransitRoute): Boolean =
        StopBoardScope.acceptsMode(mode, route.transportMode) && (route.providerId == provider ||
            (provider in setOf("bkk", "volan") && route.providerId in setOf("bkk", "volan") &&
                mode in setOf("BUS", "COACH") && route.transportMode in setOf("BUS", "COACH")))

    /**
     * A planner leg's line. BKK lines keep the shared `bkk:BKK:` identity (rail/HÉV as in [route]).
     * Volán lines found by BKK's planner have no proven EMMA (`hkir:`) alias, so they keep BKK's raw
     * agency ID under the Volán provider (`volan:volan_599`): no invented IDs, and no collapsing with
     * another operator's line of the same short name. Other agencies are genuinely unsupported.
     */
    internal fun planRoute(raw: String, mode: String, short: String?, long: String?, color: String?): TransitRoute? {
        val shown = short?.takeUnless { name -> '<' in name || name.any { Character.getType(it) == Character.PRIVATE_USE.toInt() } }
        val safeColor = color?.takeIf { COLOR.matches(it) }
        if (raw.startsWith("volan_") && raw.length > 6 && raw.all { it.isLetterOrDigit() || it in "_-." }) {
            if (mode != "BUS" && mode != "COACH") return null
            val number = shown ?: long ?: return null
            return TransitRoute("volan:$raw", number, long ?: number, emptyList(), "volan", transportMode = mode, color = safeColor)
        }
        if (!raw.startsWith("BKK_") || raw.length <= 4) return null
        route(JSONObject().put("id", raw).put("type", mode).put("shortName", shown).put("description", long).put("color", safeColor))?.let { return it }
        if (mode !in BKK_SPECIAL_MODES) return null
        val number = shown ?: long ?: return null
        return TransitRoute("bkk:BKK:" + raw.removePrefix("BKK_"), number, long ?: number, emptyList(), "bkk", transportMode = mode, color = safeColor)
    }

    private fun leg(row: JSONObject): PlanLeg {
        val mode = row.getString("mode")
        require(mode in PLAN_MODES)
        // Planner timestamps are milliseconds.
        val start = Instant.ofEpochMilli(row.getLong("startTime"))
        val end = Instant.ofEpochMilli(row.getLong("endTime"))
        val distance = row.getDouble("distance")
        require(!end.isBefore(start) && distance.isFinite() && distance >= 0)
        if (mode == "WALK") require(Duration.between(start, end).seconds + 10 >= distance / 2.2)
        val route = if (mode == "WALK") null else planRoute(row.getString("routeId"), mode, row.text("routeShortName"),
            row.text("routeLongName"), row.text("routeColor")) ?: error("Unsupported route")
        val stops = row.optJSONArray("intermediateStops")
        return PlanLeg(mode, start, end, distance, point(row.getJSONObject("from")), point(row.getJSONObject("to")), route,
            row.text("headsign"), row.optBoolean("realTime"), row.optBoolean("interlineWithPreviousLeg"),
            (0 until (stops?.length() ?: 0)).map { point(stops!!.getJSONObject(it)) }, source = DataSource.BKK)
    }

    private fun point(row: JSONObject): PlanPoint {
        val point = GeoPoint(row.getDouble("lat"), row.getDouble("lon"))
        require(point.lat in 45.0..49.5 && point.lon in 15.5..23.5)
        val source = appStopId(row.text("stopId"))?.removePrefix("emma:")
        return PlanPoint(row.text("name") ?: error("Unnamed point"), point, BkkPlatformIndex.resolve(source, row.text("platformCode")))
    }

    // ---- helpers -------------------------------------------------------------------------

    /** Google encoded polyline (BKK `polyline.points`). */
    fun decodePolyline(encoded: String): List<GeoPoint> {
        val points = ArrayList<GeoPoint>(encoded.length / 4)
        var index = 0; var lat = 0; var lon = 0
        fun next(): Int {
            var result = 0; var shift = 0; var byte: Int
            do {
                require(index < encoded.length) { "Truncated polyline" }
                byte = encoded[index++].code - 63
                result = result or ((byte and 0x1f) shl shift)
                shift += 5
            } while (byte >= 0x20)
            return if (result and 1 != 0) (result shr 1).inv() else result shr 1
        }
        while (index < encoded.length) {
            lat += next(); lon += next()
            points += GeoPoint(lat / 1e5, lon / 1e5)
        }
        return points
    }

    private val COLOR = Regex("[a-fA-F0-9]{6}")
    private val ZONE = java.time.ZoneId.of("Europe/Budapest")
    private val HH_MM = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(ZONE)
    private fun clock(seconds: Long): String = HH_MM.format(Instant.ofEpochSecond(seconds))
    private fun JSONObject.text(key: String): String? = optString(key).takeUnless { it.isBlank() || it == "null" }
    private fun JSONObject.number(key: String): Double? = (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
}
