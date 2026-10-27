package hu.app.bmenetrend.offline

import android.content.Context
import hu.app.bmenetrend.data.*
import kotlinx.coroutines.runBlocking
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
    private fun endpoint(raw: String, name: String, lat: Double) = PlannerEndpoint("emma:hkir:$raw", name, "", GeoPoint(lat, 19.0))
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
        // Keep a separate valid trip so the installer still has usable data.
        val all = times + "slow,08:00:00,08:00:00,A,1\nslow,08:40:00,08:40:00,B,2\n"
        install(fixture(extra = mapOf("stop_times.txt" to all)))
        assertEquals(time("08:40:00"), plan("B",47.5).itineraries.single().end)
    }
    @Test fun validationUsesSequenceOrderRatherThanCsvOrder() {
        val times = "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nfast,08:15:00,08:15:00,B,30\nfast,08:05:00,08:05:00,A,10\n"
        install(fixture(extra = mapOf("stop_times.txt" to times)))
        assertEquals(time("08:15:00"), plan("B",47.5).itineraries.single().end)
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
                    // Match the worker: progress publication suspends onto other executors.
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
                JOIN stop_times b ON b.trip=a.trip AND b.seq>a.seq JOIN stops sb ON sb.id=b.stop
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
