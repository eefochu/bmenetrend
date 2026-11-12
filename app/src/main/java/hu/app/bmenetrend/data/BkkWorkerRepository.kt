package hu.app.bmenetrend.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.round

/**
 * BKK-first online data through the hosted Worker. Every call parses off the main thread and
 * throws on failure so callers can fall back to direct EMMA for the same item.
 */
class BkkWorkerRepository(private val client: BkkWorkerClient = BkkWorkerClient()) {
    data class VehicleSnapshot(val vehicles: List<WorkerVehicle>, val covered: MapBounds, val fetchedAt: Instant)

    /**
     * Vehicles in [area]. One Worker request covers at most ±[MAX_HALF_SPAN]°; a larger area (see
     * [queryArea]) is fetched as a grid of such boxes in parallel, so zoomed-out views stay on BKK.
     */
    suspend fun vehicles(area: MapBounds): VehicleSnapshot = coroutineScope {
        val tiles = tiles(area)
        val now = Instant.now()
        val parts = tiles.map { tile -> async {
            val text = client.get("vehicles-for-location", tile.params() + ("includeReferences" to "routes"))
            withContext(Dispatchers.Default) { BkkWorkerParser.vehicles(text, now) }
        } }.awaitAll()
        VehicleSnapshot(if (parts.size == 1) parts[0] else parts.flatten().distinctBy { it.vehicle.id }, area, now)
    }

    /**
     * The selected vehicle straight from BKK, for when it is outside the area the map query covers
     * (line view zoomed out, vehicle found by search). Empty when BKK does not know the vehicle.
     */
    suspend fun vehicle(vehicle: Vehicle): List<WorkerVehicle> {
        val raw = workerVehicleId(vehicle) ?: return emptyList()
        val text = client.get("vehicles", mapOf("vehicleId" to raw, "includeReferences" to "routes"))
        return withContext(Dispatchers.Default) { BkkWorkerParser.vehicles(text, Instant.now()) }.filter { it.vehicle.id == vehicle.id }
    }

