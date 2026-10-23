package hu.viabusz.app.data

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Photon supports search-as-you-type. Queries are debounced by the caller and cached locally. */
class PlacesRepository(context: Context) {
    private val client = EmmaGraphqlClient.defaultHttpClient()
    private val directory = File(context.cacheDir, "places-v1")
    private val mutex = Mutex()
    private var lastRequest = 0L

    suspend fun search(raw: String): List<PlaceResult> = mutex.withLock {
        val query = PlaceSearch.query(raw)
        val key = MessageDigest.getInstance("SHA-256").digest(TransitSearch.normalize(query).toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(directory, "$key.json")
        val cached = withContext(Dispatchers.IO) { runCatching { if (file.isFile) file.readText() else null }.getOrNull() }
        if (cached != null && System.currentTimeMillis() - file.lastModified() in 0..86_399_999) {
            runCatching { PlaceSearch.parse(cached, query) }.getOrNull()?.let { return@withLock it }
        }
        delay((1000 - (android.os.SystemClock.elapsedRealtime() - lastRequest)).coerceAtLeast(0))
        lastRequest = android.os.SystemClock.elapsedRealtime()
        val url = "https://photon.komoot.io/api/".toHttpUrl().newBuilder().addQueryParameter("q", query)
            .addQueryParameter("limit", "20").addQueryParameter("bbox", "16.1,45.7,22.9,48.6")
            .addQueryParameter("lat", "47.4979").addQueryParameter("lon", "19.0402").build()
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "BMenetrendAndroid/0.3 (Hungarian transit and place search)").build())
        val text = try {
            suspendCancellableCoroutine<String> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                    override fun onResponse(call: Call, response: Response) {
                        try {
                            val text = response.use { if (!it.isSuccessful) throw IOException("Place search HTTP ${it.code}"); it.body?.string() ?: throw IOException("Empty place search") }
                            if (continuation.isActive) continuation.resume(text)
                        } catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                    }
                })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (cached != null) return@withLock PlaceSearch.parse(cached, query); throw error }
        val results = withContext(Dispatchers.Default) { PlaceSearch.parse(text, query) }
        withContext(Dispatchers.IO) {
            runCatching {
                directory.mkdirs()
                val temporary = File(directory, "$key.tmp")
                temporary.writeText(text); if (!temporary.renameTo(file)) temporary.delete()
                directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }?.drop(100)?.forEach { it.delete() }
            }
        }
        results
    }
}
