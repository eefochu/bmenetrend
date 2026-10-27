package hu.app.bmenetrend.offline

import androidx.compose.runtime.Immutable
import hu.app.bmenetrend.map.OfflineBasemapAssets
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

const val OFFLINE_MAX_BYTES = 500_000_000L
const val OFFLINE_FORMAT = 1
// Reserve the bundled map, supporting assets and the native file working copy.
const val OFFLINE_DATABASE_MAX_BYTES = OFFLINE_MAX_BYTES - OfflineBasemapAssets.INSTALLED_BYTES

@Immutable
data class OfflineSource(val id: String, val name: String, val url: String, val licenseUrl: String) {
    companion object {
        val defaults = listOf(
            OfflineSource("volan", "Volán – országos és helyi autóbuszok", "https://gtfs.kti.hu/public-gtfs/volanbusz_gtfs.zip", "https://napportal.kozut.hu/"),
            OfflineSource("bkk", "BKK – Budapest", "https://go.bkk.hu/api/static/v1/public-gtfs/budapest_gtfs.zip", "https://bkk.hu/fejlesztoknek/"),
        )
    }
}

@Immutable
data class OfflinePackMetadata(
    val id: String, val builtAt: Instant, val validFrom: LocalDate, val validUntil: LocalDate,
    val installedBytes: Long, val downloadBytes: Long, val stops: Int, val trips: Int,
    val sources: List<String>, val sha256: String,
    val retainedSourceBytes: Long = 0, val warnings: List<String> = emptyList(),
) {
    val installedFootprintBytes: Long get() = installedBytes + retainedSourceBytes + OfflineBasemapAssets.INSTALLED_BYTES

    fun json() = JSONObject().put("format", OFFLINE_FORMAT).put("id", id).put("builtAt", builtAt.toString())
        .put("validFrom", validFrom.toString()).put("validUntil", validUntil.toString())
        .put("installedBytes", installedBytes).put("downloadBytes", downloadBytes).put("stops", stops).put("trips", trips)
        .put("sources", JSONArray(sources)).put("sha256", sha256).put("retainedSourceBytes", retainedSourceBytes).put("warnings", JSONArray(warnings))
    companion object {
        fun parse(text: String): OfflinePackMetadata {
            val j = JSONObject(text)
            require(j.getInt("format") == OFFLINE_FORMAT) { "Unsupported offline pack" }
            return OfflinePackMetadata(j.getString("id"), Instant.parse(j.getString("builtAt")), LocalDate.parse(j.getString("validFrom")),
                LocalDate.parse(j.getString("validUntil")), j.getLong("installedBytes"), j.getLong("downloadBytes"), j.getInt("stops"),
                j.getInt("trips"), j.getJSONArray("sources").let { a -> (0 until a.length()).map(a::getString) }, j.getString("sha256"), j.optLong("retainedSourceBytes", 0), j.optJSONArray("warnings")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty())
                .also { require(it.stops > 0 && it.trips > 0 && it.validUntil >= it.validFrom && it.installedBytes in 1..OFFLINE_MAX_BYTES && it.downloadBytes in 0..OFFLINE_MAX_BYTES && it.retainedSourceBytes in 0..OFFLINE_MAX_BYTES && it.installedFootprintBytes in 1..OFFLINE_MAX_BYTES) }
        }
    }
}

enum class OfflinePackPhase { INITIALIZING, MISSING, DOWNLOADING, IMPORTING, READY, FAILED }
@Immutable
data class OfflinePackState(
    val metadata: OfflinePackMetadata? = null, val phase: OfflinePackPhase = OfflinePackPhase.INITIALIZING,
    val bytes: Long = 0, val total: Long = 0, val source: String = "", val error: String? = null,
    val wifiOnly: Boolean = true, val automaticUpdates: Boolean = false,
    val importProgress: OfflineImportProgress? = null,
) {
    val busy get() = phase == OfflinePackPhase.DOWNLOADING || phase == OfflinePackPhase.IMPORTING
    val loaded get() = phase != OfflinePackPhase.INITIALIZING
}

class OfflineDateUnavailable : IllegalArgumentException("Date outside downloaded timetable")
class OfflineEndpointUnavailable : IllegalArgumentException("Select a downloaded boarding stop")
