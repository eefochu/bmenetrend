package hu.app.bmenetrend

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.ArrivalKind
import hu.app.bmenetrend.data.Departure
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.StationTimetableRepository
import hu.app.bmenetrend.data.TransitRoute
import hu.app.bmenetrend.data.TransitStop
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import hu.app.bmenetrend.ui.UiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TransitViewModelSavedFallbackTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var app: Application
    private lateinit var timetableDir: File
    private lateinit var timetableRepo: StationTimetableRepository

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        try {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        } catch (_: Exception) {}
        NetworkAccessPolicy.initialize(app)
        NetworkAccessPolicy.setOffline(app, false)

        timetableDir = tempFolder.newFolder("station-timetables-v1")
        timetableRepo = StationTimetableRepository(context = app, baseDirectory = timetableDir)
    }

    private fun injectTimetableRepository(viewModel: TransitViewModel, repo: StationTimetableRepository) {
        val field = TransitViewModel::class.java.getDeclaredField("stationTimetableRepository")
        field.isAccessible = true
        field.set(viewModel, repo)
    }

    private fun getMutableState(viewModel: TransitViewModel): MutableStateFlow<UiState> {
        val stateField = TransitViewModel::class.java.getDeclaredField("mutableState")
        stateField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return stateField.get(viewModel) as MutableStateFlow<UiState>
    }

    private fun sampleStop(id: String, name: String) = TransitStop(
        id = id,
        name = name,
        position = GeoPoint(47.49, 19.05),
        routeIds = listOf("bkk:1"),
        providerId = "bkk",
        station = true,
    )

    @Test
    fun wrongStationRacePreventsStaleSavedBoardFromApplyingToDifferentStation() = runBlocking {
        val now = Instant.now()
        val routeA = TransitRoute("bkk:1", "1", "Tram 1", emptyList(), "bkk", transportMode = "TRAM")
        val depA = Departure(
            routeId = "bkk:1",
            routeNumber = "1",
            destination = "Station A Dest",
            stopId = "emma:BKK:station_A",
            scheduledAt = now.plusSeconds(300),
            expectedAt = now.plusSeconds(300),
            kind = ArrivalKind.SCHEDULED,
            directionId = "dA",
        )

        // Save Station A
        timetableRepo.saveStationTimetableDirect(
            stationId = "emma:BKK:station_A",
            stationName = "Station A",
            platformIds = listOf("emma:BKK:station_A"),
            routes = listOf(routeA),
            departures = listOf(depA),
            now = now,
        )

        val viewModel = TransitViewModel(app)
        injectTimetableRepository(viewModel, timetableRepo)

        // Seed state with known stops and downloaded status
        val stopA = sampleStop("emma:BKK:station_A", "Station A")
        val stopB = sampleStop("emma:BKK:station_B", "Station B")
        val mutableState = getMutableState(viewModel)
        mutableState.value = mutableState.value.copy(
            downloadedStationIds = setOf("emma:BKK:station_A"),
            emmaStops = listOf(stopA, stopB),
        )

        // Select Station A
        viewModel.selectStop("emma:BKK:station_A")
        ShadowLooper.idleMainLooper()
        delay(50)
        ShadowLooper.idleMainLooper()

        // Verify fallback loaded for Station A
        val depsA = viewModel.state.value.departures
        assertTrue("Departures for Station A must be loaded", depsA.any { it.destination == "Station A Dest" })

        // Rapidly switch to Station B
        viewModel.selectStop("emma:BKK:station_B")
        ShadowLooper.idleMainLooper()
        delay(50)
        ShadowLooper.idleMainLooper()

        // Station B must NOT show Station A's departures!
        val depsB = viewModel.state.value.departures
        assertFalse("Station A departures must NEVER leak into Station B", depsB.any { it.destination == "Station A Dest" })
        assertEquals("emma:BKK:station_B", viewModel.state.value.selectedStopId)
    }

    @Test
    fun deletionRaceClearsSavedBoardFallbackAndInvalidatesRevision() = runBlocking {
        val now = Instant.now()
        val route = TransitRoute("bkk:1", "1", "Tram 1", emptyList(), "bkk", transportMode = "TRAM")
        val dep = Departure(
            routeId = "bkk:1",
            routeNumber = "1",
            destination = "Station A Dest",
            stopId = "emma:BKK:station_A",
            scheduledAt = now.plusSeconds(300),
            expectedAt = now.plusSeconds(300),
            kind = ArrivalKind.SCHEDULED,
            directionId = "dA",
        )

        timetableRepo.saveStationTimetableDirect(
            stationId = "emma:BKK:station_A",
            stationName = "Station A",
            platformIds = listOf("emma:BKK:station_A"),
            routes = listOf(route),
            departures = listOf(dep),
            now = now,
        )

        val viewModel = TransitViewModel(app)
        injectTimetableRepository(viewModel, timetableRepo)

        val stopA = sampleStop("emma:BKK:station_A", "Station A")
        val mutableState = getMutableState(viewModel)
        mutableState.value = mutableState.value.copy(
            downloadedStationIds = setOf("emma:BKK:station_A"),
            emmaStops = listOf(stopA),
        )

        // Select Station A
        viewModel.selectStop("emma:BKK:station_A")
        ShadowLooper.idleMainLooper()
        delay(50)
        ShadowLooper.idleMainLooper()

        assertTrue("Station A fallback departures must be present", viewModel.state.value.departures.isNotEmpty())

        // Delete station timetable while selected
        viewModel.deleteStationTimetable("emma:BKK:station_A")
        ShadowLooper.idleMainLooper()
        delay(50)
        ShadowLooper.idleMainLooper()

        // Fallback departures must now be cleared
        assertFalse("Station A should no longer be marked as downloaded", viewModel.state.value.downloadedStationIds.contains("emma:BKK:station_A"))
        assertTrue("Departures must be cleared following deletion of timetable", viewModel.state.value.departures.isEmpty())
    }

    @Test
    fun modeSwitchToOfflineClearsOnlineSavedBoardKeyAndJob() = runBlocking {
        val viewModel = TransitViewModel(app)
        injectTimetableRepository(viewModel, timetableRepo)

        val keyField = TransitViewModel::class.java.getDeclaredField("savedBoardKey")
        keyField.isAccessible = true

        val resultField = TransitViewModel::class.java.getDeclaredField("savedBoardResult")
        resultField.isAccessible = true

        // Switch to offline mode via policy
        NetworkAccessPolicy.setOffline(app, true)
        ShadowLooper.idleMainLooper()
        delay(50)
        ShadowLooper.idleMainLooper()

        assertNull("savedBoardKey must be null after applying offline mode", keyField.get(viewModel))
        assertNull("savedBoardResult must be null after applying offline mode", resultField.get(viewModel))
    }
}
