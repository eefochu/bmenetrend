package hu.app.bmenetrend.data

import hu.app.bmenetrend.offline.NetworkAccessPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A Worker/BKK error envelope, an HTTP failure or the client's own request budget. */
class BkkWorkerException(val status: Int, val retryAt: Instant?, message: String) : IOException(message)

/**
 * Client-side share of the Worker's per-IP limits (120 requests and 12 plans per minute).
 * Mobile carriers put many phones behind one address, so the app keeps well below them and
 * falls back to EMMA instead of queueing. A 429/Retry-After pauses every Worker request.
 */
internal class BkkRequestBudget(private val perMinute: Int = 60, private val plansPerMinute: Int = 8) {
    private val general = ArrayDeque<Instant>()
    private val plans = ArrayDeque<Instant>()
    private var until: Instant? = null

    @Synchronized fun acquire(now: Instant, plan: Boolean) {
        until?.takeIf { it > now }?.let { throw BkkWorkerException(429, it, "BKK proxy is cooling down") }
        prune(general, now); prune(plans, now)
        if (general.size >= perMinute) throw BkkWorkerException(429, general.first().plusSeconds(60), "BKK proxy budget used")
        if (plan && plans.size >= plansPerMinute) throw BkkWorkerException(429, plans.first().plusSeconds(60), "BKK planner budget used")
        general.addLast(now)
        if (plan) plans.addLast(now)
    }

    @Synchronized fun cooldown(now: Instant, retryAfter: String?) {
        val seconds = retryAfter?.trim()?.toLongOrNull()?.coerceIn(1, 300) ?: 60
        val retry = now.plusSeconds(seconds)
        until = maxOf(until ?: retry, retry)
    }

    @Synchronized fun reset() { general.clear(); plans.clear(); until = null }

    /** Planner calls still available this minute; optional work (walking links) leaves room for real plans. */
    @Synchronized fun planHeadroom(now: Instant): Int {
        if (until?.let { it > now } == true) return 0
        prune(general, now); prune(plans, now)
        return minOf(plansPerMinute - plans.size, perMinute - general.size).coerceAtLeast(0)
    }

    private fun prune(queue: ArrayDeque<Instant>, now: Instant) {
        while (queue.isNotEmpty() && Duration.between(queue.first(), now).seconds >= 60) queue.removeFirst()
    }
}

/**
 * Keyless GET client for the BKK-first Cloudflare Worker. The BKK API key lives only in the
 * Worker secret; this client never sends or stores one. Identical requests share one call and
 * successful bodies are reused for the Worker's own cache lifetime, so polling faster than the
 * source cadence costs nothing upstream.
 */
