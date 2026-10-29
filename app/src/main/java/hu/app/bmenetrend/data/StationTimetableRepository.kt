package hu.app.bmenetrend.data

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Immutable
data class OfflineStationMetadata(
    val stationId: String,
    val stationName: String,
    val platformIds: Set<String>,
    val downloadedAt: Instant,
    val departureCount: Int,
    val fileSizeBytes: Long,
)

data class OfflineBoardResult(
    val departures: List<Departure>,
    val routes: List<TransitRoute>,
    val downloadedAt: Instant,
    val stationName: String,
)

internal data class StationTimetable(
    val stationId: String,
    val stationName: String,
    val platformIds: List<String>,
    val downloadedAt: Instant,
    val routes: List<TransitRoute>,
    val departures: List<Departure>,
)

/**
 * Manages lightweight, compressed offline timetable storage and downloads for stations.
 * Each station timetable contains all platforms of the station for a 24-hour horizon,
 * compressed using GZIP and deduplicated string pools (typically 1.5 - 5 KB per station).
 */
class StationTimetableRepository(
    private val context: Context? = null,
    private val graphql: EmmaGraphqlClient = EmmaGraphqlClient(),
    baseDirectory: File? = null,
) {
    private val timetableDirectory = baseDirectory
        ?: File(context?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp"), "station-timetables-v1")
    private val indexFile = File(timetableDirectory, "index.json")
    private val memoryCache = LinkedHashMap<String, StationTimetable>(8, 0.75f, true)
    private val stationMap = ConcurrentHashMap<String, OfflineStationMetadata>()
    private val stopToStationMap = ConcurrentHashMap<String, String>()
    private val mutex = Mutex()

    private var initialized = false

    /** Constructor is cheap; all persisted index and compressed timetable reads are main-safe. */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!initialized) {
                loadIndex()
                initialized = true
            }
        }
    }

    companion object {
        private val budapestZone: ZoneId = ZoneId.of("Europe/Budapest")

        fun safeKey(id: String): String =
            id.replace(':', '_').replace('/', '_').replace('\\', '_')

        fun formatFileSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.US, bytes / 1024.0)
            else -> "%.1f MB".format(java.util.Locale.US, bytes / (1024.0 * 1024.0))
        }
    }

    fun downloadedStopIds(): Set<String> = stopToStationMap.keys.toSet()

    fun allMetadata(): Map<String, OfflineStationMetadata> = stationMap.toMap()

    fun metadataForStop(stopId: String): OfflineStationMetadata? {
        val stationId = stopToStationMap[stopId] ?: stopId
        return stationMap[stationId]
    }

    fun isDownloaded(stopId: String): Boolean = stopToStationMap.containsKey(stopId)

    fun isStationDownloaded(stopId: String): Boolean = isDownloaded(stopId)

    fun getMetadata(stopOrStationId: String): OfflineStationMetadata? = metadataForStop(stopOrStationId)

    fun getAllDownloadedStationMetadata(): List<OfflineStationMetadata> = stationMap.values.toList()

    fun totalStorageBytes(): Long = stationMap.values.sumOf { it.fileSizeBytes }

    internal suspend fun loadOfflineData(stationId: String): StationTimetable? = withContext(Dispatchers.IO) {
        initialize()
        mutex.withLock { getOrLoadTimetable(stationId) }
    }

    private fun stationFile(stationId: String): File =
        File(timetableDirectory, "${safeKey(stationId)}.tt.gz")

    private fun loadIndex() {
        if (!indexFile.isFile) return
        runCatching {
            val text = indexFile.readText(Charsets.UTF_8)
            val root = JSONObject(text)
            val stations = root.optJSONArray("stations") ?: return@runCatching
            for (i in 0 until stations.length()) {
                val obj = stations.getJSONObject(i)
                val stationId = obj.getString("id")
                val stationName = obj.getString("name")
                val downloadedAt = Instant.ofEpochMilli(obj.getLong("downloadedAt"))
                val departureCount = obj.optInt("departureCount", 0)
                val fileSizeBytes = obj.optLong("size", 0L)
                val platformArray = obj.optJSONArray("platforms")
                val platformIds = mutableSetOf<String>()
                if (platformArray != null) {
                    for (j in 0 until platformArray.length()) {
                        platformIds.add(platformArray.getString(j))
                    }
                }
                platformIds.add(stationId)
                val meta = OfflineStationMetadata(
                    stationId = stationId,
                    stationName = stationName,
                    platformIds = platformIds,
                    downloadedAt = downloadedAt,
                    departureCount = departureCount,
                    fileSizeBytes = fileSizeBytes,
                )
                stationMap[stationId] = meta
                platformIds.forEach { stopToStationMap[it] = stationId }
            }
        }
    }

    private fun saveIndex() {
        timetableDirectory.mkdirs()
        runCatching {
            val root = JSONObject()
            val array = JSONArray()
            stationMap.values.forEach { meta ->
                val obj = JSONObject()
                obj.put("id", meta.stationId)
                obj.put("name", meta.stationName)
                obj.put("downloadedAt", meta.downloadedAt.toEpochMilli())
                obj.put("departureCount", meta.departureCount)
                obj.put("size", meta.fileSizeBytes)
                obj.put("platforms", JSONArray(meta.platformIds))
                array.put(obj)
            }
            root.put("stations", array)
            val temp = File(timetableDirectory, "index.tmp")
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (!temp.renameTo(indexFile)) {
                temp.copyTo(indexFile, overwrite = true)
                temp.delete()
            }
        }
    }

    internal suspend fun saveStationTimetableDirect(
        stationId: String,
        stationName: String,
        platformIds: List<String>,
        routes: List<TransitRoute>,
        departures: List<Departure>,
        now: Instant = Instant.now(),
    ): OfflineStationMetadata = withContext(Dispatchers.IO) {
        initialize()
        val allPlatforms = (platformIds + stationId).toSet()
        val timetable = StationTimetable(
            stationId = stationId,
            stationName = stationName,
            platformIds = allPlatforms.toList(),
            downloadedAt = now,
            routes = routes,
            departures = departures,
        )

        val file = stationFile(stationId)
        val sizeBytes = compressAndSave(timetable, file)

        val meta = OfflineStationMetadata(
            stationId = stationId,
            stationName = stationName,
            platformIds = allPlatforms,
            downloadedAt = now,
            departureCount = departures.size,
            fileSizeBytes = sizeBytes,
        )

        mutex.withLock {
            stationMap[stationId] = meta
            allPlatforms.forEach { stopToStationMap[it] = stationId }
            memoryCache[stationId] = timetable
            while (memoryCache.size > 8) {
                val oldest = memoryCache.keys.first()
                memoryCache.remove(oldest)
            }
            saveIndex()
        }

        meta
    }

    suspend fun downloadStationTimetable(
        station: TransitStop,
        platforms: List<TransitStop>,
        network: TransitNetwork?,
        now: Instant = Instant.now(),
    ): OfflineStationMetadata = withContext(Dispatchers.IO) {
        initialize()
        val allPlatforms = platforms.ifEmpty { listOf(station) }
        val stationId = station.parentStationId ?: allPlatforms.minByOrNull { it.id }?.id ?: station.id
        val stationName = StopClusters.stopDisplayName(station.name)

        val emmaPlatforms = allPlatforms.filter { it.id.startsWith("emma:") }
        val localPlatforms = allPlatforms.filterNot { it.id.startsWith("emma:") }

        val semaphore = Semaphore(3)
        val emmaResults = coroutineScope {
            emmaPlatforms.map { p ->
                async {
                    semaphore.acquire()
                    try {
                        val variables = JSONObject()
                            .put("id", EmmaStopsParser.sourceId(p.id))
                            .put("start", now.epochSecond)
                            .put("range", 86400) // 24 hours
                        val text = graphql.execute(EmmaStopsParser.BOARD_QUERY, variables)
                        EmmaStopsParser.board(text, p.id, now, 1440)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        null
                    } finally {
                        semaphore.release()
                    }
                }
            }.awaitAll().filterNotNull()
        }

        val localDepartures = if (network != null) {
            localPlatforms.flatMap { p ->
                DepartureCalculator.departures(network, null, p.id, now, 1440)
            }
        } else emptyList()

        val localRouteIds = localDepartures.map { it.routeId }.toSet()
        val localRoutes = network?.routes?.filter { it.id in localRouteIds }.orEmpty()

        val allDepartures = (emmaResults.flatMap { it.departures } + localDepartures)
            .distinctBy { listOf(it.routeId, it.directionId, it.expectedAt) }
            .sortedBy { it.expectedAt }

        val allRoutes = (emmaResults.flatMap { it.catalogue.routes } + localRoutes)
            .distinctBy { it.id }

        val platformIds = (allPlatforms.map { it.id } + station.id + stationId).distinct()

        saveStationTimetableDirect(
            stationId = stationId,
            stationName = stationName,
            platformIds = platformIds,
            routes = allRoutes,
            departures = allDepartures,
            now = now,
        )
    }

    suspend fun getOfflineBoard(
        selectedId: String,
        platformIds: List<String>,
        now: Instant,
        windowMinutes: Int,
    ): OfflineBoardResult? = withContext(Dispatchers.IO) {
        initialize()
        mutex.withLock { buildOfflineBoard(selectedId, platformIds, now, windowMinutes) }
    }

    private fun buildOfflineBoard(
        selectedId: String,
        platformIds: List<String>,
        now: Instant,
        windowMinutes: Int,
    ): OfflineBoardResult? {
        val stationId = stopToStationMap[selectedId]
            ?: platformIds.firstNotNullOfOrNull { stopToStationMap[it] }
            ?: selectedId

        val meta = stationMap[stationId] ?: return null
        val timetable = getOrLoadTimetable(stationId) ?: return null

        val targetPlatforms = (platformIds + selectedId).toSet()
        val matchingDepartures = timetable.departures.filter { it.stopId in targetPlatforms }
            .ifEmpty { timetable.departures }

        val windowEnd = now.plusSeconds(windowMinutes * 60L)
        val inWindow = matchingDepartures.filter { it.expectedAt >= now && it.expectedAt <= windowEnd }

        val departuresToReturn = if (inWindow.isNotEmpty()) {
            inWindow
        } else {
            // If now is outside downloaded horizon, project timetable to today by minute of day
            val todayStartEpoch = now.atZone(budapestZone).toLocalDate().atStartOfDay(budapestZone).toEpochSecond()
            matchingDepartures.mapNotNull { dep ->
                val secOfDay = dep.expectedAt.atZone(budapestZone).toLocalTime().toSecondOfDay()
                val projectedTime = Instant.ofEpochSecond(todayStartEpoch + secOfDay)
                if (projectedTime in now..windowEnd) {
                    dep.copy(scheduledAt = projectedTime, expectedAt = projectedTime)
                } else null
            }.sortedBy { it.expectedAt }
        }

        return OfflineBoardResult(
            departures = departuresToReturn,
            routes = timetable.routes,
            downloadedAt = timetable.downloadedAt,
            stationName = timetable.stationName,
        )
    }

    private fun getOrLoadTimetable(stationId: String): StationTimetable? {
        memoryCache[stationId]?.let { return it }
        val file = stationFile(stationId)
        val loaded = loadAndDecompress(file) ?: return null
        memoryCache[stationId] = loaded
        while (memoryCache.size > 8) {
            val oldest = memoryCache.keys.first()
            memoryCache.remove(oldest)
        }
        return loaded
    }

    suspend fun deleteStationTimetable(stopOrStationId: String) = withContext(Dispatchers.IO) {
        initialize()
        val stationId = stopToStationMap[stopOrStationId] ?: stopOrStationId
        mutex.withLock {
            val meta = stationMap.remove(stationId)
            meta?.platformIds?.forEach { stopToStationMap.remove(it) }
            stopToStationMap.remove(stationId)
            memoryCache.remove(stationId)
            val file = stationFile(stationId)
            if (file.exists()) file.delete()
            saveIndex()
        }
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        initialize()
        mutex.withLock {
            stationMap.clear()
            stopToStationMap.clear()
            memoryCache.clear()
            timetableDirectory.listFiles()?.forEach { it.delete() }
        }
    }

    private fun compressAndSave(timetable: StationTimetable, file: File): Long {
        file.parentFile?.mkdirs()
        val json = JSONObject()
        json.put("v", 1)
        json.put("stationId", timetable.stationId)
        json.put("stationName", timetable.stationName)
        json.put("platformIds", JSONArray(timetable.platformIds))
        json.put("downloadedAt", timetable.downloadedAt.toEpochMilli())

        val routesArray = JSONArray()
        timetable.routes.forEach { r ->
            val ro = JSONObject()
            ro.put("id", r.id)
            ro.put("num", r.number)
            ro.put("name", r.name)
            ro.put("prov", r.providerId)
            r.transportMode?.let { ro.put("mode", it) }
            r.color?.let { ro.put("col", it) }
            routesArray.put(ro)
        }
        json.put("routes", routesArray)

        // String pool for destinations to minimize size
        val destinations = timetable.departures.map { it.destination }.distinct()
        json.put("destinations", JSONArray(destinations))
        val destMap = destinations.mapIndexed { idx, dest -> dest to idx }.toMap()

        val depArray = JSONArray()
        timetable.departures.forEach { d ->
            val depObj = JSONObject()
            depObj.put("r", d.routeId)
            depObj.put("d", destMap[d.destination] ?: 0)
            depObj.put("s", d.stopId)
            depObj.put("t", d.expectedAt.epochSecond)
            d.directionId?.let { depObj.put("dir", it) }
            d.patternCode?.let { depObj.put("pat", it) }
            d.platformCode?.let { depObj.put("p", it) }
            d.trainNumber?.let { depObj.put("tn", it) }
            d.serviceName?.let { depObj.put("sn", it) }
            d.wheelchairAccessible?.let { depObj.put("wc", it) }
            d.bikesAllowed?.let { depObj.put("bk", it) }
            depArray.put(depObj)
        }
        json.put("departures", depArray)

        val temp = File(file.parentFile, "${file.name}.tmp")
        GZIPOutputStream(temp.outputStream().buffered()).use { gzip ->
            gzip.write(json.toString().toByteArray(Charsets.UTF_8))
        }
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
        return file.length()
    }

    private fun loadAndDecompress(file: File): StationTimetable? {
        if (!file.isFile) return null
        return runCatching {
            val text = GZIPInputStream(file.inputStream().buffered()).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)
            val stationId = root.getString("stationId")
            val stationName = root.getString("stationName")
            val downloadedAt = Instant.ofEpochMilli(root.getLong("downloadedAt"))
            val pArray = root.getJSONArray("platformIds")
            val platformIds = (0 until pArray.length()).map { pArray.getString(it) }

            val rArray = root.getJSONArray("routes")
            val routes = (0 until rArray.length()).map { idx ->
                val ro = rArray.getJSONObject(idx)
                TransitRoute(
                    id = ro.getString("id"),
                    number = ro.getString("num"),
                    name = ro.getString("name"),
                    directions = emptyList(),
                    providerId = ro.getString("prov"),
                    transportMode = ro.optString("mode").takeUnless { it.isBlank() },
                    color = ro.optString("col").takeUnless { it.isBlank() },
                )
            }
            val routesMap = routes.associateBy { it.id }

            val dArray = root.getJSONArray("destinations")
            val destinations = (0 until dArray.length()).map { dArray.getString(it) }

            val depArray = root.getJSONArray("departures")
            val departures = (0 until depArray.length()).mapNotNull { idx ->
                val d = depArray.getJSONObject(idx)
                val routeId = d.getString("r")
                val route = routesMap[routeId] ?: return@mapNotNull null
                val destIdx = d.getInt("d")
                val destination = destinations.getOrElse(destIdx) { "" }
                val stopId = d.getString("s")
                val epochSec = d.getLong("t")
                val time = Instant.ofEpochSecond(epochSec)
                Departure(
                    routeId = routeId,
                    routeNumber = route.number,
                    destination = destination,
                    stopId = stopId,
                    scheduledAt = time,
                    expectedAt = time,
                    kind = ArrivalKind.SCHEDULED,
                    directionId = d.optString("dir").takeUnless { it.isBlank() },
                    patternCode = d.optString("pat").takeUnless { it.isBlank() },
                    platformCode = d.optString("p").takeUnless { it.isBlank() },
                    trainNumber = d.optString("tn").takeUnless { it.isBlank() },
                    serviceName = d.optString("sn").takeUnless { it.isBlank() },
                    wheelchairAccessible = d.optString("wc").takeUnless { it.isBlank() },
                    bikesAllowed = d.optString("bk").takeUnless { it.isBlank() },
                )
            }

            StationTimetable(
                stationId = stationId,
                stationName = stationName,
                platformIds = platformIds,
                downloadedAt = downloadedAt,
                routes = routes,
                departures = departures,
            )
        }.getOrNull()
    }
}
