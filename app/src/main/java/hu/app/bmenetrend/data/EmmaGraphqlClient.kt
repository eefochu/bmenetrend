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
    suspend fun execute(query: String, variables: JSONObject = JSONObject()): String {
        backoff.check(Instant.now())
        val body = JSONObject().put("query", query).put("variables", variables).toString()
        val request = Request.Builder().url(EmmaParser.ENDPOINT).header("Accept", "application/json")
            .header("User-Agent", "BMenetrendAndroid/0.3 (native transit viewer)")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            if (!it.isSuccessful) throw EmmaHttpException(it.code, backoff.denied(it.code, it.header("Retry-After"), Instant.now()))
                            it.body?.string() ?: throw IOException("Empty EMMA response")
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (error: Exception) { continuation.resumeWithException(error) }
                }
            })
        }
    }
    companion object {
        internal val sharedBackoff = EmmaRequestBackoff()
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
        fun data(text: String): JSONObject = JSONObject(text).let {
            require((it.optJSONArray("errors")?.length() ?: 0) == 0) { "EMMA GraphQL error" }
            it.getJSONObject("data")
        }
    }
}
