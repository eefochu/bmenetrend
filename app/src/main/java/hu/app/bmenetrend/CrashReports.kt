package hu.app.bmenetrend

import java.io.File
import java.time.Instant

/** One private, local report. Nothing is uploaded; the user explicitly chooses whether to copy it. */
internal class CrashReports(private val file: File) {
    fun record(thread: Thread, error: Throwable, details: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.printWriter().use { writer ->
                writer.println("BMenetrend $details")
                writer.println("Time: ${Instant.now()}")
                writer.println("Thread: ${thread.name}")
                error.printStackTrace(writer)
            }
        }
    }

    fun read(): String? = runCatching {
        if (!file.isFile) null else file.bufferedReader().use { reader ->
            val buffer = CharArray(64_000)
            val count = reader.read(buffer)
            if (count <= 0) null else String(buffer, 0, count)
        }
    }.getOrNull()

    fun clear() { runCatching { file.delete() } }

    fun recordProcessExit(details: String) {
        runCatching { file.writeText("BMenetrend: previous Android process exit (no Java stack available)\n$details") }
    }

    fun install(details: String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { record(thread, error, details) }
            finally { previous.uncaughtException(thread, error) }
        }
    }
}
