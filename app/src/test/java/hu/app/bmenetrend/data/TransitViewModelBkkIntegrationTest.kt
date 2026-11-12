package hu.app.bmenetrend.data

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.TransitViewModel
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import hu.app.bmenetrend.ui.UiState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TransitViewModelBkkIntegrationTest {

    private lateinit var app: Application
    private val models = mutableListOf<TransitViewModel>()
    private val now = Instant.parse("2026-11-10T12:00:00Z")

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        try {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        } catch (_: Exception) {}
        NetworkAccessPolicy.initialize(app)
        NetworkAccessPolicy.setOffline(app, false)
        NetworkAccessPolicy.onConnectivityChanged(true)
        File(app.filesDir, "emma-stop-index-v3.json").delete()
        File(app.filesDir, "emma-stop-index-v2.json").delete()
        File(app.filesDir, "emma-stop-index-v1.json").delete()
    }

    @After
    fun cleanup() {
        models.forEach { it.viewModelScope.cancel() }
        NetworkAccessPolicy.setOffline(app, false)
    }

    @Suppress("UNCHECKED_CAST")
    private fun mutableState(viewModel: TransitViewModel): MutableStateFlow<UiState> {
        val field = TransitViewModel::class.java.getDeclaredField("mutableState")
        field.isAccessible = true
        return field.get(viewModel) as MutableStateFlow<UiState>
    }

    private fun injectBkkRepository(viewModel: TransitViewModel, repo: BkkWorkerRepository) {
        val field = TransitViewModel::class.java.getDeclaredField("bkkRepository")
        field.isAccessible = true
        field.set(viewModel, repo)
    }

    private fun injectStopsRepository(viewModel: TransitViewModel, repo: EmmaStopsRepository) {
        val field = TransitViewModel::class.java.getDeclaredField("stopsRepository")
        field.isAccessible = true
        field.set(viewModel, repo)
    }

    private fun bkkVehicleJob(viewModel: TransitViewModel): Job? {
        val field = TransitViewModel::class.java.getDeclaredField("bkkVehicleJob")
        field.isAccessible = true
        return field.get(viewModel) as? Job
    }

    private fun awaitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(5)
        }
        ShadowLooper.idleMainLooper()
        assertTrue("condition not met within ${timeoutMs}ms", condition())
    }

    @Test
    fun bkkBoardFetchedForBkkPlatform() = runBlocking {
        val bkkStop = TransitStop("emma:BKK:F01020", "Deák Ferenc tér M", GeoPoint(47.497, 19.054), emptyList(), "bkk")
        val bkkCalls = AtomicInteger(0)

        val bkkClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val path = url.encodedPath
            val callNow = Instant.now()
            if (path.contains("arrivals-and-departures") || path.contains("stop")) {
                bkkCalls.incrementAndGet()
            }
            val json = JSONObject().put("status", "OK").put("data", JSONObject()
                .put("references", JSONObject()
                    .put("routes", JSONObject().put("BKK_B0070", JSONObject().put("id", "BKK_B0070").put("type", "BUS").put("shortName", "7")))
                    .put("trips", JSONObject().put("BKK_TRIP1", JSONObject().put("routeId", "BKK_B0070").put("tripHeadsign", "Albertfalva")))
                    .put("stops", JSONObject().put("BKK_F01020", JSONObject().put("platformCode", "1")))
                )
                .put("entry", JSONObject()
                    .put("stopId", "BKK_F01020")
                    .put("stopTimes", JSONArray().put(JSONObject()
                        .put("stopId", "BKK_F01020")
                        .put("tripId", "BKK_TRIP1")
                        .put("departureTime", callNow.epochSecond + 180)
                        .put("pickupAllowed", true)))
                )
                .put("list", JSONArray())
            ).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody()).build()
        }.build()

        val emmaClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"station":null}}""".toResponseBody()).build()
        }.build()

        val viewModel = TransitViewModel(app).also { models += it }
        val bkkRepo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), bkkClient, { Instant.now() }, BkkRequestBudget()))
        val emmaRepo = EmmaStopsRepository(app, EmmaGraphqlClient(emmaClient))

        injectBkkRepository(viewModel, bkkRepo)
        injectStopsRepository(viewModel, emmaRepo)

        awaitUntil { viewModel.state.value.network != null }
        viewModel.setDeparturesVisible(false)
        mutableState(viewModel).update { it.copy(emmaStops = it.emmaStops + bkkStop) }
        viewModel.selectStopGroup(listOf(bkkStop.id))
        viewModel.setDeparturesVisible(true)

        awaitUntil { bkkCalls.get() >= 1 && viewModel.state.value.departures.isNotEmpty() }

        val departures = viewModel.state.value.departures
        assertFalse("Departures should be loaded from BKK worker", departures.isEmpty())
        assertEquals("7", departures[0].routeNumber)
        assertTrue(bkkCalls.get() >= 1)
    }

    @Test
    fun offlineModeCancelsWorkerJobsAndClearsVehicles() = runBlocking {
        val viewModel = TransitViewModel(app).also { models += it }

        // Enable offline mode
        NetworkAccessPolicy.setOffline(app, true)
        ShadowLooper.idleMainLooper()

        assertTrue(viewModel.state.value.offlineMode)
        assertTrue(viewModel.vehicles.value.isEmpty())
        assertNull(viewModel.state.value.selectedVehicle)

        // Update map area during offline mode -> ensures no crash and offline state stays intact
        viewModel.updateMapArea(MapBounds(19.04, 47.48, 19.06, 47.50), GeoPoint(47.49, 19.05))
        ShadowLooper.idleMainLooper()
        assertTrue(viewModel.state.value.offlineMode)
    }

    private fun vehicleJsonResponse(ids: List<String>, lat: Double, lon: Double): String = JSONObject().put("status", "OK").put("data", JSONObject()
        .put("references", JSONObject())
        .put("list", JSONArray(ids.map { id -> JSONObject()
            .put("vehicleId", id)
            .put("vehicleRouteType", "BUS")
            .put("lastUpdateTime", Instant.now().epochSecond)
            .put("location", JSONObject().put("lat", lat).put("lon", lon))
        }))
    ).toString()

    @Test
    fun bkkCoversView_returnsFalse_whenSnapshotCoversOldBiatorbagy_butMapBoundsInBudapest() {
        val viewModel = TransitViewModel(app).also { models += it }

        // Biatorbágy area snapshot
        val biatorbagyBounds = MapBounds(18.81, 47.46, 18.83, 47.48)
        val biatorbagyVehicle = WorkerVehicle(
            vehicle = Vehicle(
                id = "emma:BKK:biatorbagy_bus",
                position = GeoPoint(47.47, 18.82),
                observedAt = Instant.now(),
                providerId = "bkk"
            ),
            numbers = setOf("biatorbagy_bus")
        )
        val snapshot = BkkWorkerRepository.VehicleSnapshot(
            vehicles = listOf(biatorbagyVehicle),
            covered = biatorbagyBounds,
            fetchedAt = Instant.now()
        )

        val bkkVehiclesField = TransitViewModel::class.java.getDeclaredField("bkkVehicles").apply { isAccessible = true }
        bkkVehiclesField.set(viewModel, snapshot)

        // Set map viewport to Budapest
        val budapestBounds = MapBounds(19.04, 47.49, 19.06, 47.51)
        val budapestCenter = GeoPoint(47.50, 19.05)
        viewModel.updateMapArea(budapestBounds, budapestCenter)

        // Verify via private reflection that bkkCoversView() recognizes Budapest is NOT covered by the Biatorbágy snapshot
        val bkkCoversViewMethod = TransitViewModel::class.java.getDeclaredMethod("bkkCoversView").apply { isAccessible = true }
        val covered = bkkCoversViewMethod.invoke(viewModel) as Boolean

        assertFalse(
            "bkkCoversView must return false when snapshot covers old Biatorbagy but current mapBounds is in Budapest",
            covered
        )
    }

    @Test
    fun panningFromBiatorbagyToBudapest_retryNotSuppressedByOldBiatorbagySnapshotFetchedAt() = runBlocking {
        val biatorbagyBounds = MapBounds(18.81, 47.46, 18.83, 47.48)
        val biatorbagyCenter = GeoPoint(47.47, 18.82)
        val budapestBounds = MapBounds(19.04, 47.49, 19.06, 47.51)
        val budapestCenter = GeoPoint(47.50, 19.05)

        val biatorbagyCalls = AtomicInteger(0)
        val budapestCalls = AtomicInteger(0)
        var budapestShouldFail = true

        val bkkClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val path = url.encodedPath
            if (path.contains("vehicles-for-location")) {
                val lon = url.queryParameter("lon")?.toDoubleOrNull() ?: 0.0
                if (lon < 18.9) {
                    biatorbagyCalls.incrementAndGet()
                    val body = vehicleJsonResponse(listOf("BKK_BIATORBAGY_1"), lat = 47.47, lon = 18.82)
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(body.toResponseBody()).build()
                } else {
                    budapestCalls.incrementAndGet()
                    if (budapestShouldFail) {
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(503).message("Service Unavailable")
                            .body("""{"status":"ERROR","message":"Transient failure"}""".toResponseBody()).build()
                    } else {
                        val body = vehicleJsonResponse(listOf("BKK_BUDAPEST_1"), lat = 47.50, lon = 19.05)
                        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                            .body(body.toResponseBody()).build()
                    }
                }
            } else {
                val emptyJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject())
                    .put("list", JSONArray())
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(emptyJson.toResponseBody()).build()
            }
        }.build()

        val emmaClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"station":null,"stopsByBbox":[]}}""".toResponseBody()).build()
        }.build()

        val viewModel = TransitViewModel(app).also { models += it }
        val bkkRepo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), bkkClient, { Instant.now() }, BkkRequestBudget()))
        val emmaRepo = EmmaStopsRepository(app, EmmaGraphqlClient(emmaClient))

        injectBkkRepository(viewModel, bkkRepo)
        injectStopsRepository(viewModel, emmaRepo)

        awaitUntil { viewModel.state.value.network != null }

        // 1. Initial Biatorbágy viewport request succeeds
        viewModel.updateMapArea(biatorbagyBounds, biatorbagyCenter)
        awaitUntil { biatorbagyCalls.get() >= 1 && viewModel.vehicles.value.any { it.id == "emma:BKK:BIATORBAGY_1" } }
        assertEquals("Biatorbágy vehicle should be present", 1, viewModel.vehicles.value.count { it.id == "emma:BKK:BIATORBAGY_1" })

        // 2. User pans to Budapest: request fails transiently
        budapestShouldFail = true
        viewModel.updateMapArea(budapestBounds, budapestCenter)
        awaitUntil {
            val job = bkkVehicleJob(viewModel)
            budapestCalls.get() >= 1 && (job == null || !job.isActive)
        }
        ShadowLooper.idleMainLooper()

        // 3. Transient failure clears -> immediate repeated Budapest update (e.g. pan gesture settles or repeated frame)
        budapestShouldFail = false
        viewModel.updateMapArea(budapestBounds, budapestCenter)
        ShadowLooper.idleMainLooper()

        // Retry must NOT be suppressed by the old Biatorbágy snapshot's fresh fetchedAt:
        // A second Budapest call must be dispatched and Budapest vehicles must be published.
        awaitUntil(timeoutMs = 1500) {
            budapestCalls.get() >= 2 && viewModel.vehicles.value.any { it.id == "emma:BKK:BUDAPEST_1" }
        }
        assertTrue(
            "Budapest vehicles must be published after repeated Budapest update, not suppressed by old Biatorbágy snapshot",
            viewModel.vehicles.value.any { it.id == "emma:BKK:BUDAPEST_1" }
        )
    }

    @Test
    fun panning_cancelsInFlightOldAreaRequest_andPublishesBudapestVehicles_respectingGesture() = runBlocking {
        val biatorbagyBounds = MapBounds(18.81, 47.46, 18.83, 47.48)
        val biatorbagyCenter = GeoPoint(47.47, 18.82)
        val budapestBounds = MapBounds(19.04, 47.49, 19.06, 47.51)
        val budapestCenter = GeoPoint(47.50, 19.05)

        val biatorbagyStarted = CountDownLatch(1)
        val continueBiatorbagy = CountDownLatch(1)
        val budapestCalls = AtomicInteger(0)

        val bkkClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val path = url.encodedPath
            if (path.contains("vehicles-for-location")) {
                val lon = url.queryParameter("lon")?.toDoubleOrNull() ?: 0.0
                if (lon < 18.9) {
                    biatorbagyStarted.countDown()
                    continueBiatorbagy.await(3, TimeUnit.SECONDS)
                    val body = vehicleJsonResponse(listOf("BKK_BIATORBAGY_SLOW"), lat = 47.47, lon = 18.82)
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(body.toResponseBody()).build()
                } else {
                    budapestCalls.incrementAndGet()
                    val body = vehicleJsonResponse(listOf("BKK_BUDAPEST_FAST"), lat = 47.50, lon = 19.05)
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(body.toResponseBody()).build()
                }
            } else {
                val emptyJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject())
                    .put("list", JSONArray())
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(emptyJson.toResponseBody()).build()
            }
        }.build()

        val emmaClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"station":null,"stopsByBbox":[]}}""".toResponseBody()).build()
        }.build()

        val viewModel = TransitViewModel(app).also { models += it }
        val bkkRepo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), bkkClient, { Instant.now() }, BkkRequestBudget()))
        val emmaRepo = EmmaStopsRepository(app, EmmaGraphqlClient(emmaClient))

        injectBkkRepository(viewModel, bkkRepo)
        injectStopsRepository(viewModel, emmaRepo)

        awaitUntil { viewModel.state.value.network != null }
        mutableState(viewModel).update { it.copy(stopIndexLoaded = true) }

        // 1. Start fetching Biatorbágy (slow in-flight request)
        viewModel.updateMapArea(biatorbagyBounds, biatorbagyCenter)
        assertTrue("Biatorbágy request should be initiated", biatorbagyStarted.await(2, TimeUnit.SECONDS))

        // 2. User starts a gesture and pans to Budapest while Biatorbágy request is still in-flight
        viewModel.setMapGesture(true)
        viewModel.updateMapArea(budapestBounds, budapestCenter)

        // Release Biatorbágy network call so its thread can finish
        continueBiatorbagy.countDown()

        // 3. Budapest request responds while gesture is still active (sticky gesture)
        awaitUntil { budapestCalls.get() >= 1 }
        ShadowLooper.idleMainLooper()

        // Deferred publication: while gesture is in progress, UI vehicle list should NOT yet publish
        // (recalculatePending is true, recalculate is deferred)
        assertFalse(
            "Vehicles publication should be deferred while mapGesture is active",
            viewModel.vehicles.value.any { it.id == "emma:BKK:BUDAPEST_FAST" }
        )

        // 4. Gesture ends: publication must happen promptly
        viewModel.setMapGesture(false)
        ShadowLooper.idleMainLooper()

        awaitUntil { viewModel.vehicles.value.any { it.id == "emma:BKK:BUDAPEST_FAST" } }
        assertTrue(
            "Budapest vehicles must be published promptly once gesture finishes",
            viewModel.vehicles.value.any { it.id == "emma:BKK:BUDAPEST_FAST" }
        )

        // In-flight Biatorbágy request was cancelled and must NOT overwrite Budapest vehicles
        assertFalse(
            "Cancelled Biatorbágy vehicles must not be present in published vehicles",
            viewModel.vehicles.value.any { it.id == "emma:BKK:BIATORBAGY_SLOW" }
        )
    }

    @Test
    fun bkkCoversView_returnsFalse_whenOldAreaOnlyPartiallyOverlapsCurrentViewport() {
        val viewModel = TransitViewModel(app).also { models += it }

        // Old snapshot covers south-west section
        val oldCovered = MapBounds(19.00, 47.45, 19.05, 47.50)
        val sampleVehicle = WorkerVehicle(
            vehicle = Vehicle(
                id = "emma:BKK:v1",
                position = GeoPoint(47.47, 19.02),
                observedAt = Instant.now(),
                providerId = "bkk"
            ),
            numbers = setOf("v1")
        )
        val snapshot = BkkWorkerRepository.VehicleSnapshot(
            vehicles = listOf(sampleVehicle),
            covered = oldCovered,
            fetchedAt = Instant.now()
        )
        val bkkVehiclesField = TransitViewModel::class.java.getDeclaredField("bkkVehicles").apply { isAccessible = true }
        bkkVehiclesField.set(viewModel, snapshot)

        // Viewport partially overlaps (19.03..19.05 and 47.48..47.50) but extends outside (east to 19.08, north to 47.53)
        val partialOverlapViewport = MapBounds(19.03, 47.48, 19.08, 47.53)
        viewModel.updateMapArea(partialOverlapViewport, GeoPoint(47.50, 19.05))

        val bkkCoversViewMethod = TransitViewModel::class.java.getDeclaredMethod("bkkCoversView").apply { isAccessible = true }
        assertFalse(
            "bkkCoversView must return false when old area only partially overlaps and does not fully contain viewport",
            bkkCoversViewMethod.invoke(viewModel) as Boolean
        )

        // Also test partial overlap where viewport extends beyond west/south bounds of old covered area
        val extendingSouthWest = MapBounds(18.98, 47.43, 19.03, 47.48)
        viewModel.updateMapArea(extendingSouthWest, GeoPoint(47.46, 19.01))
        assertFalse(
            "bkkCoversView must return false when viewport extends beyond west/south bounds of old covered area",
            bkkCoversViewMethod.invoke(viewModel) as Boolean
        )
    }

    @Test
    fun bkkCoversView_returnsFalse_forUnqueryableOrEmptyOrStaleSnapshot() {
        val viewModel = TransitViewModel(app).also { models += it }
        val budapestBounds = MapBounds(19.04, 47.49, 19.06, 47.51)
        val budapestCenter = GeoPoint(47.50, 19.05)
        viewModel.updateMapArea(budapestBounds, budapestCenter)

        val bkkVehiclesField = TransitViewModel::class.java.getDeclaredField("bkkVehicles").apply { isAccessible = true }
        val bkkCoversViewMethod = TransitViewModel::class.java.getDeclaredMethod("bkkCoversView").apply { isAccessible = true }

        val sampleVehicle = WorkerVehicle(
            vehicle = Vehicle(
                id = "emma:BKK:v1",
                position = GeoPoint(47.50, 19.05),
                observedAt = Instant.now(),
                providerId = "bkk"
            ),
            numbers = setOf("v1")
        )

        // 1. Unqueryable / followed-only snapshot using NO_AREA singleton
        val noAreaField = Class.forName("hu.app.bmenetrend.TransitViewModelKt").getDeclaredField("NO_AREA").apply { isAccessible = true }
        val noArea = noAreaField.get(null) as MapBounds
        val unqueryableSnapshot = BkkWorkerRepository.VehicleSnapshot(
            vehicles = listOf(sampleVehicle),
            covered = noArea,
            fetchedAt = Instant.now()
        )
        bkkVehiclesField.set(viewModel, unqueryableSnapshot)
        assertFalse(
            "Snapshot with NO_AREA must not count as coverage for viewport",
            bkkCoversViewMethod.invoke(viewModel) as Boolean
        )

        // 2. Empty vehicle list snapshot
        val emptySnapshot = BkkWorkerRepository.VehicleSnapshot(
            vehicles = emptyList(),
            covered = budapestBounds,
            fetchedAt = Instant.now()
        )
        bkkVehiclesField.set(viewModel, emptySnapshot)
        assertFalse(
            "Empty vehicle snapshot must not count as coverage for viewport",
            bkkCoversViewMethod.invoke(viewModel) as Boolean
        )

        // 3. Stale snapshot (> BKK_MAX_AGE_SECONDS, e.g. 40s ago)
        val staleSnapshot = BkkWorkerRepository.VehicleSnapshot(
            vehicles = listOf(sampleVehicle),
            covered = budapestBounds,
            fetchedAt = Instant.now().minusSeconds(40)
        )
        bkkVehiclesField.set(viewModel, staleSnapshot)
        assertFalse(
            "Stale snapshot exceeding BKK_MAX_AGE_SECONDS must not count as coverage",
            bkkCoversViewMethod.invoke(viewModel) as Boolean
        )
    }

    @Test
    fun repeatedSameAreaUpdate_withinPollWindow_reusesCachedSnapshot_withoutExtraHttpRequest() = runBlocking {
        val budapestBounds = MapBounds(19.04, 47.49, 19.06, 47.51)
        val budapestCenter = GeoPoint(47.50, 19.05)
        val budapestCalls = AtomicInteger(0)

        val bkkClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            if (url.encodedPath.contains("vehicles-for-location")) {
                budapestCalls.incrementAndGet()
                val body = vehicleJsonResponse(listOf("BKK_BUDAPEST_CACHE_1"), lat = 47.50, lon = 19.05)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody()).build()
            } else {
                val emptyJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject())
                    .put("list", JSONArray())
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(emptyJson.toResponseBody()).build()
            }
        }.build()

        val emmaClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"station":null,"stopsByBbox":[]}}""".toResponseBody()).build()
        }.build()

        val viewModel = TransitViewModel(app).also { models += it }
        val bkkRepo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), bkkClient, { Instant.now() }, BkkRequestBudget()))
        val emmaRepo = EmmaStopsRepository(app, EmmaGraphqlClient(emmaClient))

        injectBkkRepository(viewModel, bkkRepo)
        injectStopsRepository(viewModel, emmaRepo)

        awaitUntil { viewModel.state.value.network != null }

        // Initial request for area
        viewModel.updateMapArea(budapestBounds, budapestCenter)
        awaitUntil { budapestCalls.get() == 1 && viewModel.vehicles.value.any { it.id == "emma:BKK:BUDAPEST_CACHE_1" } }

        // Repeated update with the exact same area within BKK_VEHICLE_POLL_MS (10s)
        viewModel.updateMapArea(budapestBounds, budapestCenter)
        ShadowLooper.idleMainLooper()
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()

        // Also test another update with slightly adjusted coordinates that snap to the identical queryArea box
        val slightlyPannedBounds = MapBounds(19.041, 47.491, 19.059, 47.509)
        viewModel.updateMapArea(slightlyPannedBounds, budapestCenter)
        ShadowLooper.idleMainLooper()
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()

        assertEquals("No extra HTTP request should be dispatched when reusing cached snapshot for the same area", 1, budapestCalls.get())
        assertTrue("Vehicles should remain published", viewModel.vehicles.value.any { it.id == "emma:BKK:BUDAPEST_CACHE_1" })
    }

    @Test
    fun panningToPartiallyOverlappingArea_dispatchesNewHttpRequest_notSuppressedByOldSnapshot() = runBlocking {
        val area1Bounds = MapBounds(19.04, 47.49, 19.06, 47.51)
        val area1Center = GeoPoint(47.50, 19.05)
        // Pan significantly east/north so queryArea produces a distinct grid box
        val area2Bounds = MapBounds(19.10, 47.55, 19.12, 47.57)
        val area2Center = GeoPoint(47.56, 19.11)

        val totalCalls = AtomicInteger(0)

        val bkkClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            if (url.encodedPath.contains("vehicles-for-location")) {
                totalCalls.incrementAndGet()
                val lon = url.queryParameter("lon")?.toDoubleOrNull() ?: 0.0
                val body = vehicleJsonResponse(listOf("BKK_BUS_${totalCalls.get()}"), lat = 47.50, lon = lon)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody()).build()
            } else {
                val emptyJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject())
                    .put("list", JSONArray())
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(emptyJson.toResponseBody()).build()
            }
        }.build()

        val emmaClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"station":null,"stopsByBbox":[]}}""".toResponseBody()).build()
        }.build()

        val viewModel = TransitViewModel(app).also { models += it }
        val bkkRepo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), bkkClient, { Instant.now() }, BkkRequestBudget()))
        val emmaRepo = EmmaStopsRepository(app, EmmaGraphqlClient(emmaClient))

        injectBkkRepository(viewModel, bkkRepo)
        injectStopsRepository(viewModel, emmaRepo)

        awaitUntil { viewModel.state.value.network != null }

        // Area 1
        viewModel.updateMapArea(area1Bounds, area1Center)
        awaitUntil { totalCalls.get() >= 1 && viewModel.vehicles.value.any { it.id == "emma:BKK:BUS_1" } }

        // Area 2 (different area, not suppressed by area 1 snapshot)
        viewModel.updateMapArea(area2Bounds, area2Center)
        awaitUntil { totalCalls.get() >= 2 && viewModel.vehicles.value.any { it.id == "emma:BKK:BUS_2" } }

        assertTrue("New HTTP request must be dispatched for new area", totalCalls.get() >= 2)
    }

    @Test
    fun returningToCachedBiatorbagyWhileBudapestInFlight_cancelsBudapestJob_withoutExtraHttpCall() = runBlocking {
        val biatorbagyBounds = MapBounds(18.81, 47.46, 18.83, 47.48)
        val biatorbagyCenter = GeoPoint(47.47, 18.82)
        val budapestBounds = MapBounds(19.04, 47.49, 19.06, 47.51)
        val budapestCenter = GeoPoint(47.50, 19.05)

        val biatorbagyCalls = AtomicInteger(0)
        val budapestCalls = AtomicInteger(0)
        val budapestStarted = CountDownLatch(1)
        val releaseBudapest = CountDownLatch(1)

        val bkkClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val path = url.encodedPath
            if (path.contains("vehicles-for-location")) {
                val lon = url.queryParameter("lon")?.toDoubleOrNull() ?: 0.0
                if (lon < 18.9) {
                    biatorbagyCalls.incrementAndGet()
                    val body = vehicleJsonResponse(listOf("BKK_BIATORBAGY_1"), lat = 47.47, lon = 18.82)
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(body.toResponseBody()).build()
                } else {
                    budapestCalls.incrementAndGet()
                    budapestStarted.countDown()
                    releaseBudapest.await(5, TimeUnit.SECONDS)
                    val body = vehicleJsonResponse(listOf("BKK_BUDAPEST_1"), lat = 47.50, lon = 19.05)
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(body.toResponseBody()).build()
                }
            } else {
                val emptyJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject())
                    .put("list", JSONArray())
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(emptyJson.toResponseBody()).build()
            }
        }.build()

        val emmaClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"station":null,"stopsByBbox":[]}}""".toResponseBody()).build()
        }.build()

        val viewModel = TransitViewModel(app).also { models += it }
        val bkkRepo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), bkkClient, { Instant.now() }, BkkRequestBudget()))
        val emmaRepo = EmmaStopsRepository(app, EmmaGraphqlClient(emmaClient))

        injectBkkRepository(viewModel, bkkRepo)
        injectStopsRepository(viewModel, emmaRepo)

        awaitUntil { viewModel.state.value.network != null }

        try {
            // 1. Initial Biatorbágy request succeeds
            viewModel.updateMapArea(biatorbagyBounds, biatorbagyCenter)
            awaitUntil { biatorbagyCalls.get() == 1 && viewModel.vehicles.value.any { it.id == "emma:BKK:BIATORBAGY_1" } }
            val biatorbagyJob = bkkVehicleJob(viewModel)
            assertNotNull("Initial Biatorbágy job should exist", biatorbagyJob)
            awaitUntil { !biatorbagyJob!!.isActive }
            assertEquals(1, biatorbagyCalls.get())

            // 2. Pan to Budapest: Budapest response is held by latch
            viewModel.updateMapArea(budapestBounds, budapestCenter)
            assertTrue("Budapest HTTP request should have started", budapestStarted.await(5, TimeUnit.SECONDS))
            val budapestJob = bkkVehicleJob(viewModel)
            assertNotNull("Budapest job should exist", budapestJob)
            assertTrue("Budapest job should be active while held by latch", budapestJob!!.isActive)

            // 3. Return to Biatorbágy within cache window
            // Because request != bkkVehicleRequest (Biatorbágy != Budapest), ensureBkkVehicles does not take
            // the cache early-return without cancelling the in-flight job; instead it cancels budapestJob
            // and starts Biatorbágy fetch which reuses the cached client body without a new HTTP call.
            viewModel.updateMapArea(biatorbagyBounds, biatorbagyCenter)

            // Ensure old Budapest job is cancelled
            awaitUntil { budapestJob.isCancelled }
            assertTrue("Old Budapest job must be cancelled upon returning to Biatorbágy", budapestJob.isCancelled)

            val returnBiatorbagyJob = bkkVehicleJob(viewModel)
            assertNotNull("New Biatorbágy job should exist", returnBiatorbagyJob)
            assertNotSame("New Biatorbágy job must replace the cancelled Budapest job", budapestJob, returnBiatorbagyJob)
            awaitUntil { !returnBiatorbagyJob!!.isActive }

            // 4. Release Budapest latch
            releaseBudapest.countDown()
            awaitUntil { budapestJob.isCompleted }
            ShadowLooper.idleMainLooper()

            // 5. Ensure old Budapest job cannot overwrite current A snapshot and cached A produces no additional HTTP A call
            assertEquals("Cached Biatorbágy must produce no additional HTTP call", 1, biatorbagyCalls.get())
            assertEquals("Budapest HTTP call was initiated once", 1, budapestCalls.get())
            assertTrue(
                "Biatorbágy vehicles must remain published",
                viewModel.vehicles.value.any { it.id == "emma:BKK:BIATORBAGY_1" }
            )
            assertFalse(
                "Old Budapest response must not overwrite current Biatorbágy snapshot",
                viewModel.vehicles.value.any { it.id == "emma:BKK:BUDAPEST_1" }
            )
        } finally {
            releaseBudapest.countDown()
        }
    }

    @Test
    fun nestedViewportContainedByCoveredArea_reusesSnapshot_withoutExtraHttpRequest() = runBlocking {
        val largeBounds = MapBounds(19.00, 47.45, 19.10, 47.55)
        val largeCenter = GeoPoint(47.50, 19.05)
        val nestedBounds = MapBounds(19.04, 47.49, 19.06, 47.51)
        val nestedCenter = GeoPoint(47.50, 19.05)

        val largeArea = BkkWorkerRepository.queryArea(largeBounds)
        val nestedArea = BkkWorkerRepository.queryArea(nestedBounds)
        assertNotNull(largeArea)
        assertNotNull(nestedArea)
        assertNotEquals("queryArea for nested view must differ from large view", largeArea, nestedArea)
        assertTrue(
            "nestedBounds must be geometrically contained within largeArea",
            largeArea!!.west <= nestedBounds.west && largeArea.east >= nestedBounds.east &&
                largeArea.south <= nestedBounds.south && largeArea.north >= nestedBounds.north
        )

        val httpCalls = AtomicInteger(0)
        val bkkClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            if (url.encodedPath.contains("vehicles-for-location")) {
                httpCalls.incrementAndGet()
                val body = vehicleJsonResponse(listOf("BKK_COVERING_1"), lat = 47.50, lon = 19.05)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody()).build()
            } else {
                val emptyJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject())
                    .put("list", JSONArray())
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(emptyJson.toResponseBody()).build()
            }
        }.build()

        val emmaClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"station":null,"stopsByBbox":[]}}""".toResponseBody()).build()
        }.build()

        val viewModel = TransitViewModel(app).also { models += it }
        val bkkRepo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), bkkClient, { Instant.now() }, BkkRequestBudget()))
        val emmaRepo = EmmaStopsRepository(app, EmmaGraphqlClient(emmaClient))

        injectBkkRepository(viewModel, bkkRepo)
        injectStopsRepository(viewModel, emmaRepo)

        awaitUntil { viewModel.state.value.network != null }

        // 1. Initial large viewport request succeeds
        viewModel.updateMapArea(largeBounds, largeCenter)
        awaitUntil { httpCalls.get() >= 1 && viewModel.vehicles.value.any { it.id == "emma:BKK:COVERING_1" } }
        val firstJob = bkkVehicleJob(viewModel)
        assertNotNull(firstJob)
        awaitUntil { !firstJob!!.isActive }
        val callsAfterFirst = httpCalls.get()

        // 2. Move/zoom into smaller viewport geometrically contained by largeArea
        viewModel.updateMapArea(nestedBounds, nestedCenter)
        ShadowLooper.idleMainLooper()
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()

        // 3. Must reuse existing area/snapshot within fresh poll window without extra HTTP request
        assertEquals(
            "Must reuse existing snapshot without extra HTTP request when nested viewport is covered by existing query area",
            callsAfterFirst,
            httpCalls.get()
        )
        assertTrue(
            "Vehicles must remain published",
            viewModel.vehicles.value.any { it.id == "emma:BKK:COVERING_1" }
        )
    }

    @Test
    fun inFlightCoveringAreaRequest_sharedWithNestedViewport_withoutCancelOrRefetch() = runBlocking {
        val largeBounds = MapBounds(19.00, 47.45, 19.10, 47.55)
        val largeCenter = GeoPoint(47.50, 19.05)
        val nestedBounds = MapBounds(19.04, 47.49, 19.06, 47.51)
        val nestedCenter = GeoPoint(47.50, 19.05)

        val largeArea = BkkWorkerRepository.queryArea(largeBounds)
        val nestedArea = BkkWorkerRepository.queryArea(nestedBounds)
        assertNotNull(largeArea)
        assertNotNull(nestedArea)
        assertNotEquals("queryArea for nested view must differ from large view", largeArea, nestedArea)
        assertTrue(
            "nestedBounds must be geometrically contained within largeArea",
            largeArea!!.west <= nestedBounds.west && largeArea.east >= nestedBounds.east &&
                largeArea.south <= nestedBounds.south && largeArea.north >= nestedBounds.north
        )

        val coveringStarted = CountDownLatch(1)
        val releaseCovering = CountDownLatch(1)
        val coveringCalls = AtomicInteger(0)
        val totalCalls = AtomicInteger(0)

        val bkkClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            if (url.encodedPath.contains("vehicles-for-location")) {
                totalCalls.incrementAndGet()
                val latSpan = url.queryParameter("latSpan")?.toDoubleOrNull() ?: 0.0
                if (latSpan >= 0.05) {
                    coveringCalls.incrementAndGet()
                    coveringStarted.countDown()
                    releaseCovering.await(5, TimeUnit.SECONDS)
                    val body = vehicleJsonResponse(listOf("BKK_SHARED_1"), lat = 47.50, lon = 19.05)
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(body.toResponseBody()).build()
                } else {
                    val body = vehicleJsonResponse(listOf("BKK_NESTED_PREMATURE"), lat = 47.50, lon = 19.05)
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(body.toResponseBody()).build()
                }
            } else {
                val emptyJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject())
                    .put("list", JSONArray())
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(emptyJson.toResponseBody()).build()
            }
        }.build()

        val emmaClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"station":null,"stopsByBbox":[]}}""".toResponseBody()).build()
        }.build()

        val viewModel = TransitViewModel(app).also { models += it }
        val bkkRepo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), bkkClient, { Instant.now() }, BkkRequestBudget()))
        val emmaRepo = EmmaStopsRepository(app, EmmaGraphqlClient(emmaClient))

        injectBkkRepository(viewModel, bkkRepo)
        injectStopsRepository(viewModel, emmaRepo)

        awaitUntil { viewModel.state.value.network != null }

        try {
            // 1. Initial large covering area request starts and is held in-flight
            viewModel.updateMapArea(largeBounds, largeCenter)
            assertTrue("Covering HTTP request should have started", coveringStarted.await(5, TimeUnit.SECONDS))
            val coveringJob = bkkVehicleJob(viewModel)
            assertNotNull("Covering job should exist", coveringJob)
            assertTrue("Covering job should be active", coveringJob!!.isActive)

            // 2. While covering request is in-flight, viewport moves/zooms into nested viewport
            viewModel.updateMapArea(nestedBounds, nestedCenter)
            ShadowLooper.idleMainLooper()

            // Covering request must NOT be cancelled, and nested viewport should share the in-flight request
            assertFalse("In-flight covering request must not be cancelled by nested viewport", coveringJob.isCancelled)
            assertEquals("Covering request should be shared instead of refetching for nested viewport", 1, totalCalls.get())

            // 3. Release covering request
            releaseCovering.countDown()
            awaitUntil { coveringJob.isCompleted }
            ShadowLooper.idleMainLooper()

            // 4. Vehicles from the shared covering request must be published, with no additional HTTP refetch
            awaitUntil { viewModel.vehicles.value.any { it.id == "emma:BKK:SHARED_1" } }
            assertEquals("No extra HTTP request should be dispatched; in-flight request was shared", 1, totalCalls.get())
            assertTrue(
                "Covering vehicles should be published for nested viewport",
                viewModel.vehicles.value.any { it.id == "emma:BKK:SHARED_1" }
            )
            assertFalse(
                "Premature nested refetch vehicles should not exist",
                viewModel.vehicles.value.any { it.id == "emma:BKK:NESTED_PREMATURE" }
            )
        } finally {
            releaseCovering.countDown()
        }
    }

    @Test
    fun updateMapArea_fetchesAndPublishesBkkStopDirections_evenWhenStopIndexLoaded() = runBlocking {
        val budapestBounds = MapBounds(19.04, 47.49, 19.06, 47.51)
        val budapestCenter = GeoPoint(47.50, 19.05)
        val stopDirectionsCalls = AtomicInteger(0)

        val bkkClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            val path = url.encodedPath
            if (path.contains("stop-directions-for-location")) {
                stopDirectionsCalls.incrementAndGet()
                val json = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("list", JSONArray().put(JSONObject()
                        .put("id", "BKK_F01020")
                        .put("bearing", 182.0)
                    ))
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(json.toResponseBody()).build()
            } else {
                val emptyJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject())
                    .put("list", JSONArray())
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(emptyJson.toResponseBody()).build()
            }
        }.build()

        val emmaClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"station":null,"stopsByBbox":[]}}""".toResponseBody()).build()
        }.build()

        val viewModel = TransitViewModel(app).also { models += it }
        val bkkRepo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), bkkClient, { Instant.now() }, BkkRequestBudget()))
        val emmaRepo = EmmaStopsRepository(app, EmmaGraphqlClient(emmaClient))

        injectBkkRepository(viewModel, bkkRepo)
        injectStopsRepository(viewModel, emmaRepo)

        awaitUntil { viewModel.state.value.network != null }

        // Set stopIndexLoaded = true
        mutableState(viewModel).update { it.copy(stopIndexLoaded = true) }
        assertTrue(viewModel.state.value.stopIndexLoaded)

        // Trigger updateMapArea
        viewModel.updateMapArea(budapestBounds, budapestCenter)

        // Must fetch /api/stop-directions-for-location.json and publish bkkStopDirections to UiState
        awaitUntil(timeoutMs = 1500) {
            stopDirectionsCalls.get() >= 1 && viewModel.state.value.bkkStopDirections.isNotEmpty()
        }

        assertTrue("stop-directions-for-location endpoint must be queried", stopDirectionsCalls.get() >= 1)
        assertEquals(182.0, viewModel.state.value.bkkStopDirections["emma:BKK:F01020"]!!, 1e-4)
    }

    private fun stopDirectionsJsonResponse(id: String, bearing: Double): String = JSONObject().put("status", "OK").put("data", JSONObject()
        .put("list", JSONArray().put(JSONObject()
            .put("id", id)
            .put("bearing", bearing)
        ))
    ).toString()

    @Test
    fun stopDirections_nestedViewportContainedByCoveredArea_reusesDirectionCache_withoutExtraHttpCall() = runBlocking {
        val largeBounds = MapBounds(19.00, 47.45, 19.10, 47.55)
        val largeCenter = GeoPoint(47.50, 19.05)
        val nestedBounds = MapBounds(19.04, 47.49, 19.06, 47.51)
        val nestedCenter = GeoPoint(47.50, 19.05)

        val stopDirectionsCalls = AtomicInteger(0)
        val bkkClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            if (url.encodedPath.contains("stop-directions-for-location")) {
                stopDirectionsCalls.incrementAndGet()
                val json = stopDirectionsJsonResponse("BKK_F01020", 90.0)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(json.toResponseBody()).build()
            } else {
                val emptyJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject())
                    .put("list", JSONArray())
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(emptyJson.toResponseBody()).build()
            }
        }.build()

        val emmaClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"station":null,"stopsByBbox":[]}}""".toResponseBody()).build()
        }.build()

        val viewModel = TransitViewModel(app).also { models += it }
        val bkkRepo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), bkkClient, { Instant.now() }, BkkRequestBudget()))
        val emmaRepo = EmmaStopsRepository(app, EmmaGraphqlClient(emmaClient))

        injectBkkRepository(viewModel, bkkRepo)
        injectStopsRepository(viewModel, emmaRepo)

        awaitUntil { viewModel.state.value.network != null }
        mutableState(viewModel).update { it.copy(stopIndexLoaded = true) }

        // 1. Initial query for large covering area
        viewModel.updateMapArea(largeBounds, largeCenter)
        awaitUntil { stopDirectionsCalls.get() == 1 && viewModel.state.value.bkkStopDirections.containsKey("emma:BKK:F01020") }
        assertEquals(1, stopDirectionsCalls.get())

        // 2. Pan/zoom into nested viewport contained within large area
        viewModel.updateMapArea(nestedBounds, nestedCenter)
        ShadowLooper.idleMainLooper()
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()

        // 3. Cache must be reused without dispatching a new HTTP request
        assertEquals("Nested viewport must reuse direction cache without extra HTTP call", 1, stopDirectionsCalls.get())
        assertTrue("Directions must remain present in UiState", viewModel.state.value.bkkStopDirections.containsKey("emma:BKK:F01020"))
    }

    @Test
    fun stopDirections_inFlightCoveringRequest_sharedWithNestedViewport_withoutCancelOrRefetch() = runBlocking {
        val largeBounds = MapBounds(19.00, 47.45, 19.10, 47.55)
        val largeCenter = GeoPoint(47.50, 19.05)
        val nestedBounds = MapBounds(19.04, 47.49, 19.06, 47.51)
        val nestedCenter = GeoPoint(47.50, 19.05)

        val coveringStarted = CountDownLatch(1)
        val releaseCovering = CountDownLatch(1)
        val stopDirectionsCalls = AtomicInteger(0)

        val bkkClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            if (url.encodedPath.contains("stop-directions-for-location")) {
                stopDirectionsCalls.incrementAndGet()
                coveringStarted.countDown()
                releaseCovering.await(5, TimeUnit.SECONDS)
                val json = stopDirectionsJsonResponse("BKK_SHARED_STOP", 180.0)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(json.toResponseBody()).build()
            } else {
                val emptyJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject())
                    .put("list", JSONArray())
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(emptyJson.toResponseBody()).build()
            }
        }.build()

        val emmaClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"station":null,"stopsByBbox":[]}}""".toResponseBody()).build()
        }.build()

        val viewModel = TransitViewModel(app).also { models += it }
        val bkkRepo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), bkkClient, { Instant.now() }, BkkRequestBudget()))
        val emmaRepo = EmmaStopsRepository(app, EmmaGraphqlClient(emmaClient))

        injectBkkRepository(viewModel, bkkRepo)
        injectStopsRepository(viewModel, emmaRepo)

        awaitUntil { viewModel.state.value.network != null }
        mutableState(viewModel).update { it.copy(stopIndexLoaded = true) }

        try {
            // 1. Initial large covering area request starts and is held in-flight
            viewModel.updateMapArea(largeBounds, largeCenter)
            assertTrue("Covering stop-directions HTTP request should have started", coveringStarted.await(5, TimeUnit.SECONDS))
            assertEquals(1, stopDirectionsCalls.get())

            // 2. Viewport zooms into nested viewport while covering request is still in-flight
            viewModel.updateMapArea(nestedBounds, nestedCenter)
            ShadowLooper.idleMainLooper()

            // In-flight covering request must be shared: no new HTTP call should be dispatched
            assertEquals("In-flight request should be shared without refetching", 1, stopDirectionsCalls.get())

            // 3. Release covering request
            releaseCovering.countDown()
            awaitUntil { viewModel.state.value.bkkStopDirections.containsKey("emma:BKK:SHARED_STOP") }

            assertEquals("No extra HTTP request should be made", 1, stopDirectionsCalls.get())
            assertEquals(180.0, viewModel.state.value.bkkStopDirections["emma:BKK:SHARED_STOP"]!!, 1e-4)
        } finally {
            releaseCovering.countDown()
        }
    }

    @Test
    fun stopDirections_offlineModeCancelsInFlightAndPreventsStalePublication() = runBlocking {
        val budapestBounds = MapBounds(19.04, 47.49, 19.06, 47.51)
        val budapestCenter = GeoPoint(47.50, 19.05)

        val requestStarted = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val stopDirectionsCalls = AtomicInteger(0)

        val bkkClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            if (url.encodedPath.contains("stop-directions-for-location")) {
                stopDirectionsCalls.incrementAndGet()
                requestStarted.countDown()
                releaseRequest.await(5, TimeUnit.SECONDS)
                val json = stopDirectionsJsonResponse("BKK_STALE_STOP", 270.0)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(json.toResponseBody()).build()
            } else {
                val emptyJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject())
                    .put("list", JSONArray())
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(emptyJson.toResponseBody()).build()
            }
        }.build()

        val emmaClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"station":null,"stopsByBbox":[]}}""".toResponseBody()).build()
        }.build()

        val viewModel = TransitViewModel(app).also { models += it }
        val bkkRepo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), bkkClient, { Instant.now() }, BkkRequestBudget()))
        val emmaRepo = EmmaStopsRepository(app, EmmaGraphqlClient(emmaClient))

        injectBkkRepository(viewModel, bkkRepo)
        injectStopsRepository(viewModel, emmaRepo)

        awaitUntil { viewModel.state.value.network != null }
        mutableState(viewModel).update { it.copy(stopIndexLoaded = true) }

        try {
            // 1. Start fetching stop-directions
            viewModel.updateMapArea(budapestBounds, budapestCenter)
            assertTrue("HTTP request should be initiated", requestStarted.await(5, TimeUnit.SECONDS))

            // 2. User switches to offline mode while request is in-flight
            viewModel.setOfflineMode(true)
            ShadowLooper.idleMainLooper()

            // 3. Complete HTTP response
            releaseRequest.countDown()
            ShadowLooper.idleMainLooper()
            Thread.sleep(50)
            ShadowLooper.idleMainLooper()

            // 4. Stale publication must NOT occur while offline
            assertFalse(
                "Stale stop-directions must not be published when switched to offline mode",
                viewModel.state.value.bkkStopDirections.containsKey("emma:BKK:STALE_STOP")
            )
        } finally {
            releaseRequest.countDown()
            viewModel.setOfflineMode(false)
        }
    }

    @Test
    fun stopDirections_failureAllowsImmediateRetry_withoutFalseCoverage() = runBlocking {
        val budapestBounds = MapBounds(19.04, 47.49, 19.06, 47.51)
        val budapestCenter = GeoPoint(47.50, 19.05)

        val stopDirectionsCalls = AtomicInteger(0)
        var shouldFail = true

        val bkkClient = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            if (url.encodedPath.contains("stop-directions-for-location")) {
                stopDirectionsCalls.incrementAndGet()
                if (shouldFail) {
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(502).message("Bad Gateway")
                        .body("""{"status":"ERROR","message":"Upstream failed"}""".toResponseBody()).build()
                } else {
                    val json = stopDirectionsJsonResponse("BKK_RETRY_STOP", 45.0)
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(json.toResponseBody()).build()
                }
            } else {
                val emptyJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject())
                    .put("list", JSONArray())
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(emptyJson.toResponseBody()).build()
            }
        }.build()

        val emmaClient = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":{"station":null,"stopsByBbox":[]}}""".toResponseBody()).build()
        }.build()

        val viewModel = TransitViewModel(app).also { models += it }
        val bkkRepo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), bkkClient, { Instant.now() }, BkkRequestBudget()))
        val emmaRepo = EmmaStopsRepository(app, EmmaGraphqlClient(emmaClient))

        injectBkkRepository(viewModel, bkkRepo)
        injectStopsRepository(viewModel, emmaRepo)

        awaitUntil { viewModel.state.value.network != null }
        mutableState(viewModel).update { it.copy(stopIndexLoaded = true) }

        // 1. Initial attempt fails with 502
        shouldFail = true
        viewModel.updateMapArea(budapestBounds, budapestCenter)
        awaitUntil { stopDirectionsCalls.get() >= 1 }
        ShadowLooper.idleMainLooper()
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()

        assertEquals("First attempt should have been made", 1, stopDirectionsCalls.get())
        assertFalse(
            "Directions must not be published on failure",
            viewModel.state.value.bkkStopDirections.containsKey("emma:BKK:RETRY_STOP")
        )

        // 2. Retry with failure cleared: must not be suppressed by false coverage
        shouldFail = false
        viewModel.updateMapArea(budapestBounds, budapestCenter)
        awaitUntil {
            stopDirectionsCalls.get() >= 2 && viewModel.state.value.bkkStopDirections.containsKey("emma:BKK:RETRY_STOP")
        }

        assertEquals("Second attempt must be dispatched without being blocked by false coverage", 2, stopDirectionsCalls.get())
        assertEquals(45.0, viewModel.state.value.bkkStopDirections["emma:BKK:RETRY_STOP"]!!, 1e-4)
    }
    @Test
    fun loadDepartureTripAppendsTripPatternAndDoesNotFetchItAgain() {
        val trip = "volan:volan_2811_21_20261102"
        val calls = AtomicInteger(0)
        val json = JSONObject().put("status", "OK").put("data", JSONObject()
            .put("references", JSONObject()
                .put("routes", JSONObject().put("volan_760", JSONObject()
                    .put("id", "volan_760").put("type", "BUS").put("shortName", "760").put("longName", "Bus")))
                .put("trips", JSONObject().put("volan_2811_21_20261102", JSONObject()
                    .put("routeId", "volan_760").put("tripHeadsign", "Terminus").put("directionId", "0")))
                .put("stops", JSONObject()
                .put("BKK_S1", JSONObject().put("id", "BKK_S1").put("name", "Short working stop").put("lat", 47.49).put("lon", 19.05))
                .put("BKK_S2", JSONObject().put("id", "BKK_S2").put("name", "Terminus").put("lat", 47.50).put("lon", 19.06))))
            .put("entry", JSONObject().put("tripId", "volan_2811_21_20261102")
                .put("stopTimes", JSONArray()
                    .put(JSONObject().put("stopId", "BKK_S1").put("departureTime", now.epochSecond))
                    .put(JSONObject().put("stopId", "BKK_S2").put("arrivalTime", now.epochSecond + 300)))))
            .toString()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody()).build()
        }.build()
        val viewModel = TransitViewModel(app).also { models += it }
        injectBkkRepository(viewModel, BkkWorkerRepository(BkkWorkerClient(
            "https://worker.test".toHttpUrl(), client, { now }, BkkRequestBudget())))
        val route = TransitRoute("volan:760", "760", "Bus", emptyList(), "volan")
        val main = LinePattern("main", setOf("main-code"), "Budapest", emptyList(), emptyList())
        mutableState(viewModel).update { it.copy(lineDetails = mapOf(route.id to LineDetails(route, listOf(main)))) }
        val departure = Departure(route.id, "760", "Terminus", "emma:BKK:S1", now, now,
            ArrivalKind.LIVE, directionId = trip, sourceDirectionId = "0")

        viewModel.loadDepartureTrip(route.id, departure)
        assertEquals(route.id, viewModel.state.value.loadingLineId)
        awaitUntil { viewModel.state.value.lineDetails[route.id]?.patterns?.size == 2 && viewModel.state.value.loadingLineId == null }
        val patterns = viewModel.state.value.lineDetails.getValue(route.id).patterns
        assertEquals(main, patterns.first())
        assertEquals("trip:$trip", patterns.last().id)
        assertEquals(setOf(trip), patterns.last().codes)
        assertEquals("0", patterns.last().directionId)
        assertEquals("Terminus", patterns.last().headsign)
        assertEquals(listOf("emma:BKK:S1", "emma:BKK:S2"), patterns.last().stops.map { it.id })
        viewModel.loadDepartureTrip(route.id, departure)
        ShadowLooper.idleMainLooper()
        assertEquals(1, calls.get())
        assertEquals(patterns, viewModel.state.value.lineDetails.getValue(route.id).patterns)
    }

}
