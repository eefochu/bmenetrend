package hu.app.bmenetrend.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import hu.app.bmenetrend.data.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "hu-rHU-w400dp-h850dp")
class SearchLoadingTest {
    @get:Rule val compose = createComposeRule()
    private val route = TransitRoute("volan:762", "762", "Biatorbágy – Budapest", emptyList(), "volan", transportMode = "COACH")
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test fun vehicleResultsDoNotWaitForTheStopIndex() {
        val stopIndex = CompletableDeferred<List<TransitSearch.StopEntry>>()
        compose.setContent { ViaBuszTheme {
            CategorizedSearch(UiState(emmaRoutes = listOf(route)), "762", SearchCategory.VEHICLES,
                {}, {}, {}, {}, {}, { _, _ -> }, buildStopIndex = { _, _ -> stopIndex.await() })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("762").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("762").assertIsDisplayed()
        compose.onAllNodes(progress).assertCountEquals(0)
    }

    @Test fun changingTabsKeepsComputedResultsWithoutAnotherDebounce() {
        val category = mutableStateOf(SearchCategory.ALL)
        compose.setContent { ViaBuszTheme {
            CategorizedSearch(UiState(emmaRoutes = listOf(route), stopIndexLoaded = true, placeQuery = "762"), "762", category.value,
                { category.value = it }, {}, {}, {}, {}, { _, _ -> }, buildStopIndex = { _, _ -> emptyList() })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("762").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodes(progress).fetchSemanticsNodes().isEmpty() }
        compose.mainClock.autoAdvance = false
        compose.onNode(hasText("Járművek") and hasClickAction()).performClick()
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        compose.onNode(hasText("Járművek") and hasClickAction()).assertIsSelected()
        compose.onNodeWithText("762").assertIsDisplayed()
        compose.onAllNodes(progress).assertCountEquals(0)
    }
}
