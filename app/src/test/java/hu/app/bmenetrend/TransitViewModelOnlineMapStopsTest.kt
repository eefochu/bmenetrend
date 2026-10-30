package hu.app.bmenetrend

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import hu.app.bmenetrend.ui.UiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class TransitViewModelOnlineMapStopsTest {

    private lateinit var app: Application

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        try {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        } catch (_: Exception) {}
        NetworkAccessPolicy.initialize(app)
        NetworkAccessPolicy.setOffline(app, false)
        File(app.filesDir, "emma-stop-index-v2.json").delete()
        File(app.filesDir, "emma-stop-index-v1.json").delete()
    }

    private fun sampleStopJson(id: String, name: String, lat: Double, lon: Double) = JSONObject()
        .put("gtfsId", id)
        .put("name", name)
        .put("lat", lat)
        .put("lon", lon)
        .put("vehicleMode", "BUS")
        .put("platformCode", "1")
        .put("locationType", "STOP")
        .put("parentStation", JSONObject().put("gtfsId", "hkir:station_$id").put("name", "$name Station"))

    private fun bboxResponse(vararg stops: JSONObject) = JSONObject()
        .put("data", JSONObject().put("stopsByBbox", JSONArray(stops.toList())))
        .toString()

    private fun injectRepository(viewModel: TransitViewModel, repo: EmmaStopsRepository) {
        val field = TransitViewModel::class.java.getDeclaredField("stopsRepository")
        field.isAccessible = true
        field.set(viewModel, repo)
    }

    @Test
    fun initialMapDotsPublishedEarlyWithoutWaitingForGlobalIndex() {
        runBlocking {
            val nationalLatch = CompletableDeferred<Unit>()
            val mapAreaCalls = AtomicInteger()

            val mockClient = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                val body = buffer.readUtf8()

                if (body.contains("MapStopsInArea")) {
                    mapAreaCalls.incrementAndGet()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(bboxResponse(sampleStopJson("BKK:early_dot_1", "Early Dot", 47.49, 19.05)).toResponseBody())
                        .build()
                } else if (body.contains("stops {")) {
                    runBlocking { withTimeout(5000) { nationalLatch.await() } }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(JSONObject().put("data", JSONObject().put("stops", JSONArray())).toString().toResponseBody())
                        .build()
                } else {
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("{\"data\":{}}".toResponseBody()).build()
                }
            }.build()

            val repo = EmmaStopsRepository(app, EmmaGraphqlClient(mockClient))
            val viewModel = TransitViewModel(app)
            injectRepository(viewModel, repo)

            assertFalse("stopIndexLoaded must initially be false", viewModel.state.value.stopIndexLoaded)

            // Trigger map area in Budapest center
            val bounds = MapBounds(19.04, 47.48, 19.06, 47.50)
            viewModel.updateMapArea(bounds, GeoPoint(47.49, 19.05))

            // Allow coroutine on Main dispatcher to run
            ShadowLooper.idleMainLooper()
            delay(100)
            ShadowLooper.idleMainLooper()

            // Map area stops should be published early!
            val published = viewModel.state.value.emmaStops
            assertTrue("emmaStops must contain early dot", published.any { it.id == "emma:BKK:early_dot_1" })
            assertFalse("stopIndexLoaded must STILL be false (national index not marked loaded)", viewModel.state.value.stopIndexLoaded)
            assertEquals(1, mapAreaCalls.get())

            nationalLatch.complete(Unit)
        }
    }

    @Test
    fun zoomThresholdGuardSkipsNationwideOrZoomedOutMapArea() {
        runBlocking {
            val mapAreaCalls = AtomicInteger()
            val mockClient = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer()
                chain.request().body?.writeTo(buffer)
                if (buffer.readUtf8().contains("MapStopsInArea")) mapAreaCalls.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(bboxResponse().toResponseBody()).build()
            }.build()

            val repo = EmmaStopsRepository(app, EmmaGraphqlClient(mockClient))
            val viewModel = TransitViewModel(app)
            injectRepository(viewModel, repo)

            // Zoomed out across all of Hungary: lat diff = 2.9 (> 1.0)
            val zoomedOutBounds = MapBounds(16.5, 45.8, 22.0, 48.5)
            viewModel.updateMapArea(zoomedOutBounds, GeoPoint(47.0, 19.0))
            ShadowLooper.idleMainLooper()

            assertEquals("Zoomed out bounds must not trigger mapArea query", 0, mapAreaCalls.get())

            // Lon diff = 1.8 (> 1.5)
            val wideBounds = MapBounds(18.0, 47.4, 19.9, 47.6)
            viewModel.updateMapArea(wideBounds, GeoPoint(47.5, 19.0))
            ShadowLooper.idleMainLooper()

            assertEquals("Wide bounds must not trigger mapArea query", 0, mapAreaCalls.get())
        }
    }

    @Test
    fun coveredBoundsAvoidsDuplicateNetworkQueries() {
        runBlocking {
            val mapAreaCalls = AtomicInteger()
            val mockClient = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer()
                chain.request().body?.writeTo(buffer)
                if (buffer.readUtf8().contains("MapStopsInArea")) mapAreaCalls.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(bboxResponse(sampleStopJson("BKK:dot1", "Dot 1", 47.5, 19.05)).toResponseBody()).build()
            }.build()

            val repo = EmmaStopsRepository(app, EmmaGraphqlClient(mockClient))
            val viewModel = TransitViewModel(app)
            injectRepository(viewModel, repo)

            // First view: lat [47.48, 47.52] (height = 0.04), lon [19.04, 19.08] (width = 0.04)
            val bounds1 = MapBounds(19.04, 47.48, 19.08, 47.52)
            viewModel.updateMapArea(bounds1, GeoPoint(47.50, 19.06))
            ShadowLooper.idleMainLooper()
            delay(100)
            ShadowLooper.idleMainLooper()
            assertEquals(1, mapAreaCalls.get())

            // Small pan completely inside the 20% padded area:
            val bounds2 = MapBounds(19.042, 47.482, 19.078, 47.518)
            viewModel.updateMapArea(bounds2, GeoPoint(47.50, 19.06))
            ShadowLooper.idleMainLooper()

            // Should NOT trigger a second call because bounds2 is covered by bounds1's padded cache!
            assertEquals("Panning inside padded bounds must not re-query", 1, mapAreaCalls.get())

            // Large pan outside the padded area:
            val bounds3 = MapBounds(19.15, 47.55, 19.19, 47.59)
            viewModel.updateMapArea(bounds3, GeoPoint(47.57, 19.17))
            ShadowLooper.idleMainLooper()
            delay(100)
            ShadowLooper.idleMainLooper()
            assertEquals("Panning outside padded bounds must re-query", 2, mapAreaCalls.get())
        }
    }

    @Test
    fun hungaryBorderClampingAndInvalidBounds() {
        runBlocking {
            var requestedVariables: JSONObject? = null
            val mockClient = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer()
                chain.request().body?.writeTo(buffer)
                val json = JSONObject(buffer.readUtf8())
                requestedVariables = json.getJSONObject("variables")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(bboxResponse().toResponseBody()).build()
            }.build()

            val repo = EmmaStopsRepository(app, EmmaGraphqlClient(mockClient))
            val viewModel = TransitViewModel(app)
            injectRepository(viewModel, repo)

            // View near south-west boundary of Hungary
            val swBounds = MapBounds(16.11, 45.71, 16.15, 45.75)
            viewModel.updateMapArea(swBounds, GeoPoint(45.73, 16.13))
            ShadowLooper.idleMainLooper()
            delay(100)
            ShadowLooper.idleMainLooper()

            assertNotNull(requestedVariables)
            assertTrue("South margin must be clamped to >= 45.7", requestedVariables!!.getDouble("south") >= 45.7)
            assertTrue("West margin must be clamped to >= 16.1", requestedVariables!!.getDouble("west") >= 16.1)
        }
    }

    @Test
    fun rapidPanStaleCompletionCancellation() {
        runBlocking {
            val mockClient = OkHttpClient.Builder().addInterceptor { chain ->
                val buffer = Buffer()
                chain.request().body?.writeTo(buffer)
                val json = JSONObject(buffer.readUtf8())
                val vars = json.getJSONObject("variables")
                val south = vars.getDouble("south")

                // Delay slow response for first pan
                if (south < 47.45) {
                    Thread.sleep(150)
                }

                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(bboxResponse(sampleStopJson("BKK:stop_$south", "Stop $south", south, 19.05)).toResponseBody()).build()
            }.build()

            val repo = EmmaStopsRepository(app, EmmaGraphqlClient(mockClient))
            val viewModel = TransitViewModel(app)
            injectRepository(viewModel, repo)

            // Pan 1
            viewModel.updateMapArea(MapBounds(19.04, 47.40, 19.08, 47.44), GeoPoint(47.42, 19.06))
            // Rapid Pan 2 immediately after
            viewModel.updateMapArea(MapBounds(19.04, 47.50, 19.08, 47.54), GeoPoint(47.52, 19.06))

            delay(250)
            ShadowLooper.idleMainLooper()

            // Only the second pan's results should be in emmaStops
            val stops = viewModel.state.value.emmaStops
            assertFalse("Stale pan 1 must be cancelled/discarded", stops.any { it.id.contains("47.392") })
            assertTrue("Latest pan 2 must be present in emmaStops", stops.any { it.id.contains("47.492") })
        }
    }

    @Test
    fun offlineModeGuardAndMidFlightCancellation() {
        runBlocking {
            val mapAreaCalls = AtomicInteger()
            val mockClient = OkHttpClient.Builder().addInterceptor { chain ->
                mapAreaCalls.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(bboxResponse().toResponseBody()).build()
            }.build()

            // A missing pack intentionally restores online mode; install a real catalogue
            // so this test exercises the offline guard rather than the missing-pack fallback.
            val store = hu.app.bmenetrend.offline.OfflinePackStore(app)
            val stage = store.staging()
            hu.app.bmenetrend.offline.GtfsImporter.create(stage).use { db ->
                db.execSQL("INSERT INTO stops VALUES (1,'bkk:fixture','Fixture','fixture',47.5,19.0,'','',0,'bkk')")
                db.execSQL("INSERT INTO routes VALUES (1,'bkk:fixture','1','Fixture','BUS','199DCF','bkk')")
                db.execSQL("INSERT INTO trips VALUES (1,'bkk:fixture',1,1,'Fixture','0',0)")
                db.execSQL("CREATE TABLE stop_routes(stop INTEGER,route INTEGER,PRIMARY KEY(stop,route)) WITHOUT ROWID")
            }
            val today = java.time.LocalDate.now()
            store.activate(stage, hu.app.bmenetrend.offline.OfflinePackMetadata(
                "map-guard-fixture", java.time.Instant.now(), today.minusDays(1), today.plusDays(1),
                stage.length(), 1000, 1, 1, listOf("Fixture"), hu.app.bmenetrend.offline.OfflinePackStore.digest(stage)))
            val repo = EmmaStopsRepository(app, EmmaGraphqlClient(mockClient))
            val viewModel = TransitViewModel(app)
            injectRepository(viewModel, repo)

            try {
                // Turn on offline mode
                NetworkAccessPolicy.setOffline(app, true)

                viewModel.updateMapArea(MapBounds(19.04, 47.48, 19.08, 47.52), GeoPoint(47.50, 19.06))
                ShadowLooper.idleMainLooper()

                assertTrue("Fixture must keep offline mode active", NetworkAccessPolicy.offline.value)
                assertEquals("When offline, ensureMapStops must NOT dispatch network queries", 0, mapAreaCalls.get())
            } finally {
                viewModel.viewModelScope.cancel()
                NetworkAccessPolicy.setOffline(app, false)
                store.delete()
            }
        }
    }

    @Test
    fun mapVisibilityGuard() {
        runBlocking {
            val mapAreaCalls = AtomicInteger()
            val mockClient = OkHttpClient.Builder().addInterceptor { chain ->
                mapAreaCalls.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(bboxResponse().toResponseBody()).build()
            }.build()

            val repo = EmmaStopsRepository(app, EmmaGraphqlClient(mockClient))
            val viewModel = TransitViewModel(app)
            injectRepository(viewModel, repo)

            viewModel.setMapVisible(false)

            viewModel.updateMapArea(MapBounds(19.04, 47.48, 19.08, 47.52), GeoPoint(47.50, 19.06))
            ShadowLooper.idleMainLooper()

            assertEquals("When mapVisible is false, ensureMapStops must NOT dispatch queries", 0, mapAreaCalls.get())
        }
    }

    @Test
    fun enrichedRouteIdsPreservedWhenFastBboxReturns() {
        runBlocking {
            val mockClient = OkHttpClient.Builder().addInterceptor { chain ->
                // Bbox returns stop with NO routes
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(bboxResponse(sampleStopJson("BKK:stop_existing", "Existing Stop", 47.50, 19.05)).toResponseBody()).build()
            }.build()

            val repo = EmmaStopsRepository(app, EmmaGraphqlClient(mockClient))
            val viewModel = TransitViewModel(app)
            injectRepository(viewModel, repo)

            // Seed emmaStops with an enriched stop that already has routeIds
            val enrichedStop = TransitStop(
                id = "emma:BKK:stop_existing",
                name = "Existing Stop",
                position = GeoPoint(47.50, 19.05),
                routeIds = listOf("bkk:BKK:route_100E", "bkk:BKK:route_16"),
                providerId = "bkk"
            )
            val stateField = TransitViewModel::class.java.getDeclaredField("mutableState")
            stateField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val mutableState = stateField.get(viewModel) as kotlinx.coroutines.flow.MutableStateFlow<UiState>
            mutableState.value = mutableState.value.copy(emmaStops = listOf(enrichedStop))

            assertEquals(listOf("bkk:BKK:route_100E", "bkk:BKK:route_16"), viewModel.state.value.emmaStops.single().routeIds)

            // Now mapArea returns the same stop without routes
            viewModel.updateMapArea(MapBounds(19.04, 47.48, 19.08, 47.52), GeoPoint(47.50, 19.06))
            ShadowLooper.idleMainLooper()
            delay(100)
            ShadowLooper.idleMainLooper()

            val updatedStop = viewModel.state.value.emmaStops.firstOrNull { it.id == "emma:BKK:stop_existing" }
            assertNotNull(updatedStop)
            assertEquals("Enriched routeIds must be preserved", listOf("bkk:BKK:route_100E", "bkk:BKK:route_16"), updatedStop!!.routeIds)
        }
    }
}