class BkkWorkerClient internal constructor(
    private val baseUrl: HttpUrl,
    client: OkHttpClient,
    private val clock: () -> Instant,
    private val budget: BkkRequestBudget,
) {
    constructor() : this(hu.app.bmenetrend.BuildConfig.BKK_WORKER_URL.toHttpUrl(), EmmaGraphqlClient.defaultHttpClient(), Instant::now, sharedBudget)

    // Never follow a redirect away from the configured Worker host.
    private val transport = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val inFlight = EmmaInFlightRequests()
    private class Cached(val at: Instant, val text: String)
    /** A Worker body and when this client received it; a cache hit keeps its original time. */
    class Fetched(val text: String, val fetchedAt: Instant)
    // Access-ordered and bounded by total characters: a viewport's vehicles are ~350 KB of JSON.
    private val cache = LinkedHashMap<String, Cached>(16, .75f, true)
    private var cachedChars = 0L

    private fun store(key: String, fetched: Fetched) = synchronized(cache) {
        val text = fetched.text
        cache.put(key, Cached(fetched.fetchedAt, text))?.let { cachedChars -= it.text.length }
        cachedChars += text.length
        val iterator = cache.entries.iterator()
        while ((cachedChars > MAX_CACHED_CHARS || cache.size > 32) && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.key == key) continue
            cachedChars -= eldest.value.text.length
            iterator.remove()
        }
    }

    suspend fun get(endpoint: String, params: Map<String, String>): String = fetch(endpoint, params).text

    fun planHeadroom(): Int = budget.planHeadroom(clock())

    suspend fun fetch(endpoint: String, params: Map<String, String>): Fetched {
        val ttl = requireNotNull(TTL_MILLIS[endpoint]) { "Unsupported Worker endpoint" }
        NetworkAccessPolicy.requireOnline()
        val generation = NetworkAccessPolicy.generation
        val url = baseUrl.newBuilder().addPathSegment("api").addPathSegment("$endpoint.json").apply {
            params.toSortedMap().forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        val key = url.toString()
        val startedAt = System.nanoTime()
        cached(key, ttl)?.let { trace(endpoint, startedAt, "cache ${it.text.length}"); return it }
        return try {
            val text = inFlight.execute(key to generation) {
                cached(key, ttl)?.text ?: run {
                    budget.acquire(clock(), endpoint == "plan-trip")
                    execute(url, generation).also { text ->
                        // Large area responses are not worth pinning in memory.
                        if (ttl > 0 && text.length <= 600_000) store(key, Fetched(text, clock()))
                    }
                }
            }
            // Shared callers read the receipt time recorded by the call that fetched the body.
            (cached(key, ttl)?.takeIf { it.text === text } ?: Fetched(text, clock())).also { trace(endpoint, startedAt, "ok ${it.text.length}") }
        } catch (error: Exception) { trace(endpoint, startedAt, error.toString()); throw error }
    }

    /** Debug-only latency trace (`adb logcat -s BkkHttp`), the measurement for cache vs network time. */
    private fun trace(endpoint: String, startedAt: Long, outcome: String) {
        hu.app.bmenetrend.TransitDiagnostics.event("http") { "$endpoint ms=${(System.nanoTime() - startedAt) / 1_000_000} outcome=$outcome" }
        if (!hu.app.bmenetrend.BuildConfig.DEBUG) return
        runCatching { android.util.Log.d("BkkHttp", "$endpoint ${(System.nanoTime() - startedAt) / 1_000_000}ms $outcome") }
    }

    private fun cached(key: String, ttl: Long): Fetched? = if (ttl <= 0) null else synchronized(cache) {
        cache[key]?.takeIf { Duration.between(it.at, clock()).toMillis() in 0 until ttl }?.let { Fetched(it.text, it.at) }
    }

    private suspend fun execute(url: HttpUrl, generation: Long): String {
        if (generation != NetworkAccessPolicy.generation) throw IOException("Network mode changed")
        val request = Request.Builder().url(url).get().header("Accept", "application/json")
            .header("User-Agent", "BMenetrendAndroid/0.3 (native transit viewer)").build()
        val call = NetworkAccessPolicy.protect(transport).newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use { reply ->
                            if (reply.code == 429) {
                                budget.cooldown(clock(), reply.header("Retry-After"))
                                throw BkkWorkerException(429, clock().plusSeconds(reply.header("Retry-After")?.toLongOrNull() ?: 60), "BKK proxy rate limited")
                            }
                            if (!reply.isSuccessful) throw BkkWorkerException(reply.code, null, "BKK proxy HTTP ${reply.code}")
                            reply.body?.string() ?: throw IOException("Empty BKK proxy response")
                        }
                        // API errors can arrive as HTTP 200 envelopes; never cache or parse those as data.
                        if (!OK_ENVELOPE.containsMatchIn(text.take(400))) throw BkkWorkerException(502, null, "BKK proxy returned an error")
                        if (generation != NetworkAccessPolicy.generation) throw IOException("Network mode changed")
                        if (continuation.isActive) continuation.resume(text)
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
            })
        }
    }

    companion object {
        internal val sharedBudget = BkkRequestBudget()
        private const val MAX_CACHED_CHARS = 1_500_000L
        private val OK_ENVELOPE = Regex("\"status\"\\s*:\\s*\"OK\"")
        /** The Worker's own cache lifetimes (README); there is nothing newer to fetch sooner. */
        internal val TTL_MILLIS = mapOf(
            "vehicles-for-location" to 5_000L, "vehicles" to 5_000L, "vehicles-for-route" to 5_000L,
            "arrivals-and-departures-for-stop" to 10_000L, "arrivals-and-departures-for-stops" to 10_000L,
            "trip-details" to 30_000L, "route-details" to 30_000L, "route-details-for-stop" to 30_000L,
            "references" to 300_000L, "search" to 10_000L, "stops-for-location" to 300_000L, "plan-trip" to 5_000L,
            "stop-directions-for-location" to 3_600_000L,
        )
    }
}
