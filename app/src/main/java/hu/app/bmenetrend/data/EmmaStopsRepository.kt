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
class EmmaStopsRepository(context: Context, private val graphql: EmmaGraphqlClient = EmmaGraphqlClient()) {
    private val file = File(context.filesDir, "emma-stop-index-v2.json")
    private var memory: Index? = null
    private val indexMutex = Mutex()
    data class Index(val catalogue: EmmaStopsParser.Catalogue, val fetchedAt: Instant, val stale: Boolean = false)

    suspend fun index(onCached: (Index) -> Unit = {}): Index = indexMutex.withLock {
        val now = Instant.now()
        var saved = memory ?: withContext(Dispatchers.IO) {
            runCatching {
                val cached = if (file.isFile) file else File(file.parentFile, "emma-stop-index-v1.json")
                if (!cached.isFile) null else Index(EmmaStopsParser.index(cached.readText()),
                    if (cached == file) Instant.ofEpochMilli(cached.lastModified()) else Instant.EPOCH)
            }.getOrNull()
        }
        if (saved != null) {
            val fresh = Duration.between(saved.fetchedAt, now).seconds in 0..86_399
            val available = saved.copy(stale = !fresh)
            memory = available
            onCached(available)
            if (fresh) return@withLock available
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

    data class Lines(val catalogue: EmmaStopsParser.Catalogue,val fetchedAt: Instant,val stale: Boolean=false)
    private val lineMemory=linkedMapOf<String,Lines>()
    private val linesMutex=Mutex()
    private val linesDirectory=File(context.filesDir,"emma-station-lines-v1")

    /** Static serving routes have a daily cache and never depend on the selected departure window. */
    suspend fun servingLines(stopId: String,force: Boolean=false): Lines = linesMutex.withLock {
        val now=Instant.now()
        val key=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(stopId.toByteArray(Charsets.UTF_8))
        val cache=File(linesDirectory,"$key.json")
        val saved=lineMemory[stopId] ?: withContext(Dispatchers.IO) {
            runCatching { if(cache.isFile) Lines(EmmaStopsParser.servingLines(cache.readText(),stopId),Instant.ofEpochMilli(cache.lastModified())) else null }.getOrNull()
        }
        if(!force && saved!=null && Duration.between(saved.fetchedAt,now).seconds in 0..86_399) return@withLock saved
        try {
            val text=graphql.execute(EmmaStopsParser.LINES_QUERY,org.json.JSONObject().put("id",EmmaStopsParser.sourceId(stopId)))
            val result=withContext(Dispatchers.Default) { Lines(EmmaStopsParser.servingLines(text,stopId),now) }
            lineMemory[stopId]=result
            while(lineMemory.size>64) lineMemory.remove(lineMemory.keys.first())
            withContext(Dispatchers.IO) { runCatching {
                linesDirectory.mkdirs()
                val temporary=File(linesDirectory,"$key.tmp")
                temporary.writeText(text)
                if(!temporary.renameTo(cache)) temporary.delete()
            } }
            result
        } catch(cancelled: CancellationException) { throw cancelled
        } catch(error: Exception) { saved?.copy(stale=true) ?: throw error }
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
