package hu.app.bmenetrend.ui

import androidx.compose.ui.test.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.Color
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
    // Budapest is UTC+1 on this date (the DST change has already occurred).
    private val clock=Instant.parse("2026-10-26T13:00:00Z")
    private fun departure(status: String?=null, delaySeconds: Long=300, kind: ArrivalKind=ArrivalKind.LIVE,
                          scheduledAt: Instant?=clock)=Departure("route","762","Biatorbágy, vasútállomás","stop",scheduledAt,
        clock.plusSeconds(delaySeconds),kind,realtimeState=status)
    @Test fun cancelledDepartureShowsWordsAndCannotOpenABoardingAction() {
        var opened=false
        compose.setContent { ViaBuszTheme { DepartureBoardRow(departure("CANCELED"),"volan","COACH",null,5,null,{opened=true}) } }
        compose.onNodeWithText("Kimarad").assertIsDisplayed()
        compose.onNodeWithText("Várható: 14:05").assertDoesNotExist()
        compose.onNodeWithText("Menetrend: 14:00").assertDoesNotExist()
        compose.onNode(hasClickAction()).assertIsNotEnabled()
        compose.runOnIdle { org.junit.Assert.assertFalse(opened) }
    }
    @Test fun compactRowShowsScheduleDetailsOnlyAfterTapping() {
        val opened=mutableStateOf(false)
        compose.setContent { ViaBuszTheme {
            if(opened.value) DepartureDetails(departure())
            else DepartureBoardRow(departure(),"volan","COACH",null,5,null,{opened.value=true})
        } }
        compose.onNodeWithText("5′").assertIsDisplayed()
        compose.onNodeWithText("Várható: 14:05").assertDoesNotExist()
        compose.onNodeWithText("Menetrend: 14:00").assertDoesNotExist()
        compose.onNodeWithText("Biatorbágy, vasútállomás").performClick()
        compose.onNodeWithText("Várható: 14:05").assertIsDisplayed()
        compose.onNodeWithText("Menetrend: 14:00").assertIsDisplayed()
    }
    @Test fun smallDelayCrossingDisplayedMinuteStillShowsBothTimesInDetails() {
        val scheduled = clock.plusSeconds(45)
        compose.setContent { ViaBuszTheme { DepartureDetails(departure(delaySeconds=75,scheduledAt=scheduled)) } }
        compose.onNodeWithText("Várható: 14:01").assertIsDisplayed()
        compose.onNodeWithText("Menetrend: 14:00").assertIsDisplayed()
    }
    @Test fun onTimeAndUnverifiedScheduledRowsDoNotClaimAnEstimate() {
        compose.setContent {
            androidx.compose.foundation.lazy.LazyColumn {
                item { DepartureBoardRow(departure(delaySeconds=0),"volan","COACH",null,5,null,{}) }
                item { DepartureBoardRow(departure(delaySeconds=0,kind=ArrivalKind.SCHEDULED,scheduledAt=null),"volan","COACH",null,5,null,{}) }
            }
        }
        compose.onNodeWithText("Várható: 14:00").assertDoesNotExist()
        compose.onNodeWithText("Menetrend: 14:00").assertDoesNotExist()
    }
    @Test fun largeTextRetainsTheDestinationAndCompactTime() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f,1.8f)) {
                ViaBuszTheme { DepartureBoardRow(departure(),"volan","COACH",null,5,"2. kocsiállás",{}) }
            }
        }
        compose.onNodeWithText("Biatorbágy, vasútállomás").assertIsDisplayed()
        compose.onNodeWithText("5′").assertIsDisplayed()
        compose.onNodeWithText("Várható: 14:05").assertDoesNotExist()
        compose.onNodeWithText("Menetrend: 14:00").assertDoesNotExist()
        compose.onNodeWithText("2. kocsiállás").assertDoesNotExist()
    }
    @Test fun vehicleJourneyLabelsLiveAndOriginalTimesAtLargeTextOnNarrowScreen() {
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f,1.8f)) {
                ViaBuszTheme {
                    LineStopRow(
                        stop=TransitStop("stop","Biatorbágy, vasútállomás",GeoPoint(47.47,19.02),emptyList()),
                        first=false,last=false,color=Color(0xFF199DCF),onClick={},
                        arrivalTime="14:05",scheduledTime="14:00",isLive=true,delayMinutes=5,
                    )
                }
            }
        }
        compose.onNodeWithText("Várható: 14:05").assertIsDisplayed()
        compose.onNodeWithText("Menetrend: 14:00").assertIsDisplayed()
        compose.onNodeWithText("Biatorbágy, vasútállomás").assertIsDisplayed()
    }
    @Test fun vehicleJourneyDoesNotLabelTimetableTimeAsLiveEstimate() {
        compose.setContent {
            ViaBuszTheme {
                LineStopRow(
                    stop=TransitStop("stop","Biatorbágy, vasútállomás",GeoPoint(47.47,19.02),emptyList()),
                    first=false,last=false,color=Color(0xFF199DCF),onClick={},
                    arrivalTime="14:00",scheduledTime="14:00",isLive=false,
                )
            }
        }
        compose.onNodeWithText("14:00").assertIsDisplayed()
        compose.onNodeWithText("Várható: 14:00").assertDoesNotExist()
        compose.onNodeWithText("Menetrend: 14:00").assertDoesNotExist()
    }
}
