package hu.app.bmenetrend.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import hu.app.bmenetrend.offline.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "hu-rHU-w400dp-h1200dp")
class OfflinePackSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val meta = OfflinePackMetadata("fixture", Instant.parse("2026-10-26T12:00:00Z"), LocalDate.of(2026,10,26), LocalDate.of(2026,11,24), 1000, 500, 3, 2, listOf("Fixture"), "hash")
    @Test fun missingPackCannotEnableOfflineMode() {
        var toggled = false
        compose.setContent { ViaBuszTheme { androidx.compose.foundation.layout.Column {
            OfflinePackSettings(UiState(offlineMode = false), { toggled = true }, {}, {}, {}, {})
        } } }
        compose.onNodeWithText("Az offline mód bekapcsolásához előbb töltsd le a csomagot.").assertExists()
        compose.onNode(hasText("Offline mód") and hasClickAction()).assertIsNotEnabled()
        assertFalse(toggled)
    }
    @Test fun installedPackCanBeEnabledWithoutStartingDownload() {
        var enabled = false; var downloads = 0
        compose.setContent { ViaBuszTheme { androidx.compose.foundation.layout.Column {
            OfflinePackSettings(UiState(offlineMode = false, offlinePack = OfflinePackState(meta, OfflinePackPhase.READY)), { enabled = it }, { downloads++ }, {}, {}, {})
        } } }
        compose.onNode(hasText("Offline mód") and hasClickAction()).performClick()
        assertTrue(enabled); assertEquals(0, downloads)
    }
    @Test fun processingDoesNotPresentFrozenDownloadBytesAsInstallationProgress() {
        compose.setContent { ViaBuszTheme { androidx.compose.foundation.layout.Column {
            OfflinePackSettings(UiState(offlineMode = false, offlinePack = OfflinePackState(phase = OfflinePackPhase.IMPORTING,
                bytes = 167_352_729, total = 167_352_729, source = "BKK – Budapest")), {}, {}, {}, {}, {})
        } } }
        compose.onNodeWithText("Menetrend feldolgozása és ellenőrzése").assertExists()
        compose.onNodeWithText(hu.app.bmenetrend.data.StationTimetableRepository.formatFileSize(167_352_729)).assertDoesNotExist()
    }

    @Test fun processingShowsRecordCountAndMeasuredFileProgress() {
        val detail = OfflineImportProgress(OfflineImportStep.STOP_TIMES, records = 42, completed = 50, total = 100)
        compose.setContent { ViaBuszTheme { androidx.compose.foundation.layout.Column {
            OfflinePackSettings(UiState(offlineMode = false, offlinePack = OfflinePackState(phase = OfflinePackPhase.IMPORTING,
                source = "BKK – Budapest", importProgress = detail)), {}, {}, {}, {}, {})
        } } }
        compose.onNodeWithText("Indulási és érkezési idők feldolgozása").assertExists()
        compose.onNodeWithText("Feldolgozott sorok: 42").assertExists()
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
            androidx.compose.ui.semantics.ProgressBarRangeInfo(.5f, 0f..1f))).assertExists()
    }

}