    /**
     * Boards for BKK platforms, six per Worker request (`arrivals-and-departures-for-stops`), batches
     * issued concurrently. Every requested BKK platform has an entry: a board (possibly legitimately
     * empty) when BKK answered for it, or null when that stop failed, so the caller can ask EMMA for
     * exactly those stops. One failed batch never affects another.
     */
    suspend fun boards(stops: List<TransitStop>, windowMinutes: Int): Map<String, EmmaStopsParser.Board?> = coroutineScope {
        stops.filter { BkkWorkerParser.workerStopId(it) != null }.distinctBy { it.id }.chunked(MAX_BATCH_STOPS).map { batch -> async {
            val boards = try { batchBoards(batch, windowMinutes) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { emptyMap() }
            batch.map { it.id to boards[it.id] }
        } }.awaitAll().flatten().toMap()
    }

    private suspend fun batchBoards(stops: List<TransitStop>, windowMinutes: Int): Map<String, EmmaStopsParser.Board> {
        val ids = stops.mapNotNull(BkkWorkerParser::workerStopId)
        require(ids.size in 1..MAX_BATCH_STOPS)
        // Parameters stay identical across screens so requests share one upstream call and its cache.
        val fetched = client.fetch("arrivals-and-departures-for-stops", mapOf("stopId" to ids.joinToString(","), "minutesBefore" to "0",
            "minutesAfter" to windowMinutes.coerceIn(30, 120).toString(), "limit" to "50", "includeReferences" to "routes,trips,stops"))
        val now = Instant.now()
        return withContext(Dispatchers.Default) { BkkWorkerParser.boards(fetched.text, stops, now, windowMinutes) }
            // Departures are filtered against now; the board keeps the time BKK's answer was received.
            .mapValues { (_, board) -> board.copy(fetchedAt = minOf(now, fetched.fetchedAt)) }
    }

    suspend fun board(stop: TransitStop, windowMinutes: Int): EmmaStopsParser.Board {
        val raw = requireNotNull(BkkWorkerParser.workerStopId(stop)) { "Not a BKK stop" }
        // The Worker's departure window and row limit are fixed; requests stay identical across
        // screens so they share one upstream call and its cache.
        val text = client.get("arrivals-and-departures-for-stop", mapOf("stopId" to raw, "minutesBefore" to "0",
            "minutesAfter" to windowMinutes.coerceIn(30, 120).toString(), "limit" to "50", "includeReferences" to "routes,trips,stops"))
        val now = Instant.now()
        return withContext(Dispatchers.Default) { BkkWorkerParser.boards(text, listOf(stop), now, windowMinutes) }[stop.id]
            ?: error("Missing BKK board")
    }

    /** Serving lines for favourites, from the same batched boards (stop references carry every serving route). */
    suspend fun servingLines(stops: List<TransitStop>): Map<String, EmmaStopsRepository.Lines?> =
        boards(stops, FAVOURITE_WINDOW_MINUTES).mapValues { (_, board) ->
            board?.let { EmmaStopsRepository.Lines(it.catalogue, it.fetchedAt, source = DataSource.BKK) }
        }

    /** BKK line variants; [appRouteId] must be a BKK-identified line (see [BkkWorkerParser.workerRouteId]). */
    suspend fun routeDetails(appRouteId: String, date: java.time.LocalDate): LineDetails {
        val raw = requireNotNull(BkkWorkerParser.workerRouteId(appRouteId)) { "Not a BKK line" }
        val text = client.get("route-details", mapOf("routeId" to raw, "date" to date.format(DateTimeFormatter.BASIC_ISO_DATE),
            "related" to "false", "includeReferences" to "routes,stops"))
        return withContext(Dispatchers.Default) { BkkWorkerParser.routeDetails(text, appRouteId) }
    }

    suspend fun journey(tripId: String): VehicleJourney {
        val raw = requireNotNull(BkkWorkerParser.workerTripId(tripId)) { "Not a BKK trip" }
        val text = client.get("trip-details", mapOf("tripId" to raw, "includeReferences" to "routes,trips,stops"))
        return withContext(Dispatchers.Default) { BkkWorkerParser.journey(text, tripId) }
    }

    /** Resolve a native Volán vehicle's FUTÁR trip by its shared plate, never by guessing trip IDs. */
    suspend fun journey(vehicle: Vehicle): VehicleJourney? {
        var trip = vehicle.bkkTripId?.takeIf { BkkWorkerParser.workerTripId(it) != null }
            ?: vehicle.tripId?.takeIf { BkkWorkerParser.workerTripId(it) != null }
        if (trip == null && vehicle.providerId == "volan") trip = vehicle(vehicle).firstOrNull()?.vehicle?.bkkTripId
        return trip?.let { journey(it) }
    }

    suspend fun searchStops(query: String, near: GeoPoint?): EmmaStopsParser.Catalogue = search(query, near).catalogue

    /** BKK search (stops, line IDs and Worker-relayed trains). Map and planner share the cached call. */
    suspend fun search(query: String, near: GeoPoint?): BkkWorkerParser.SearchResult {
        val fetched = client.fetch("search", searchParams(query, near))
        return withContext(Dispatchers.Default) { BkkWorkerParser.search(fetched.text, Instant.now()) }
    }

    /**
     * Live vehicles matching [query] anywhere BKK knows them, not only in the loaded viewport: vehicles
     * of the matching BKK lines, an exact BKK vehicle number, and trains the Worker relays for the query.
     */
    suspend fun searchVehicles(query: String, near: GeoPoint?): List<WorkerVehicle> = coroutineScope {
        val trimmed = query.trim()
        val exactId = trimmed.replace(TextPatterns.FEED_ID_PREFIX, "")
        val exact = async {
            val plate = exactId.uppercase(java.util.Locale.ROOT).replace("-", "").takeIf { it.matches(TextPatterns.LICENSE_PLATE) }
            if (!VEHICLE_NUMBER.matches(exactId) && plate == null) emptyList() else optional {
                val raw = if (plate != null) "volan_$plate" else "BKK_$exactId"
                val text = client.get("vehicles", mapOf("vehicleId" to raw, "includeReferences" to "routes"))
                withContext(Dispatchers.Default) { BkkWorkerParser.vehicles(text, Instant.now()) }
            }
        }
        val found = search(query, near)
        val byRoute = found.routeIds.take(MAX_SEARCH_ROUTES).takeIf { it.isNotEmpty() }?.let { ids ->
            optional {
                val text = client.get("vehicles-for-route", mapOf("routeId" to ids.joinToString(","), "includeReferences" to "routes"))
                withContext(Dispatchers.Default) { BkkWorkerParser.vehicles(text, Instant.now()) }
            }
        }.orEmpty()
        // BKK's own records first; relayed trains only fill numbers BKK did not report.
        val primary = (exact.await() + byRoute + found.vehicles).filterNot { it.fallback }.distinctBy { it.vehicle.id }
        val numbers = primary.flatMapTo(HashSet()) { it.numbers }
        primary + (exact.await() + byRoute + found.vehicles).filter { it.fallback && it.numbers.none(numbers::contains) }.distinctBy { it.vehicle.id }
    }

    private suspend fun <T> optional(block: suspend () -> List<T>): List<T> = try { block() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }

    private fun searchParams(query: String, near: GeoPoint?): Map<String, String> {
        val trimmed = query.trim().take(80)
        require(trimmed.length >= 2)
        return buildMap {
            put("query", trimmed); put("includeReferences", "stops,routes")
            near?.takeIf { inHungary(it) }?.let { put("lat", coordinate(it.lat)); put("lon", coordinate(it.lon)) }
        }
    }

    suspend fun areaStops(area: MapBounds): EmmaStopsParser.Catalogue {
        val text = client.get("stops-for-location", area.params() + ("includeReferences" to "routes"))
        return withContext(Dispatchers.Default) { BkkWorkerParser.areaStops(text) }
    }

    suspend fun stopDirections(area: MapBounds): Map<String, Double> = coroutineScope {
        tiles(area).map { tile -> async {
            val text = client.get("stop-directions-for-location", tile.params())
            withContext(Dispatchers.Default) { BkkWorkerParser.stopDirections(text) }
        } }.awaitAll().fold(emptyMap()) { combined, tile -> combined + tile }
    }

    suspend fun plan(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant): List<TripItinerary> = planResult(from, to, ready, false).itineraries

    /** BKK's planner for any accepted Hungarian coordinates. Bus-only uses the Worker's `BUS,COACH,WALK` mode. */
    suspend fun planResult(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): BkkWorkerParser.PlanResult {
        val fetched = client.fetch("plan-trip", planParams(place(from), place(to), ready, if (busesOnly) "BUS,COACH,WALK" else "TRANSIT,WALK", "3"))
        return withContext(Dispatchers.Default) { BkkWorkerParser.planResult(fetched.text, ready, fetched.fetchedAt) }
    }

    /** One street walk through BKK's planner (`mode=WALK`); null when BKK found none. */
    suspend fun walking(from: PlanPoint, to: PlanPoint, ready: Instant): WalkingLink? {
        val text = client.get("plan-trip", planParams(place(from.name, from.position), place(to.name, to.position), ready, "WALK", "1"))
        return withContext(Dispatchers.Default) { BkkWorkerParser.walking(text, from, to, ready) }
    }

    /** Planner calls left this minute (client-side budget). */
    fun planHeadroom(): Int = client.planHeadroom()

    private fun planParams(from: String, to: String, ready: Instant, mode: String, count: String): Map<String, String> {
        val local = ready.atZone(TripPlanner.zone)
        return mapOf(
            "fromPlace" to from, "toPlace" to to, "mode" to mode,
            "date" to local.toLocalDate().toString(), "time" to local.format(DateTimeFormatter.ofPattern("HH:mm")),
            "numItineraries" to count, "showIntermediateStops" to "true",
            "includeReferences" to "false", "compact" to "true",
        )
    }

    companion object {
        val shared by lazy { BkkWorkerRepository(BkkWorkerClient()) }
        /** The Worker accepts at most 0.09° from centre to edge. */
        const val MAX_HALF_SPAN = 0.09
        /** 2 × 2 requests per 10 s poll stays well inside the client's per-minute Worker budget. */
        const val MAX_TILES_PER_AXIS = 2
        /** The Worker's batch board limit. */
        const val MAX_BATCH_STOPS = 6
        private const val MAX_SEARCH_ROUTES = 4
        private const val FAVOURITE_WINDOW_MINUTES = 120
        private val VEHICLE_NUMBER = Regex("[A-Za-z]{0,3}\\d{3,14}(?:-\\d{3,14})*")
        private const val STEP = 0.01
        private val SERVICE_AREA = MapBounds(18.55, 47.15, 19.75, 47.85)
        /** The app's Hungary bounds within the Worker's accepted coordinates. */
        private val WORKER_AREA = MapBounds(16.1, 45.7, 22.9, 48.6)

        /**
         * A grid-aligned query box covering [viewport], or null when it is too large for one
         * Worker request or lies outside Hungary. Snapping makes small pans reuse the
         * same URL, so the client/Worker caches answer them.
         */
        fun queryArea(viewport: MapBounds): MapBounds? {
            if (viewport.west >= viewport.east || viewport.south >= viewport.north) return null
            // Anywhere the Worker accepts coordinates: BKK answers for its own network and, through its
            // planner data, beyond Budapest; an empty answer leaves the area to EMMA.
            if (viewport.east < WORKER_AREA.west || viewport.west > WORKER_AREA.east ||
                viewport.north < WORKER_AREA.south || viewport.south > WORKER_AREA.north) return null
            val lat = round((viewport.south + viewport.north) / 2 / STEP) * STEP
            val lon = round((viewport.west + viewport.east) / 2 / STEP) * STEP
            val latSpan = ceil((max(viewport.north - lat, lat - viewport.south) + STEP / 2) / STEP) * STEP
            val lonSpan = ceil((max(viewport.east - lon, lon - viewport.west) + STEP / 2) / STEP) * STEP
            val limit = MAX_HALF_SPAN * MAX_TILES_PER_AXIS + 1e-9
            if (latSpan > limit || lonSpan > limit) return null
            return MapBounds(lon - lonSpan, lat - latSpan, lon + lonSpan, lat + latSpan)
        }

        /** [area] split into the fewest equal boxes one Worker request can cover each. */
        internal fun tiles(area: MapBounds): List<MapBounds> {
            val columns = ceil((area.east - area.west) / (2 * MAX_HALF_SPAN) - 1e-9).toInt().coerceAtLeast(1)
            val rows = ceil((area.north - area.south) / (2 * MAX_HALF_SPAN) - 1e-9).toInt().coerceAtLeast(1)
            val width = (area.east - area.west) / columns
            val height = (area.north - area.south) / rows
            return (0 until rows).flatMap { row -> (0 until columns).map { column ->
                MapBounds(area.west + column * width, area.south + row * height, area.west + (column + 1) * width, area.south + (row + 1) * height)
            } }
        }

        /** BKK's own id for a vehicle the app knows under the shared EMMA identity, or null. */
        internal fun workerVehicleId(vehicle: Vehicle): String? = when (vehicle.providerId) {
            "volan" -> vehicle.id.removePrefix("emma:hkir:hkir_").takeIf { it.matches(TextPatterns.LICENSE_PLATE) }?.let { "volan_$it" }
            "bkk" -> vehicle.id.removePrefix("emma:BKK:").takeIf { it != vehicle.id && VEHICLE_NUMBER.matches(it) }?.let { "BKK_$it" }
            else -> null
        }

        /** Coordinates the Worker's planner accepts (the app's own Hungary bounds are narrower). */
        fun plannable(point: GeoPoint) = TripPlanner.supported(point) && point.lat in 45.0..49.0 && point.lon in 16.0..23.5

        fun inServiceArea(point: GeoPoint) = point.lat in SERVICE_AREA.south..SERVICE_AREA.north && point.lon in SERVICE_AREA.west..SERVICE_AREA.east

        private fun MapBounds.params(): Map<String, String> = mapOf(
            "lat" to coordinate((south + north) / 2), "lon" to coordinate((west + east) / 2),
            "latSpan" to coordinate(abs(north - south) / 2), "lonSpan" to coordinate(abs(east - west) / 2),
        )
        private fun coordinate(value: Double) = String.format(java.util.Locale.ROOT, "%.5f", value)
        private fun inHungary(point: GeoPoint) = point.lat in 45.0..49.0 && point.lon in 16.0..23.5
        private fun place(endpoint: PlannerEndpoint): String = place(endpoint.name, endpoint.position)
        private fun place(label: String, position: GeoPoint): String {
            val name = label.replace("::", " ").filter { it >= ' ' }.trim().take(120).ifEmpty { "Hely" }
            return "$name::${coordinate(position.lat)},${coordinate(position.lon)}"
        }
    }
}
