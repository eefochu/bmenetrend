package hu.app.bmenetrend.offline

import android.content.Context
import android.net.Uri
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class OfflinePackRepository(private val context: Context, scope: CoroutineScope) {
    val store = OfflinePackStore(context)
    private val preferences = context.getSharedPreferences("offline", 0)
    private val work = WorkManager.getInstance(context)
    private val mutable = MutableStateFlow(OfflinePackState(phase = OfflinePackPhase.INITIALIZING))
    val state = mutable.asStateFlow()

    private val stateLock = Any()
    private var mutationEpoch = 0L
    private var deleting = false
    private var importingCustom = false

    init {
        scope.launch {
            work.getWorkInfosByTagFlow(OfflinePackWorker.TAG).collectLatest { all ->
                val emissionEpoch = synchronized(stateLock) {
                    if (deleting || importingCustom) return@collectLatest
                    mutationEpoch
                }
                val selectedId = preferences.getString("work_id", null)
                val downloadId = preferences.getString("download_work_id", null)
                val failedDownload = all.firstOrNull { it.id.toString() == downloadId && it.state == WorkInfo.State.FAILED }
                val info = all.firstOrNull { it.state == WorkInfo.State.RUNNING } ?: failedDownload ?: all.firstOrNull { it.id.toString() == selectedId }
                val meta = withContext(Dispatchers.IO) { store.metadata() }
                synchronized(stateLock) {
                    if (deleting || importingCustom || emissionEpoch != mutationEpoch) return@collectLatest
                    val currentPhase = mutable.value.phase
                    // Ignore the brief pre-enqueue emission, but always consume terminal
                    // states. Keeping IMPORTING for a finished worker left Settings stuck.
                    if (info == null && selectedId != null && (currentPhase == OfflinePackPhase.IMPORTING || currentPhase == OfflinePackPhase.DOWNLOADING)) return@collectLatest
                    val phase = offlinePackPhase(info?.state, info?.progress?.getString("phase") == "IMPORTING", meta != null)
                    val step = info?.progress?.getString("import_step")?.let { runCatching { OfflineImportStep.valueOf(it) }.getOrNull() }
                    val detail = step?.let { OfflineImportProgress(it, info?.progress?.getLong("import_records", 0) ?: 0, info?.progress?.getLong("import_completed", 0) ?: 0, info?.progress?.getLong("import_total", 0) ?: 0) }
                    mutable.value = mutable.value.copy(
                        metadata = meta,
                        phase = phase,
                        bytes = info?.progress?.getLong("bytes", 0) ?: 0,
                        total = info?.progress?.getLong("total", 0) ?: 0,
                        source = info?.progress?.getString("source").orEmpty(),
                        error = info?.outputData?.getString("error"),
                        automaticUpdates = preferences.getBoolean("automatic_updates", false),
                        importProgress = detail
                    )
                }
            }
        }
    }

    fun download(wifiOnly: Boolean) {
        if (!state.value.loaded || state.value.busy || NetworkAccessPolicy.offline.value) return
        synchronized(stateLock) {
            if (deleting) return
            ++mutationEpoch
            mutable.value = state.value.copy(phase = OfflinePackPhase.DOWNLOADING, error = null, wifiOnly = wifiOnly, importProgress = null)
        }
        enqueueDownload(context, wifiOnly)
    }

    fun setAutomaticUpdates(enabled: Boolean) {
        preferences.edit().putBoolean("automatic_updates", enabled).apply()
        synchronized(stateLock) {
            mutable.value = state.value.copy(automaticUpdates = enabled)
        }
        if (!enabled) work.cancelUniqueWork(OfflinePackWorker.AUTO_WORK_NAME)
        else {
            val request = PeriodicWorkRequestBuilder<OfflinePackWorker>(24, java.util.concurrent.TimeUnit.HOURS)
                .setInitialDelay(24, java.util.concurrent.TimeUnit.HOURS).addTag(OfflinePackWorker.TAG)
                .setInputData(workDataOf("automatic" to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresStorageNotLow(true).build()).build()
            work.enqueueUniquePeriodicWork(OfflinePackWorker.AUTO_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    fun cancel() {
        work.cancelUniqueWork(OfflinePackWorker.WORK_NAME)
        if (state.value.automaticUpdates) setAutomaticUpdates(false)
    }

    suspend fun delete() = withContext(Dispatchers.IO) {
        check(state.value.loaded && !state.value.busy)
        synchronized(stateLock) {
            deleting = true
            ++mutationEpoch
        }
        try {
            work.cancelAllWorkByTag(OfflinePackWorker.TAG).result.get()
            preferences.edit().putBoolean("automatic_updates", false).apply()
            OfflinePackWorker.workMutex.withLock {
                NetworkAccessPolicy.setOffline(context, false)
                store.delete()
                store.clearDownloads()
                hu.app.bmenetrend.map.OfflineBasemap.deleteWorkingCopy(context.applicationContext)
            }
            preferences.edit().remove("work_id").remove("download_work_id").apply()
        } finally {
            synchronized(stateLock) {
                deleting = false
                ++mutationEpoch
                mutable.value = OfflinePackState(phase = OfflinePackPhase.MISSING)
            }
        }
    }

    suspend fun importFeed(uri: Uri) = withContext(Dispatchers.IO) {
        OfflinePackWorker.workMutex.withLock {
            check(state.value.loaded && !state.value.busy && !NetworkAccessPolicy.offline.value)
            synchronized(stateLock) {
                if (deleting) return@withLock
                importingCustom = true
                ++mutationEpoch
                mutable.value = state.value.copy(phase = OfflinePackPhase.IMPORTING, source = "GTFS", error = null)
            }
            val target = File(store.downloads, "custom.zip")
            val staged = File(store.downloads, "custom.part")
            try {
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    staged.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024); var bytes = 0L
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            bytes += n; require(bytes <= OFFLINE_DATABASE_MAX_BYTES) { "size" }; output.write(buffer, 0, n)
                        }
                    }
                }
                java.util.zip.ZipFile(staged).use { require(it.getEntry("stop_times.txt") != null) }
                check(staged.renameTo(target))
                synchronized(stateLock) {
                    if (!deleting) {
                        ++mutationEpoch
                        importingCustom = false
                        mutable.value = state.value.copy(phase = if (state.value.metadata == null) OfflinePackPhase.MISSING else OfflinePackPhase.READY)
                    }
                }
                download(state.value.wifiOnly)
            } catch (error: Exception) {
                synchronized(stateLock) {
                    if (!deleting) {
                        ++mutationEpoch
                        importingCustom = false
                        mutable.value = state.value.copy(phase = OfflinePackPhase.FAILED, error = "import")
                    }
                }
                throw error
            } finally {
                synchronized(stateLock) { importingCustom = false }
                staged.delete()
            }
        }
    }

    companion object {
        internal fun downloadRequests(wifiOnly: Boolean): Pair<OneTimeWorkRequest, OneTimeWorkRequest> {
            val download = OneTimeWorkRequestBuilder<OfflinePackWorker>().addTag(OfflinePackWorker.TAG)
                .setInputData(workDataOf("stage" to "download"))
                // CONNECTED also requires Android's VALIDATED bit on API 26+.
                // For mobile-enabled downloads the app's verified connectivity
                // policy owns this check; otherwise usable captive-check-blocked
                // networks can leave a request queued forever.
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
                    .setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, java.util.concurrent.TimeUnit.SECONDS).build()
            val index = OneTimeWorkRequestBuilder<OfflinePackWorker>().addTag(OfflinePackWorker.TAG)
                .setInputData(workDataOf("stage" to "index"))
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build()).build()
            return download to index
        }

        internal fun enqueueDownload(context: Context, wifiOnly: Boolean) {
            val (download, index) = downloadRequests(wifiOnly)
            context.getSharedPreferences("offline", 0).edit().putString("work_id", index.id.toString())
                .putString("download_work_id", download.id.toString()).apply()
            WorkManager.getInstance(context).beginUniqueWork(OfflinePackWorker.WORK_NAME, ExistingWorkPolicy.KEEP, download)
                .then(index).enqueue()
        }
    }
}

internal fun offlinePackPhase(work: WorkInfo.State?, importing: Boolean, installed: Boolean): OfflinePackPhase = when {
    work == WorkInfo.State.FAILED -> OfflinePackPhase.FAILED
    work != null && !work.isFinished -> if (work == WorkInfo.State.RUNNING && importing) OfflinePackPhase.IMPORTING else OfflinePackPhase.DOWNLOADING
    installed -> OfflinePackPhase.READY
    else -> OfflinePackPhase.MISSING
}
