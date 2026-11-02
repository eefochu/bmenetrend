package hu.app.bmenetrend.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import hu.app.bmenetrend.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Direct provider GTFS download avoids introducing an unconfigured third-party pack server. */
class OfflinePackWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (workMutex.isLocked) {
            progress("IMPORTING", 0, 0, "", OfflineImportProgress(OfflineImportStep.WAITING))
        }
        workMutex.withLock {
        val store = OfflinePackStore(applicationContext)
        var staging: File? = null
        var importing = false
        try {
            if (inputData.getBoolean("automatic", false)) {
                if (!NetworkAccessPolicy.offline.value && store.metadata()?.builtAt?.let { java.time.Duration.between(it, Instant.now()).toHours() < 20 } != true &&
                    WorkManager.getInstance(applicationContext).getWorkInfosForUniqueWork(WORK_NAME).get().none { !it.state.isFinished }) {
                    OfflinePackRepository.enqueueDownload(applicationContext, true)
                }
                return@withContext Result.success()
            }
            if (inputData.getString("stage") != "index" && NetworkAccessPolicy.offline.value) return@withContext Result.retry()
            setForeground(notification(applicationContext.getString(R.string.offline_preparing)))
            require(store.downloads.usableSpace >= 1_500_000_000L) { "storage" }
            val sources = OfflineSource.defaults + OfflineSource.emmaRail
            val first = LocalDate.now(ZoneId.of("Europe/Budapest"))
            val imported = File(store.downloads, "custom.zip").takeIf { it.isFile }
            if (inputData.getString("stage") != "index") {
                NetworkAccessPolicy.requireOnline()
                val totals = DownloadTotals(imported?.length() ?: 0, sources.size)
                coroutineScope {
                    val local = async {
                        val network = hu.app.bmenetrend.data.TransitRepository(applicationContext).planningNetwork()
                        File(store.downloads, "local-network.json").writeText(hu.app.bmenetrend.data.NetworkJson.encode(network))
                    }
                    sources.map { source -> async {
                        val target = File(store.downloads, "${source.id}.zip")
                        if (source.id == OfflineSource.emmaRail.id) {
                            EmmaRailSnapshot().download(target, first.minusDays(2), first.plusDays(30)) { done, count ->
                                progress("DOWNLOADING", totals.snapshot().first, 0,
                                    "${source.name} · $done / $count")
                            }
                            val aggregate = totals.update(source.id, target.length(), target.length())
                            progress("DOWNLOADING", aggregate.first, aggregate.second, source.name)
                        } else download(source, target, totals)
                    } }.awaitAll()
                    local.await()
                }
                File(store.downloads, "snapshot-date.txt").writeText(first.toString())
                if (inputData.getString("stage") == "download") return@withContext Result.success()
            }
            // This stage has no network constraint: all inputs were downloaded and
            // checked by its predecessor, so losing connectivity cannot abort indexing.
            val completed = sources.sumOf { File(store.downloads, "${it.id}.zip").length() } + (imported?.length() ?: 0)
            require(completed <= OFFLINE_MAX_BYTES) { "size" }
            progress("IMPORTING", completed, completed, applicationContext.getString(R.string.offline_installing))
            importing = true
            staging = store.staging()
            // The queued indexing stage must use the same service-day window as its downloader.
            val snapshotFirst = File(store.downloads, "snapshot-date.txt").readTextOrNull()?.let(LocalDate::parse) ?: first
            var until = snapshotFirst.plusDays(29)
            val names = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            var activeSource = ""
            val publishImport: suspend (OfflineImportProgress) -> Unit = { detail ->
                progress("IMPORTING", completed, completed, activeSource, detail)
            }
            onOfflineDatabaseThread {
                GtfsImporter.create(staging).use { db ->
                    db.beginTransaction()
                    try {
                        val importer = GtfsImporter(db, snapshotFirst.minusDays(2), until.plusDays(1), publishImport)
                        for (source in sources) {
                            activeSource = source.name
                            importer.import(File(store.downloads, "${source.id}.zip"), source)
                            names += source.name
                        }
                        if (imported != null) {
                            val custom = OfflineSource("imported", applicationContext.getString(R.string.offline_imported_feed), "local", "local")
                            activeSource = custom.name
                            importer.import(imported, custom); names += custom.name
                        }
                        activeSource = ""
                        val local = File(store.downloads, "local-network.json").takeIf { it.isFile }
                            ?.let { hu.app.bmenetrend.data.NetworkJson.decode(it.readText()) }
                            ?: hu.app.bmenetrend.data.TransitNetwork(emptyList(), emptyList(), emptyList(), Instant.now(), "offline")
                        names += importer.importLocal(local)
                        currentCoroutineContext().ensureActive()
                        warnings += importer.warnings
                        importer.finish()
                        publishImport(OfflineImportProgress(OfflineImportStep.SAVING))
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                    db.rawQuery("SELECT min(last_day) FROM feeds", null).use { if (it.moveToFirst()) until = minOf(until, LocalDate.ofEpochDay(it.getLong(0)).minusDays(1)) }
                }
            }
            require(until >= first) { "Expired source timetable" }
            require(staging.length() + (imported?.length() ?: 0) <= OFFLINE_DATABASE_MAX_BYTES) { "size" }
            publishImport(OfflineImportProgress(OfflineImportStep.VERIFYING))
            val counts = android.database.sqlite.SQLiteDatabase.openDatabase(staging.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                val stops = db.rawQuery("SELECT count(*) FROM stops", null).use { it.moveToFirst(); it.getInt(0) }
                val trips = db.rawQuery("SELECT count(*) FROM trips", null).use { it.moveToFirst(); it.getInt(0) }
                stops to trips
            }
            currentCoroutineContext().ensureActive()
            val sha256 = OfflinePackStore.digest(staging)
            val meta = OfflinePackMetadata(UUID.randomUUID().toString(), Instant.now(), snapshotFirst, until, staging.length(), completed, counts.first, counts.second, names, sha256, imported?.length() ?: 0, warnings)
            publishImport(OfflineImportProgress(OfflineImportStep.ACTIVATING))
            currentCoroutineContext().ensureActive()
            store.activate(staging, meta, verifiedDigest = sha256)
            // Default archives are temporary: the installed footprint also stays below 500 MB.
            sources.forEach { source ->
                File(store.downloads, "${source.id}.zip").delete()
                File(store.downloads, "${source.id}.zip.etag").delete()
            }
            File(store.downloads, "local-network.json").delete()
            File(store.downloads, "snapshot-date.txt").delete()
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (!importing && NetworkAccessPolicy.offline.value) return@withContext Result.retry()
            android.util.Log.w("OfflinePackWorker", "Offline pack preparation failed", error)
            if (importing && (error is java.util.zip.ZipException || error.message?.contains("Corrupted GTFS") == true)) (OfflineSource.defaults + OfflineSource.emmaRail).forEach { source ->
                File(store.downloads, "${source.id}.zip").delete()
                File(store.downloads, "${source.id}.zip.etag").delete()
            }
            val code = when { error.message == "storage" -> "storage"; error.message == "size" || error.message?.contains("500 MB") == true -> "size"; else -> "download" }
            Result.failure(workDataOf("error" to code))
        } finally { staging?.delete() }
        }
    }

    private suspend fun progress(phase: String, bytes: Long, total: Long, label: String, detail: OfflineImportProgress? = null) {
        setProgress(workDataOf("phase" to phase, "bytes" to bytes, "total" to total, "source" to label,
            "import_step" to detail?.step?.name, "import_records" to (detail?.records ?: 0),
            "import_completed" to (detail?.completed ?: 0), "import_total" to (detail?.total ?: 0)))
        val stage = detail?.step?.let { applicationContext.getString(it.labelResource) }.orEmpty()
        setForeground(notification(listOf(label, stage).filter { it.isNotBlank() }.joinToString(" · ")))
    }

    private suspend fun download(source: OfflineSource, file: File, totals: DownloadTotals) {
        // Only interrupted installs retain complete default archives; successful installs delete
        // them. Retry the same recent snapshot without downloading it again, including providers
        // without ETags. Each parsed entry is CRC-checked before activation.
        if (file.isFile && System.currentTimeMillis() - file.lastModified() in 0..21_600_000L) {
            totals.update(source.id, file.length(), file.length())
            val complete = runCatching { java.util.zip.ZipFile(file).use { it.getEntry("stops.txt") != null && it.getEntry("stop_times.txt") != null } }.getOrDefault(false)
            if (complete) return
            file.delete()
        }
        val client = NetworkAccessPolicy.protect(OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build())
        val partial = File(file.parentFile, "${file.name}.part")
        val validator = File(file.parentFile, "${file.name}.etag")
        // A complete cached file is checked with HEAD before reuse. No stale archive can be called an update.
        val head = Request.Builder().url(source.url).header("User-Agent", "BMenetrend-Android/0.4").head().build()
        client.newCall(head).execute().use { response ->
            if (response.isSuccessful) {
                val length = response.header("Content-Length")?.toLongOrNull()
                if (length != null) totals.update(source.id, 0, length)
                val etag = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                if (partial.isFile && length == partial.length() && etag != null && validator.readTextOrNull() == etag) {
                    java.util.zip.ZipFile(partial).use { require(it.getEntry("stop_times.txt") != null) }
                    check(partial.renameTo(file)); totals.update(source.id, file.length(), file.length()); return
                }
                if (file.isFile && length == file.length() && etag != null && validator.readTextOrNull() == etag) {
                    totals.update(source.id, file.length(), file.length()); return
                }
            }
        }
        val previousTag = validator.readTextOrNull()
        val offset = partial.length().takeIf { previousTag != null } ?: 0L
        if (offset == 0L) partial.delete()
        val request = Request.Builder().url(source.url).header("User-Agent", "BMenetrend-Android/0.4")
        if (offset > 0) request.header("Range", "bytes=$offset-").header("If-Range", previousTag!!)
        val call = client.newCall(request.build())
        val cancellation = currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("GTFS HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty GTFS archive")
                val append = response.code == 206 && offset > 0
                if (append) require(response.header("Content-Range")?.startsWith("bytes $offset-") == true) { "Invalid resumed download" }
                val start = if (append) offset else 0L
                val length = body.contentLength()
                totals.update(source.id, start, if (length < 0) 0 else start + length)
                val etag = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                if (etag == null) validator.delete() else validator.writeText(etag)
                var bytes = start
                var lastProgress = 0L
                body.byteStream().use { input ->
                    java.io.FileOutputStream(partial, append).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            NetworkAccessPolicy.requireOnline()
                            val n = input.read(buffer); if (n < 0) break
                            bytes += n
                            val aggregate = totals.update(source.id, bytes, if (length > 0) start + length else 0)
                            output.write(buffer, 0, n)
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastProgress > 750) {
                                progress("DOWNLOADING", aggregate.first, aggregate.second, source.name)
                                lastProgress = now
                            }
                        }
                        output.fd.sync()
                    }
                }
                require(bytes > 0 && (length < 0 || bytes == start + length)) { "Incomplete GTFS download" }
                java.util.zip.ZipFile(partial).use { require(it.getEntry("stops.txt") != null && it.getEntry("stop_times.txt") != null) }
                check(partial.renameTo(file)) { "Cannot store GTFS archive" }
                val aggregate = totals.update(source.id, bytes, bytes)
                progress("DOWNLOADING", aggregate.first, aggregate.second, source.name)
            }
        } finally { cancellation?.dispose() }
    }

    private fun notification(label: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("offline-pack", applicationContext.getString(R.string.offline_pack_title), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "offline-pack")
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(applicationContext.getString(R.string.offline_pack_title))
            .setContentText(label).setOngoing(true).setProgress(0, 0, true)
            .addAction(android.R.drawable.ic_delete, applicationContext.getString(R.string.offline_cancel), WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(3201, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(3201, notification)
    }
    companion object {
        const val WORK_NAME = "offline-hungary-pack"
        const val AUTO_WORK_NAME = "offline-hungary-pack-update"
        const val TAG = "offline-pack"
        internal val workMutex = kotlinx.coroutines.sync.Mutex()
    }
}

/** Shared accounting keeps parallel transfers within the combined storage budget. */
internal class DownloadTotals(private val retained: Long, private val sourceCount: Int = OfflineSource.defaults.size) {
    private val received = mutableMapOf<String, Long>()
    private val lengths = mutableMapOf<String, Long>()
    @Synchronized fun update(source: String, bytes: Long, total: Long): Pair<Long, Long> {
        require(bytes >= 0 && total >= 0)
        received[source] = bytes
        lengths[source] = total
        val downloaded = retained + received.values.sum()
        val expected = retained + lengths.values.sum()
        require(downloaded <= OFFLINE_MAX_BYTES && expected <= OFFLINE_MAX_BYTES) { "size" }
        return downloaded to if (lengths.size == sourceCount && lengths.values.all { it > 0 }) expected else 0
    }
    @Synchronized fun snapshot(): Pair<Long, Long> = retained + received.values.sum() to 0L
}
private fun File.readTextOrNull(): String? = if (isFile) readText().takeIf { it.isNotBlank() } else null
