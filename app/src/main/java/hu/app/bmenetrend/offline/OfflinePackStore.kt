package hu.app.bmenetrend.offline

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/** Readers hold the store monitor through a query, so activation/deletion cannot invalidate their DB. */
class OfflinePackStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "offline-pack").apply { mkdirs() }
    val downloads = File(root, "downloads").apply { mkdirs() }
    private val pointer = AtomicFile(File(root, "active.json"))
    private val lock get() = locks.getOrPut(root.absolutePath) { Any() }

    fun metadata(): OfflinePackMetadata? = synchronized(lock) {
        runCatching {
            val meta = OfflinePackMetadata.parse(pointer.openRead().bufferedReader().use { it.readText() })
            require(meta.id.matches(Regex("[a-zA-Z0-9-]+")))
            require(File(root, "${meta.id}.db").length() == meta.installedBytes)
            meta
        }.getOrNull()
    }

    fun staging(): File = synchronized(lock) {
        val active = metadata()?.id
        root.listFiles()?.filter { it.extension == "db" && it.name != "$active.db" }?.forEach { it.delete() }
        File(root, "staging.db").also { file ->
            file.delete(); listOf("-journal", "-wal", "-shm").forEach { File(file.path + it).delete() }
        }
    }
    fun <T> read(block: (SQLiteDatabase, OfflinePackMetadata) -> T): T = synchronized(lock) {
        val meta = metadata() ?: error("Offline pack is not installed")
        SQLiteDatabase.openDatabase(File(root, "${meta.id}.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db -> block(db, meta) }
    }

    fun activate(staging: File, metadata: OfflinePackMetadata, verifiedDigest: String? = null) = synchronized(lock) {
        require(staging.length() == metadata.installedBytes && metadata.retainedSourceBytes in 0..OFFLINE_MAX_BYTES && metadata.installedFootprintBytes in 1..OFFLINE_MAX_BYTES)
        // verifiedDigest: digest of this exact file computed by the caller (single pass over a
        // ~388 MB pack instead of one per verification site). When absent, recompute here.
        require((verifiedDigest ?: digest(staging)) == metadata.sha256) { "Pack checksum mismatch" }
        SQLiteDatabase.openDatabase(staging.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            require(db.version == OFFLINE_FORMAT)
            db.rawQuery("PRAGMA quick_check", null).use { require(it.moveToFirst() && it.getString(0) == "ok") }
            db.rawQuery("SELECT count(*) FROM stops", null).use { require(it.moveToFirst() && it.getInt(0) > 0) }
            db.rawQuery("SELECT count(*) FROM trips", null).use { require(it.moveToFirst() && it.getInt(0) > 0) }
        }
        val destination = File(root, "${metadata.id}.db")
        check(staging.renameTo(destination)) { "Cannot install offline pack" }
        var output: java.io.FileOutputStream? = null
        try {
            output = pointer.startWrite()
            output.write(metadata.json().toString().toByteArray())
            pointer.finishWrite(output)
        } catch (e: Exception) {
            pointer.failWrite(output); destination.delete(); throw e
        }
        root.listFiles()?.filter { it.extension == "db" && it != destination }?.forEach { it.delete() }
        NetworkAccessPolicy.onPackAvailabilityChanged(true)
    }

    fun delete() = synchronized(lock) {
        pointer.delete()
        root.listFiles()?.filter { it.extension == "db" }?.forEach { it.delete() }
        NetworkAccessPolicy.onPackAvailabilityChanged(false)
    }
    fun clearDownloads() { downloads.listFiles()?.forEach { it.delete() } }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
