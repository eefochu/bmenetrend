package hu.app.bmenetrend.data

/** A separate suggestion layer: literal results are never replaced or silently rewritten. */
object StationRecommendations {
    private val hubs = listOf("keleti", "kelenfold", "nyugati", "deli")
    private val shorthand = mapOf("kelen" to "kelenfold", "kelenfoldi" to "kelenfold", "kelet" to "keleti")
    private val stationWords = setOf("palyaudvar", "vasutallomas", "allomas", "pu", "pu.")

    fun stops(index: List<TransitSearch.StopEntry>, query: TransitSearch.Query, location: GeoPoint?, favorites: Set<String>): List<TransitStop> {
        if (query.text.length < 3 || query.terms.isEmpty() || query.modes.isNotEmpty()) return emptyList()
        val terms = query.terms.map { term ->
            shorthand[term] ?: if (term.startsWith("palyaudvar")) "palyaudvar" else term
        }
        val generic = terms.all { it in stationWords }
        return index.asSequence().mapNotNull { entry ->
            if (query.provider != null && query.provider !in entry.stop.providerIds) return@mapNotNull null
            val hub = hubs.firstOrNull { key -> entry.words.any { it.startsWith(key) } }
            val rail = entry.stop.transportMode == "RAIL" || "mav" in entry.stop.providerIds
            var quality = 0
            for (term in terms) {
                quality += when {
                    term in stationWords && (hub != null || rail || entry.words.any { it in stationWords }) -> 20
                    entry.words.any { it == term } -> 50
                    entry.words.any { it.startsWith(term) } -> 40
                    entry.text.contains(term) -> 30
                    term.length >= 4 && entry.words.any { nearWord(term, it) } -> 10
                    else -> return@mapNotNull null
                }
            }
            val score = quality + (if (rail) 18 else 0) + (if (entry.stop.station || entry.isMultiStop) 12 else 0) +
                (if (entry.stop.id in favorites) 5 else 0) + (if (generic && hub != null) 120 - hubs.indexOf(hub) * 40 else 0)
            val distance = location?.let { loc ->
                val lat = entry.stop.position.lat - loc.lat
                val lon = entry.stop.position.lon - loc.lon
                lat * lat + lon * lon
            } ?: 0.0
            Candidate(entry.stop, hub ?: entry.name, score, distance)
        }.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.distance })
            .distinctBy { it.group }.take(3).map { it.stop }.toList()
    }

    private data class Candidate(val stop: TransitStop, val group: String, val score: Int, val distance: Double)

    /** One insertion, deletion, substitution or adjacent transposition; no edit-distance matrix. */
    private fun nearWord(a: String, b: String): Boolean {
        if (b.length < 4 || a.firstOrNull() != b.firstOrNull() || kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                b.length > a.length -> j++
                i + 1 < a.length && a[i] == b[j + 1] && a[i + 1] == b[j] -> { i += 2; j += 2 }
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}
