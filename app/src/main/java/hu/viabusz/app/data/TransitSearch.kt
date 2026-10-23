package hu.viabusz.app.data

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

    @Volatile
    private var lastRoutesRef: List<TransitRoute>? = null
    @Volatile
    private var cachedRouteEntries: List<RouteEntry>? = null

    private fun getOrBuildRouteEntries(routes: List<TransitRoute>): List<RouteEntry> {
        val cached = cachedRouteEntries
        if (cached != null && routes === lastRoutesRef) return cached
        val entries = routes.map { route ->
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
        lastRoutesRef = routes
        cachedRouteEntries = entries
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

    @Volatile
    private var lastStopsRef: List<TransitStop>? = null
    @Volatile
    private var cachedStopIndex: List<StopEntry>? = null

    fun getCachedStopIndex(stops: List<TransitStop>): List<StopEntry>? {
        val cached = cachedStopIndex
        return if (cached != null && stops === lastStopsRef) cached else null
    }

    fun indexStops(stops: List<TransitStop>): List<StopEntry> {
        val cached = cachedStopIndex
        if (cached != null && stops === lastStopsRef) {
            return cached
        }
        val consolidated = StopClusters.consolidateForSearch(stops)
        val indexed = consolidated.map { stop ->
            val multiTokens = if (stop.isMultiStop) "tobbmegallo tobb megallo multiple stations multiplestations" else ""
            StopEntry(
                stop = stop,
                text = normalize("${stop.name} ${stop.aliases.joinToString(" ")} ${stop.platformCode.orEmpty()} ${stop.sourceStopIds.joinToString(" ")} $multiTokens"),
                name = normalize(stop.name),
                isMultiStop = stop.isMultiStop,
            )
        }
        lastStopsRef = stops
        cachedStopIndex = indexed
        return indexed
    }

    @Volatile
    private var lastVehiclesRef: List<Vehicle>? = null
    @Volatile
    private var cachedVehicleEntries: List<VehicleEntry>? = null

    private fun getOrBuildVehicleEntries(vehicles: List<Vehicle>): List<VehicleEntry> {
        val cached = cachedVehicleEntries
        if (cached != null && vehicles === lastVehiclesRef) return cached
        val entries = vehicles.map { vehicle ->
            val mode = vehicle.transportMode ?: "BUS"
            val text = normalize(listOfNotNull(vehicle.registration, vehicle.trainNumber, vehicle.serviceName, vehicle.id, vehicle.routeNumber, vehicle.routeName, vehicle.destination, vehicle.providerId).joinToString(" "))
            VehicleEntry(
                vehicle = vehicle,
                mode = mode,
                providerId = vehicle.providerId,
                normalizedNumber = normalize(vehicle.routeNumber.orEmpty()),
                normalizedText = text,
                compactText = text.replace(nonAlphanumericRegex, ""),
                cleanRegistration = normalize(vehicle.registration.orEmpty()).replace("-", ""),
            )
        }
        lastVehiclesRef = vehicles
        cachedVehicleEntries = entries
        return entries
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

    private class ScoredStop(
        val stop: TransitStop,
        val rank: Int,
        val distSq: Double,
    ) : Comparable<ScoredStop> {
        override fun compareTo(other: ScoredStop): Int {
            val r = other.rank.compareTo(this.rank)
            if (r != 0) return r
            return this.distSq.compareTo(other.distSq)
        }
    }

    fun stops(index: List<StopEntry>, query: Query, location: GeoPoint?, favorites: Set<String>): List<TransitStop> {
        if (query.modes.isNotEmpty()) return emptyList()
        val hasLocation = location != null
        val locLat = location?.lat ?: 0.0
        val locLon = location?.lon ?: 0.0

        return index.asSequence().mapNotNull { entry ->
            if (query.provider != null && query.provider !in entry.stop.providerIds) return@mapNotNull null
            for (term in query.terms) {
                if (!entry.text.contains(term)) return@mapNotNull null
            }
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
            ScoredStop(entry.stop, rank, distSq)
        }.sorted()
            .take(100)
            .map { it.stop }
            .toList()
    }
}
