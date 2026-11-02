package hu.app.bmenetrend.offline

import android.content.Context
import hu.app.bmenetrend.data.ArrivalKind
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.PlannerEndpoint
import hu.app.bmenetrend.data.TripPlanner
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import java.security.KeyStore
import java.security.cert.CertificateFactory
import hu.app.bmenetrend.data.EmmaGraphqlClient
import org.junit.Assume
import hu.app.bmenetrend.map.OfflineBasemapAssets
import okhttp3.OkHttpClient

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EmmaRailSnapshotTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val fromDate = LocalDate.of(2026, 11, 1)
    private val untilDate = LocalDate.of(2026, 11, 30)
    private lateinit var store: OfflinePackStore

    @Before
    fun setup() {
        NetworkAccessPolicy.setOffline(context, false)
        NetworkAccessPolicy.onConnectivityChanged(true)
        store = OfflinePackStore(context)
        store.delete()
    }

    @After
    fun cleanup() {
        NetworkAccessPolicy.setOffline(context, false)
        store.delete()
    }

    // --- Aspect 6: Non-rail exclusion ---
    @Test
    fun nonRailModesAndNonRailPrefixesAreStrictlyExcluded() {
        val nonRailModes = listOf("BUS", "COACH", "TRAM", "SUBWAY", "SUBURBAN_RAILWAY", "FERRY", "FUNICULAR", "")
        for (mode in nonRailModes) {
            val route = JSONObject().put("gtfsId", "1:route1").put("mode", mode)
            assertFalse("Mode $mode should not be accepted as rail", EmmaRailSnapshot.isRail(route))
        }

        val nonFeed1Rail = JSONObject().put("gtfsId", "2:route1").put("mode", "RAIL")
        assertFalse("Routes not starting with 1: must be rejected", EmmaRailSnapshot.isRail(nonFeed1Rail))

        val railModes = listOf("RAIL", "TRAIN", "TRAMTRAIN", "RAIL_REPLACEMENT_BUS")
        for (mode in railModes) {
            val route = JSONObject().put("gtfsId", "1:route1").put("mode", mode)
            assertTrue("Mode $mode starting with 1: must be accepted as rail", EmmaRailSnapshot.isRail(route))
        }
    }

    // --- Aspect 5: Rejection of missing/null/partial batches and GraphQL errors ---
    @Test
    fun rejectsMissingCatalogue() = runBlocking {
        val snapshot = EmmaRailSnapshot { query, _ ->
            if (query == EmmaRailSnapshot.CATALOGUE_QUERY) {
                """{"data":{"routes":[]}}"""
            } else error("Unexpected query")
        }
        val target = File(context.cacheDir, "rail-empty.zip")
        try {
            snapshot.download(target, fromDate, untilDate)
            fail("Expected missing catalogue to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue("Expected 'Missing EMMA rail catalogue', got: ${e.message}", e.message?.contains("Missing EMMA rail catalogue") == true)
        } finally {
            target.delete()
        }
    }

    @Test
    fun rejectsGraphQLErrors() = runBlocking {
        val snapshot = EmmaRailSnapshot { _, _ ->
            """{"errors":[{"message":"Backend unavailable","locations":[{"line":1,"column":1}]}]}"""
        }
        val target = File(context.cacheDir, "rail-error.zip")
        try {
            snapshot.download(target, fromDate, untilDate)
            fail("Expected GraphQL error to throw")
        } catch (e: IllegalArgumentException) {
            assertTrue("Expected 'EMMA GraphQL error', got: ${e.message}", e.message?.contains("EMMA GraphQL error") == true)
        } finally {
            target.delete()
        }
    }

    @Test
    fun rejectsIncompleteOrMismatchedBatches() = runBlocking {
        val catalogue = """{"data":{"routes":[
            {"gtfsId":"1:routeA","mode":"RAIL"},
            {"gtfsId":"1:routeB","mode":"RAIL"}
        ]}}"""

        // Test returning fewer items than requested
        val partialSnapshot = EmmaRailSnapshot { query, _ ->
            if (query == EmmaRailSnapshot.CATALOGUE_QUERY) catalogue
            else """{"data":{"routes":[{"gtfsId":"1:routeA","mode":"RAIL","shortName":"S1","longName":"Line 1","trips":[]}]}}"""
        }
        val target1 = File(context.cacheDir, "rail-partial.zip")
        try {
            partialSnapshot.download(target1, fromDate, untilDate)
            fail("Expected incomplete batch to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Incomplete EMMA rail batch") == true)
        } finally {
            target1.delete()
        }

        // Test returning mismatched IDs
        val mismatchSnapshot = EmmaRailSnapshot { query, _ ->
            if (query == EmmaRailSnapshot.CATALOGUE_QUERY) catalogue
            else """{"data":{"routes":[
                {"gtfsId":"1:routeA","mode":"RAIL","shortName":"S1","longName":"Line 1","trips":[]},
                {"gtfsId":"1:routeUNKNOWN","mode":"RAIL","shortName":"S1","longName":"Line 1","trips":[]}
            ]}}"""
        }
        val target2 = File(context.cacheDir, "rail-mismatch.zip")
        try {
            mismatchSnapshot.download(target2, fromDate, untilDate)
            fail("Expected mismatched batch to be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Incomplete EMMA rail batch") == true)
        } finally {
            target2.delete()
        }
    }

    @Test
    fun rejectsBatchesWithNoServiceInDateRange() = runBlocking {
        val catalogue = """{"data":{"routes":[{"gtfsId":"1:routeA","mode":"RAIL"}]}}"""
        val routesData = """{"data":{"routes":[{
            "gtfsId":"1:routeA","mode":"RAIL","shortName":"S10","longName":"Győr","color":"0055A5",
            "trips":[{
                "gtfsId":"1:trip1","activeDates":["20261001","20261015"],
                "tripHeadsign":"Győr","directionId":"0",
                "stoptimes":[
                    {"scheduledArrival":28800,"scheduledDeparture":28800,"pickupType":"SCHEDULED","dropoffType":"SCHEDULED",
                     "stop":{"gtfsId":"1:005501024_0","name":"Budapest-Kelenföld","lat":47.46,"lon":19.02,"locationType":"STOP"}},
                    {"scheduledArrival":29400,"scheduledDeparture":29400,"pickupType":"SCHEDULED","dropoffType":"SCHEDULED",
                     "stop":{"gtfsId":"1:005501057_0","name":"Biatorbágy","lat":47.48,"lon":18.83,"locationType":"STOP"}}
                ]
            }]
        }]}}"""

        val snapshot = EmmaRailSnapshot { query, _ ->
            if (query == EmmaRailSnapshot.CATALOGUE_QUERY) catalogue else routesData
        }
        val target = File(context.cacheDir, "rail-no-dates.zip")
        try {
            snapshot.download(target, fromDate, untilDate)
            fail("Expected failure when no trips fall into active date range")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("No EMMA rail service in downloaded date range") == true)
        } finally {
            target.delete()
        }
    }

    // --- Aspects 1, 2, 3, 4: Active dates, scheduled vs realtime, overnight times, parent stations & escaping ---
    @Test
    fun snapshotStreamsValidGtfsAndImportsCorrectly() = runBlocking {
        val sampleRoute = buildRealisticSampleRoute()
        val catalogue = """{"data":{"routes":[{"gtfsId":"1:V2912_106","mode":"RAIL"}]}}"""
        val routesResponse = JSONObject().put("data", JSONObject().put("routes", JSONArray().put(sampleRoute))).toString()

        val snapshot = EmmaRailSnapshot { query, _ ->
            if (query == EmmaRailSnapshot.CATALOGUE_QUERY) catalogue else routesResponse
        }

        val targetZip = File(context.cacheDir, "emma-rail-test.zip")
        try {
            snapshot.download(targetZip, fromDate, untilDate)
            assertTrue("Target ZIP must exist and be non-empty", targetZip.isFile && targetZip.length() > 0)

            // 1. Inspect generated ZIP entries
            ZipFile(targetZip).use { zip ->
                val entries = zip.entries().asSequence().map { it.name }.toSet()
                assertEquals(
                    setOf("agency.txt", "stops.txt", "routes.txt", "trips.txt", "calendar_dates.txt", "stop_times.txt"),
                    entries
                )

                // Verify activeDates filtering and non-contiguous dates
                val datesContent = zip.getInputStream(zip.getEntry("calendar_dates.txt")).bufferedReader().readText()
                val datesLines = datesContent.lines().filter { it.isNotBlank() }
                assertEquals("service_id,date,exception_type", datesLines.first())
                val datesInCalendar = datesLines.drop(1).map { line ->
                    val parts = line.split(",").map { it.trim('"') }
                    parts[0] to parts[1]
                }
                // Only dates within Nov 1 - Nov 30 should be present
                for ((_, d) in datesInCalendar) {
                    val ld = LocalDate.parse(d, DateTimeFormatter.BASIC_ISO_DATE)
                    assertTrue("Date $ld must be within $fromDate..$untilDate", ld in fromDate..untilDate)
                }
                // Specifically verify that out-of-range date 20261020 is not included
                assertFalse("Date 20261020 should have been filtered out", datesContent.contains("20261020"))
                // Non-contiguous dates 20261102, 20261105, 20261127 should be included
                assertTrue(datesContent.contains("20261102"))
                assertTrue(datesContent.contains("20261105"))
                assertTrue(datesContent.contains("20261127"))

                // Verify CSV escaping with quotes and commas
                val stopsContent = zip.getInputStream(zip.getEntry("stops.txt")).bufferedReader().readText()
                assertTrue("Stops must properly escape commas and quotes", stopsContent.contains("Budapest-Kelenföld, vasútállomás"))
                assertTrue("Stops must properly escape internal double quotes", stopsContent.contains("\"\"1\"\" vágány"))

                // Verify overnight scheduled times in stop_times.txt
                val timesContent = zip.getInputStream(zip.getEntry("stop_times.txt")).bufferedReader().readText()
                assertTrue("Overnight arrival 24:35:00 should be formatted", timesContent.contains("24:35:00"))
                assertTrue("Overnight departure 24:36:00 should be formatted", timesContent.contains("24:36:00"))
            }

            // 2. Import into OfflinePackStore via GtfsImporter
            val stage = store.staging()
            onOfflineDatabaseThread {
                GtfsImporter.create(stage).use { db ->
                    db.beginTransaction()
                    try {
                        val importer = GtfsImporter(db, fromDate, untilDate)
                        importer.import(targetZip, OfflineSource.emmaRail)
                        importer.finish()
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
            }

            val meta = OfflinePackMetadata(
                "test-meta", Instant.now(), fromDate, untilDate, stage.length(),
                targetZip.length(), 1, 1, listOf("MÁV"), OfflinePackStore.digest(stage)
            )
            store.activate(stage, meta)

            // 3. Verify OfflineTransitData catalogue mapping
            val transitData = OfflineTransitData(store)
            val (stops, routes) = transitData.catalogue()

            // Verify stop IDs retain exact emma:1:... mapping
            val kelenfoldPlatform = stops.firstOrNull { it.id == "emma:1:005501024_0" }
            assertNotNull("Platform stop must have emma:1:... ID", kelenfoldPlatform)
            assertEquals("Platform code must match", "1", kelenfoldPlatform?.platformCode)
            assertEquals("Parent station ID must match", "emma:1:005501024", kelenfoldPlatform?.parentStationId)
            assertFalse("Platform stop is not a station", kelenfoldPlatform?.station == true)

            val kelenfoldStation = stops.firstOrNull { it.id == "emma:1:005501024" }
            assertNotNull("Parent station must be in catalogue", kelenfoldStation)
            assertTrue("Parent station must have station = true", kelenfoldStation?.station == true)
            assertEquals("Parent station must inherit RAIL mode", "RAIL", kelenfoldStation?.transportMode)
            assertTrue("Parent station must inherit serving routes from platforms", kelenfoldStation?.routeIds?.isNotEmpty() == true)

            // Verify route ID mapping
            val railRoute = routes.firstOrNull { it.id == "mav:1:V2912_106" }
            assertNotNull("Rail route must be present with mav:1: prefix", railRoute)
            assertEquals("S10", railRoute?.number)
            assertEquals("RAIL", railRoute?.transportMode)

            // 4. Verify Departure Board (scheduled vs realtime values and ArrivalKind.SCHEDULED)
            val now = fromDate.plusDays(1).atTime(6, 0).atZone(TripPlanner.zone).toInstant() // 2026-11-02 06:00
            val departures = transitData.board(listOf("emma:1:005501024"), now, 240) // Query parent station
            assertTrue("Departure board for parent station must return departures", departures.isNotEmpty())

            val dep = departures.first()
            assertEquals("ArrivalKind must be SCHEDULED", ArrivalKind.SCHEDULED, dep.kind)
            assertEquals("Scheduled and expected times must be identical", dep.scheduledAt, dep.expectedAt)
            assertEquals("Platform code should be propagated", "1", dep.platformCode)

            // 5. Verify OfflineTripPlanner between rail stations
            val planner = OfflineTripPlanner(store)
            val origin = PlannerEndpoint(
                id = kelenfoldStation!!.id,
                name = kelenfoldStation.name,
                detail = "",
                position = kelenfoldStation.position,
                sourceStopIds = listOf(kelenfoldPlatform!!.id)
            )
            val biatorbagyStation = stops.first { it.id == "emma:1:005501057" }
            val destination = PlannerEndpoint(
                id = biatorbagyStation.id,
                name = biatorbagyStation.name,
                detail = "",
                position = biatorbagyStation.position,
                sourceStopIds = listOf("emma:1:005501057_0")
            )

            val plan = planner.plan(origin, destination, now, now)
            assertNotNull("Trip plan must not be null", plan)
            assertTrue("Plan must contain at least one rail itinerary", plan?.itineraries?.isNotEmpty() == true)
            val leg = plan!!.itineraries.first().legs.first()
            assertEquals("RAIL", leg.mode)
            assertEquals("S10", leg.route?.number)
        } finally {
            targetZip.delete()
        }
    }

    // --- Aspect 7: Combined accounting three sources ---
    @Test
    fun combinedDownloadAccountingWithThreeSourcesEnforcesBudget() {
        // With 3 sources (Volán, BKK, MÁV), progress does not emit total until all 3 report
        val totals = DownloadTotals(retained = 10_000_000L, sourceCount = 3)

        // 1. Volán reports 50 MB / 90 MB
        val u1 = totals.update("volan", 50_000_000L, 90_000_000L)
        assertEquals("Total expected should be 0 until all 3 sources report", 0L, u1.second)
        assertEquals(60_000_000L, u1.first) // 50 MB + 10 MB retained

        // 2. BKK reports 40 MB / 60 MB
        val u2 = totals.update("bkk", 40_000_000L, 60_000_000L)
        assertEquals("Total expected should still be 0", 0L, u2.second)
        assertEquals(100_000_000L, u2.first) // 50 MB + 40 MB + 10 MB retained

        // 3. MÁV reports 10 MB / 30 MB -> now all 3 sources have reported!
        val u3 = totals.update("mav", 10_000_000L, 30_000_000L)
        // Expected total = retained (10 MB) + 90 MB + 60 MB + 30 MB = 190 MB
        assertEquals(190_000_000L, u3.second)
        assertEquals(110_000_000L, u3.first) // 50 MB + 40 MB + 10 MB + 10 MB retained

        // 4. Exceeding combined budget (> 500 MB) must throw
        try {
            totals.update("mav", 400_000_000L, 400_000_000L)
            fail("Expected budget violation exception")
        } catch (_: IllegalArgumentException) {
            // Budget correctly enforced
        }
    }

    // --- Regression: Calendar Deduplication for identical sorted activeDates ---
    @Test
    fun calendarDeduplicationSharesServiceIdAcrossTripsWithIdenticalSortedDates() {
        val workDir = File(context.cacheDir, "test-dedup-staging-${System.nanoTime()}").apply { mkdirs() }
        try {
            val stopA = JSONObject().put("gtfsId", "1:stop_a").put("name", "Stop A").put("lat", 47.4).put("lon", 19.0)
            val stopB = JSONObject().put("gtfsId", "1:stop_b").put("name", "Stop B").put("lat", 47.5).put("lon", 19.1)
            fun tripJson(id: String, activeDates: List<String>): JSONObject = JSONObject()
                .put("gtfsId", id)
                .put("tripHeadsign", "Headsign $id")
                .put("directionId", "0")
                .put("activeDates", JSONArray(activeDates))
                .put("stoptimes", JSONArray(listOf(
                    JSONObject().put("scheduledArrival", 20000).put("scheduledDeparture", 20000).put("pickupType", "SCHEDULED").put("dropoffType", "NONE").put("stop", stopA),
                    JSONObject().put("scheduledArrival", 21000).put("scheduledDeparture", 21000).put("pickupType", "NONE").put("dropoffType", "SCHEDULED").put("stop", stopB)
                )))

            // Trip 1 and Trip 2 have identical sorted dates [2026-11-02, 2026-11-05]
            val trip1 = tripJson("1:trip_1", listOf("20261105", "20261102"))
            val trip2 = tripJson("1:trip_2", listOf("20261102", "20261105", "20261102")) // duplicate & reversed
            val route1 = JSONObject()
                .put("gtfsId", "1:route_1")
                .put("shortName", "R1")
                .put("longName", "Route 1")
                .put("mode", "RAIL")
                .put("color", "0055A5")
                .put("trips", JSONArray(listOf(trip1, trip2)))

            // Route 2 has Trip 3 with the same sorted dates [2026-11-02, 2026-11-05] (should share service-0)
            // and Trip 4 with different dates [2026-11-10] (should get service-1)
            val trip3 = tripJson("1:trip_3", listOf("20261102", "20261105"))
            val trip4 = tripJson("1:trip_4", listOf("20261110"))
            val route2 = JSONObject()
                .put("gtfsId", "1:route_2")
                .put("shortName", "R2")
                .put("longName", "Route 2")
                .put("mode", "RAIL")
                .put("color", "0055A5")
                .put("trips", JSONArray(listOf(trip3, trip4)))

            SnapshotWriter(workDir, fromDate, untilDate).use { writer ->
                writer.route(route1)
                writer.route(route2)
                assertEquals(4, writer.tripCount)
            }

            // Verify trips.txt
            val tripsFile = File(workDir, "trips.txt")
            assertTrue(tripsFile.exists())
            val tripRows = tripsFile.readLines().filter { it.isNotBlank() }.drop(1).map { line ->
                val cols = line.split(",").map { it.trim('"') }
                cols[0] to cols[2] // trip_id to service_id
            }.toMap()

            assertEquals("service-0", tripRows["1:trip_1"])
            assertEquals("service-0", tripRows["1:trip_2"])
            assertEquals("service-0", tripRows["1:trip_3"])
            assertEquals("service-1", tripRows["1:trip_4"])

            // Verify calendar_dates.txt: should only contain service-0 dates ONCE each, and service-1 once
            val datesFile = File(workDir, "calendar_dates.txt")
            assertTrue(datesFile.exists())
            val dateRows = datesFile.readLines().filter { it.isNotBlank() }.drop(1).map { line ->
                val cols = line.split(",").map { it.trim('"') }
                cols[0] to cols[1]
            }
            val service0Dates = dateRows.filter { it.first == "service-0" }.map { it.second }
            assertEquals(listOf("20261102", "20261105"), service0Dates.sorted())
            assertEquals("service-0 must have exactly 2 deduplicated date entries", 2, service0Dates.size)

            val service1Dates = dateRows.filter { it.first == "service-1" }.map { it.second }
            assertEquals(listOf("20261110"), service1Dates)
            assertEquals("Total date entries must be exactly 3", 3, dateRows.size)
        } finally {
            workDir.deleteRecursively()
        }
    }

    // --- Regression: Tram-Train Route Type 900 Mapping ---
    @Test
    fun tramTrainRouteType900MappedToTramTrainForMavAndTramOtherwise() = runBlocking {
        val workDir = File(context.cacheDir, "test-tt-staging-${System.nanoTime()}").apply { mkdirs() }
        val mavZip = File(context.cacheDir, "test-mav-${System.nanoTime()}.zip")
        val otherZip = File(context.cacheDir, "test-other-${System.nanoTime()}.zip")
        try {
            val stopA = JSONObject().put("gtfsId", "1:tt_stop_a").put("name", "TT Stop A").put("lat", 46.25).put("lon", 20.14)
            val stopB = JSONObject().put("gtfsId", "1:tt_stop_b").put("name", "TT Stop B").put("lat", 46.41).put("lon", 20.32)
            val ttTrip = JSONObject()
                .put("gtfsId", "1:tt_trip_1")
                .put("tripHeadsign", "Hódmezővásárhely")
                .put("directionId", "0")
                .put("activeDates", JSONArray(listOf("20261102")))
                .put("stoptimes", JSONArray(listOf(
                    JSONObject().put("scheduledArrival", 20000).put("scheduledDeparture", 20000).put("pickupType", "SCHEDULED").put("dropoffType", "NONE").put("stop", stopA),
                    JSONObject().put("scheduledArrival", 22000).put("scheduledDeparture", 22000).put("pickupType", "NONE").put("dropoffType", "SCHEDULED").put("stop", stopB)
                )))
            val ttRoute = JSONObject()
                .put("gtfsId", "1:TT_01")
                .put("shortName", "1")
                .put("longName", "Szeged - Hódmezővásárhely")
                .put("mode", "TRAMTRAIN")
                .put("color", "0055A5")
                .put("trips", JSONArray(listOf(ttTrip)))

            // 1. SnapshotWriter writes route_type 900 for TRAMTRAIN mode
            SnapshotWriter(workDir, fromDate, untilDate).use { writer ->
                writer.route(ttRoute)
            }
            val routesContent = File(workDir, "routes.txt").readLines()
            val header = routesContent.first().split(",")
            val routeTypeIdx = header.indexOf("route_type")
            assertTrue("routes.txt must include route_type", routeTypeIdx >= 0)
            val routeRow = routesContent[1].split(",")
            assertEquals("SnapshotWriter must output 900 for TRAMTRAIN mode", "900", routeRow[routeTypeIdx].trim('"'))

            // Package into ZIP
            ZipOutputStream(mavZip.outputStream().buffered()).use { zip ->
                for (file in workDir.listFiles().orEmpty()) {
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }

            // Create another ZIP with route_type 900 for non-MÁV source (e.g. BKK / standard GTFS)
            ZipOutputStream(otherZip.outputStream().buffered()).use { zip ->
                fun entry(name: String, content: String) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
                entry("agency.txt", "agency_id,agency_name,agency_url,agency_timezone\nBKK,BKK,https://bkk.hu,Europe/Budapest\n")
                entry("calendar_dates.txt", "service_id,date,exception_type\nsvc_other,20261102,1\n")
                entry("stops.txt", "stop_id,stop_name,stop_lat,stop_lon\nother_a,Stop Other A,47.5,19.0\nother_b,Stop Other B,47.6,19.0\n")
                entry("routes.txt", "route_id,route_short_name,route_long_name,route_type\nOTHER_TT,900,Other 900,900\n")
                entry("trips.txt", "route_id,service_id,trip_id,trip_headsign\nOTHER_TT,svc_other,trip_other,Other B\n")
                entry("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type\ntrip_other,08:00:00,08:00:00,other_a,1,0,0\ntrip_other,08:20:00,08:20:00,other_b,2,0,0\n")
            }

            // 2. Import MÁV feed: GtfsImporter maps route_type 900 to TRAMTRAIN when source.id == "mav"
            val stage = store.staging()
            val otherSource = OfflineSource("bkk", "BKK", "https://example.org", "https://example.org")
            onOfflineDatabaseThread {
                GtfsImporter.create(stage).use { db ->
                    db.beginTransaction()
                    try {
                        val importer = GtfsImporter(db, fromDate, untilDate)
                        importer.import(mavZip, OfflineSource.emmaRail)
                        importer.import(otherZip, otherSource)
                        importer.finish()
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }
                }
            }
            val meta = OfflinePackMetadata(
                "test-tt-meta", Instant.now(), fromDate, untilDate, stage.length(),
                mavZip.length() + otherZip.length(), 4, 2, listOf("MÁV", "BKK"), OfflinePackStore.digest(stage)
            )
            store.activate(stage, meta)

            val transitData = OfflineTransitData(store)
            val (_, routes) = transitData.catalogue()

            val mavRoute = routes.firstOrNull { it.id == "mav:1:TT_01" }
            assertNotNull("MÁV tram-train route must be present", mavRoute)
            assertEquals("MÁV route with route_type 900 must map to TRAMTRAIN", "TRAMTRAIN", mavRoute?.transportMode)

            val bkkRoute = routes.firstOrNull { it.id == "bkk:BKK:OTHER_TT" }
            assertNotNull("Non-MÁV route must be present", bkkRoute)
            assertEquals("Non-MÁV route with route_type 900 must map to TRAM", "TRAM", bkkRoute?.transportMode)
        } finally {
            workDir.deleteRecursively()
            mavZip.delete()
            otherZip.delete()
        }
    }

    // --- Opt-in: Production EMMA Rail Catalogue Live Download ---
    @Test(timeout = 900_000L)
    fun downloadProductionEmmaRailSnapshot() = runBlocking {
        Assume.assumeTrue(
            "Opt-in test: set OFFLINE_REAL_RAIL=1 or OFFLINE_DOWNLOAD_REAL_RAIL=1",
            System.getenv("OFFLINE_REAL_RAIL") == "1" || System.getenv("OFFLINE_DOWNLOAD_REAL_RAIL") == "1"
        )
        val targetDir = File("/tmp/bmenetrend-offline-feeds")
        targetDir.mkdirs()
        val targetZip = File(targetDir, "mav.zip")
        val force = System.getenv("OFFLINE_FORCE_DOWNLOAD") == "1"
        if (targetZip.exists() && targetZip.length() > 0 && !force) {
            println("MAV_ZIP_CACHED path=${targetZip.absolutePath} bytes=${targetZip.length()}")
            return@runBlocking
        }

        val first = System.getenv("OFFLINE_FEED_DATE")?.let(LocalDate::parse) ?: LocalDate.now(TripPlanner.zone)
        val from = first.minusDays(2)
        val until = first.plusDays(30)
        val msgStart = "STARTING_EMMA_RAIL_DOWNLOAD from=$from until=$until target=${targetZip.absolutePath}"
        println(msgStart)
        System.err.println(msgStart)

        val startTime = System.nanoTime()
        var lastReported = 0L
        val testClient = createMacOsTrustedHttpClient()
        val snapshot = EmmaRailSnapshot(execute = { query, variables ->
            EmmaGraphqlClient(testClient).execute(query, variables)
        })
        snapshot.download(targetZip, from, until) { done, total ->
            val now = System.nanoTime()
            if (done == total || done - lastReported >= 240) {
                val msg = "EMMA_DOWNLOAD_PROGRESS done=$done total=$total elapsedSeconds=${(now - startTime) / 1e9}"
                println(msg)
                System.err.println(msg)
                lastReported = done
            }
        }
        val elapsed = (System.nanoTime() - startTime) / 1e9
        val msgEnd = "EMMA_DOWNLOAD_COMPLETE elapsedSeconds=$elapsed bytes=${targetZip.length()} target=${targetZip.absolutePath}"
        println(msgEnd)
        System.err.println(msgEnd)
        assertTrue("Downloaded mav.zip must exist", targetZip.isFile)
        assertTrue("Downloaded mav.zip must be non-empty", targetZip.length() > 0)
    }

    private fun createMacOsTrustedHttpClient(): OkHttpClient {
        val process = ProcessBuilder("security", "find-certificate", "-a", "-p", "/System/Library/Keychains/SystemRootCertificates.keychain").start()
        val certFactory = CertificateFactory.getInstance("X.509")
        val certs = process.inputStream.use { certFactory.generateCertificates(it) }
        val exitCode = process.waitFor()
        check(exitCode == 0 && certs.isNotEmpty()) { "Failed to export macOS system root certificates (exitCode=$exitCode, certs=${certs.size})" }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certs.forEachIndexed { i, cert ->
                setCertificateEntry("macos-root-$i", cert)
            }
        }
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        val trustManager = trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().first()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
        return EmmaGraphqlClient.plannerHttpClient().newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }

    // --- Opt-in: Full 3-Feed (Volan + BKK + MAV) Import, Storage Footprint & Planning Validation ---
    @Test(timeout = 900_000L)
    fun completeThreeFeedsWithRailCanBeImportedWithinStorageCapAndPlanned() = runBlocking {
        Assume.assumeTrue(
            "Opt-in test: set OFFLINE_REAL_FEEDS=1 or OFFLINE_REAL_RAIL=1",
            System.getenv("OFFLINE_REAL_FEEDS") == "1" || System.getenv("OFFLINE_REAL_RAIL") == "1"
        )
        val root = File("/tmp/bmenetrend-offline-feeds")
        val volanZip = File(root, "volan.zip")
        val bkkZip = File(root, "bkk.zip")
        val mavZip = File(root, "mav.zip")
        assertTrue("Missing volan.zip at ${volanZip.absolutePath}", volanZip.exists())
        assertTrue("Missing bkk.zip at ${bkkZip.absolutePath}", bkkZip.exists())
        assertTrue("Missing mav.zip at ${mavZip.absolutePath} (run download test first)", mavZip.exists())

        val first = System.getenv("OFFLINE_FEED_DATE")?.let(LocalDate::parse) ?: LocalDate.now(TripPlanner.zone)
        val stage = store.staging()
        // Include every bundled local provider as an upper bound on the worker's verified
        // local-source selection, without requiring more HTTP requests for this size check.
        val local = hu.app.bmenetrend.data.TransitRepository(context).bundledNetwork().let { network ->
            network.copy(verifiedProviderIds = network.routes.map { it.providerId }.toSet())
        }
        val started = System.nanoTime()
        onOfflineDatabaseThread {
            GtfsImporter.create(stage).use { db ->
                db.beginTransaction()
                try {
                    var lastStep: OfflineImportStep? = null
                    val report: suspend (OfflineImportProgress) -> Unit = { progress ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { kotlinx.coroutines.yield() }
                        if (progress.step != lastStep) {
                            val msg = "THREE_FEED_IMPORT_STAGE ${progress.step} elapsedSeconds=${(System.nanoTime() - started) / 1e9}"
                            println(msg)
                            System.err.println(msg)
                            lastStep = progress.step
                        }
                    }
                    val importer = GtfsImporter(db, first.minusDays(2), first.plusDays(30), report)
                    println("IMPORTING_VOLAN bytes=${volanZip.length()}")
                    importer.import(volanZip, OfflineSource.defaults[0])
                    println("IMPORTING_BKK bytes=${bkkZip.length()}")
                    importer.import(bkkZip, OfflineSource.defaults[1])
                    println("IMPORTING_MAV bytes=${mavZip.length()}")
                    importer.import(mavZip, OfflineSource.emmaRail)
                    val localSources = importer.importLocal(local)
                    assertEquals(setOf("viabusz", "kanyargo", "homm"), localSources.toSet())
                    println("LOCAL_SOURCES_INCLUDED ${localSources.joinToString()}")
                    println("IMPORT_FINISHING elapsedSeconds=${(System.nanoTime() - started) / 1e9}")
                    importer.finish()
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }
        val dbBytes = stage.length()
        val totalRetained = 0L
        val footprint = dbBytes + totalRetained + OfflineBasemapAssets.INSTALLED_BYTES
        val elapsedImport = (System.nanoTime() - started) / 1e9
        val msgComplete = "THREE_FEED_IMPORT_COMPLETE dbBytes=$dbBytes basemapBytes=${OfflineBasemapAssets.INSTALLED_BYTES} totalFootprint=$footprint importSeconds=$elapsedImport"
        println(msgComplete)
        System.err.println(msgComplete)
        assertTrue("Combined DB + map footprint ($footprint bytes) must be <= $OFFLINE_MAX_BYTES", footprint <= OFFLINE_MAX_BYTES)

        val meta = OfflinePackMetadata(
            java.util.UUID.randomUUID().toString(),
            Instant.now(),
            first,
            first.plusDays(29),
            dbBytes,
            volanZip.length() + bkkZip.length() + mavZip.length(),
            1, 1,
            listOf("Volán", "BKK", "MÁV"),
            OfflinePackStore.digest(stage)
        )
        store.activate(stage, meta)

        // Verify rail catalogue, departure boards, and route planning
        val transitData = OfflineTransitData(store)
        val (allStops, allRoutes) = transitData.catalogue()
        val routesById = allRoutes.associateBy { it.id }
        val mavRoutes = allRoutes.filter { it.providerId == "mav" || it.id.startsWith("mav:") }
        assertTrue("Pack must contain MAV routes", mavRoutes.isNotEmpty())
        val railRoutes = mavRoutes.filter { it.transportMode == "RAIL" }
        assertTrue("Pack must contain RAIL routes", railRoutes.isNotEmpty())
        val msgCounts = "CATALOGUE_COUNTS totalStops=${allStops.size} totalRoutes=${allRoutes.size} mavRoutes=${mavRoutes.size} railRoutes=${railRoutes.size}"
        println(msgCounts)
        System.err.println(msgCounts)

        // Departure board at Kelenföld:
        // Find Kelenföld rail parent station or platform
        val kelenfoldStation = allStops.firstOrNull { it.id == "emma:1:005501024" }
            ?: allStops.firstOrNull { it.name.contains("Kelenföld") && it.station && it.transportMode == "RAIL" }
        assertNotNull("Must find Kelenföld rail station", kelenfoldStation)
        val queryInstant = first.atTime(8, 0).atZone(TripPlanner.zone).toInstant()
        val board = transitData.board(listOf(kelenfoldStation!!.id), queryInstant, 180)
        println("KELENFOLD_RAIL_BOARD departures=${board.size}")
        assertTrue("Kelenföld rail station must have departures", board.isNotEmpty())
        assertTrue("Kelenföld rail departures must include RAIL mode", board.any { routesById[it.routeId]?.transportMode == "RAIL" })

        // Biatorbágy rail station
        val biatorbagyStation = allStops.firstOrNull { it.id == "emma:1:005501057" }
            ?: allStops.firstOrNull { it.name.contains("Biatorbágy") && it.transportMode == "RAIL" }
        assertNotNull("Must find Biatorbágy rail station", biatorbagyStation)

        // Verify Trip Planning from Kelenföld to Biatorbágy via RAIL
        val planner = OfflineTripPlanner(store)
        val originEndpoint = PlannerEndpoint.fromStop(kelenfoldStation)
        val destEndpoint = PlannerEndpoint.fromStop(biatorbagyStation!!)
        val plan = planner.plan(originEndpoint, destEndpoint, queryInstant, queryInstant)
        assertNotNull("Plan must not be null", plan)
        assertTrue("Must find itineraries from Kelenföld to Biatorbágy", plan?.itineraries?.isNotEmpty() == true)
        val railItinerary = plan!!.itineraries.firstOrNull { it.legs.any { leg -> leg.mode == "RAIL" } }
        assertNotNull("Must find at least one itinerary with a RAIL leg", railItinerary)
        val railLeg = railItinerary!!.legs.first { it.mode == "RAIL" }
        println("KELENFOLD_BIATORBAGY_RAIL_PLAN line=${railLeg.route?.number} start=${railLeg.start} end=${railLeg.end} duration=${railItinerary.durationSeconds}s")
    }

    // Helper building realistic rail route JSON with trips, parent stations, overnight times, escaping
    private fun buildRealisticSampleRoute(): JSONObject {
        val parentKelenfold = JSONObject()
            .put("gtfsId", "1:005501024")
            .put("name", "Budapest-Kelenföld, vasútállomás") // tests comma escaping
            .put("lat", 47.464444)
            .put("lon", 19.020000)

        val platformKelenfold = JSONObject()
            .put("gtfsId", "1:005501024_0")
            .put("name", "Budapest-Kelenföld \"1\" vágány") // tests double quote escaping
            .put("lat", 47.464444)
            .put("lon", 19.020000)
            .put("platformCode", "1")
            .put("locationType", "STOP")
            .put("parentStation", parentKelenfold)

        val parentBiatorbagy = JSONObject()
            .put("gtfsId", "1:005501057")
            .put("name", "Biatorbágy")
            .put("lat", 47.480278)
            .put("lon", 18.834444)

        val platformBiatorbagy = JSONObject()
            .put("gtfsId", "1:005501057_0")
            .put("name", "Biatorbágy")
            .put("lat", 47.480278)
            .put("lon", 18.834444)
            .put("platformCode", "2")
            .put("locationType", "STOP")
            .put("parentStation", parentBiatorbagy)

        val parentGyor = JSONObject()
            .put("gtfsId", "1:005501289")
            .put("name", "Győr")
            .put("lat", 47.681944)
            .put("lon", 17.634722)

        val platformGyor = JSONObject()
            .put("gtfsId", "1:005501289_0")
            .put("name", "Győr")
            .put("lat", 47.681944)
            .put("lon", 17.634722)
            .put("platformCode", "3")
            .put("locationType", "STOP")
            .put("parentStation", parentGyor)

        // Trip 1: Daytime trip with non-contiguous dates (20261102, 20261105, 20261127) plus out-of-range date (20261020)
        val trip1 = JSONObject()
            .put("gtfsId", "1:trip_9302")
            .put("tripHeadsign", "Győr")
            .put("directionId", "0")
            .put("activeDates", JSONArray(listOf("20261020", "20261102", "20261105", "20261127", "20261102"))) // duplicates & out of range
            .put("stoptimes", JSONArray(listOf(
                JSONObject().put("scheduledArrival", 22200).put("scheduledDeparture", 22200) // 06:10:00
                    .put("pickupType", "SCHEDULED").put("dropoffType", "NONE").put("stop", platformKelenfold),
                JSONObject().put("scheduledArrival", 22980).put("scheduledDeparture", 23040) // 06:23:00 - 06:24:00
                    .put("pickupType", "SCHEDULED").put("dropoffType", "SCHEDULED").put("stop", platformBiatorbagy),
                JSONObject().put("scheduledArrival", 25800).put("scheduledDeparture", 25800) // 07:10:00
                    .put("pickupType", "NONE").put("dropoffType", "SCHEDULED").put("stop", platformGyor)
            )))

        // Trip 2: Overnight trip past midnight (arrival 24:35:00 = 88500s, departure 24:36:00 = 88560s)
        val trip2 = JSONObject()
            .put("gtfsId", "1:trip_overnight")
            .put("tripHeadsign", "Győr éjszakai")
            .put("directionId", "0")
            .put("activeDates", JSONArray(listOf("20261102", "20261103")))
            .put("stoptimes", JSONArray(listOf(
                JSONObject().put("scheduledArrival", 88500).put("scheduledDeparture", 88560) // 24:35:00 - 24:36:00
                    .put("pickupType", "CALL_AGENCY").put("dropoffType", "NONE").put("stop", platformKelenfold),
                JSONObject().put("scheduledArrival", 89400).put("scheduledDeparture", 89460) // 24:50:00 - 24:51:00
                    .put("pickupType", "COORDINATE_WITH_DRIVER").put("dropoffType", "SCHEDULED").put("stop", platformBiatorbagy)
            )))

        return JSONObject()
            .put("gtfsId", "1:V2912_106")
            .put("shortName", "S10")
            .put("longName", "Győr - Budapest-Déli")
            .put("mode", "RAIL")
            .put("color", "0055A5")
            .put("trips", JSONArray(listOf(trip1, trip2)))
    }
}
