package hu.app.bmenetrend.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GitHubUpdatesTest {
    private fun release(tag: String, vararg assets: String, pre: Boolean = false, draft: Boolean = false) = JSONObject()
        .put("tag_name",tag).put("prerelease",pre).put("draft",draft).put("body","Changes\nFixed routing")
        .put("target_commitish","a".repeat(40)).put("assets",JSONArray(assets.map { asset ->
            JSONObject().put("name",asset).put("state","uploaded").put("size",1234)
                .put("browser_download_url","${AppRepository.WEB}/releases/download/$tag/$asset")
        }))
    @Test fun organizationReleaseWithBothApksServesEachChannelItsCorrectArtifact() {
        val text=JSONArray().put(release("v0.3.4","app-debug.apk","app-release.apk")).toString()
        for(channel in UpdateChannel.entries) {
            val info=GitHubUpdates.parse(text,channel)!!
            assertEquals(channel.tag,info.tag)
            assertEquals("0.3.4",info.version)
            assertEquals("https://github.com/eefochu/bmenetrend/releases/download/v0.3.4/${channel.assetName}",info.apkUrl)
            assertEquals("${AppRepository.WEB}/commit/${"a".repeat(40)}",info.lastCommit)
            assertTrue(info.changelog!!.contains("Fixed routing"))
            assertEquals(AppRepository.RELEASES_API,channel.url)
        }
    }
    @Test fun stableSkipsDebugAndPrereleasesWhileDebugCanUseThem() {
        val text=JSONArray().put(release("v0.5.0-debug","app-debug.apk",pre=true))
            .put(release("v0.4.0-debug","app-debug.apk","app-release.apk"))
            .put(release("v0.3.4","app-debug.apk","app-release.apk")).toString()
        assertEquals("0.3.4",GitHubUpdates.parse(text,UpdateChannel.STABLE)!!.version)
        assertEquals("0.5.0-debug",GitHubUpdates.parse(text,UpdateChannel.DEBUG)!!.version)
    }
    @Test fun picksNewestVersionEvenWhenOldReleasesWerePublishedMoreRecently() {
        val text=JSONArray().put(release("v0.3.3","app-release.apk")).put(release("v0.3.4","app-release.apk")).toString()
        assertEquals("0.3.4",GitHubUpdates.parse(text,UpdateChannel.STABLE)!!.version)
    }
    @Test fun missingApksDraftsForeignLinksEmptyUploadsAndNonVersionTagsDoNotOfferUpdates() {
        val foreign=release("v0.9.0","app-release.apk").apply {
            getJSONArray("assets").getJSONObject(0).put("browser_download_url","https://github.com/another/app/releases/download/v0.9.0/app-release.apk")
        }
        val empty=release("v0.8.0","app-release.apk").apply { getJSONArray("assets").getJSONObject(0).put("size",0) }
        val text=JSONArray().put(release("nightly","app-release.apk")).put(release("v1.0.0","app-release.apk",draft=true))
            .put(release("v0.7.0","mapping.txt")).put(foreign).put(empty).toString()
        assertNull(GitHubUpdates.parse(text,UpdateChannel.STABLE))
        assertNull(GitHubUpdates.parse("[]",UpdateChannel.DEBUG))
    }
    @Test fun branchNameIsNeverPresentedAsACommitLink() {
        val text=JSONArray().put(release("v0.3.4","app-debug.apk").put("target_commitish","master")).toString()
        assertNull(GitHubUpdates.parse(text,UpdateChannel.DEBUG)!!.lastCommit)
    }
}
