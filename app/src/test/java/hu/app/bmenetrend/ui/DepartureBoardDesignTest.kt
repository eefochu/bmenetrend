package hu.app.bmenetrend.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import hu.app.bmenetrend.data.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30],qualifiers="hu-rHU-w320dp-h800dp")
class DepartureBoardDesignTest {
    @get:Rule val compose=createComposeRule()
    private val clock=Instant.parse("2026-10-26T12:00:00Z")
    private fun departure(status: String?=null)=Departure("route","762","Biatorbágy, vasútállomás","stop",clock,
        clock.plusSeconds(300),ArrivalKind.LIVE,realtimeState=status)
    @Test fun cancelledDepartureShowsWordsAndCannotOpenABoardingAction() {
        var opened=false
        compose.setContent { ViaBuszTheme { DepartureBoardRow(departure("CANCELED"),"volan","COACH",null,5,null,{opened=true}) } }
        compose.onNodeWithText("Kimarad").assertIsDisplayed()
        compose.onNode(hasClickAction()).assertIsNotEnabled()
        compose.runOnIdle { org.junit.Assert.assertFalse(opened) }
    }
    @Test fun delayHasAnExplicitLabelBesideTheDepartureTime() {
        compose.setContent { ViaBuszTheme { DepartureBoardRow(departure(),"volan","COACH",null,5,null,{}) } }
        compose.onNodeWithText("+5 perc").assertIsDisplayed()
        compose.onNodeWithText("Biatorbágy, vasútállomás").assertIsDisplayed()
    }
    @Test fun largeTextRetainsBothTheDestinationAndDepartureStatus() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f,1.8f)) {
                ViaBuszTheme { DepartureBoardRow(departure(),"volan","COACH",null,5,"2. kocsiállás",{}) }
            }
        }
        compose.onNodeWithText("Biatorbágy, vasútállomás").assertIsDisplayed()
        compose.onNodeWithText("+5 perc").assertIsDisplayed()
        compose.onNodeWithText("2. kocsiállás").assertIsDisplayed()
    }
}
