package hu.app.bmenetrend.map

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OfflineBasemapTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun assetBytes(path: String): Long = context.assets.list(path).orEmpty().sumOf { name ->
        val child = "$path/$name"
        if (context.assets.list(child).orEmpty().isNotEmpty()) assetBytes(child)
        else context.assets.open(child).use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) { val n = input.read(buffer); if (n < 0) break; total += n }
            total
        }
    }

    private fun sha256(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun bundledArchiveStylesGlyphsAndLicensesMatchGeneratedFootprint() {
        val bundled = assetBytes("map/offline") +
            context.assets.open("map/offline.json").use { it.available().toLong() } +
            context.assets.open("map/offline-dark.json").use { it.available().toLong() }
        assertEquals(35_446_032L, OfflineBasemapAssets.BUNDLED_BYTES)
        assertEquals(34_857_842L, OfflineBasemapAssets.ARCHIVE_BYTES)
        assertEquals(bundled, OfflineBasemapAssets.BUNDLED_BYTES)

        val manifest = context.assets.open("map/offline/manifest.json").bufferedReader().use { JSONObject(it.readText()) }
        val files = manifest.getJSONObject("files")
        for (key in files.keys()) {
            val entry = files.getJSONObject(key)
            val assetPath = "map/$key"
            assertEquals(assetPath, entry.getLong("bytes"), context.assets.open(assetPath).use { it.available().toLong() })
            assertEquals(assetPath, entry.getString("sha256"), sha256(assetPath))
        }
        assertEquals(OfflineBasemapAssets.ARCHIVE_SHA256, sha256("map/offline/hungary.pmtiles"))

        // Hungarian place names use these Unicode ranges: basic Latin plus Latin-1 and Latin Extended-A.
        listOf("0-255", "256-511").forEach { range ->
            val glyph = "map/offline/glyphs/Noto Sans Regular/$range.pbf"
            assertTrue("Missing required glyph range $range", context.assets.open(glyph).use { it.available() > 0 })
        }
        assertTrue(context.assets.open("map/offline/ODbL-1.0.html").use { it.available() > 0 })
        assertTrue(context.assets.open("map/offline/Noto-OFL.txt").use { it.available() > 0 })
    }

    @Test fun lightAndDarkStylesUseLocalVectorTilesAndLocalGlyphs() {
        val light = JSONObject(context.assets.open("map/offline.json").bufferedReader().use { it.readText() })
        val dark = JSONObject(context.assets.open("map/offline-dark.json").bufferedReader().use { it.readText() })
        for (style in listOf(light, dark)) {
            assertEquals("asset://map/offline/glyphs/{fontstack}/{range}.pbf", style.getString("glyphs"))
            val source = style.getJSONObject("sources").getJSONObject("offline-hungary")
            assertEquals("vector", source.getString("type"))
            assertEquals("pmtiles://file://OFFLINE_BASEMAP", source.getString("url"))
            val layers = style.getJSONArray("layers")
            val sourceLayers = (0 until layers.length()).map { layers.getJSONObject(it).optString("source-layer") }.toSet()
            assertTrue(sourceLayers.containsAll(setOf("roads", "places", "land", "water")))
        }
        val lightBackground = light.getJSONArray("layers").getJSONObject(0).getJSONObject("paint").getString("background-color")
        val darkBackground = dark.getJSONArray("layers").getJSONObject(0).getJSONObject("paint").getString("background-color")
        assertTrue(lightBackground != darkBackground)
    }

    @Test fun styleCopiesAndVerifiesArchiveThenDefersDeletionUntilMapRelease() = runBlocking {
        val directory = File(context.noBackupFilesDir, "offline-basemap")
        OfflineBasemap.deleteWorkingCopy(context)
        val lightText = OfflineBasemap.style(context, dark = false)
        val lightSource = JSONObject(lightText).getJSONObject("sources").getJSONObject("offline-hungary")
        val uri = Uri.parse(lightSource.getString("url").removePrefix("pmtiles://"))
        val archive = File(uri.path!!)
        assertEquals(OfflineBasemapAssets.ARCHIVE_BYTES, archive.length())
        assertEquals(OfflineBasemapAssets.ARCHIVE_SHA256, sha256File(archive))
        archive.inputStream().use { input ->
            val header = ByteArray(8)
            assertEquals(8, input.read(header))
            assertEquals("PMTiles", String(header, 0, 7, Charsets.US_ASCII))
            assertEquals(3, header[7].toInt())
        }
        assertTrue(lightText.contains(archive.absolutePath))
        assertFalse(lightText.contains("OFFLINE_BASEMAP"))

        val darkText = OfflineBasemap.style(context, dark = true)
        assertTrue(JSONObject(darkText).getString("name").contains("dark", ignoreCase = true))
        OfflineBasemap.retain()
        try {
            OfflineBasemap.deleteWorkingCopy(context)
            assertTrue("Archive removed while native map is active", archive.exists())
        } finally {
            OfflineBasemap.release(context)
        }
        withTimeout(5_000) {
            while (archive.exists()) delay(25)
        }
        assertFalse(archive.exists())
        assertTrue(directory.isDirectory)
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
