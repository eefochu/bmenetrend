package hu.app.bmenetrend.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Duration
import java.time.Instant

/** Static national stop index cached on disk; departure boards always query the selected platform. */
class EmmaStopsRepository(context: Context) {
    private val graphql = EmmaGraphqlClient()
    private val file = File(context.filesDir, "emma-stop-index-v2.json")
    private var memory: Index? = null
    private val indexMutex = Mutex()
    data class Index(val catalogue: EmmaStopsParser.Catalogue, val fetchedAt: Instant, val stale: Boolean = false)

    suspend fun index(): Index = indexMutex.withLock {
        val now = Instant.now()
        var saved = memory ?: withContext(Dispatchers.IO) {
            runCatching {
                val cached = if (file.isFile) file else File(file.parentFile, "emma-stop-index-v1.json")
                if (!cached.isFile) null else Index(EmmaStopsParser.index(cached.readText()),
                    if (cached == file) Instant.ofEpochMilli(cached.lastModified()) else Instant.EPOCH)
            }.getOrNull()
        }
        if (saved != null && Duration.between(saved.fetchedAt, now).seconds in 0..86_399) {
            memory = saved
            return@withLock saved
        }
        try {
            val text = graphql.execute(EmmaStopsParser.INDEX_QUERY)
            val catalogue = withContext(Dispatchers.Default) { EmmaStopsParser.index(text) }
            val result = Index(catalogue, now)
            memory = result
            withContext(Dispatchers.IO) {
                runCatching {
                    val temporary = File(file.parentFile, "${file.name}.tmp")
                    temporary.bufferedWriter().use { it.write(text) }
                    if (!temporary.renameTo(file)) temporary.delete()
                }
            }
            result
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: Exception) {
            saved = saved ?: memory
            saved?.copy(stale = true)?.also { memory = it } ?: throw failure
        }
    }

    suspend fun board(stopId: String, windowMinutes: Int): EmmaStopsParser.Board {
        val now = Instant.now()
        val variables = org.json.JSONObject().put("id", EmmaStopsParser.sourceId(stopId))
            .put("start", now.epochSecond).put("range", windowMinutes.coerceIn(30, 300) * 60)
        val text = graphql.execute(EmmaStopsParser.BOARD_QUERY, variables)
        return withContext(Dispatchers.Default) { EmmaStopsParser.board(text, stopId, now, windowMinutes) }
    }

    suspend fun boards(stopIds: List<String>, windowMinutes: Int): Map<String, EmmaStopsParser.Board?> {
        val ids = stopIds.distinct()
        require(ids.size in 1..6)
        val now = Instant.now()
        val query = EmmaStopsParser.batchQuery(ids.size)
        val variables = org.json.JSONObject().put("start", now.epochSecond).put("range", windowMinutes.coerceIn(30,300) * 60)
        ids.forEachIndexed { index, id -> variables.put("id$index", EmmaStopsParser.sourceId(id)) }
        val text = graphql.execute(query, variables)
        return withContext(Dispatchers.Default) { EmmaStopsParser.batch(text, ids, now, windowMinutes) }
    }

}
