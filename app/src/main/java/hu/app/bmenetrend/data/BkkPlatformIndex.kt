package hu.app.bmenetrend.data

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository and in-memory cache for BKK platform codes (e.g. "A1", "A4", "E4", "B1", "C2").
 * Loaded lazily from assets/bkk-platform-codes.json, with support for runtime additions.
 */
object BkkPlatformIndex {
    private val memoryCache = ConcurrentHashMap<String, String>()
    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            try {
                context.assets.open("bkk-platform-codes.json").use { stream ->
                    val text = stream.bufferedReader().readText()
                    val json = JSONObject(text)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = json.getString(key)
                        if (value.isNotBlank()) {
                            memoryCache[key] = value
                        }
                    }
                }
                initialized = true
            } catch (_: Exception) {
                // If asset is not found or fails in isolated tests, continue gracefully
            }
        }
    }

    /**
     * Resolves the platform code for any stop or departure.
     * Preserves existing non-blank platformCode (e.g. MÁV tracks, Volán bays).
     * If existingPlatformCode is null or blank, looks up in the BKK platform index.
     */
    fun resolve(rawStopId: String?, existingPlatformCode: String? = null): String? {
        if (!existingPlatformCode.isNullOrBlank()) return existingPlatformCode
        return getPlatformCode(rawStopId)
    }

    fun getPlatformCode(rawStopId: String?): String? {
        if (rawStopId.isNullOrBlank()) return null
        val direct = memoryCache[rawStopId]
        if (direct != null) return direct

        // Normalize various stop ID schemes:
        // "emma:BKK:093021", "BKK:093021", "BKK_093021", "093021-773735_1", "093021_1", etc.
        val clean = rawStopId
            .removePrefix("emma:")
            .removePrefix("BKK:")
            .removePrefix("bkk:")
            .removePrefix("BKK_")
            .removePrefix("bkk_")
            .substringBefore("-")
            .substringBefore("_")

        return memoryCache[clean]
            ?: memoryCache["BKK_$clean"]
            ?: memoryCache["BKK:$clean"]
    }

    fun put(stopId: String, platformCode: String) {
        if (stopId.isNotBlank() && platformCode.isNotBlank()) {
            memoryCache[stopId] = platformCode
            val clean = stopId
                .removePrefix("emma:")
                .removePrefix("BKK:")
                .removePrefix("BKK_")
                .substringBefore("-")
                .substringBefore("_")
            memoryCache[clean] = platformCode
        }
    }
}
