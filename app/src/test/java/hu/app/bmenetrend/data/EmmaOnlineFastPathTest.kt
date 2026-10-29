package hu.app.bmenetrend.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
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
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class EmmaOnlineFastPathTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "emma-stop-index-v2.json").delete()
        File(context.filesDir, "emma-stop-index-v1.json").delete()
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

    private fun indexResponse(vararg stops: JSONObject) = JSONObject()
        .put("data", JSONObject().put("stops", JSONArray(stops.toList())))
        .toString()

    @Test
    fun mapAreaQueryContainsOnlyBoundedFieldsWithoutRoutes() {
        val query = EmmaStopsParser.MAP_AREA_QUERY
        assertTrue("Query must request stopsByBbox with coordinates", query.contains("stopsByBbox(minLat:\$south,minLon:\$west,maxLat:\$north,maxLon:\$east)"))
        assertTrue("Query must include gtfsId", query.contains("gtfsId"))
        assertTrue("Query must include name", query.contains("name"))
        assertTrue("Query must include lat", query.contains("lat"))
        assertTrue("Query must include lon", query.contains("lon"))
        assertTrue("Query must include vehicleMode", query.contains("vehicleMode"))
        assertTrue("Query must include platformCode", query.contains("platformCode"))
        assertTrue("Query must include locationType", query.contains("locationType"))
        assertTrue("Query must include parentStation", query.contains("parentStation"))

        // Critical: Must NOT query routes in the fast dot path!
        assertFalse("MAP_AREA_QUERY must NOT include routes field", query.contains("routes"))
        assertFalse("MAP_AREA_QUERY must NOT include stoptimes", query.contains("stoptimes"))
    }

    @Test
    fun mapAreaBypassesIndexMutexWhenNationalIndexIsSlowOrBlocked() = runBlocking {
        val indexLatch = CompletableDeferred<Unit>()
        val indexStarted = CompletableDeferred<Unit>()
        val mapAreaCalls = AtomicInteger()
        val indexCalls = AtomicInteger()

        val mockClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val bodyString = buffer.readUtf8()

            if (bodyString.contains("MapStopsInArea")) {
                mapAreaCalls.incrementAndGet()
                // Fast response
                val responseJson = bboxResponse(
                    sampleStopJson("BKK:fast_stop_1", "Fast Stop 1", 47.50, 19.05),
                    sampleStopJson("hkir:fast_stop_2", "Fast Stop 2", 47.51, 19.06)
                )
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseJson.toResponseBody())
                    .build()
            } else if (bodyString.contains("stops {")) {
                indexCalls.incrementAndGet()
                indexStarted.complete(Unit)
                // Block until test releases indexLatch
                runBlocking {
                    withTimeout(5000) {
                        indexLatch.await()
                    }
                }
                val responseJson = indexResponse(
                    sampleStopJson("hkir:national_1", "National 1", 46.0, 18.0)
                )
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseJson.toResponseBody())
                    .build()
            } else {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(400)
                    .message("Unexpected query")
                    .body("{}".toResponseBody())
                    .build()
            }
        }.build()

        val repo = EmmaStopsRepository(context, EmmaGraphqlClient(mockClient))

        // Start slow national index
        val slowIndexDeferred = async { repo.index() }

        // Wait until slow index has started and entered mutex
        indexStarted.await()

        // Call mapArea while index is holding mutex
        val startTime = System.currentTimeMillis()
        val fastResult = repo.mapArea(MapBounds(19.0, 47.4, 19.1, 47.6))
        val elapsed = System.currentTimeMillis() - startTime

        // Map area must complete fast without waiting for the slow index!
        assertTrue("mapArea must return immediately (took $elapsed ms)", elapsed < 1000)
        assertEquals(2, fastResult.stops.size)
        assertEquals("emma:BKK:fast_stop_1", fastResult.stops[0].id)
        assertEquals("emma:hkir:fast_stop_2", fastResult.stops[1].id)
        assertEquals(1, mapAreaCalls.get())
        assertTrue("Index should still be in flight", slowIndexDeferred.isActive)

        // Now release slow index
        indexLatch.complete(Unit)
        val indexResult = slowIndexDeferred.await()
        assertEquals(1, indexResult.catalogue.stops.size)
        assertEquals(1, indexCalls.get())
    }

    @Test
    fun mapAreaSendsCorrectVariablesAndParsesStationsAccurately() = runBlocking {
        var capturedVariables: JSONObject? = null
        val bounds = MapBounds(west = 19.02, south = 47.48, east = 19.08, north = 47.52)

        val mockClient = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val json = JSONObject(buffer.readUtf8())
            capturedVariables = json.getJSONObject("variables")

            val responseJson = bboxResponse(
                sampleStopJson("BKK:stop_123", "Deak Ferenc ter", 47.4975, 19.0540)
            )
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseJson.toResponseBody())
                .build()
        }.build()

        val repo = EmmaStopsRepository(context, EmmaGraphqlClient(mockClient))
        val result = repo.mapArea(bounds)

        assertNotNull(capturedVariables)
        assertEquals(bounds.south, capturedVariables!!.getDouble("south"), 1e-6)
        assertEquals(bounds.west, capturedVariables!!.getDouble("west"), 1e-6)
        assertEquals(bounds.north, capturedVariables!!.getDouble("north"), 1e-6)
        assertEquals(bounds.east, capturedVariables!!.getDouble("east"), 1e-6)

        assertEquals(1, result.stops.size)
        val stop = result.stops[0]
        assertEquals("emma:BKK:stop_123", stop.id)
        assertEquals("Deak Ferenc ter", stop.name)
        assertEquals(47.4975, stop.position.lat, 1e-6)
        assertEquals(19.0540, stop.position.lon, 1e-6)
        assertEquals("1", stop.platformCode)
        assertEquals("emma:hkir:station_BKK:stop_123", stop.parentStationId)
        assertTrue("routeIds must be empty in lightweight bbox result", stop.routeIds.isEmpty())
    }

    @Test
    fun mapAreaNeverRequiresOfflinePack() = runBlocking {
        // Guarantee no offline pack exists
        val packFile = File(context.filesDir, "bmenetrend-offline.db")
        packFile.delete()

        val mockClient = OkHttpClient.Builder().addInterceptor { chain ->
            val responseJson = bboxResponse(sampleStopJson("BKK:s1", "Stop 1", 47.5, 19.0))
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseJson.toResponseBody())
                .build()
        }.build()

        val repo = EmmaStopsRepository(context, EmmaGraphqlClient(mockClient))
        val result = repo.mapArea(MapBounds(19.0, 47.4, 19.1, 47.6))
        assertEquals(1, result.stops.size)
    }
}
