package hu.app.bmenetrend.data

import androidx.compose.runtime.Immutable
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

/** Normalize the national stop index once. Query work is token matching, not repeated accent folding. */
object TransitSearch {
    @Immutable
    data class StopEntry(
        val stop: TransitStop,
        val text: String,
        val name: String,
        val isMultiStop: Boolean = stop.isMultiStop,
        val words: List<String> = name.split(' ', ',', '-', '(', ')').filter(String::isNotBlank),
    )

    @Immutable
    data class Query(val text: String, val terms: List<String>, val modes: Set<String>, val provider: String?)

    @Immutable
    internal data class RouteEntry(
        val route: TransitRoute,
        val number: String,
        val normalizedNumber: String,
        val normalizedText: String,
        val mode: String?,
        val providerId: String,
    )

    @Immutable
    internal data class VehicleEntry(
        val vehicle: Vehicle,
        val mode: String,
        val providerId: String,
        val normalizedNumber: String,
        val normalizedText: String,
        val compactText: String,
        val cleanRegistration: String,
        /** The raw fields the normalized ones came from; equal source, reusable entry. */
        val source: String,
    )

    private val marks = Regex("""\p{M}+""")
    private val numberSuffixRegex = Regex("""\b([a-z]?\d+[a-z]?)-(?:es|as|os)\b""")
    private val whitespaceRegex = Regex("""\s+""")
    private val numericTermRegex = Regex("[a-z]?\\d+[a-z]?")
    private val nonAlphanumericRegex = Regex("[^a-z0-9]")

    fun normalize(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        var hasNonAscii = false
        for (i in 0 until trimmed.length) {
            if (trimmed[i].code >= 128) {
                hasNonAscii = true
                break
            }
        }
        if (!hasNonAscii) {
            return trimmed.lowercase(Locale.ROOT)
        }
        return Normalizer.normalize(trimmed, Normalizer.Form.NFD)
            .replace(marks, "")
            .lowercase(Locale.ROOT)
    }

    private val aliases = mapOf(
        "villamos" to setOf("TRAM"), "tram" to setOf("TRAM"),
        "troli" to setOf("TROLLEYBUS"), "trolibusz" to setOf("TROLLEYBUS"), "trolley" to setOf("TROLLEYBUS"), "trolleybus" to setOf("TROLLEYBUS"),
        "busz" to setOf("BUS", "COACH"), "autobusz" to setOf("BUS", "COACH"), "bus" to setOf("BUS", "COACH"), "coach" to setOf("BUS", "COACH"),
        "hev" to setOf("SUBURBAN_RAILWAY"), "suburban" to setOf("SUBURBAN_RAILWAY"),
        "metro" to setOf("SUBWAY"), "subway" to setOf("SUBWAY"),
        "vonat" to setOf("RAIL", "SUBURBAN_RAILWAY", "TRAMTRAIN"), "train" to setOf("RAIL", "SUBURBAN_RAILWAY", "TRAMTRAIN"),
    )

    fun query(text: String): Query {
        val normalized = normalize(text).replace(numberSuffixRegex, "$1")
        val tokens = normalized.split(whitespaceRegex).filter(String::isNotBlank)
        val modes = tokens.flatMap { aliases[it].orEmpty() }.toSet()
        val provider = tokens.firstOrNull { it in setOf("volan", "bkk", "viabusz", "kanyargo", "homm", "mav") }
        return Query(normalized, tokens.filter { it !in aliases && it != provider }, modes, provider)
    }

    private data class RouteIndex(val source: List<TransitRoute>, val entries: List<RouteEntry>)
    @Volatile private var cachedRoutes: RouteIndex? = null

    private fun getOrBuildRouteEntries(routes: List<TransitRoute>): List<RouteEntry> {
        val cached = cachedRoutes
        if (cached != null && routes === cached.source) return cached.entries
        val entries = reuseEntries(routes, cached?.entries.orEmpty(), { it.route }) { route ->
            val mode = route.transportMode ?: if (route.providerId in setOf("volan", "viabusz", "kanyargo", "homm")) "BUS" else null
            RouteEntry(
                route = route,
                number = route.number,
                normalizedNumber = normalize(route.number),
                normalizedText = normalize("${route.number} ${route.name} ${route.providerId}"),
                mode = mode,
                providerId = route.providerId,
            )
        }
        cachedRoutes = RouteIndex(routes, entries)
        return entries
    }

    private class ScoredRoute(
        val route: TransitRoute,
        val score: Int,
        val length: Int,
        val number: String,
    ) : Comparable<ScoredRoute> {
        override fun compareTo(other: ScoredRoute): Int {
            val s = other.score.compareTo(this.score)
            if (s != 0) return s
            val l = this.length.compareTo(other.length)
            if (l != 0) return l
            return this.number.compareTo(other.number)
        }
    }

