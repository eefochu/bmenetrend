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
            NetworkAccessPolicy.initialize(applicationContext)
            if (inputData.getBoolean("automatic", false) && (NetworkAccessPolicy.offline.value || store.metadata()?.builtAt?.let { java.time.Duration.between(it, Instant.now()).toHours() < 20 } == true)) return@withContext Result.success()
            NetworkAccessPolicy.requireOnline()
            setForeground(notification(applicationContext.getString(R.string.offline_preparing)))
            require(store.downloads.usableSpace >= 1_500_000_000L) { "storage" }
            val sources = OfflineSource.defaults
            var completed = 0L
            for (source in sources) {
                NetworkAccessPolicy.requireOnline()
                val file = File(store.downloads, "${source.id}.zip")
                download(source, file, completed)
                completed += file.length()
                require(completed <= OFFLINE_MAX_BYTES) { "size" }
            }
            val imported = File(store.downloads, "custom.zip").takeIf { it.isFile }
            if (imported != null) completed += imported.length()
            require(completed <= OFFLINE_MAX_BYTES) { "size" }
            progress("IMPORTING", completed, completed, applicationContext.getString(R.string.offline_installing))
            importing = true
            staging = store.staging()
            val first = LocalDate.now(ZoneId.of("Europe/Budapest"))
            var until = first.plusDays(29)
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
                        val importer = GtfsImporter(db, first.minusDays(2), until.plusDays(1), publishImport)
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
                        publishImport(OfflineImportProgress(OfflineImportStep.LOCAL_SOURCES))
                        val local = hu.app.bmenetrend.data.TransitRepository(applicationContext).planningNetwork()
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
            NetworkAccessPolicy.requireOnline()
            val meta = OfflinePackMetadata(UUID.randomUUID().toString(), Instant.now(), first, until, staging.length(), completed, counts.first, counts.second, names, OfflinePackStore.digest(staging), imported?.length() ?: 0, warnings)
            publishImport(OfflineImportProgress(OfflineImportStep.ACTIVATING))
            currentCoroutineContext().ensureActive()
            store.activate(staging, meta)
            // Default archives are temporary: the installed footprint also stays below 500 MB.
            sources.forEach { source ->
                File(store.downloads, "${source.id}.zip").delete()
                File(store.downloads, "${source.id}.zip.etag").delete()
            }
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (importing) OfflineSource.defaults.forEach { source ->
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

    private suspend fun download(source: OfflineSource, file: File, completed: Long) {
        // Only interrupted installs retain complete default archives; successful installs delete
        // them. Retry the same recent snapshot without downloading it again, including providers
        // without ETags. Each parsed entry is CRC-checked before activation.
        if (file.isFile && System.currentTimeMillis() - file.lastModified() in 0..21_600_000L) {
            require(file.length() + completed <= OFFLINE_MAX_BYTES) { "size" }
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
                require(length == null || length + completed <= OFFLINE_MAX_BYTES) { "size" }
                val etag = response.header("ETag")?.takeUnless { it.startsWith("W/") }
                if (partial.isFile && length == partial.length() && etag != null && validator.readTextOrNull() == etag) {
                    java.util.zip.ZipFile(partial).use { require(it.getEntry("stop_times.txt") != null) }
                    check(partial.renameTo(file)); return
                }
                if (file.isFile && length == file.length() && etag != null && validator.readTextOrNull() == etag) return
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
                require(length < 0 || start + length + completed <= OFFLINE_MAX_BYTES) { "size" }
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
                            require(bytes + completed <= OFFLINE_MAX_BYTES) { "size" }
                            output.write(buffer, 0, n)
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastProgress > 750) {
                                progress("DOWNLOADING", completed + bytes, if (length > 0) completed + start + length else 0, source.name)
                                lastProgress = now
                            }
                        }
                        output.fd.sync()
                    }
                }
                require(bytes > 0 && (length < 0 || bytes == start + length)) { "Incomplete GTFS download" }
                java.util.zip.ZipFile(partial).use { require(it.getEntry("stops.txt") != null && it.getEntry("stop_times.txt") != null) }
                check(partial.renameTo(file)) { "Cannot store GTFS archive" }
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
private fun File.readTextOrNull(): String? = if (isFile) readText().takeIf { it.isNotBlank() } else null
