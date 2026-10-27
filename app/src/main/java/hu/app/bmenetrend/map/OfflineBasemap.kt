package hu.app.bmenetrend.map

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * MapLibre 11.8 supports PMTiles file range reads, but cannot range-read APK
 * assets. Keep a checked, bounded file copy; the pack budget counts both copies.
 * Tiles are read individually by native code, never as a national GeoJSON heap.
 */
object OfflineBasemap {
    private val mutex = Mutex()
    private var verifiedPath: String? = null
    private val lifecycleLock = Any()
    private var activeMaps = 0
    private var deleteRequested = false
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun retain() = synchronized(lifecycleLock) { activeMaps++ }

    fun release(context: Context) {
        val cleanup = synchronized(lifecycleLock) {
            activeMaps--
            activeMaps == 0 && deleteRequested
        }
        if (cleanup) cleanupScope.launch { cleanIfUnused(context.applicationContext) }
    }

    /** Native requests may still use the file until the last offline MapView dies. */
    suspend fun deleteWorkingCopy(context: Context) {
        synchronized(lifecycleLock) { deleteRequested = true }
        cleanIfUnused(context)
    }

    private suspend fun cleanIfUnused(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            synchronized(lifecycleLock) {
                if (activeMaps == 0 && deleteRequested) {
                    val directory = File(context.noBackupFilesDir, "offline-basemap")
                    File(directory, "hungary.pmtiles").delete()
                    File(directory, "hungary.part").delete()
                    verifiedPath = null
                    deleteRequested = false
                }
            }
        }
    }

    suspend fun style(context: Context, dark: Boolean): String = withContext(Dispatchers.IO) {
        val archive = mutex.withLock {
            val directory = File(context.noBackupFilesDir, "offline-basemap").apply { check(isDirectory || mkdirs()) }
            val target = File(directory, "hungary.pmtiles")
            if (verifiedPath != target.path || target.length() != OfflineBasemapAssets.ARCHIVE_BYTES) {
                if (target.length() != OfflineBasemapAssets.ARCHIVE_BYTES || sha256(target) != OfflineBasemapAssets.ARCHIVE_SHA256) {
                    val staged = File(directory, "hungary.part")
                    try {
                        val digest = MessageDigest.getInstance("SHA-256")
                        var bytes = 0L
                        context.assets.open("map/offline/hungary.pmtiles").use { input ->
                            FileOutputStream(staged).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    bytes += count
                                    check(bytes <= OfflineBasemapAssets.ARCHIVE_BYTES)
                                    digest.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                }
                                output.fd.sync()
                            }
                        }
                        check(bytes == OfflineBasemapAssets.ARCHIVE_BYTES)
                        check(digest.hex() == OfflineBasemapAssets.ARCHIVE_SHA256)
                        Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } finally { staged.delete() }
                }
                verifiedPath = target.path
            }
            target
        }
        context.assets.open(if (dark) "map/offline-dark.json" else "map/offline.json")
            .bufferedReader().use { it.readText() }
            .replace("pmtiles://file://OFFLINE_BASEMAP", "pmtiles://${Uri.fromFile(archive)}")
    }

    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.hex()
    }

    private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it) }
}