    fun routes(routes: List<TransitRoute>, query: Query): List<TransitRoute> {
        val entries = getOrBuildRouteEntries(routes)
        val numeric = query.terms.firstOrNull { it.matches(numericTermRegex) }
        val queryText = query.text

        return entries.asSequence().mapNotNull { entry ->
            if (query.modes.isNotEmpty() && entry.mode !in query.modes) return@mapNotNull null
            if (query.provider != null && query.provider != entry.providerId) return@mapNotNull null
            if (numeric != null && query.modes.isNotEmpty() && numeric != entry.normalizedNumber) return@mapNotNull null

            for (term in query.terms) {
                if (term == numeric) {
                    if (!entry.normalizedNumber.startsWith(term)) return@mapNotNull null
                } else {
                    if (!entry.normalizedText.contains(term)) return@mapNotNull null
                }
            }
            val score = when {
                numeric == entry.normalizedNumber -> 100
                entry.normalizedNumber == queryText -> 100
                else -> 0
            }
            ScoredRoute(entry.route, score, entry.number.length, entry.number)
        }.sorted()
            .take(100)
            .map { it.route }
            .toList()
    }

    private data class StopIndex(
        val local: List<TransitStop>, val national: List<TransitStop>, val entries: List<StopEntry>,
    )
    @Volatile private var cachedStops: StopIndex? = null

    fun getCachedStopIndex(stops: List<TransitStop>, national: List<TransitStop> = emptyList()): List<StopEntry>? =
        cachedStops?.takeIf { it.local === stops && it.national === national }?.entries

    private val stopIndexLock = Any()

    /** Single-flight: concurrent callers for the same catalogues share one build. */
    fun indexStops(stops: List<TransitStop>, national: List<TransitStop> = emptyList()): List<StopEntry> {
        getCachedStopIndex(stops, national)?.let { return it }
        return synchronized(stopIndexLock) { getCachedStopIndex(stops, national) ?: buildStopIndex(stops, national) }
    }

    private fun buildStopIndex(stops: List<TransitStop>, national: List<TransitStop>): List<StopEntry> {
        val combined = if (national.isEmpty()) stops else if (stops.isEmpty()) national else stops + national
        val consolidated = StopClusters.consolidateForSearch(combined)
        val indexed = reuseEntries(consolidated, cachedStops?.entries.orEmpty(), { it.stop }, ::stopEntry)
        cachedStops = StopIndex(stops, national, indexed)
        return indexed
    }

    private fun stopEntry(stop: TransitStop): StopEntry {
        val multiTokens = if (stop.isMultiStop) "tobbmegallo tobb megallo multiple stations multiplestations" else ""
        val platform = stop.platformCode ?: BkkPlatformIndex.resolve(stop.id, null).orEmpty()
        return StopEntry(
            stop = stop,
            text = normalize("${stop.name} ${stop.aliases.joinToString(" ")} $platform ${stop.sourceStopIds.joinToString(" ")} $multiTokens"),
            name = normalize(stop.name),
            isMultiStop = stop.isMultiStop,
        )
    }

    /**
     * Catalogue merges (a search result, a loaded line) replace the list but keep almost every item, so
     * the previous generation's entries are reused: by identity first, then by value for items rebuilt
     * equal (clustered stops are new objects on every pass). Only new or changed items are normalized.
     */
    private inline fun <T : Any, E> reuseEntries(items: List<T>, previous: List<E>, key: (E) -> T,
                                                 build: (T) -> E): List<E> {
        if (previous.isEmpty()) return items.map { build(it) }
        val byIdentity = java.util.IdentityHashMap<T, E>(previous.size)
        for (entry in previous) byIdentity[key(entry)] = entry
        val result = ArrayList<E?>(items.size)
        var misses = 0
        for (item in items) {
            val reused = byIdentity.remove(item)
            if (reused == null) misses++
            result.add(reused)
        }
        if (misses > 0) {
            // Only the previous entries nobody claimed by identity are worth hashing by value.
            val byValue = HashMap<T, E>(byIdentity.size)
            for ((item, entry) in byIdentity) byValue[item] = entry
            for (i in items.indices) if (result[i] == null) result[i] = byValue.remove(items[i]) ?: build(items[i])
        }
        @Suppress("UNCHECKED_CAST")
        return result as List<E>
    }

    private data class VehicleIndex(val source: List<Vehicle>, val entries: List<VehicleEntry>)
    @Volatile private var cachedVehicles: VehicleIndex? = null

    private fun getOrBuildVehicleEntries(vehicles: List<Vehicle>): List<VehicleEntry> {
        val cached = cachedVehicles
        if (cached != null && vehicles === cached.source) return cached.entries
        // Every live tick is a new list of moved vehicles whose names rarely change: a vehicle whose
        // text fields are unchanged keeps its normalized text instead of a regex pass per keystroke.
        val previous = cached?.entries?.let { old -> HashMap<String, VehicleEntry>(old.size).apply { for (e in old) put(e.vehicle.id, e) } }
        val entries = vehicles.map { vehicle ->
            val source = vehicleSource(vehicle)
            val reusable = previous?.get(vehicle.id)?.takeIf { it.source == source }
            if (reusable != null) return@map if (reusable.vehicle === vehicle) reusable else reusable.copy(vehicle = vehicle)
            val text = normalize(listOfNotNull(vehicle.registration, vehicle.trainNumber, vehicle.serviceName, vehicle.id, vehicle.displayRouteNumber, vehicle.routeName, vehicle.destination, vehicle.providerId).joinToString(" "))
            VehicleEntry(
                vehicle = vehicle,
                mode = vehicle.transportMode ?: "BUS",
                providerId = vehicle.providerId,
                normalizedNumber = normalize(vehicle.displayRouteNumber.orEmpty()),
                normalizedText = text,
                compactText = text.replace(nonAlphanumericRegex, ""),
                cleanRegistration = normalize(vehicle.registration.orEmpty()).replace("-", ""),
                source = source,
            )
        }
        cachedVehicles = VehicleIndex(vehicles, entries)
        return entries
    }

