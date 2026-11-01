package hu.app.bmenetrend

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.offline.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerOfflineErrorsTest {
    private fun checkError(expected: PlannerError, validity: Pair<LocalDate, LocalDate>? = null) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = OfflinePackStore(app)
        NetworkAccessPolicy.onConnectivityChanged(true)
        store.delete()
        if (validity != null) {
            val staged = store.staging()
            SQLiteDatabase.openOrCreateDatabase(staged, null).use { db ->
                db.version = OFFLINE_FORMAT
                db.execSQL("CREATE TABLE stops(id INTEGER PRIMARY KEY,source_id TEXT,station INTEGER)")
                db.execSQL("INSERT INTO stops VALUES(1,'volan:unrelated',0)")
                db.execSQL("CREATE TABLE trips(id INTEGER PRIMARY KEY)")
                db.execSQL("INSERT INTO trips VALUES(1)")
            }
            val metadata = OfflinePackMetadata("error-fixture", Instant.now(), validity.first, validity.second, staged.length(), 0, 1, 1,
                listOf("fixture"), OfflinePackStore.digest(staged))
            store.activate(staged, metadata)
        }
        NetworkAccessPolicy.setOffline(app, true)
        var requests = 0
        val backend = object : PlannerBackend {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                requests++; error("Offline submission reached HTTP backend")
            }
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant) = error("Offline walking reached HTTP backend")
        }
        val model = PlannerViewModel(app, backend) { error("Offline submission loaded online timetables") }
        fun awaitCondition(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!condition() && System.nanoTime() < deadline) { testScheduler.runCurrent(); Thread.sleep(5) }
            assertTrue("Planner state did not settle", condition())
        }
        try {
            awaitCondition { model.state.value.offline }
            model.edit(PlannerField.ORIGIN)
            model.choose(PlannerEndpoint("emma:hkir:A", "A", "", GeoPoint(47.49, 18.74)))
            model.edit(PlannerField.DESTINATION)
            model.choose(PlannerEndpoint("emma:hkir:B", "B", "", GeoPoint(47.51, 19.05)))
            model.plan()
            awaitCondition { !model.state.value.planning && model.state.value.error != null }
            assertEquals(expected, model.state.value.error)
            assertEquals(0, requests)
            assertTrue(NetworkAccessPolicy.offline.value)
        } finally {
            model.viewModelScope.cancel()
            store.delete()
            NetworkAccessPolicy.setOffline(app, false)
            NetworkAccessPolicy.onConnectivityChanged(true)
            Dispatchers.resetMain()
        }
    }

    @Test fun missingPackHasAnActionableOfflineError() = checkError(PlannerError.OFFLINE_PACK)

    @Test fun expiredPackIsDistinguishedFromServiceFailure() {
        val today = LocalDate.now(TripPlanner.zone)
        checkError(PlannerError.OFFLINE_DATE, today.minusDays(3) to today.minusDays(1))
    }

    @Test fun unknownEndpointIsDistinguishedFromNoRoute() {
        val today = LocalDate.now(TripPlanner.zone)
        checkError(PlannerError.OFFLINE_ENDPOINT, today.minusDays(1) to today.plusDays(29))
    }
}
