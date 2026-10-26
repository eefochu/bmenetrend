package hu.app.bmenetrend.data

import org.json.JSONArray
import java.net.URI

object AppRepository {
    const val NAME = "eefochu/bmenetrend"
    const val WEB = "https://github.com/$NAME"
    const val RELEASES_API = "https://api.github.com/repos/$NAME/releases?per_page=100"
}

/** Use published releases from the current repository; GitHub Pages is a separate deployment. */
object GitHubUpdates {
    fun parse(text: String,channel: UpdateChannel): OtaUpdateInfo? {
        val releases=JSONArray(text)
        var latest: OtaUpdateInfo? = null
        for(i in 0 until releases.length()) {
            val release=releases.getJSONObject(i)
            val tag=release.optString("tag_name")
            if(release.optBoolean("draft") || channel==UpdateChannel.STABLE &&
                (release.optBoolean("prerelease") || tag.contains('-'))) continue
            if(!tag.matches(Regex("[vV]?[0-9]+(?:\\.[0-9]+){1,3}(?:-[A-Za-z0-9.-]+)?"))) continue
            val assets=release.optJSONArray("assets") ?: continue
            val apk=(0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull {
                it.optString("name")==channel.assetName && it.optString("state","uploaded")=="uploaded" &&
                    it.optLong("size",1)>0 && validAsset(it.optString("browser_download_url"))
            } ?: continue
            val version=tag.removePrefix("v").removePrefix("V")
            val commit=release.optString("target_commitish").takeIf { it.matches(Regex("[a-fA-F0-9]{40}")) }
            val info=OtaUpdateInfo(channel.tag,version,apk.getString("browser_download_url"),
                commit?.let { "${AppRepository.WEB}/commit/$it" },release.optString("body").takeIf { it.isNotBlank() && it!="null" })
            if(latest==null || VersionComparator.isNewer(info.version,latest.version)) latest=info
        }
        return latest
    }
    private fun validAsset(url: String): Boolean = runCatching {
        val uri=URI(url)
        uri.scheme=="https" && uri.host=="github.com" && uri.rawPath.startsWith("/${AppRepository.NAME}/releases/download/")
    }.getOrDefault(false)
}