    private fun vehicleSource(vehicle: Vehicle): String = buildString {
        for (part in arrayOf(vehicle.registration, vehicle.trainNumber, vehicle.serviceName, vehicle.displayRouteNumber,
            vehicle.routeName, vehicle.destination, vehicle.providerId, vehicle.transportMode)) {
            append(part.orEmpty()); append('\u0000')
        }
    }

    private class ScoredVehicle(
        val vehicle: Vehicle,
        val score: Int,
        val observedAt: Instant,
    ) : Comparable<ScoredVehicle> {
        override fun compareTo(other: ScoredVehicle): Int {
            val s = other.score.compareTo(this.score)
            if (s != 0) return s
            return other.observedAt.compareTo(this.observedAt)
        }
    }

    fun vehicles(vehicles: List<Vehicle>, query: Query): List<Vehicle> {
        val entries = getOrBuildVehicleEntries(vehicles)
        val numeric = query.terms.firstOrNull { it.matches(numericTermRegex) }
        val cleanQueryText = query.text.replace("-", "")
        val termCompacts = query.terms.map { it to it.replace(nonAlphanumericRegex, "") }

        return entries.asSequence().mapNotNull { entry ->
            if (query.modes.isNotEmpty() && entry.mode !in query.modes) return@mapNotNull null
            if (query.provider != null && query.provider != entry.providerId) return@mapNotNull null
            if (numeric != null && query.modes.isNotEmpty() && numeric != entry.normalizedNumber && numeric != entry.vehicle.trainNumber) return@mapNotNull null

            for ((term, compactTerm) in termCompacts) {
                if (!entry.normalizedText.contains(term) && !entry.compactText.contains(compactTerm)) {
                    return@mapNotNull null
                }
            }
            val score = when {
                entry.cleanRegistration == cleanQueryText -> 100
                entry.normalizedNumber == query.text -> 50
                else -> 0
            }
            ScoredVehicle(entry.vehicle, score, entry.vehicle.observedAt)
        }.sorted()
            .take(100)
            .map { it.vehicle }
            .toList()
    }

    /** [order] is the index position: equal rank and distance keep catalogue order (a stable sort). */
    private class ScoredStop(val stop: TransitStop, val rank: Int, val distSq: Double, val order: Int)

    private val bestStopFirst = Comparator<ScoredStop> { a, b ->
        val r = b.rank.compareTo(a.rank)
        if (r != 0) return@Comparator r
        val d = a.distSq.compareTo(b.distSq)
        if (d != 0) d else a.order.compareTo(b.order)
    }
    private val worstStopFirst = bestStopFirst.reversed()
    private const val STOP_LIMIT = 100

    /** [checkpoint] runs every few hundred entries so a superseded keystroke can stop the scan early. */
    fun stops(index: List<StopEntry>, query: Query, location: GeoPoint?, favorites: Set<String>,
              checkpoint: () -> Unit = {}): List<TransitStop> {
        if (query.modes.isNotEmpty()) return emptyList()
        val hasLocation = location != null
        val locLat = location?.lat ?: 0.0
        val locLon = location?.lon ?: 0.0
        // Only the best STOP_LIMIT are kept: a one-letter query matches most of the country, and an
        // object per match plus a full sort cost more than the scan itself on low-end phones.
        val best = java.util.PriorityQueue(STOP_LIMIT + 1, worstStopFirst)
        for (i in index.indices) {
            if (i and 511 == 0) checkpoint()
            val entry = index[i]
            if (query.provider != null && query.provider !in entry.stop.providerIds) continue
            var matches = true
            for (term in query.terms) if (!entry.text.contains(term)) { matches = false; break }
            if (!matches) continue
            val rank = when {
                entry.name == query.text -> 2
                entry.stop.id in favorites -> 1
                else -> 0
            }
            val distSq = if (hasLocation) {
                val dLat = entry.stop.position.lat - locLat
                val dLon = entry.stop.position.lon - locLon
                dLat * dLat + dLon * dLon
            } else 0.0
            if (best.size == STOP_LIMIT) {
                // Later entries lose ties, so only a strictly better rank or distance gets in.
                val worst = best.peek()!!
                if (rank < worst.rank || rank == worst.rank && distSq >= worst.distSq) continue
                best.poll()
            }
            best.add(ScoredStop(entry.stop, rank, distSq, i))
        }
        return best.sortedWith(bestStopFirst).map { it.stop }
    }
}
