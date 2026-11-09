package hu.app.bmenetrend.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared EMMA transport for vehicles, stops and departure boards. */
class EmmaGraphqlClient(private val client: OkHttpClient = defaultHttpClient(), private val backoff: EmmaRequestBackoff = sharedBackoff) {
    private data class RequestKey(val client: OkHttpClient, val backoff: EmmaRequestBackoff, val body: String, val generation: Long)
    // Avoid automatic retries of 503/Retry-After: 0 before recording the denial.
    private val transportClient = client.newBuilder().retryOnConnectionFailure(false).build()
    private val queryBackoffs = object : LinkedHashMap<String, EmmaRequestBackoff>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EmmaRequestBackoff>?): Boolean = size > 32
    }

    suspend fun execute(query: String, variables: JSONObject = JSONObject()): String {
        hu.app.bmenetrend.offline.NetworkAccessPolicy.requireOnline()
        val networkGeneration = hu.app.bmenetrend.offline.NetworkAccessPolicy.generation
        backoff.check(Instant.now())
        val queryBackoff = synchronized(queryBackoffs) { queryBackoffs.getOrPut(query) { EmmaRequestBackoff() } }
        queryBackoff.check(Instant.now())
        val body = JSONObject().put("query", query).put("variables", variables).toString()
        val startedAt = System.nanoTime()
        return try {
            inFlight.execute(RequestKey(client, backoff, body, networkGeneration)) {
                backoff.check(Instant.now())
                queryBackoff.check(Instant.now())
                executeRequest(body, networkGeneration, queryBackoff)
            }.also { trace(query, startedAt, "ok ${it.length}") }
        } catch (error: Exception) { trace(query, startedAt, error.toString()); throw error }
    }

    private fun trace(query: String, startedAt: Long, outcome: String) {
        if (!hu.app.bmenetrend.BuildConfig.DEBUG) return
        runCatching { android.util.Log.d("EmmaHttp", "${query.take(28).replace('\n', ' ')} ${(System.nanoTime() - startedAt) / 1_000_000}ms $outcome") }
    }

    private suspend fun executeRequest(body: String, networkGeneration: Long, queryBackoff: EmmaRequestBackoff): String {
        if (networkGeneration != hu.app.bmenetrend.offline.NetworkAccessPolicy.generation) throw IOException("Network mode changed")
        val request = Request.Builder().url(EmmaParser.ENDPOINT).header("Accept", "application/json")
            .header("User-Agent", "BMenetrendAndroid/0.3 (native transit viewer)")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val call = hu.app.bmenetrend.offline.NetworkAccessPolicy.protect(transportClient).newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            if (!it.isSuccessful) {
                                // 429 explicitly limits traffic globally. A 403 or 503 for a
                                // particular operation does not prove that planning is unavailable.
                                val policy = if (it.code == 429) backoff else queryBackoff
                                throw EmmaHttpException(it.code, policy.denied(it.code, it.header("Retry-After"), Instant.now()))
                            }
                            it.body?.string() ?: throw IOException("Empty EMMA response")
                        }
                        backoff.succeeded(); queryBackoff.succeeded()
                        if (networkGeneration != hu.app.bmenetrend.offline.NetworkAccessPolicy.generation) throw IOException("Network mode changed")
                        if (continuation.isActive) continuation.resume(text)
                    } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                }
            })
        }
    }
    companion object {
        internal val sharedBackoff = EmmaRequestBackoff()
        private val inFlight = EmmaInFlightRequests()
        // Repositories and MapLibre share connections, TLS sessions and dispatcher threads.
        // Callers needing different timeouts use newBuilder(), which retains these pools.
        private val sharedHttpClient by lazy {
            val dispatcher = Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 16
            }
            val connectionPool = ConnectionPool(8, 2, TimeUnit.MINUTES)
            hu.app.bmenetrend.offline.NetworkAccessPolicy.protect(
                OkHttpClient.Builder()
                    .dispatcher(dispatcher)
                    .connectionPool(connectionPool)
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .callTimeout(25, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()
            )
        }
        fun defaultHttpClient(): OkHttpClient = sharedHttpClient
        private val sharedPlannerHttpClient by lazy {
            // Share sockets/TLS, but keep live-map traffic out of routing's queue.
            defaultHttpClient().newBuilder().dispatcher(Dispatcher().apply {
                maxRequests = 4
                maxRequestsPerHost = 4
            }).readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
        }
        fun plannerHttpClient(): OkHttpClient = sharedPlannerHttpClient
        fun data(text: String): JSONObject = JSONObject(text).let {
            require((it.optJSONArray("errors")?.length() ?: 0) == 0) { "EMMA GraphQL error" }
            it.getJSONObject("data")
        }
    }
}
