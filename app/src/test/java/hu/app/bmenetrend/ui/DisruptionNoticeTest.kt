package hu.app.bmenetrend.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import hu.app.bmenetrend.data.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "hu-rHU-w360dp-h800dp")
class DisruptionNoticeTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-11-14T21:20:00Z")
    private val context = DisruptionContext(true, now, route = "0150", stops = setOf("F01037"))
    private val alert = ServiceAlert("a", emptyList(), listOf(AlertSelector(null,"0150",null,"F01037",null,null)),
        listOf(AlertText("hu","Megállóáthelyezés")),listOf(AlertText("hu","A megálló a Victor Hugo utcába került.")),
        listOf(AlertText("hu","https://bkk.hu/bkk-info#!i=123")),9,3)
    private fun display(snapshot: DisruptionSnapshot, scope: DisruptionContext = context) {
        compose.setContent { ViaBuszTheme { CompositionLocalProvider(LocalDisruptions provides DisruptionPresentation(snapshot,now)) {
            DisruptionNotice(listOf(scope))
        } } }
    }
    @Test fun affectedNoticeOpensReadableDetailsAndOfficialLink() {
        display(DisruptionSnapshot(listOf(alert),alertsAt = now, changesAt = now, attempted = true))
        compose.onNodeWithText("Megállóáthelyezés").assertIsDisplayed().performClick()
        compose.onNodeWithText("A megálló a Victor Hugo utcába került.").assertIsDisplayed()
        compose.onNodeWithText("Részletek a BKK oldalán").assertIsDisplayed()
        compose.onNodeWithText("Bezárás").performClick()
        compose.onNodeWithText("A megálló a Victor Hugo utcába került.").assertDoesNotExist()
    }
    @Test fun unrelatedStopDoesNotShowAnAlert() {
        display(DisruptionSnapshot(listOf(alert),alertsAt = now, changesAt = now, attempted = true),context.copy(stops = setOf("different")))
        compose.onAllNodesWithText("Megállóáthelyezés").assertCountEquals(0)
    }
    @Test fun unavailableFeedWithoutAnActualNoticeDoesNotShowABanner() {
        display(DisruptionSnapshot(attempted = true, alertsUnavailable = true, changesUnavailable = true))
        compose.onNodeWithText("A forgalmi változások most nem ellenőrizhetők").assertDoesNotExist()
        compose.onNodeWithContentDescription("Forgalmi változások részletei").assertDoesNotExist()
    }
}
