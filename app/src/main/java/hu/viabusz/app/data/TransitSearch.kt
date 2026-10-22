package hu.viabusz.app.data

import java.text.Normalizer
import java.util.Locale

/** Normalize the national stop index once. Query work is token matching, not repeated accent folding. */
object TransitSearch {
    data class StopEntry(val stop: TransitStop, val text: String, val name: String)
    data class Query(val text: String, val terms: List<String>, val modes: Set<String>, val provider: String?)
    private val marks = Regex("\\p{M}+")
    fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(marks, "").lowercase(Locale.ROOT).trim()
    private val aliases = mapOf(
        "villamos" to setOf("TRAM"), "tram" to setOf("TRAM"),
        "troli" to setOf("TROLLEYBUS"), "trolibusz" to setOf("TROLLEYBUS"), "trolley" to setOf("TROLLEYBUS"), "trolleybus" to setOf("TROLLEYBUS"),
        "busz" to setOf("BUS", "COACH"), "autobusz" to setOf("BUS", "COACH"), "bus" to setOf("BUS", "COACH"), "coach" to setOf("BUS", "COACH"),
        "metro" to setOf("SUBWAY"), "subway" to setOf("SUBWAY"),
        "vonat" to setOf("RAIL", "SUBURBAN_RAILWAY", "TRAMTRAIN"), "train" to setOf("RAIL", "SUBURBAN_RAILWAY", "TRAMTRAIN"),
    )
    fun query(text: String): Query {
        val normalized = normalize(text).replace(Regex("\\b([a-z]?\\d+[a-z]?)-(?:es|as|os)\\b"), "$1")
        val tokens = normalized.split(Regex("\\s+")).filter(String::isNotBlank)
        val modes = tokens.flatMap { aliases[it].orEmpty() }.toSet()
        val provider = tokens.firstOrNull { it in setOf("volan", "bkk", "viabusz", "kanyargo", "homm") }
        return Query(normalized, tokens.filter { it !in aliases && it != provider }, modes, provider)
    }
    fun routes(routes: List<TransitRoute>, query: Query): List<TransitRoute> = routes.mapNotNull { route ->
        val mode = route.transportMode ?: if (route.providerId in setOf("volan", "viabusz", "kanyargo", "homm")) "BUS" else null
        if (query.modes.isNotEmpty() && mode !in query.modes || query.provider != null && query.provider != route.providerId) return@mapNotNull null
        val number = normalize(route.number)
        val text = normalize("${route.number} ${route.name} ${route.providerId}")
        val numeric = query.terms.firstOrNull { it.matches(Regex("[a-z]?\\d+[a-z]?")) }
        if (numeric != null && query.modes.isNotEmpty() && numeric != number) return@mapNotNull null
        if (query.terms.any { if (it == numeric) !number.startsWith(it) else !text.contains(it) }) return@mapNotNull null
        route to if (numeric == number) 100 else if (number == query.text) 100 else 0
    }.sortedWith(compareByDescending<Pair<TransitRoute, Int>> { it.second }.thenBy { it.first.number.length }.thenBy { it.first.number })
        .take(100).map { it.first }

    fun indexStops(stops: List<TransitStop>) = stops.map { StopEntry(it, normalize("${it.name} ${it.aliases.joinToString(" ")} ${it.platformCode.orEmpty()} ${it.sourceStopIds.joinToString(" ")}"), normalize(it.name)) }
    fun stops(index: List<StopEntry>, query: Query, location: GeoPoint?, favorites: Set<String>): List<TransitStop> {
        if (query.modes.isNotEmpty()) return emptyList()
        return index.asSequence().filter { entry ->
            (query.provider == null || query.provider in entry.stop.providerIds) && query.terms.all { entry.text.contains(it) }
        }.sortedWith(compareByDescending<StopEntry> { if (it.name == query.text) 2 else if (it.stop.id in favorites) 1 else 0 }
            .thenBy { entry -> location?.let { (entry.stop.position.lat - it.lat) * (entry.stop.position.lat - it.lat) + (entry.stop.position.lon - it.lon) * (entry.stop.position.lon - it.lon) } ?: 0.0 })
            .take(100).map { it.stop }.toList()
    }
}
