package hu.app.bmenetrend.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class PlannerRecoveryTest {
    private val time = Instant.parse("2026-11-09T12:00:00Z")
    private val a = PlannerEndpoint("A", "A", "", GeoPoint(47.5, 19.0))
    private val b = PlannerEndpoint("B", "B", "", GeoPoint(47.6, 19.0))
    private fun plan(empty: Boolean = false) = TripPlan(a, b, time, time,
        if (empty) emptyList() else listOf(TripItinerary("saved", time, time.plusSeconds(600), emptyList())),
        offlinePack = "downloaded", offlineValidUntil = "2026-11-10")

    @Test fun liveFailureUsesDownloadedResultWithItsSourceAndPartialFlag() = runBlocking {
        val result = planWithRecovery({ throw IOException("live failed") }, { plan() })
        assertTrue(result.partial)
        assertEquals("downloaded", result.offlinePack)
        assertEquals("2026-11-10", result.offlineValidUntil)
        assertEquals("saved", result.itineraries.single().id)
    }

    @Test fun successfulEmptyLiveAnswerDoesNotTriggerOfflineSearch() = runBlocking {
        val expected = plan(true).copy(offlinePack = null)
        assertSame(expected, planWithRecovery({ expected }, { error("No fallback needed") }))
    }

    @Test fun failedOrEmptyFallbackPreservesOriginalLiveFailure() = runBlocking {
        val failure = IOException("original")
        for (empty in listOf(false, true)) {
            try {
                planWithRecovery({ throw failure }, { if (empty) plan(true) else throw IOException("pack invalid") })
                fail("Unavailable data must not become an empty successful plan")
            } catch (actual: IOException) { assertSame(failure, actual) }
        }
    }

    @Test fun cancellingEitherSearchDoesNotStartOrMaskOtherWork() = runBlocking {
        try {
            planWithRecovery({ throw CancellationException("cancel online") }, { error("Must not start fallback") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        try {
            planWithRecovery({ throw IOException("failed") }, { throw CancellationException("cancel fallback") })
            fail("Expected cancellation")
        } catch (cancelled: CancellationException) { assertEquals("cancel fallback", cancelled.message) }
    }
}
