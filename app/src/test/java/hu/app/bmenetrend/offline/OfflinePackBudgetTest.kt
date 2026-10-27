package hu.app.bmenetrend.offline

import hu.app.bmenetrend.map.OfflineBasemapAssets
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class OfflinePackBudgetTest {
    private val date = LocalDate.of(2026, 10, 27)

    private fun metadata(databaseBytes: Long, retainedBytes: Long = 0) = OfflinePackMetadata(
        id = "budget-fixture", builtAt = Instant.EPOCH, validFrom = date, validUntil = date,
        installedBytes = databaseBytes, downloadBytes = 1, stops = 1, trips = 1,
        sources = listOf("fixture"), sha256 = "0".repeat(64), retainedSourceBytes = retainedBytes,
    )

    @Test fun reservesBundledAndWorkingBasemapWithinTheFiveHundredMegabyteLimit() {
        assertEquals(35_446_032L, OfflineBasemapAssets.BUNDLED_BYTES)
        assertEquals(34_857_842L, OfflineBasemapAssets.ARCHIVE_BYTES)
        assertEquals(70_303_874L, OfflineBasemapAssets.INSTALLED_BYTES)
        assertEquals(429_696_126L, OFFLINE_DATABASE_MAX_BYTES)
        assertEquals(500_000_000L, OFFLINE_MAX_BYTES)

        val currentPack = metadata(388_014_080L)
        assertEquals(458_317_954L, currentPack.installedFootprintBytes)
        assertEquals(currentPack, OfflinePackMetadata.parse(currentPack.json().toString()))
    }

    @Test fun databaseAndRetainedArchivesShareTheReservedDatabaseAllowance() {
        val maximum = metadata(OFFLINE_DATABASE_MAX_BYTES)
        assertEquals(OFFLINE_MAX_BYTES, maximum.installedFootprintBytes)
        assertEquals(maximum, OfflinePackMetadata.parse(maximum.json().toString()))

        val withRetainedSource = metadata(OFFLINE_DATABASE_MAX_BYTES - 123_456, retainedBytes = 123_456)
        assertEquals(OFFLINE_MAX_BYTES, withRetainedSource.installedFootprintBytes)
        assertEquals(withRetainedSource, OfflinePackMetadata.parse(withRetainedSource.json().toString()))

        assertRejected(metadata(OFFLINE_DATABASE_MAX_BYTES + 1))
        assertRejected(metadata(OFFLINE_DATABASE_MAX_BYTES, retainedBytes = 1))
        assertRejected(metadata(Long.MAX_VALUE))
        assertRejected(metadata(1, retainedBytes = Long.MAX_VALUE))
    }

    private fun assertRejected(value: OfflinePackMetadata) {
        assertTrue(runCatching { OfflinePackMetadata.parse(value.json().toString()) }.isFailure)
    }
}
