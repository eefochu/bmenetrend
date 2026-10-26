package hu.app.bmenetrend.data

import org.junit.Assert.*
import org.junit.Test

class OtaUpdateTest {

    @Test
    fun versionComparatorCorrectlyComparesVersions() {
        // Newer versions
        assertTrue(VersionComparator.isNewer("0.3.1", "0.3.0"))
        assertTrue(VersionComparator.isNewer("0.4.0", "0.3.0"))
        assertTrue(VersionComparator.isNewer("1.0.0", "0.3.0"))
        assertTrue(VersionComparator.isNewer("v0.3.1", "0.3.0"))
        assertTrue(VersionComparator.isNewer("0.3.1-debug", "0.3.0"))
        assertTrue(VersionComparator.isNewer("0.3.0.1", "0.3.0"))

        // Equal versions
        assertFalse(VersionComparator.isNewer("0.3.0", "0.3.0"))
        assertFalse(VersionComparator.isNewer("v0.3.0", "0.3.0"))
        assertFalse(VersionComparator.isNewer("0.3.0-debug", "0.3.0"))
        assertFalse(VersionComparator.isNewer("0.3", "0.3.0"))

        // Older versions
        assertFalse(VersionComparator.isNewer("0.2.9", "0.3.0"))
        assertFalse(VersionComparator.isNewer("0.1.5", "0.3.0"))
        assertFalse(VersionComparator.isNewer("0.3.0", "0.3.1"))
        assertFalse(VersionComparator.isNewer("0.3.0", "1.0.0"))
    }

    @Test
    fun otaRepositoryParsesDebugJsonCorrectly() {
        val json = """
            {
              "tag": "DEBUG",
              "version": "0.3.0",
              "apk": "https://github.com/eefochu/bmenetrend/releases/download/v0.3.0-debug/app-debug.apk",
              "lastCommit": "https://github.com/eefochu/bmenetrend/commit/da014fc48142ef2d210d3314848135e379cc8654",
              "changelog": "Whatever bence fixed or improved.\nAnd whatever comes here"
            }
        """.trimIndent()

        val info = OtaRepository.parseJson(json)
        assertEquals("DEBUG", info.tag)
        assertEquals("0.3.0", info.version)
        assertEquals("https://github.com/eefochu/bmenetrend/releases/download/v0.3.0-debug/app-debug.apk", info.apkUrl)
        assertEquals("https://github.com/eefochu/bmenetrend/commit/da014fc48142ef2d210d3314848135e379cc8654", info.lastCommit)
        assertTrue(info.changelog?.contains("Whatever bence fixed") == true)
    }

    @Test
    fun otaRepositoryParsesStableJsonWithNaApkCorrectly() {
        val json = """
            {
              "tag": "STABLE",
              "version": "0.3.0",
              "apk": "N/A",
              "changelog": "Whatever bence fixed or improved.\nAnd whatever comes here"
            }
        """.trimIndent()

        val info = OtaRepository.parseJson(json)
        assertEquals("STABLE", info.tag)
        assertEquals("0.3.0", info.version)
        assertNull(info.apkUrl)
        assertNull(info.lastCommit)
        assertTrue(info.changelog?.contains("Whatever bence fixed") == true)
    }

    @Test
    fun updateChannelFromKeyReturnsExpectedValues() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromKey("stable"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromKey("STABLE"))
        assertEquals(UpdateChannel.DEBUG, UpdateChannel.fromKey("debug"))
        assertEquals(UpdateChannel.DEBUG, UpdateChannel.fromKey("DEBUG"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromKey("unknown"))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.fromKey(null))
    }
}
