package hu.app.bmenetrend.offline

import androidx.work.NetworkType
import androidx.work.WorkInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OfflinePackPipelineTest {
    @Test fun completedAndCancelledWorkersReleaseTheBusyState() {
        assertEquals(OfflinePackPhase.IMPORTING, offlinePackPhase(WorkInfo.State.RUNNING, true, false))
        assertEquals(OfflinePackPhase.READY, offlinePackPhase(WorkInfo.State.SUCCEEDED, true, true))
        assertEquals(OfflinePackPhase.FAILED, offlinePackPhase(WorkInfo.State.FAILED, true, true))
        assertEquals(OfflinePackPhase.READY, offlinePackPhase(WorkInfo.State.CANCELLED, true, true))
        assertEquals(OfflinePackPhase.MISSING, offlinePackPhase(WorkInfo.State.CANCELLED, true, false))
    }

    @Test fun wifiConstraintOnlyAppliesToDownloadingSoIndexingSurvivesConnectionLoss() {
        for ((wifiOnly, expected) in listOf(true to NetworkType.UNMETERED, false to NetworkType.NOT_REQUIRED)) {
            val (download, index) = OfflinePackRepository.downloadRequests(wifiOnly)
            assertEquals(expected, download.workSpec.constraints.requiredNetworkType)
            assertEquals(NetworkType.NOT_REQUIRED, index.workSpec.constraints.requiredNetworkType)
            assertEquals("download", download.workSpec.input.getString("stage"))
            assertEquals("index", index.workSpec.input.getString("stage"))
        }
    }

    @Test fun parallelDownloadAccountingEnforcesTheCombinedBudget() {
        val progress = DownloadTotals(10)
        assertEquals(100L to 0L, progress.update("volan", 90, 100))
        assertEquals(150L to 310L, progress.update("bkk", 50, 200))
        try {
            progress.update("bkk", OFFLINE_MAX_BYTES, OFFLINE_MAX_BYTES)
            fail("Combined input size must be bounded")
        } catch (_: IllegalArgumentException) { }
    }
}
