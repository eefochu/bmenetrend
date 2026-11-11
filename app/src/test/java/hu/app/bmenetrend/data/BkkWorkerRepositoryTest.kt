package hu.app.bmenetrend.data

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class BkkWorkerRepositoryTest {

    private val now = Instant.parse("2026-11-10T12:00:00Z")

    @Test
    fun queryArea_enforcesBoundsMaxSpanAndGridSnapping() {
        // 1. Valid viewport in Budapest center:
        // lon: 19.04..19.06 (half-span = 0.01 <= 0.09)
        // lat: 47.49..47.51 (half-span = 0.01 <= 0.09)
        val valid = MapBounds(19.042, 47.493, 19.058, 47.507)
        val snapped = BkkWorkerRepository.queryArea(valid)
        assertNotNull(snapped)
        // Snapped center: 19.05, 47.50
        assertEquals(47.50, (snapped!!.south + snapped.north) / 2.0, 1e-4)
        assertEquals(19.05, (snapped.west + snapped.east) / 2.0, 1e-4)

        // 2. Viewport exceeding max half-span 0.09 (zoomed out too far):
        // lon span: 18.90..19.20 -> half-span = 0.15 > 0.09 -> must return null
        val tooWide = MapBounds(18.90, 47.45, 19.20, 47.55)
        assertNull(BkkWorkerRepository.queryArea(tooWide))

        // lat span: 47.30..47.60 -> half-span = 0.15 > 0.09 -> must return null
        val tooTall = MapBounds(19.00, 47.30, 19.10, 47.60)
        assertNull(BkkWorkerRepository.queryArea(tooTall))

        // 3. All Hungary within bounds <= 0.09 is accepted (e.g. Debrecen):
        val debrecen = MapBounds(21.61, 47.52, 21.63, 47.54)
        assertNotNull(BkkWorkerRepository.queryArea(debrecen))

        // Viewport outside Hungary (e.g. Munich):
        val outsideHungary = MapBounds(11.55, 48.10, 11.60, 48.15)
        assertNull(BkkWorkerRepository.queryArea(outsideHungary))

        // 4. Inverted or empty bounds:
        val inverted = MapBounds(19.06, 47.50, 19.04, 47.49)
        assertNull(BkkWorkerRepository.queryArea(inverted))
    }

    @Test
    fun inServiceArea_checksBudapestBoundingBox() {
        // Inside Budapest/Agglomeration
        assertTrue(BkkWorkerRepository.inServiceArea(GeoPoint(47.497, 19.054))) // Deák tér
        assertTrue(BkkWorkerRepository.inServiceArea(GeoPoint(47.53, 18.90)))  // Agglomeration west
        assertTrue(BkkWorkerRepository.inServiceArea(GeoPoint(47.20, 19.20)))  // Agglomeration south

        // Outside (Szeged, Győr, Debrecen)
        assertFalse(BkkWorkerRepository.inServiceArea(GeoPoint(46.25, 20.14))) // Szeged
        assertFalse(BkkWorkerRepository.inServiceArea(GeoPoint(47.68, 17.63))) // Győr
        assertFalse(BkkWorkerRepository.inServiceArea(GeoPoint(47.53, 21.62))) // Debrecen
    }

    @Test
    fun plan_sendsRequiredParametersIncludingIncludeReferencesFalse() = runBlocking {
        var requestedUrl: HttpUrl? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedUrl = chain.request().url
            val responseBody = JSONObject().put("status", "OK").put("data", JSONObject()
                .put("entry", JSONObject().put("plan", JSONObject().put("itineraries", JSONArray())))
            ).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(responseBody.toResponseBody()).build()
        }.build()

        val repo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), client, { now }, BkkRequestBudget()))
        val from = PlannerEndpoint("deak", "Deák tér", "", GeoPoint(47.497, 19.054))
        val to = PlannerEndpoint("kelenfold", "Kelenföld", "", GeoPoint(47.464, 19.021))

        repo.plan(from, to, now)

        assertNotNull(requestedUrl)
        assertEquals("false", requestedUrl!!.queryParameter("includeReferences"))
        assertEquals("true", requestedUrl!!.queryParameter("compact"))
        assertEquals("3", requestedUrl!!.queryParameter("numItineraries"))
        assertEquals("true", requestedUrl!!.queryParameter("showIntermediateStops"))
        assertEquals("Deák tér::47.49700,19.05400", requestedUrl!!.queryParameter("fromPlace"))
        assertEquals("Kelenföld::47.46400,19.02100", requestedUrl!!.queryParameter("toPlace"))
    }

    @Test
    fun boards_queriesBkkAndMavStopsAndHandlesPerStopFailureAuthoritatively() = runBlocking {
        val bkkStop1 = TransitStop("emma:BKK:F01020", "Deák tér", GeoPoint(47.497, 19.054), emptyList(), "bkk")
        val bkkStop2 = TransitStop("emma:BKK:F01021", "Astoria", GeoPoint(47.494, 19.060), emptyList(), "bkk")
        val railStop = TransitStop("emma:1:005510009", "Kelenföld", GeoPoint(47.464, 19.021), emptyList(), "mav")
        val volanStop = TransitStop("volan:12345", "Volán megálló", GeoPoint(47.5, 19.0), emptyList(), "volan")

        var requestedStopIds: String? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requestedStopIds = chain.request().url.queryParameter("stopId")
            val okJson = JSONObject().put("status", "OK").put("data", JSONObject()
                .put("successfulStopIds", JSONArray().put("BKK_F01020").put("BKK_005510009"))
                .put("failedStopIds", JSONArray().put("BKK_F01021"))
                .put("references", JSONObject())
                .put("entry", JSONObject().put("stopTimes", JSONArray()))
            ).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(okJson.toResponseBody()).build()
        }.build()

        val repo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), client, { now }, BkkRequestBudget()))
        val boards = repo.boards(listOf(bkkStop1, bkkStop2, railStop, volanStop), windowMinutes = 30)

        // Verifies stopIds sent to worker batch query
        assertNotNull(requestedStopIds)
        assertTrue(requestedStopIds!!.contains("BKK_F01020"))
        assertTrue(requestedStopIds!!.contains("BKK_F01021"))
        assertTrue("MÁV rail stop code must map to BKK_005510009 and be queried", requestedStopIds!!.contains("BKK_005510009"))
        assertFalse("Volán stop without proven shared code must NOT be queried", requestedStopIds!!.contains("12345"))

        // bkkStop1 in successfulStopIds -> returns legitimate empty board
        assertNotNull(boards[bkkStop1.id])
        // railStop in successfulStopIds -> returns board
        assertNotNull(boards[railStop.id])
        // bkkStop2 in failedStopIds -> null (triggers EMMA fallback)
        assertNull(boards[bkkStop2.id])
        // volanStop not queried -> null
        assertNull(boards[volanStop.id])
    }

    @Test
    fun boards_batchesUpToSixStopsPerRequest() = runBlocking {
        val stops = (1..8).map { i ->
            TransitStop("emma:BKK:F0102$i", "Megálló $i", GeoPoint(47.50, 19.05), emptyList(), "bkk")
        }

        val requestBatches = java.util.concurrent.CopyOnWriteArrayList<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val stopIds = chain.request().url.queryParameter("stopId") ?: ""
            requestBatches.add(stopIds)
            val okJson = JSONObject().put("status", "OK").put("data", JSONObject()
                .put("successfulStopIds", JSONArray(stopIds.split(",")))
                .put("references", JSONObject())
                .put("entry", JSONObject().put("stopTimes", JSONArray()))
            ).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(okJson.toResponseBody()).build()
        }.build()

        val repo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), client, { now }, BkkRequestBudget()))
        val boards = repo.boards(stops, windowMinutes = 30)

        assertEquals("8 stops should be chunked into 2 batches (max 6 per request)", 2, requestBatches.size)
        val batchCounts = requestBatches.map { it.split(",").size }.sorted()
        assertEquals(listOf(2, 6), batchCounts)
        assertEquals(8, boards.size)
    }

    @Test
    fun searchVehicles_includesFoundVehiclesInPrimaryListAndStripsBkkPrefix() = runBlocking {
        var requestedQuery: String? = null
        var directVehicleId: String? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            if (url.encodedPath.contains("vehicles")) {
                directVehicleId = url.queryParameter("vehicleId")
                val vehicleJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject())
                    .put("list", JSONArray())
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(vehicleJson.toResponseBody()).build()
            } else {
                requestedQuery = url.queryParameter("query")
                val searchJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject()
                        .put("vehicles", JSONObject().put("BKK_2035", JSONObject()
                            .put("vehicleId", "BKK_2035")
                            .put("label", "2035")
                            .put("location", JSONObject().put("lat", 47.50).put("lon", 19.05))
                            .put("lastUpdateTime", Instant.now().epochSecond)
                            .put("vehicleRouteType", "BUS")
                        ))
                    )
                    .put("entry", JSONObject()
                        .put("vehicleIds", JSONArray().put("BKK_2035"))
                    )
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(searchJson.toResponseBody()).build()
            }
        }.build()

        val repo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), client, { now }, BkkRequestBudget()))
        val result = repo.searchVehicles("BKK_2035", near = null)
        // Direct exact vehicleId lookup stripped BKK_ prefix and sent BKK_2035
        assertEquals("BKK_2035", directVehicleId)
        // Search endpoint was queried
        assertEquals("BKK_2035", requestedQuery)
        // found.vehicles with NO route IDs was included in primary list
        assertEquals(1, result.size)
        assertEquals("emma:BKK:2035", result[0].vehicle.id)
        assertEquals("bkk", result[0].vehicle.providerId)
    }

    @Test
    fun searchVehicles_direct12DigitUicAndPrefixStrippingAndFallbackFiltering() = runBlocking {
        var directVehicleId: String? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            if (url.encodedPath.contains("vehicles")) {
                directVehicleId = url.queryParameter("vehicleId")
                val vehicleJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject())
                    .put("list", JSONArray())
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(vehicleJson.toResponseBody()).build()
            } else {
                val searchJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject()
                        .put("vehicles", JSONObject()
                            .put("BKK_915504150201", JSONObject()
                                .put("vehicleId", "BKK_915504150201")
                                .put("label", "415 020")
                                .put("location", JSONObject().put("lat", 47.50).put("lon", 19.05))
                                .put("lastUpdateTime", Instant.now().epochSecond)
                                .put("vehicleRouteType", "RAIL")
                            )
                        )
                    )
                    .put("entry", JSONObject()
                        .put("vehicleIds", JSONArray().put("BKK_915504150201"))
                    )
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(searchJson.toResponseBody()).build()
            }
        }.build()

        val repo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), client, { now }, BkkRequestBudget()))

        // 1. Direct 12-digit UIC query
        val uicResult = repo.searchVehicles("915504150201", near = null)
        assertEquals("BKK_915504150201", directVehicleId)
        assertEquals(1, uicResult.size)
        assertEquals("emma:1:915504150201", uicResult[0].vehicle.id)
        assertFalse("Primary search vehicle must not be marked fallback", uicResult[0].fallback)

        // 2. Case-insensitive bkk_ prefix stripping
        repo.searchVehicles("bkk_915504150201", near = null)
        assertEquals("BKK_915504150201", directVehicleId)
    }

    @Test
    fun boards_successfulEmptyVsFailedBatchFallback() = runBlocking {
        // 7 stops: first batch has 6 stops, second batch has 1 stop
        val stops = (1..7).map { i ->
            TransitStop("emma:BKK:F0102$i", "Stop $i", GeoPoint(47.49, 19.05), emptyList(), "bkk", transportMode = "BUS")
        }

        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val stopIds = chain.request().url.queryParameter("stopId").orEmpty()
            if (stopIds.split(",").size == 6) {
                // Batch 1 succeeds with empty stopTimes (successful empty)
                val okJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject().put("stops", JSONObject()))
                    .put("entry", JSONObject().put("stopTimes", JSONArray()))
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(okJson.toResponseBody()).build()
            } else {
                // Batch 2 fails with server error
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(500).message("Internal Server Error")
                    .body("{}".toResponseBody()).build()
            }
        }.build()

        val repo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), client, { now }, BkkRequestBudget()))
        val boards = repo.boards(stops, windowMinutes = 30)

        // Successful empty batch produces non-null Board entries with 0 departures
        for (i in 1..6) {
            val board = boards["emma:BKK:F0102$i"]
            assertNotNull("Batch 1 stops must have non-null Board", board)
            assertTrue("Successful empty board has empty departures", board!!.departures.isEmpty())
        }

        // Failed batch produces null Board entry for fallback
        assertNull("Failed batch stop must produce null for fallback to EMMA", boards["emma:BKK:F01027"])
    }

    @Test
    fun searchVehicles_volanPlatesAndPrefixStripping() = runBlocking {
        var requestedDirectVehicleId: String? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            if (url.encodedPath.contains("vehicles")) {
                requestedDirectVehicleId = url.queryParameter("vehicleId")
                val vehicleJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject())
                    .put("list", JSONArray())
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(vehicleJson.toResponseBody()).build()
            } else {
                val searchJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject())
                    .put("entry", JSONObject())
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(searchJson.toResponseBody()).build()
            }
        }.build()

        val repo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), client, { now }, BkkRequestBudget()))

        // 1. Exact plate
        repo.searchVehicles("SRD244", near = null)
        assertEquals("volan_SRD244", requestedDirectVehicleId)

        // 2. volan_ prefix
        repo.searchVehicles("volan_SRD244", near = null)
        assertEquals("volan_SRD244", requestedDirectVehicleId)

        // 3. Hyphenated lowercase plate
        repo.searchVehicles("srd-244", near = null)
        assertEquals("volan_SRD244", requestedDirectVehicleId)
    }

    @Test
    fun journey_volanVehicleWithoutBkkTripId_resolvesByExactPlate() = runBlocking {
        var requestedVehicleId: String? = null
        var requestedTripId: String? = null

        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            if (url.encodedPath.contains("vehicles")) {
                requestedVehicleId = url.queryParameter("vehicleId")
                val vehicleJson = JSONObject().put("status", "OK").put("data", JSONObject()
                    .put("references", JSONObject().put("routes", JSONObject().put("volan_735", JSONObject()
                        .put("id", "volan_735").put("type", "COACH").put("shortName", "735")
                    )))
                    .put("list", JSONArray().put(JSONObject()
                        .put("vehicleId", "volan_SRD244")
                        .put("location", JSONObject().put("lat", 47.45).put("lon", 18.95))
                        .put("lastUpdateTime", Instant.now().epochSecond)
                        .put("vehicleRouteType", "COACH")
                        .put("routeId", "volan_735")
                        .put("tripId", "volan_trip_123")
                    ))
                ).toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(vehicleJson.toResponseBody()).build()
            } else if (url.encodedPath.contains("trip-details")) {
                val currentTime = Instant.now().epochSecond
                requestedTripId = url.queryParameter("tripId")
                val data = JSONObject()
                    .put("references", JSONObject()
                        .put("routes", JSONObject().put("volan_735", JSONObject()
                            .put("id", "volan_735").put("type", "COACH").put("shortName", "735")
                        ))
                        .put("trips", JSONObject().put("volan_trip_123", JSONObject()
                            .put("id", "volan_trip_123").put("routeId", "volan_735").put("tripHeadsign", "Érd")
                        ))
                        .put("stops", JSONObject()
                            .put("volan_773532_1", JSONObject()
                                .put("id", "volan_773532_1").put("name", "Kelenföld").put("lat", 47.46).put("lon", 19.02)
                                .put("type", "BUS").put("locationType", 0)
                            )
                            .put("volan_773532_2", JSONObject()
                                .put("id", "volan_773532_2").put("name", "Budaörs").put("lat", 47.45).put("lon", 18.95)
                                .put("type", "BUS").put("locationType", 0)
                            )
                        )
                    )
                    .put("entry", JSONObject()
                        .put("tripId", "volan_trip_123")
                        .put("stopTimes", JSONArray()
                            .put(JSONObject().put("stopId", "volan_773532_1").put("departureTime", currentTime))
                            .put(JSONObject().put("stopId", "volan_773532_2").put("arrivalTime", currentTime + 600))
                        )
                    )
                val tripJson = """{"status":"OK","data":$data}"""
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(tripJson.toResponseBody()).build()
            } else {
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Not Found")
                    .body("{}".toResponseBody()).build()
            }
        }.build()

        val repo = BkkWorkerRepository(BkkWorkerClient("https://worker.test".toHttpUrl(), client, { now }, BkkRequestBudget()))

        val nativeVolan = Vehicle(
            id = "emma:hkir:hkir_SRD244",
            position = GeoPoint(47.45, 18.95),
            observedAt = now,
            heading = 90.0,
            providerId = "volan",
            routeId = "volan:hkir:hkir_V-R-200-735",
            routeNumber = "735",
            tripId = null,
            bkkTripId = null,
            destination = "Érd",
            nextStopId = null,
            nextStopName = null
        )

        val journey = repo.journey(nativeVolan)
        assertNotNull("Journey must be resolved by looking up exact plate", journey)
        assertEquals("volan_SRD244", requestedVehicleId)
        assertEquals("volan_trip_123", requestedTripId)
        assertEquals("volan:volan_trip_123", journey!!.tripId)
        assertEquals("735", journey.routeNumber)
    }
}
