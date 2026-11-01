package hu.app.bmenetrend

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PlannerPrefetchTest {
    @Test fun prefetchedOnlineResultsAreUsedOnSubmissionAndRefreshBypassesThem() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<Application>()
        NetworkAccessPolicy.setOffline(app, false)
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            val buffer = Buffer().also { chain.request().body!!.writeTo(it) }
            val vars = JSONObject(buffer.readUtf8()).getJSONObject("variables")
            val start = LocalDateTime.parse(vars.getString("date") + "T" + vars.getString("time"))
                .atZone(TripPlanner.zone).toInstant().plusSeconds(60).toEpochMilli()
            val end = start + 120_000
            fun point(name: String) = JSONObject().put("name", name).put("lat", 47.5).put("lon", 19.0)
            val leg = JSONObject().put("mode", "WALK").put("startTime", start).put("endTime", end)
                .put("distance", 100).put("from", point("A")).put("to", point("B"))
            val row = JSONObject().put("startTime", start).put("endTime", end).put("legs", JSONArray().put(leg))
            val body = JSONObject().put("data", JSONObject().put("plan", JSONObject().put("itineraries", JSONArray().put(row))))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toString().toResponseBody()).build()
        }.build()
        val real = EmmaPlannerBackend(EmmaGraphqlClient(http, EmmaRequestBackoff()))
        val finishedCalls = AtomicInteger()
        val backend = object : PlannerBackend {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> =
                real.routes(from, to, ready, busesOnly).also { finishedCalls.incrementAndGet() }
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant) = WalkingResult(emptyMap())
            override fun clearRouteCache() = real.clearRouteCache()
        }
        val local = CompletableDeferred<Unit>()
        val model = PlannerViewModel(app, backend) {
            local.await()
            TransitNetwork(emptyList(), emptyList(), emptyList(), Instant.now(), "fixture")
        }
        fun awaitCondition(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!condition() && System.nanoTime() < deadline) { testScheduler.runCurrent(); Thread.sleep(5) }
            assertTrue("Asynchronous condition did not complete", condition())
        }
        try {
            runCurrent() // Start the initial network-mode observer before selecting endpoints.
            model.setDeparture(Instant.now().plusSeconds(600))
            model.edit(PlannerField.ORIGIN)
            model.choose(PlannerEndpoint("a", "Herceghalom", "", GeoPoint(47.49, 18.74)))
            model.edit(PlannerField.DESTINATION)
            model.choose(PlannerEndpoint("b", "Nyugati", "", GeoPoint(47.51, 19.05)))
            runCurrent()
            advanceTimeBy(251)
            awaitCondition { finishedCalls.get() == 2 }
            assertEquals(2, calls.get())
            val submitted = testScheduler.currentTime
            model.plan()
            awaitCondition { model.state.value.result?.itineraries?.isNotEmpty() == true }
            assertEquals("Submission reuses both prefetched HTTP answers", 2, calls.get())
            assertEquals("No debounce/network timer is added on a warm submission", submitted, testScheduler.currentTime)
            assertTrue(model.state.value.planning)
            local.complete(Unit)
            awaitCondition { !model.state.value.planning }
            model.plan(force = true)
            awaitCondition { !model.state.value.planning && calls.get() >= 4 }
            assertEquals(4, calls.get())
            assertNull(model.state.value.error)
        } finally {
            model.viewModelScope.cancel()
            http.dispatcher.cancelAll()
            http.dispatcher.executorService.shutdownNow()
            http.connectionPool.evictAll()
            Dispatchers.resetMain()
        }
    }
}
