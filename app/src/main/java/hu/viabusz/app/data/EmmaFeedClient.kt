package hu.viabusz.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.IOException
import java.time.Duration
import java.time.Instant

/** One shared nationwide request, cancellation-aware HTTP, and a bounded stale fallback. */
class EmmaFeedClient(
    client: OkHttpClient = EmmaGraphqlClient.defaultHttpClient(),
    private val clock: () -> Instant = Instant::now,
    backoff: EmmaRequestBackoff = EmmaGraphqlClient.sharedBackoff,
) {
    data class Feed(val vehicles: List<Vehicle>, val fetchedAt: Instant, val stale: Boolean = false)
    private val graphql = EmmaGraphqlClient(client, backoff)
    private val mutex = Mutex()
    private var snapshot: Feed? = null

    suspend fun get(force: Boolean = false): Feed = mutex.withLock {
        val cached = snapshot
        if (!force && cached != null && Duration.between(cached.fetchedAt, clock()).toMillis() in 0..999) return@withLock cached
        var failure: Exception? = null
        for (attempt in 0..1) {
            try {
                val body = graphql.execute(EmmaParser.QUERY)
                val now = clock()
                return@withLock Feed(withContext(Dispatchers.Default) { EmmaParser.parse(body, now) }, now).also { snapshot = it }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                failure = error
                if (error is EmmaCooldownException || error is EmmaHttpException && error.retryAt != null) break
            }
        }
        if (cached != null && Duration.between(cached.fetchedAt, clock()).seconds in 0..120)
            return@withLock cached.copy(stale = true)
        throw IOException("EMMA vehicle feed unavailable", failure)
    }

}
