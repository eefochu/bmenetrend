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

    init {
        scope.launch {
            work.getWorkInfosByTagFlow(OfflinePackWorker.TAG).collectLatest { all ->
                val emissionEpoch = synchronized(stateLock) {
                    if (deleting) return@collectLatest
                    mutationEpoch
                }
                val selectedId = preferences.getString("work_id", null)
                val info = all.firstOrNull { it.state == WorkInfo.State.RUNNING } ?: all.firstOrNull { it.id.toString() == selectedId }
                val meta = withContext(Dispatchers.IO) { store.metadata() }
                synchronized(stateLock) {
                    if (deleting || emissionEpoch != mutationEpoch) return@collectLatest
                    val currentPhase = mutable.value.phase
                    val phase = when {
                        currentPhase == OfflinePackPhase.IMPORTING && (info == null || info.state.isFinished) -> OfflinePackPhase.IMPORTING
                        currentPhase == OfflinePackPhase.DOWNLOADING && (info == null || info.state.isFinished) -> OfflinePackPhase.DOWNLOADING
                        info == null -> if (meta == null) OfflinePackPhase.MISSING else OfflinePackPhase.READY
                        !info.state.isFinished -> if (info.state == WorkInfo.State.RUNNING && info.progress.getString("phase") == "IMPORTING") OfflinePackPhase.IMPORTING else OfflinePackPhase.DOWNLOADING
                        info.state == WorkInfo.State.FAILED -> OfflinePackPhase.FAILED
                        meta != null -> OfflinePackPhase.READY
                        else -> OfflinePackPhase.MISSING
                    }
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
        val request = OneTimeWorkRequestBuilder<OfflinePackWorker>().addTag(OfflinePackWorker.TAG).setConstraints(Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).setRequiresStorageNotLow(true).build()).build()
        synchronized(stateLock) {
            if (deleting) return
            ++mutationEpoch
            mutable.value = state.value.copy(phase = OfflinePackPhase.DOWNLOADING, error = null, wifiOnly = wifiOnly, importProgress = null)
        }
        preferences.edit().putString("work_id", request.id.toString()).apply()
        work.enqueueUniqueWork(OfflinePackWorker.WORK_NAME, ExistingWorkPolicy.KEEP, request)
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
            preferences.edit().remove("work_id").apply()
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
                        mutable.value = state.value.copy(phase = if (state.value.metadata == null) OfflinePackPhase.MISSING else OfflinePackPhase.READY)
                    }
                }
                download(state.value.wifiOnly)
            } catch (error: Exception) {
                synchronized(stateLock) {
                    if (!deleting) {
                        ++mutationEpoch
                        mutable.value = state.value.copy(phase = OfflinePackPhase.FAILED, error = "import")
                    }
                }
                throw error
            } finally { staged.delete() }
        }
    }
}
