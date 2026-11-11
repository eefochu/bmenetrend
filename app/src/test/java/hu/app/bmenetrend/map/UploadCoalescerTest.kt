package hu.app.bmenetrend.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadCoalescerTest {
    @Test fun firstRequestAfterQuietPeriodUsesQuietDelay() {
        var now = 10_000L
        val coalescer = UploadCoalescer { now }
        assertEquals(UploadCoalescer.QUIET_DELAY_MS, coalescer.delayMillis())
        coalescer.uploaded()
        now += UploadCoalescer.BURST_WINDOW_MS
        assertEquals(UploadCoalescer.QUIET_DELAY_MS, coalescer.delayMillis())
    }

    @Test fun requestShortlyAfterUploadUsesBurstDelay() {
        var now = 10_000L
        val coalescer = UploadCoalescer { now }
        coalescer.uploaded()
        now += 1
        assertEquals(UploadCoalescer.BURST_DELAY_MS, coalescer.delayMillis())
    }

    @Test fun repeatedRestartsKeepTheOriginalPendingDeadline() {
        var now = 10_000L
        val coalescer = UploadCoalescer { now }
        coalescer.uploaded()
        val started = now
        for (elapsed in listOf(0L, 400L, 800L, 1_200L, 1_600L, 1_999L)) {
            now = started + elapsed
            val delay = coalescer.delayMillis()
            assertTrue(delay >= 0)
            assertTrue(elapsed + delay <= UploadCoalescer.MAX_WAIT_MS)
        }
        assertEquals(1L, coalescer.delayMillis())
    }

    @Test fun skippedStartsAFreshPendingWait() {
        var now = 10_000L
        val coalescer = UploadCoalescer { now }
        coalescer.uploaded()
        coalescer.delayMillis()
        now += 1_600
        assertEquals(400L, coalescer.delayMillis())
        coalescer.skipped()
        assertEquals(UploadCoalescer.BURST_DELAY_MS, coalescer.delayMillis())
    }
}
