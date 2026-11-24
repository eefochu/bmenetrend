package hu.app.bmenetrend.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * Keeps routed street walks across searches and app restarts. Transfers between the fixed stops of
 * the local networks are the same walks every time, yet each search used to route hundreds of them
 * again before ViaBusz/KanyarGO/HOMM journeys could be combined. Only walks the services actually
 * routed are stored, so a cached link is exactly what a fresh request returned; journeys are not cached.
 */
internal class PersistentWalkingBackend(
    private val delegate: PlannerBackend,
    private val store: WalkingLinkStore,
) : PlannerBackend by delegate {
    override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
        val known = store.find(pairs.map { it.first.position to it.second.position })
        val missing = pairs.filter { (a, b) -> (a.position to b.position) !in known }
        val fetched = if (missing.isEmpty()) WalkingResult(emptyMap()) else delegate.walking(missing, ready)
        store.save(fetched.links.values)
        val links = pairs.mapNotNull { (a, b) ->
            val key = a.position to b.position
            (fetched.links[key] ?: known[key]?.let { WalkingLink(a, b, it.seconds, it.meters) })?.let { key to it }
        }.toMap()
        return WalkingResult(links, fetched.partial)
    }
}

/** A small, bounded file of routed walks; entries expire so changed streets are routed again. */
internal class WalkingLinkStore(
    private val file: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val today: () -> LocalDate = LocalDate::now,
) {
    data class Walk(val seconds: Long, val meters: Double, val savedOn: LocalDate)

    private val mutex = Mutex()
    private var walks: LinkedHashMap<WalkKey, Walk>? = null

    suspend fun find(keys: List<WalkKey>): Map<WalkKey, Walk> = mutex.withLock {
        val loaded = loaded()
        val oldest = today().minusDays(MAX_AGE_DAYS)
        keys.mapNotNull { key -> loaded[key.rounded()]?.takeIf { it.savedOn >= oldest }?.let { key to it } }.toMap()
    }

    suspend fun save(links: Collection<WalkingLink>) {
        if (links.isEmpty()) return
        mutex.withLock {
            val loaded = loaded()
            val day = today()
            links.forEach { loaded[(it.from.position to it.to.position).rounded()] = Walk(it.seconds, it.meters, day) }
            while (loaded.size > MAX_ENTRIES) loaded.remove(loaded.keys.first())
            withContext(io) {
                runCatching {
                    val temporary = File(file.path + ".tmp")
                    temporary.writeText(loaded.entries.joinToString("\n") { (key, walk) -> encode(key, walk) })
                    if (!temporary.renameTo(file)) { file.delete(); temporary.renameTo(file) }
                }
            }
        }
    }

    private suspend fun loaded(): LinkedHashMap<WalkKey, Walk> = walks ?: withContext(io) {
        // Access order: the walks searches keep using survive the size cap.
        LinkedHashMap<WalkKey, Walk>(256, .75f, true).also { map ->
            runCatching { if (file.isFile) file.forEachLine { line -> decode(line)?.let { (key, walk) -> map[key] = walk } } }
        }
    }.also { walks = it }

    companion object {
        const val MAX_ENTRIES = 4_096
        const val MAX_AGE_DAYS = 60L

        /** Six decimals (~0.1 m): the same stop always maps to the same entry. */
        private fun WalkKey.rounded(): WalkKey = first.rounded() to second.rounded()
        private fun GeoPoint.rounded() = GeoPoint(Math.round(lat * 1e6) / 1e6, Math.round(lon * 1e6) / 1e6)

        private fun encode(key: WalkKey, walk: Walk) = String.format(Locale.ROOT, "%.6f\t%.6f\t%.6f\t%.6f\t%d\t%.1f\t%s",
            key.first.lat, key.first.lon, key.second.lat, key.second.lon, walk.seconds, walk.meters, walk.savedOn)

        private fun decode(line: String): Pair<WalkKey, Walk>? {
            val parts = line.split('\t')
            if (parts.size != 7) return null
            val numbers = parts.take(4).map { it.toDoubleOrNull() ?: return null }
            val seconds = parts[4].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val meters = parts[5].toDoubleOrNull()?.takeIf { it >= 0 } ?: return null
            val savedOn = runCatching { LocalDate.parse(parts[6]) }.getOrNull() ?: return null
            return (GeoPoint(numbers[0], numbers[1]) to GeoPoint(numbers[2], numbers[3])) to Walk(seconds, meters, savedOn)
        }
    }
}
