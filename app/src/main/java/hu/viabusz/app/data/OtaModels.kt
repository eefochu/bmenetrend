package hu.viabusz.app.data

import androidx.compose.runtime.Immutable
import hu.viabusz.app.R

enum class UpdateChannel(
    val key: String,
    val tag: String,
    val labelRes: Int,
    val descriptionRes: Int,
    val url: String
) {
    STABLE(
        key = "stable",
        tag = "STABLE",
        labelRes = R.string.ota_channel_stable,
        descriptionRes = R.string.ota_channel_stable_desc,
        url = "https://micr0softstore.github.io/ota/bmenetrend/stable.json"
    ),
    DEBUG(
        key = "debug",
        tag = "DEBUG",
        labelRes = R.string.ota_channel_debug,
        descriptionRes = R.string.ota_channel_debug_desc,
        url = "https://micr0softstore.github.io/ota/bmenetrend/debug.json"
    );

    companion object {
        fun fromKey(key: String?): UpdateChannel =
            entries.find { it.key.equals(key, ignoreCase = true) || it.tag.equals(key, ignoreCase = true) } ?: STABLE
    }
}

@Immutable
data class OtaUpdateInfo(
    val tag: String,
    val version: String,
    val apkUrl: String?,
    val lastCommit: String?,
    val changelog: String?
)
