package hu.app.bmenetrend.data

import androidx.compose.runtime.Immutable
import hu.app.bmenetrend.R

enum class UpdateChannel(
    val key: String,
    val tag: String,
    val labelRes: Int,
    val descriptionRes: Int,
    val assetName: String,
    val url: String
) {
    STABLE(
        key = "stable",
        tag = "STABLE",
        labelRes = R.string.ota_channel_stable,
        descriptionRes = R.string.ota_channel_stable_desc,
        assetName = "app-release.apk",
        url = AppRepository.RELEASES_API
    ),
    DEBUG(
        key = "debug",
        tag = "DEBUG",
        labelRes = R.string.ota_channel_debug,
        descriptionRes = R.string.ota_channel_debug_desc,
        assetName = "app-debug.apk",
        url = AppRepository.RELEASES_API
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
