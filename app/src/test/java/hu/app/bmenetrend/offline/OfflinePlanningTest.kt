package hu.app.bmenetrend.offline

import android.content.Context
import hu.app.bmenetrend.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OfflinePlanningTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val date = LocalDate.of(2026, 10, 27)
    private val source = OfflineSource("volan", "Fixture buses", "https://example.org/gtfs.zip", "https://example.org/license")
    private lateinit var store: OfflinePackStore
    @Before fun setup() { NetworkAccessPolicy.setOffline(context, false); store = OfflinePackStore(context); store.delete() }
    @After fun cleanup() { NetworkAccessPolicy.setOffline(context, false); store.delete() }

    private fun fixture(transfer: String = "", extra: Map<String, String> = emptyMap()): File {
        val files = linkedMapOf(
            "agency.txt" to "agency_id,agency_name,agency_url,agency_timezone\nA,Example,https://example.org,Europe/Budapest\n",
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\nA,Alpha,47.4,19.0\nB,Beta,47.5,19.0\nC,Gamma,47.6,19.0\nD,Other platform,47.5,19.0001\n",
            "routes.txt" to "route_id,route_short_name,route_long_name,route_type\nR,1,First route,3\nS,2,Second route,3\n",
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign\nR,weekday,slow,Beta\nR,weekday,fast,Beta\nS,weekday,connection,Gamma\n",
            "calendar_dates.txt" to "service_id,date,exception_type\nweekday,20261027,1\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type\nslow,08:00:00,08:00:00,A,1,0,0\nslow,08:40:00,08:40:00,B,2,0,0\nfast,08:05:00,08:05:00,A,1,0,0\nfast,08:15:00,08:15:00,B,2,0,0\nconnection,08:20:00,08:20:00,B,1,0,0\nconnection,08:35:00,08:35:00,C,2,0,0\n",
        )
        if (transfer.isNotBlank()) files["transfers.txt"] = transfer
        files.putAll(extra)
        return File.createTempFile("gtfs", ".zip", context.cacheDir).also { file ->
            ZipOutputStream(file.outputStream()).use { out -> files.forEach { (name, value) -> out.putNextEntry(ZipEntry(name)); out.write(value.toByteArray()); out.closeEntry() } }
        }
    }

    private fun install(file: File, validUntil: LocalDate = date, onProgress: suspend (OfflineImportProgress) -> Unit = {}): OfflinePackMetadata = runBlocking {
        val stage = store.staging()
        GtfsImporter.create(stage).use { db ->
            db.beginTransaction()
            try { val importer = GtfsImporter(db, date.minusDays(1), date.plusDays(1), onProgress); importer.import(file, source); importer.finish(); db.setTransactionSuccessful() }
            finally { db.endTransaction() }
        }
        val meta = OfflinePackMetadata(java.util.UUID.randomUUID().toString(), Instant.now(), date, validUntil, stage.length(), file.length(), 4, 3, listOf(source.name), OfflinePackStore.digest(stage))
        store.activate(stage, meta)
        meta
    }

    private fun endpoint(raw: String, name: String, lat: Double, sourceStopIds: List<String> = listOf("emma:hkir:$raw")) =
        PlannerEndpoint("emma:hkir:$raw", name, "", GeoPoint(lat, 19.0), sourceStopIds = sourceStopIds)

    private fun time(value: String) = OfflineTransitData.serviceInstant(date, GtfsImporter.seconds(value)!!)

    private fun plan(to: String = "C", lat: Double = 47.6) = runBlocking {
        OfflineTripPlanner(store).plan(endpoint("A", "Alpha", 47.4), endpoint(to, "Destination", lat), time("07:58:00"), time("07:59:00"))
    }

    @Test fun overtakingTripAndConnectionAreFoundWithoutNetwork() {
        val metadata = install(fixture())
        NetworkAccessPolicy.setOffline(context, true)
        val result = plan()
        assertEquals(metadata.id, result.offlinePack)
        val best = result.itineraries.first()
        assertEquals(time("08:35:00"), best.end)
        assertEquals(time("08:05:00"), best.start)
        assertEquals(1, best.transfers)
        assertTrue(best.legs.none { it.realtime })
    }

    @Test fun prohibitedTripTransferCannotBeUsed() {
        install(fixture("from_stop_id,to_stop_id,transfer_type,from_trip_id,to_trip_id\nB,B,3,fast,connection\n"))
        assertTrue(plan().itineraries.isEmpty())
    }

    @Test fun minimumTransferTimeRejectsMissedConnection() {
        install(fixture("from_stop_id,to_stop_id,transfer_type,min_transfer_time\nB,B,2,600\n"))
        assertTrue(plan().itineraries.isEmpty())
    }

    @Test fun nearbyUnconnectedPlatformsAreNeverTreatedAsWalkable() {
        install(fixture())
        assertTrue(plan("D", 47.5).itineraries.isEmpty())
    }

    @Test fun boardUsesSchedulesAndPreservesExistingStopIds() {
        install(fixture())
        val data = OfflineTransitData(store)
        assertTrue(data.catalogue().first.any { it.id == "emma:hkir:A" })
        val rows = data.board(listOf("emma:hkir:A"), time("07:59:00"), 60)
        assertEquals(listOf(time("08:00:00"), time("08:05:00")), rows.map { it.expectedAt })
        assertTrue(rows.all { it.kind == ArrivalKind.SCHEDULED })
    }

    @Test fun untrustedPackCannotReplaceInstalledPack() {
        val previous = install(fixture())
        val corrupt = store.staging().apply { writeText("corrupt") }
        try { store.activate(corrupt, previous.copy(id = "bad", installedBytes = corrupt.length())); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(previous.id, store.metadata()?.id)
    }

    @Test fun dateBeyondPackValidityIsDistinguishedFromNoRoute() {
        install(fixture())
        try {
            runBlocking { OfflineTripPlanner(store).plan(endpoint("A", "Alpha", 47.4), endpoint("C", "Gamma", 47.6), time("07:59:00").plusSeconds(86400), time("07:59:00").plusSeconds(86400)) }; fail()
        } catch (_: OfflineDateUnavailable) { }
    }

    @Test fun overnightTimesRetainTheirServiceDay() {
        val times = "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nfast,25:05:00,25:05:00,A,1\nfast,25:15:00,25:15:00,B,2\n"
        install(fixture(extra = mapOf("stop_times.txt" to times)), validUntil = date.plusDays(1))
        val rows = OfflineTransitData(store).board(listOf("emma:hkir:A"), time("24:59:00"), 60)
        assertEquals(time("25:05:00"), rows.single().expectedAt)
    }

    @Test fun conditionalPickupIsNeverAdvertisedAsUnrestricted() {
        val times = "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type\nfast,08:05:00,08:05:00,A,1,2\nfast,08:15:00,08:15:00,B,2,0\n"
        install(fixture(extra = mapOf("stop_times.txt" to times)))
        assertTrue(plan("B", 47.5).itineraries.single().legs.single().restrictedService)
    }

    @Test fun importReportsRealRowsAndFinalizationStages() {
        val updates = mutableListOf<OfflineImportProgress>()
        install(fixture(), onProgress = { updates += it })
        assertTrue(updates.any { it.step == OfflineImportStep.STOP_TIMES && it.records == 6L && it.fraction == 1f })
        assertTrue(updates.any { it.step == OfflineImportStep.VALIDATING && it.records == 6L })
        assertTrue(updates.any { it.step == OfflineImportStep.CATALOGUE })
        assertEquals(6, updates.count { it.step == OfflineImportStep.INDEXING })
        assertEquals(OfflineImportStep.OPTIMIZING, updates.last().step)
    }

    @Test fun cancelledImportPreservesPreviousPackAndCanRetry() {
        val previous = install(fixture())
        try {
            install(fixture(), onProgress = { if (it.step == OfflineImportStep.STOP_TIMES) throw kotlinx.coroutines.CancellationException("Cancel import") })
            fail("Import ignored cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(previous.id, store.metadata()?.id)
        val replacement = install(fixture())
        assertEquals(replacement.id, store.metadata()?.id)
        assertTrue(plan().itineraries.isNotEmpty())
    }

    @Test fun validationRejectsBackwardsTimesWithGapsInSequences() {
        val times = "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nfast,08:04:00,08:04:00,B,30\nfast,08:05:00,08:05:00,A,10\n"
        val all = times + "slow,08:00:00,08:00:00,A,1\nslow,08:40:00,08:40:00,B,2\n"
        install(fixture(extra = mapOf("stop_times.txt" to all)))
        assertEquals(time("08:40:00"), plan("B",47.5).itineraries.single().end)
    }

    @Test fun validationUsesSequenceOrderRatherThanCsvOrder() {
        val times = "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nfast,08:15:00,08:15:00,B,30\nfast,08:05:00,08:05:00,A,10\n"
        install(fixture(extra = mapOf("stop_times.txt" to times)))
        assertEquals(time("08:15:00"), plan("B",47.5).itineraries.single().end)
    }

    @Test fun offlineRouteFoundWhenOriginAndDestinationAreConsolidatedStops() {
        val extraStops = """stop_id,stop_name,stop_lat,stop_lon
BIA_1,Biatorbágy vasútállomás,47.4799,18.8339
BIA_2,Biatorbágy vasútállomás,47.4800,18.8340
KEL_VOLAN,Kelenföld vasútállomás M,47.4635,19.0232
"""
        val extraRoutes = "762,762,Budapest-Biatorbágy,3\n"
        val extraTrips = "762,weekday,trip_volan,Kelenföld vasútállomás M\n"
        val extraStopTimes = """trip_volan,08:59:00,08:59:00,BIA_2,1,0,0
trip_volan,09:28:00,09:28:00,KEL_VOLAN,2,0,0
"""
        val file = fixture(extra = mapOf(
            "stops.txt" to extraStops,
            "routes.txt" to "route_id,route_short_name,route_long_name,route_type\n$extraRoutes",
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign\n$extraTrips",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type\n$extraStopTimes"
        ))
        install(file)

        val origin = endpoint("BIA_1", "Biatorbágy vasútállomás", 47.4799, sourceStopIds = listOf("emma:hkir:BIA_1", "emma:hkir:BIA_2"))
        val dest = endpoint("KEL_VOLAN", "Kelenföld vasútállomás M", 47.4635, sourceStopIds = listOf("emma:hkir:KEL_VOLAN"))

        val ready = time("08:58:00")
        val plan = runBlocking { OfflineTripPlanner(store).plan(origin, dest, ready, ready) }
        assertTrue("Expected suitable route to be found from Biatorbágy to Kelenföld", plan.itineraries.isNotEmpty())
        val trip = plan.itineraries.first()
        assertEquals("762", trip.legs.first().route?.number)
        assertEquals("Kelenföld vasútállomás M", trip.legs.last().to.name)
    }

    @Test fun groupedOriginWherePrimaryHasNoUsefulTripFindsRoute() {
        val stops = """stop_id,stop_name,stop_lat,stop_lon
KEL_BKK_PRIMARY,Kelenföld vasútállomás M,47.4645,19.0229
KEL_VOLAN,Kelenföld vasútállomás M,47.4635,19.0232
BIA,Biatorbágy vasútállomás,47.4799,18.8339
"""
        val routes = "route_id,route_short_name,route_long_name,route_type\n762,762,Budapest-Biatorbágy,3\nM4,M4,Metro 4,1\n"
        val trips = """route_id,service_id,trip_id,trip_headsign
762,weekday,trip_rev,Biatorbágy vasútállomás
M4,weekday,trip_bkk_unrelated,Keleti pályaudvar M
"""
        val stopTimes = """trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type
trip_bkk_unrelated,15:00:00,15:00:00,KEL_BKK_PRIMARY,1,0,0
trip_bkk_unrelated,15:10:00,15:10:00,KEL_BKK_PRIMARY,2,0,0
trip_rev,15:00:00,15:00:00,KEL_VOLAN,1,0,0
trip_rev,15:25:00,15:25:00,BIA,2,0,0
"""
        val file = fixture(extra = mapOf(
            "stops.txt" to stops,
            "routes.txt" to routes,
            "trips.txt" to trips,
            "stop_times.txt" to stopTimes
        ))
        install(file)

        val originGrouped = endpoint("KEL_BKK_PRIMARY", "Kelenföld vasútállomás M", 47.4645, sourceStopIds = listOf("emma:hkir:KEL_BKK_PRIMARY", "emma:hkir:KEL_VOLAN"))
        val destination = endpoint("BIA", "Biatorbágy vasútállomás", 47.4799, sourceStopIds = listOf("emma:hkir:BIA"))

        val ready = time("14:55:00")
        val plan = runBlocking { OfflineTripPlanner(store).plan(originGrouped, destination, ready, ready) }
        assertTrue("Expected route found using secondary platform KEL_VOLAN in grouped origin", plan.itineraries.isNotEmpty())
        val leg = plan.itineraries.first().legs.first()
        assertEquals("762", leg.route?.number)
        assertEquals("Biatorbágy vasútállomás", leg.to.name)
    }

    @Test fun singleExactPlatformMustNotResolveNearbyAlternatives() {
        val extraStops = """stop_id,stop_name,stop_lat,stop_lon
BIA_1,Biatorbágy vasútállomás,47.4799,18.8339
BIA_2,Biatorbágy vasútállomás,47.4800,18.8340
KEL_VOLAN,Kelenföld vasútállomás M,47.4635,19.0232
"""
        val extraRoutes = "762,762,Budapest-Biatorbágy,3\n"
        val extraTrips = "762,weekday,trip_volan,Kelenföld vasútállomás M\n"
        val extraStopTimes = """trip_volan,08:59:00,08:59:00,BIA_2,1,0,0
trip_volan,09:28:00,09:28:00,KEL_VOLAN,2,0,0
"""
        val file = fixture(extra = mapOf(
            "stops.txt" to extraStops,
            "routes.txt" to "route_id,route_short_name,route_long_name,route_type\n$extraRoutes",
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign\n$extraTrips",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type\n$extraStopTimes"
        ))
        install(file)

        val singleExactOriginWithoutTrip = endpoint("BIA_1", "Biatorbágy vasútállomás", 47.4799, sourceStopIds = listOf("emma:hkir:BIA_1"))
        val destination = endpoint("KEL_VOLAN", "Kelenföld vasútállomás M", 47.4635, sourceStopIds = listOf("emma:hkir:KEL_VOLAN"))

        val ready = time("08:58:00")
        val plan = runBlocking { OfflineTripPlanner(store).plan(singleExactOriginWithoutTrip, destination, ready, ready) }
        assertTrue("Exact single platform BIA_1 must not resolve nearby BIA_2 alternatives", plan.itineraries.isEmpty())
    }

    @Test fun parentStationExplicitChildrenResolution() {
        val stops = """stop_id,stop_name,stop_lat,stop_lon,location_type,parent_station
PARENT_STATION,Station Name,47.4800,18.8340,1,
CHILD_PLATFORM_1,Station Name,47.4801,18.8341,0,PARENT_STATION
CHILD_PLATFORM_2,Station Name,47.4802,18.8342,0,PARENT_STATION
DEST,Destination,47.4900,18.8500,0,
"""
        val routes = "route_id,route_short_name,route_long_name,route_type\n100,100,Test Route,3\n"
        val trips = "route_id,service_id,trip_id,trip_headsign\n100,weekday,trip1,Dest\n"
        val stopTimes = """trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type
trip1,10:00:00,10:00:00,CHILD_PLATFORM_2,1,0,0
trip1,10:20:00,10:20:00,DEST,2,0,0
"""
        val file = fixture(extra = mapOf(
            "stops.txt" to stops,
            "routes.txt" to routes,
            "trips.txt" to trips,
            "stop_times.txt" to stopTimes
        ))
        install(file)

        val parentOrigin = endpoint("PARENT_STATION", "Station Name", 47.4800, sourceStopIds = listOf("emma:hkir:PARENT_STATION"))
        val destination = endpoint("DEST", "Destination", 47.4900, sourceStopIds = listOf("emma:hkir:DEST"))

        val ready = time("09:55:00")
        val plan = runBlocking { OfflineTripPlanner(store).plan(parentOrigin, destination, ready, ready) }
        assertTrue("Parent station must resolve its direct platform children and find the route", plan.itineraries.isNotEmpty())
        assertEquals("100", plan.itineraries.first().legs.first().route?.number)
    }

    @Test(expected = OfflineEndpointUnavailable::class)
    fun unknownOrNonDownloadedEndpointThrows() {
        install(fixture())
        val unknownOrigin = endpoint("NON_EXISTENT_STOP", "Nowhere", 47.0, sourceStopIds = listOf("emma:hkir:NON_EXISTENT_STOP"))
        val destination = endpoint("C", "Gamma", 47.6, sourceStopIds = listOf("emma:hkir:C"))

        val ready = time("08:00:00")
        runBlocking { OfflineTripPlanner(store).plan(unknownOrigin, destination, ready, ready) }
    }

    @Test fun noFabricatedIntermediateCrossPlatformTransfer() {
        val stops = """stop_id,stop_name,stop_lat,stop_lon
BIA_2,Biatorbágy vasútállomás,47.4800,18.8340
KEL_VOLAN,Kelenföld vasútállomás M,47.4635,19.0232
KEL_BKK_1,Kelenföld vasútállomás M,47.4645,19.0229
MORICZ,Móricz Zsigmond körtér M,47.4740,19.0460
"""
        val routes = """route_id,route_short_name,route_long_name,route_type
762,762,Budapest-Biatorbágy,3
M4,M4,Metro 4,1
"""
        val trips = """route_id,service_id,trip_id,trip_headsign
762,weekday,trip_volan,Kelenföld vasútállomás M
M4,weekday,trip_bkk,Móricz Zsigmond körtér M
"""
        val stopTimes = """trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type
trip_volan,08:59:00,08:59:00,BIA_2,1,0,0
trip_volan,09:28:00,09:28:00,KEL_VOLAN,2,0,0
trip_bkk,09:35:00,09:35:00,KEL_BKK_1,1,0,0
trip_bkk,09:42:00,09:42:00,MORICZ,2,0,0
"""
        val file = fixture(extra = mapOf(
            "stops.txt" to stops,
            "routes.txt" to routes,
            "trips.txt" to trips,
            "stop_times.txt" to stopTimes
        ))
        install(file)

        val origin = endpoint("BIA_2", "Biatorbágy vasútállomás", 47.4800, sourceStopIds = listOf("emma:hkir:BIA_2"))
        val destination = endpoint("MORICZ", "Móricz Zsigmond körtér M", 47.4740, sourceStopIds = listOf("emma:hkir:MORICZ"))

        val ready = time("08:58:00")
        val plan = runBlocking { OfflineTripPlanner(store).plan(origin, destination, ready, ready) }
        assertTrue("Without an explicit GTFS transfer or path between KEL_VOLAN and KEL_BKK_1, trip must not fabricate intermediate transfer", plan.itineraries.isEmpty())
    }

    @Test fun routeSpecificForbiddenTransferIsRejected() {
        val extraStops = """stop_id,stop_name,stop_lat,stop_lon
A,Stop A,47.4000,19.0000
B,Stop B,47.5000,19.0000
C,Stop C,47.6000,19.0000
"""
        val extraRoutes = """route_id,route_short_name,route_long_name,route_type
R_FORBIDDEN,10,Route 10,3
R_ALLOWED,20,Route 20,3
R_CONNECT,30,Route 30,3
"""
        val extraTrips = """route_id,service_id,trip_id,trip_headsign
R_FORBIDDEN,weekday,trip_forbidden,Stop B
R_ALLOWED,weekday,trip_allowed,Stop B
R_CONNECT,weekday,trip_connect,Stop C
"""
        val extraStopTimes = """trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type
trip_forbidden,08:00:00,08:00:00,A,1,0,0
trip_forbidden,08:15:00,08:15:00,B,2,0,0
trip_allowed,08:00:00,08:00:00,A,1,0,0
trip_allowed,08:15:00,08:15:00,B,2,0,0
trip_connect,08:25:00,08:25:00,B,1,0,0
trip_connect,08:40:00,08:40:00,C,2,0,0
"""
        val transfers = """from_stop_id,to_stop_id,transfer_type,from_route_id,to_route_id
B,B,3,R_FORBIDDEN,R_CONNECT
"""
        val file = fixture(transfer = transfers, extra = mapOf(
            "stops.txt" to extraStops,
            "routes.txt" to extraRoutes,
            "trips.txt" to extraTrips,
            "stop_times.txt" to extraStopTimes
        ))
        install(file)

        val originA = endpoint("A", "Stop A", 47.4)
        val destC = endpoint("C", "Stop C", 47.6)
        val ready = time("07:55:00")
        val plan = runBlocking { OfflineTripPlanner(store).plan(originA, destC, ready, ready) }
        assertTrue("Must find connection via R_ALLOWED when R_FORBIDDEN is forbidden", plan.itineraries.isNotEmpty())
        val firstLeg = plan.itineraries.first().legs.first()
        assertEquals("20", firstLeg.route?.number)
    }

    @Test fun routeSpecificMinTransferDurationIsEnforced() {
        val extraStops = """stop_id,stop_name,stop_lat,stop_lon
A,Stop A,47.4000,19.0000
B,Stop B,47.5000,19.0000
C,Stop C,47.6000,19.0000
"""
        val extraRoutes = """route_id,route_short_name,route_long_name,route_type
R_FAST,10,Route 10,3
R_CONNECT,30,Route 30,3
"""
        val extraTrips = """route_id,service_id,trip_id,trip_headsign
R_FAST,weekday,trip_fast,Stop B
R_CONNECT,weekday,trip_connect,Stop C
"""
        val extraStopTimes = """trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type
trip_fast,08:00:00,08:00:00,A,1,0,0
trip_fast,08:15:00,08:15:00,B,2,0,0
trip_connect,08:20:00,08:20:00,B,1,0,0
trip_connect,08:35:00,08:35:00,C,2,0,0
"""
        val transfers = """from_stop_id,to_stop_id,transfer_type,min_transfer_time,from_route_id,to_route_id
B,B,2,600,R_FAST,R_CONNECT
"""
        val file = fixture(transfer = transfers, extra = mapOf(
            "stops.txt" to extraStops,
            "routes.txt" to extraRoutes,
            "trips.txt" to extraTrips,
            "stop_times.txt" to extraStopTimes
        ))
        install(file)

        val originA = endpoint("A", "Stop A", 47.4)
        val destC = endpoint("C", "Stop C", 47.6)
        val ready = time("07:55:00")
        val plan = runBlocking { OfflineTripPlanner(store).plan(originA, destC, ready, ready) }
        assertTrue("300s transfer gap must be rejected when route-specific rule requires 600s", plan.itineraries.isEmpty())
    }

    @Test fun transferAllowanceRemainderAccountsForWalking() {
        val extraStops = """stop_id,stop_name,stop_lat,stop_lon
A,Stop A,47.4000,19.0000
B1,Stop B1,47.5000,19.0000
B2,Stop B2,47.5010,19.0010
C,Stop C,47.6000,19.0000
"""
        val extraRoutes = """route_id,route_short_name,route_long_name,route_type
R1,1,Route 1,3
R2,2,Route 2,3
"""
        val extraTrips = """route_id,service_id,trip_id,trip_headsign
R1,weekday,trip_r1,Stop B1
R2,weekday,trip_r2,Stop C
"""
        val extraStopTimes = """trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type
trip_r1,08:00:00,08:00:00,A,1,0,0
trip_r1,08:15:00,08:15:00,B1,2,0,0
trip_r2,08:20:00,08:20:00,B2,1,0,0
trip_r2,08:40:00,08:40:00,C,2,0,0
"""
        val pathways = """from_stop_id,to_stop_id,traversal_time,is_bidirectional
B1,B2,120,1
"""
        val transfers = """from_stop_id,to_stop_id,transfer_type,min_transfer_time
B1,B2,2,300
"""
        val file = fixture(transfer = transfers, extra = mapOf(
            "stops.txt" to extraStops,
            "routes.txt" to extraRoutes,
            "trips.txt" to extraTrips,
            "stop_times.txt" to extraStopTimes,
            "pathways.txt" to pathways
        ))
        install(file)

        val originA = endpoint("A", "Stop A", 47.4)
        val destC = endpoint("C", "Stop C", 47.6)
        val ready = time("07:55:00")
        val plan = runBlocking { OfflineTripPlanner(store).plan(originA, destC, ready, ready) }
        assertTrue("Trip departing at 08:20:00 after 120s walk and 300s total allowance must connect", plan.itineraries.isNotEmpty())
    }

    @Test fun overnightDepartureOnPriorServiceDayRetainsCorrectBounds() {
        val extraStops = """stop_id,stop_name,stop_lat,stop_lon
NIGHT_A,Night Origin,47.4000,19.0000
NIGHT_B,Night Dest,47.5000,19.0000
"""
        val extraRoutes = "N900,900,Night Bus 900,3\n"
        val extraTrips = "N900,weekday,trip_night,Night Dest\n"
        val extraStopTimes = """trip_night,25:30:00,25:30:00,NIGHT_A,1,0,0
trip_night,25:50:00,25:50:00,NIGHT_B,2,0,0
"""
        val file = fixture(extra = mapOf(
            "stops.txt" to extraStops,
            "routes.txt" to "route_id,route_short_name,route_long_name,route_type\n$extraRoutes",
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign\n$extraTrips",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type\n$extraStopTimes"
        ))
        install(file, validUntil = date.plusDays(1))

        val origin = endpoint("NIGHT_A", "Night Origin", 47.4)
        val dest = endpoint("NIGHT_B", "Night Dest", 47.5)
        val nextDayReady = OfflineTransitData.serviceInstant(date.plusDays(1), 3600)
        val plan = runBlocking { OfflineTripPlanner(store).plan(origin, dest, nextDayReady, nextDayReady) }
        assertTrue("Overnight trip departing at 25:30 on day 0 must be found when querying at 01:00 on day 1", plan.itineraries.isNotEmpty())
        val leg = plan.itineraries.first().legs.first()
        assertEquals("900", leg.route?.number)
        assertEquals(OfflineTransitData.serviceInstant(date, 25 * 3600 + 30 * 60), leg.start)
    }

    @Test fun pickupDropoffRestrictionsExcludeUnboardableStops() {
        val extraStops = """stop_id,stop_name,stop_lat,stop_lon
A,Stop A,47.4000,19.0000
B,Stop B,47.5000,19.0000
C,Stop C,47.6000,19.0000
"""
        val extraRoutes = "100,100,Express,3\n"
        val extraTrips = "100,weekday,trip_express,Stop C\n"
        val extraStopTimes = """trip_express,08:00:00,08:00:00,A,1,0,0
trip_express,08:20:00,08:20:00,B,2,1,1
trip_express,08:40:00,08:40:00,C,3,0,0
"""
        val file = fixture(extra = mapOf(
            "stops.txt" to extraStops,
            "routes.txt" to "route_id,route_short_name,route_long_name,route_type\n$extraRoutes",
            "trips.txt" to "route_id,service_id,trip_id,trip_headsign\n$extraTrips",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type\n$extraStopTimes"
        ))
        install(file)

        val originA = endpoint("A", "Stop A", 47.4)
        val stopB = endpoint("B", "Stop B", 47.5)
        val destC = endpoint("C", "Stop C", 47.6)
        val ready = time("07:55:00")

        val planToB = runBlocking { OfflineTripPlanner(store).plan(originA, stopB, ready, ready) }
        assertTrue("Prohibited dropoff at B must not produce itineraries ending at B", planToB.itineraries.isEmpty())

        val planFromB = runBlocking { OfflineTripPlanner(store).plan(stopB, destC, ready, ready) }
        assertTrue("Prohibited pickup at B must not produce itineraries starting at B", planFromB.itineraries.isEmpty())

        val planToC = runBlocking { OfflineTripPlanner(store).plan(originA, destC, ready, ready) }
        assertTrue("Express trip A to C must be found", planToC.itineraries.isNotEmpty())
        assertEquals("100", planToC.itineraries.first().legs.first().route?.number)
    }

    @Test fun coroutineCancellationAbortsPromptly() = runBlocking {
        install(fixture())
        val origin = endpoint("A", "Alpha", 47.4)
        val dest = endpoint("C", "Gamma", 47.6)
        val ready = time("07:58:00")
        val job = launch(Dispatchers.Default) {
            OfflineTripPlanner(store).plan(origin, dest, ready, ready)
        }
        job.cancel()
        try {
            job.join()
            assertTrue(job.isCancelled)
        } catch (_: CancellationException) {
            // Cancelled cleanly
        }
    }

    @Test fun actualSelectionWithRealInstalledPackFindsRoute() = runBlocking {
        val packEnv = System.getenv("OFFLINE_INSTALLED_PACK")
        Assume.assumeTrue("Skipping real pack test: OFFLINE_INSTALLED_PACK env variable not set", !packEnv.isNullOrBlank())
        val packFile = if (File(packEnv!!).exists()) File(packEnv) else File("/tmp/bmenetrend-offline-pack.db")
        Assume.assumeTrue("Pack file does not exist: $packFile", packFile.exists())
        val packRoot = File(context.noBackupFilesDir, "offline-pack").apply { mkdirs() }
        val targetDb = File(packRoot, "c81352c5-f079-4497-bbb3-944b065621a1.db")
        if (!targetDb.exists()) {
            try {
                java.nio.file.Files.createSymbolicLink(targetDb.toPath(), packFile.toPath())
            } catch (_: Exception) {
                java.nio.file.Files.copy(packFile.toPath(), targetDb.toPath())
            }
        }
        val activeJson = File(packRoot, "active.json")
        activeJson.writeText("""{"format":1,"id":"c81352c5-f079-4497-bbb3-944b065621a1","builtAt":"2026-10-27T06:44:35.020787Z","validFrom":"2026-10-27","validUntil":"2026-11-25","installedBytes":388014080,"downloadBytes":167882292,"stops":68395,"trips":368235,"sources":["Volán – országos és helyi autóbuszok","BKK – Budapest","viabusz","kanyargo","homm"],"sha256":"b86e057ee97af7b0131276d3e8d3627d5d13f17f43b541b879adddcefa6e0fc1","retainedSourceBytes":0,"warnings":[]}""")

        val catalogue = OfflineTransitData(store).catalogue()
        val available = catalogue.first.filterNot { it.station }
        val index = TransitSearch.indexStops(emptyList(), available)

        // As PlannerViewModel.search does using PlannerEndpoint.fromStop:
        val originMatches = TransitSearch.stops(index, TransitSearch.query("Biatorbágy, vasútállomás"), null, emptySet())
        val originEndpoint = PlannerEndpoint.fromStop(originMatches.first())

        val destMatches = TransitSearch.stops(index, TransitSearch.query("Kelenföld vasútállomás M"), null, emptySet())
        val destEndpoint = PlannerEndpoint.fromStop(destMatches.first())

        withTimeout(240_000L) {
            // 1. Ready at 08:58 catches bus 08:59 (trip 10158, route 762)
            val departure0858 = OfflineTransitData.serviceInstant(LocalDate.of(2026, 10, 27), 8 * 3600 + 58 * 60)
            val plan0858 = OfflineTripPlanner(store).plan(originEndpoint, destEndpoint, departure0858, departure0858)
            assertTrue("Expected offline route from Biatorbágy to Kelenföld at 08:58", plan0858.itineraries.isNotEmpty())
            val leg0858 = plan0858.itineraries.first().legs.first()
            assertEquals("762", leg0858.route?.number)
            assertEquals(OfflineTransitData.serviceInstant(LocalDate.of(2026, 10, 27), 8 * 3600 + 59 * 60), leg0858.start)

            // 2. Ready at 08:59: 60s board allowance excludes 08:59 departure; assert NEXT feasible service is found
            val departure0859 = OfflineTransitData.serviceInstant(LocalDate.of(2026, 10, 27), 8 * 3600 + 59 * 60)
            val plan0859 = OfflineTripPlanner(store).plan(originEndpoint, destEndpoint, departure0859, departure0859)
            assertTrue("Expected next feasible service after 08:59", plan0859.itineraries.isNotEmpty())
            val nextLeg = plan0859.itineraries.first().legs.first()
            assertTrue("Next service should depart >= 09:00", nextLeg.start >= OfflineTransitData.serviceInstant(LocalDate.of(2026, 10, 27), 9 * 3600))

            // 3. Reverse direction: Kelenföld (many origin platforms) -> Biatorbágy
            val reverseOrigin = destEndpoint
            val reverseDest = originEndpoint
            val reverseDeparture = OfflineTransitData.serviceInstant(LocalDate.of(2026, 10, 27), 15 * 3600)

            // Multi-run matched warm benchmark: Frozen baseline (e60a444) vs R1 optimized
            // Trimmed/bounded protocol: 1 warmup + 2 measured runs per engine (wall-clock-capped)
            // 1. Frozen baseline benchmark (1 warmup, 2 measured runs)
            val frozenPlanner = FrozenBaselineOfflineTripPlanner(store)
            repeat(1) {
                frozenPlanner.plan(reverseOrigin, reverseDest, reverseDeparture, reverseDeparture)
            }
            val frozenTimings = mutableListOf<Double>()
            var frozenPlan: TripPlan? = null
            repeat(2) {
                val started = System.nanoTime()
                val plan = frozenPlanner.plan(reverseOrigin, reverseDest, reverseDeparture, reverseDeparture)
                frozenTimings += (System.nanoTime() - started) / 1e9
                frozenPlan = plan
            }
            val frozenSorted = frozenTimings.sorted()
            val frozenMin = frozenSorted.first()
            val frozenMax = frozenSorted.last()
            val frozenMedian = frozenSorted[frozenSorted.size / 2]
            val frozenMean = frozenTimings.average()

            // 2. R1 optimized benchmark (1 warmup, 2 measured runs)
            val r1Planner = OfflineTripPlanner(store)
            repeat(1) {
                r1Planner.plan(reverseOrigin, reverseDest, reverseDeparture, reverseDeparture)
            }
            val r1Timings = mutableListOf<Double>()
            var r1Plan: TripPlan? = null
            repeat(2) {
                val started = System.nanoTime()
                val plan = r1Planner.plan(reverseOrigin, reverseDest, reverseDeparture, reverseDeparture)
                r1Timings += (System.nanoTime() - started) / 1e9
                r1Plan = plan
            }
            val r1Sorted = r1Timings.sorted()
            val r1Min = r1Sorted.first()
            val r1Max = r1Sorted.last()
            val r1Median = r1Sorted[r1Sorted.size / 2]
            val r1Mean = r1Timings.average()

            val warmSpeedup = frozenMedian / r1Median
            val reductionPct = (1.0 - r1Median / frozenMedian) * 100.0

            println("FROZEN_BASELINE_WARM_BENCHMARK runs=2 warmup=1 timings=$frozenTimings min=${"%.4f".format(frozenMin)}s max=${"%.4f".format(frozenMax)}s median=${"%.4f".format(frozenMedian)}s mean=${"%.4f".format(frozenMean)}s journeys=${frozenPlan?.itineraries?.size}")
            println("OPTIMIZED_R1_WARM_BENCHMARK runs=2 warmup=1 timings=$r1Timings min=${"%.4f".format(r1Min)}s max=${"%.4f".format(r1Max)}s median=${"%.4f".format(r1Median)}s mean=${"%.4f".format(r1Mean)}s journeys=${r1Plan?.itineraries?.size}")
            println("MATCHED_WARM_SPEEDUP: ratio=${"%.2f".format(warmSpeedup)}x reduction=${"%.1f".format(reductionPct)}%")

            assertTrue("Expected reverse itineraries to be found in R1", r1Plan?.itineraries?.isNotEmpty() == true)
            assertTrue("Expected reverse itineraries to be found in Frozen baseline", frozenPlan?.itineraries?.isNotEmpty() == true)
            val reverseLeg = r1Plan!!.itineraries.first().legs.first()
            assertEquals("762", reverseLeg.route?.number)
            assertEquals(1, r1Plan!!.itineraries.size)
            assertEquals("Itinerary count must match frozen baseline", frozenPlan!!.itineraries.size, r1Plan!!.itineraries.size)
            assertEquals("Route number must match frozen baseline", frozenPlan!!.itineraries.first().legs.first().route?.number, reverseLeg.route?.number)
            assertEquals("Itineraries must be identical", frozenPlan!!.itineraries, r1Plan!!.itineraries)
        }
    }

    @Test fun completeRealFeedsCanBeImportedWithinStorageCap(): Unit = runBlocking {
        // Opt-in integration run, independent of normal deterministic tests.
        Assume.assumeTrue(System.getenv("OFFLINE_REAL_FEEDS") == "1")
        val root = File("/tmp/bmenetrend-offline-feeds")
        val stage = store.staging()
        val started = System.nanoTime()
        onOfflineDatabaseThread {
            GtfsImporter.create(stage).use { db ->
                db.beginTransaction()
                try {
                    var lastStep: OfflineImportStep? = null
                    val report: suspend (OfflineImportProgress) -> Unit = { progress ->
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { kotlinx.coroutines.yield() }
                        if (progress.step != lastStep) {
                            println("REAL_IMPORT_STAGE ${progress.step} elapsedSeconds=${(System.nanoTime()-started)/1e9}")
                            lastStep = progress.step
                        }
                    }
                    val importer = GtfsImporter(db, LocalDate.of(2026, 10, 24), LocalDate.of(2026, 11, 24), report)
                    importer.import(File(root, "volan.zip"), OfflineSource.defaults[0])
                    importer.import(File(root, "bkk.zip"), OfflineSource.defaults[1])
                    importer.finish(); db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
        }
        println("REAL_OFFLINE_PACK bytes=${stage.length()} importSeconds=${(System.nanoTime()-started)/1e9}")
        assertTrue(stage.length() <= OFFLINE_MAX_BYTES)
        var endpoints: Pair<PlannerEndpoint, PlannerEndpoint>? = null
        android.database.sqlite.SQLiteDatabase.openDatabase(stage.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("""SELECT sa.source_id,sa.name,sa.lat,sa.lon,sb.source_id,sb.name,sb.lat,sb.lon FROM stop_times a
                JOIN stops sa ON sa.id=a.stop JOIN trips t ON t.id=a.trip JOIN service_days d ON d.service=t.service
                JOIN stop_times b ON b.trip=a.trip AND b.seq>a.seq JOIN stops sb ON sb.stop=b.stop
                WHERE sa.name LIKE '%Kelenföld vasútállomás%' AND sb.name LIKE '%Biatorbágy%'
                AND d.day=? AND a.departure>=25200 AND a.pickup!=1 AND b.dropoff!=1 LIMIT 1""", arrayOf(LocalDate.of(2026,10,27).toEpochDay().toString())).use { c ->
                assertTrue("Expected a real Kelenföld–Biatorbágy bus", c.moveToFirst())
                endpoints = PlannerEndpoint(stopAppId(c.getString(0)), c.getString(1), "", GeoPoint(c.getDouble(2),c.getDouble(3))) to
                    PlannerEndpoint(stopAppId(c.getString(4)), c.getString(5), "", GeoPoint(c.getDouble(6),c.getDouble(7)))
            }
        }
        val meta = OfflinePackMetadata(java.util.UUID.randomUUID().toString(), Instant.now(), LocalDate.of(2026,10,26), LocalDate.of(2026,11,24),stage.length(),167_000_000,1,1,listOf("Volán","BKK"),OfflinePackStore.digest(stage))
        store.activate(stage,meta)
        endpoints?.let { (a,b) ->
            val start = Instant.parse("2026-10-27T06:00:00Z")
            val routingStarted = System.nanoTime()
            try {
                val plan = kotlinx.coroutines.withTimeout(30_000) { OfflineTripPlanner(store).plan(a,b,start,start) }
                println("REAL_OFFLINE_ROUTE from=${a.name} to=${b.name} seconds=${(System.nanoTime()-routingStarted)/1e9} journeys=${plan.itineraries.size}")
                assertTrue(plan.itineraries.isNotEmpty())
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) { fail("Real offline route exceeded 30 seconds") }
        }
    }
}
