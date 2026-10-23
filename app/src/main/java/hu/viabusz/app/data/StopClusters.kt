package hu.viabusz.app.data

import java.text.Normalizer
import java.util.Locale
import kotlin.math.*

object StopClusters {
    const val MAX_PLATFORM_DISTANCE_METERS = 250.0
    const val MAX_CLUSTER_DIAMETER_METERS = 350.0

    private val marks = Regex("""\p{M}+""")
    private val parenRegex = Regex("""\([^)]*\)|\[[^\]]*\]""")
    private val punctRegex = Regex("""[,\.\-\–\/\\:;]+""")
    private val transitWordsRegex = Regex("""\b(m|metro|va|vasutallomas|pu|palyaudvar|autobusz-allomas|autobusz-palyaudvar|autobuszallomas|kocsiallas|vagany)\b""")
    private val adjectiveStemRegex = Regex("""\b([a-z]{3,})i\b""")
    private val cityPrefixRegex = Regex("""^(budapest|budaors|biatorbagy|paty|erd|solymar|szentendre|dunakeszi|godollo)\s+""")

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(marks, "")
            .lowercase(Locale.ROOT)
            .trim()

    fun cleanName(raw: String): String {
        var s = normalize(raw)
        s = s.replace(parenRegex, " ")
        s = s.replace(punctRegex, " ")
        s = s.replace(cityPrefixRegex, "")
        s = s.replace(transitWordsRegex, " ")
        s = s.replace(adjectiveStemRegex, "$1")
        s = s.replace(Regex("""\s+"""), " ").trim()
        return s.ifEmpty { normalize(raw).replace(punctRegex, " ").replace(Regex("""\s+"""), " ").trim() }
    }

    fun areNamesCloselyMatching(nameA: String, nameB: String): Boolean {
        val normA = normalize(nameA)
        val normB = normalize(nameB)
        if (normA == normB) return true

        val cleanA = cleanName(nameA)
        val cleanB = cleanName(nameB)
        if (cleanA.isNotEmpty() && cleanA == cleanB) return true

        val wordsA = cleanA.split(" ").filter { it.isNotBlank() }.toSet()
        val wordsB = cleanB.split(" ").filter { it.isNotBlank() }.toSet()
        if (wordsA.isNotEmpty() && wordsB.isNotEmpty()) {
            if (wordsA.containsAll(wordsB) || wordsB.containsAll(wordsA)) return true
        }

        if (cleanA.length >= 5 && cleanB.length >= 5) {
            if (cleanA.startsWith(cleanB) || cleanB.startsWith(cleanA)) return true
        }

        return false
    }

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat = Math.toRadians(b.lat - a.lat)
        val lon = Math.toRadians(b.lon - a.lon)
        val haversine = sin(lat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(lon / 2).pow(2)
        return 6_371_000 * 2 * atan2(sqrt(haversine.coerceIn(0.0, 1.0)), sqrt((1 - haversine).coerceIn(0.0, 1.0)))
    }

    fun clusterFor(stopId: String, allStops: List<TransitStop>): List<TransitStop> {
        val target = allStops.find { it.id == stopId } ?: return emptyList()
        return clusterFor(target, allStops)
    }

    fun clusterFor(target: TransitStop, allStops: List<TransitStop>): List<TransitStop> {
        val maxLatDelta = 0.004 // ~400m
        val maxLonDelta = 0.007 // ~400m
        val candidates = allStops.filter { other ->
            if (other.id == target.id) true
            else if (abs(other.position.lat - target.position.lat) > maxLatDelta ||
                abs(other.position.lon - target.position.lon) > maxLonDelta) false
            else {
                val sameParent = target.parentStationId != null && target.parentStationId == other.parentStationId
                val nameMatches = areNamesCloselyMatching(target.name, other.name)
                (sameParent || nameMatches) && distanceMeters(target.position, other.position) <= MAX_CLUSTER_DIAMETER_METERS
            }
        }
        return if (candidates.isEmpty()) listOf(target) else candidates
    }

    fun isMultiStop(stopId: String, allStops: List<TransitStop>): Boolean {
        val stop = allStops.find { it.id == stopId } ?: return false
        if (stop.isMultiStop) return true
        return clusterFor(stop, allStops).size > 1
    }

    fun stopDisplayName(name: String, isMultiStop: Boolean, multipleLabel: String): String {
        if (!isMultiStop) return name
        if (name.contains("(több megálló)", ignoreCase = true) ||
            name.contains("(multiple stations)", ignoreCase = true)) {
            return name
        }
        return "$name $multipleLabel"
    }

    fun consolidateForSearch(stops: List<TransitStop>): List<TransitStop> {
        if (stops.isEmpty()) return emptyList()

        val groups = stops.groupBy { cleanName(it.name) }
        val consolidated = mutableListOf<TransitStop>()

        for ((_, nameGroup) in groups) {
            if (nameGroup.size == 1) {
                consolidated.add(nameGroup[0])
                continue
            }
            val visited = BooleanArray(nameGroup.size)
            for (i in nameGroup.indices) {
                if (visited[i]) continue
                visited[i] = true
                val seed = nameGroup[i]
                val cluster = mutableListOf(seed)
                for (j in i + 1 until nameGroup.size) {
                    if (visited[j]) continue
                    val other = nameGroup[j]
                    val sameParent = seed.parentStationId != null && seed.parentStationId == other.parentStationId
                    val dist = distanceMeters(seed.position, other.position)
                    if (sameParent || dist <= MAX_PLATFORM_DISTANCE_METERS) {
                        visited[j] = true
                        cluster.add(other)
                    }
                }
                if (cluster.size == 1) {
                    consolidated.add(seed)
                } else {
                    consolidated.add(createConsolidatedStop(cluster))
                }
            }
        }
        return consolidated
    }

    private fun createConsolidatedStop(cluster: List<TransitStop>): TransitStop {
        val primary = cluster.minByOrNull {
            when {
                it.id.startsWith("homm:") || it.id.startsWith("viabusz:") || it.id.startsWith("kanyargo:") -> 0
                it.providerId in setOf("viabusz", "kanyargo", "homm") -> 1
                it.id.startsWith("emma:BKK:") -> 2
                else -> 3
            }
        } ?: cluster.first()

        val bestName = cluster.map { it.name }
            .filterNot { it.contains("(") || it.contains("[") }
            .minByOrNull { it.length } ?: primary.name

        val allRouteIds = cluster.flatMap { it.routeIds }.distinct()
        val allProviderIds = cluster.flatMap { it.providerIds }.distinct()
        val allSourceStopIds = cluster.flatMap { it.sourceStopIds }.distinct()
        val allAliases = cluster.flatMap { listOf(it.name) + it.aliases }.distinct().filterNot { it == bestName }

        return primary.copy(
            name = bestName,
            routeIds = allRouteIds,
            providerIds = allProviderIds,
            sourceStopIds = allSourceStopIds,
            aliases = allAliases,
            platformCode = null,
            station = true,
            isMultiStop = true,
        )
    }
}
