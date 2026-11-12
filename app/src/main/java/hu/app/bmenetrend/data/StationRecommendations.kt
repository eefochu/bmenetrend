package hu.app.bmenetrend.data

/** A separate suggestion layer: literal results are never replaced or silently rewritten. */
object StationRecommendations {
    private val hubs = listOf("keleti", "kelenfold", "nyugati", "deli")
    private val shorthand = mapOf("kelen" to "kelenfold", "kelenfoldi" to "kelenfold", "kelet" to "keleti")
    private val stationWords = setOf("palyaudvar", "vasutallomas", "allomas", "pu", "pu.")

    /**
     * [checkpoint] is called every few hundred entries so a superseded keystroke can stop early
     * (pass `{ coroutineContext.ensureActive() }`); the scan itself never suspends.
     */
    fun stops(index: List<TransitSearch.StopEntry>, query: TransitSearch.Query, location: GeoPoint?, favorites: Set<String>,
              checkpoint: () -> Unit = {}): List<TransitStop> {
        if (query.text.length < 3 || query.terms.isEmpty() || query.modes.isNotEmpty()) return emptyList()
        val terms = query.terms.map { term ->
            shorthand[term] ?: if (term.startsWith("palyaudvar")) "palyaudvar" else term
        }
        val generic = terms.all { it in stationWords }
        // Best candidate per group, so nothing has to sort every match in the country.
        val best = HashMap<String, Candidate>()
        for ((i, entry) in index.withIndex()) {
            if (i and 511 == 0) checkpoint()
            if (query.provider != null && query.provider !in entry.stop.providerIds) continue
            val rail = entry.stop.transportMode == "RAIL" || "mav" in entry.stop.providerIds
            // Only entries that survive the term filter pay for the hub lookup.
            var hubKnown = false
            var hub: String? = null
            fun hub(): String? {
                if (!hubKnown) { hub = hubs.firstOrNull { key -> entry.words.any { it.startsWith(key) } }; hubKnown = true }
                return hub
            }
            var quality = 0
            var rejected = false
            for (term in terms) {
                val inText = entry.text.contains(term)
                quality += when {
                    term in stationWords && (rail || hub() != null || entry.words.any { it in stationWords }) -> 20
                    // Exact and prefix word matches are always substrings of the text, so most entries stop here.
                    inText && entry.words.any { it == term } -> 50
                    inText && entry.words.any { it.startsWith(term) } -> 40
                    inText -> 30
                    term.length >= 4 && entry.words.any { nearWord(term, it) } -> 10
                    else -> { rejected = true; break }
                }
            }
            if (rejected) continue
            val group = hub()
            val score = quality + (if (rail) 18 else 0) + (if (entry.stop.station || entry.isMultiStop) 12 else 0) +
                (if (entry.stop.id in favorites) 5 else 0) + (if (generic && group != null) 120 - hubs.indexOf(group) * 40 else 0)
            val distance = location?.let { loc ->
                val lat = entry.stop.position.lat - loc.lat
                val lon = entry.stop.position.lon - loc.lon
                lat * lat + lon * lon
            } ?: 0.0
            val candidate = Candidate(entry.stop, group ?: entry.name, score, distance)
            val previous = best[candidate.group]
            if (previous == null || order.compare(candidate, previous) < 0) best[candidate.group] = candidate
        }
        return best.values.sortedWith(order).take(3).map { it.stop }
    }

    private val order = compareByDescending<Candidate> { it.score }.thenBy { it.distance }

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
