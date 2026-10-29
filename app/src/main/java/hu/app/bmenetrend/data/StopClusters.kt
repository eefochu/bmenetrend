package hu.app.bmenetrend.data

import java.text.Normalizer
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object StopClusters {
    const val MAX_PLATFORM_DISTANCE_METERS = 250.0
    const val MAX_CLUSTER_DIAMETER_METERS = 350.0
    const val MAX_HUB_DISTANCE_METERS = 700.0

    private val marks = Regex("""\p{M}+""")
    private val parenRegex = Regex("""\([^)]*\)|\[[^\]]*\]""")
    private val punctRegex = Regex("""[,\.\-\u2013\/\:;]+""")
    private val transitWordsRegex = Regex("""\b(m|metro|va|vasutallomas|pu|palyaudvar|autobusz-allomas|autobusz-palyaudvar|autobuszallomas|kocsiallas|vagany|vb|volanbusz)\b""")
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

    fun isCleanRouteNumber(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.length !in 1..5) return false
        if (trimmed.contains('_') || trimmed.contains(':') || trimmed.contains('/') || trimmed.contains('\\')) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        if (lower.startsWith("hkir") || lower.startsWith("emma") || lower.startsWith("bkk") || lower.startsWith("mav") || lower.startsWith("volan")) return false
        if (lower.startsWith("v29") || lower.startsWith("v28") || lower.startsWith("v-")) return false
        return trimmed.matches(Regex("^[A-Za-z0-9]{1,5}$"))
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
            val shorter = if (cleanA.length <= cleanB.length) cleanA else cleanB
            val longer = if (cleanA.length <= cleanB.length) cleanB else cleanA
            if (longer.contains(shorter)) return true
        }

        return false
    }

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(h))
    }

    fun clusterFor(stopId: String, allStops: List<TransitStop>): List<TransitStop> {
        val target = allStops.find { it.id == stopId } ?: return emptyList()
        return clusterFor(target, allStops)
    }

    fun clusterFor(target: TransitStop, allStops: List<TransitStop>): List<TransitStop> {
        val cleanTargetName = cleanName(target.name)
        return allStops.filter { other ->
            if (other.id == target.id) return@filter true

            val sameParent = target.parentStationId != null && target.parentStationId == other.parentStationId
            if (sameParent) return@filter true

            val cleanOtherName = cleanName(other.name)
            val nameMatches = cleanTargetName == cleanOtherName || areNamesCloselyMatching(target.name, other.name)
            if (!nameMatches) return@filter false

            val dist = distanceMeters(target.position, other.position)
            dist <= MAX_HUB_DISTANCE_METERS
        }
    }

    fun isMultiStop(stopId: String, allStops: List<TransitStop>): Boolean =
        clusterFor(stopId, allStops).size > 1

    fun stopDisplayName(name: String): String = name
        .replace(Regex("""\s*\((?:több megálló|multiple stations)\)""", RegexOption.IGNORE_CASE), "").trim()

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
                it.transportMode == "RAIL" || it.providerId == "mav" -> 0
                it.id.startsWith("homm:") || it.id.startsWith("viabusz:") || it.id.startsWith("kanyargo:") -> 1
                it.providerId in setOf("viabusz", "kanyargo", "homm") -> 2
                it.id.startsWith("emma:BKK:") -> 3
                else -> 4
            }
        } ?: cluster.first()

        val bestName = cluster.map { it.name }
            .filterNot { it.contains("(") || it.contains("[") }
            .minByOrNull { it.length } ?: primary.name

        val allRouteIds = cluster.flatMap { it.routeIds }.distinct()
        val allProviderIds = cluster.flatMap { it.providerIds }.distinct()
        val allSourceStopIds = cluster.flatMap { it.sourceStopIds }.distinct()
        val allAliases = cluster.flatMap { listOf(it.name) + it.aliases }.distinct().filterNot { it == bestName }
        val isRail = cluster.any { it.transportMode == "RAIL" || it.providerId == "mav" }

        return primary.copy(
            name = bestName,
            routeIds = allRouteIds,
            providerIds = allProviderIds,
            sourceStopIds = allSourceStopIds,
            aliases = allAliases,
            platformCode = null,
            station = true,
            isMultiStop = true,
            transportMode = if (isRail) "RAIL" else primary.transportMode
        )
    }
}
