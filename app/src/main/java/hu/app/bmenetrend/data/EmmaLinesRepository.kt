package hu.app.bmenetrend.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.Duration

/** The static catalogue makes lines searchable even when no vehicle is currently tracked. */
class EmmaLinesRepository(context: Context) {
    private val graphql = EmmaGraphqlClient()
    private val file = File(context.filesDir, "emma-lines-v1.json")
    private val mutex = Mutex()
    data class Catalogue(val routes: List<TransitRoute>, val fetchedAt: Instant, val stale: Boolean = false)
    private var memory: Catalogue? = null
    suspend fun get(): Catalogue = mutex.withLock {
        val now = Instant.now()
        val saved = memory ?: withContext(Dispatchers.IO) { runCatching {
            if (file.isFile) Catalogue(EmmaRouteParser.catalogue(file.readText()), Instant.ofEpochMilli(file.lastModified())) else null
        }.getOrNull() }
        if (saved != null && Duration.between(saved.fetchedAt, now).seconds in 0..86_399) return@withLock saved.also { memory = it }
        try {
            val text = graphql.execute(EmmaRouteParser.CATALOGUE_QUERY)
            val routes = withContext(Dispatchers.Default) { EmmaRouteParser.catalogue(text) }
            withContext(Dispatchers.IO) { runCatching {
                val temporary = File(file.parentFile, "${file.name}.tmp")
                temporary.writeText(text)
                if (!temporary.renameTo(file)) temporary.delete()
            } }
            Catalogue(routes, now).also { memory = it }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { saved?.copy(stale = true)?.also { memory = it } ?: throw error }
    }
}
