package hu.app.bmenetrend.offline

import android.content.ContextWrapper
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
import hu.app.bmenetrend.TransitViewModel
import hu.app.bmenetrend.PlannerViewModel
import hu.app.bmenetrend.PlannerField
import hu.app.bmenetrend.data.*
import hu.app.bmenetrend.map.OfflineBasemap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Opt-in real Android/SQLite benchmark; no activity, UI input or live HTTP needed. */
class OfflineRealPackTest {
    @Test fun installedPackBacksBothViewModelsAndFavoritesWhileOffline() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("verifyInstalledOfflinePack") == "true")
        val app = instrumentation.targetContext.applicationContext as android.app.Application
        val metadata = OfflinePackStore(app).metadata() ?: error("Run the full download check first")
        val manual = app.getSharedPreferences("offline", 0).getBoolean("enabled", false)
        val preferences = app.getSharedPreferences("viabusz", 0)
        val favorites = preferences.getStringSet("favorites", emptySet())!!.toSet()
        NetworkAccessPolicy.setOffline(app, true)
        val model = TransitViewModel(app)
        val planner = PlannerViewModel(app)
        try {
            val loaded = withTimeout(15_000) { model.state.first { it.offlineMode && !it.loading && !it.nearbyLoading && it.emmaStops.isNotEmpty() } }
            assertTrue(loaded.vehicles.isEmpty())
            assertTrue(loaded.emmaRoutes.isNotEmpty())
            withContext(Dispatchers.Main) {
                planner.updateOfflinePack(metadata)
                planner.updateStops(emptyList(), loaded.emmaStops)
                planner.edit(PlannerField.ORIGIN)
                planner.search("Biatorbágy vasútállomás")
            }
            val originSearch = withTimeout(10_000) { planner.state.first { !it.searching && (it.recommendation != null || it.suggestions.isNotEmpty()) } }
            val origin = originSearch.recommendation ?: originSearch.suggestions.first { it.isStation }
            withContext(Dispatchers.Main) {
                planner.choose(origin)
                planner.edit(PlannerField.DESTINATION)
                planner.search("Kelenföld vasútállomás")
            }
            val destinationSearch = withTimeout(10_000) { planner.state.first { !it.searching && (it.recommendation != null || it.suggestions.isNotEmpty()) } }
            val destination = destinationSearch.recommendation ?: destinationSearch.suggestions.first { it.isStation }
            android.util.Log.i("OfflineRealPack", "Selected origin=${origin.sourceStopIds} destination=${destination.sourceStopIds}")
            val date = LocalDate.now(TripPlanner.zone).plusDays(1)
            withContext(Dispatchers.Main) {
                planner.choose(destination)
                planner.setDeparture(date.atTime(7, 0).atZone(TripPlanner.zone).toInstant())
                planner.plan()
            }
            val result = withTimeout(20_000) { planner.state.first { !it.planning && (it.result != null || it.error != null) } }
            assertNull("Offline UI-selected trip failed: ${result.error}", result.error)
            assertTrue(result.result!!.itineraries.isNotEmpty())
            assertEquals(metadata.id, result.result!!.offlinePack)
            withContext(Dispatchers.Main) {
                model.setFavoritesVisible(true)
                val stop = origin.sourceStopIds.firstOrNull() ?: origin.id
                if (stop !in model.state.value.favorites) model.toggleFavorite(stop)
            }
            withTimeout(10_000) { model.state.first { it.favoriteStations.isNotEmpty() && !it.favoritesLoading } }
            assertTrue(NetworkAccessPolicy.offline.value)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "OFFLINE_VIEWMODELS search, trip planning, catalogue and favourites passed\n") })
        } finally {
            model.viewModelScope.cancel()
            planner.viewModelScope.cancel()
            preferences.edit().putStringSet("favorites", favorites).apply()
            NetworkAccessPolicy.setOffline(app, manual)
        }
    }

    @Test fun fullDownloadCompilesOfflinePackAndCanBeRead() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("fullOfflineDownload") == "true")
        val app = instrumentation.targetContext
        assumeTrue("Use a fresh development app: this check does not replace an existing pack", OfflinePackStore(app).metadata() == null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = OfflinePackRepository(app, scope)
        try {
            withTimeout(10_000) { repository.state.first { it.loaded } }
            NetworkAccessPolicy.setOffline(app, false)
            withTimeout(12_000) { NetworkAccessPolicy.internetAvailable.first { it } }
            assertFalse("An internet connection is required for this opt-in download", NetworkAccessPolicy.offline.value)
            // This opt-in fresh-development-app check owns any unfinished retry
            // from its previous invocation, never an installed production pack.
            repository.cancel()
            withTimeout(10_000) { repository.state.first { it.loaded && !it.busy } }
            val start = SystemClock.elapsedRealtime()
            var indexStarted: Long? = null
            val observations = scope.launch {
                repository.state.collect {
                    if (it.phase == OfflinePackPhase.IMPORTING && indexStarted == null) indexStarted = SystemClock.elapsedRealtime()
                    android.util.Log.i("OfflineRealPack", "download ${it.phase} ${it.source} ${it.importProgress?.step} ${SystemClock.elapsedRealtime() - start} ms")
                }
            }
            repository.download(false)
            val ready = withTimeout(180_000) { repository.state.first { it.phase == OfflinePackPhase.READY || it.phase == OfflinePackPhase.FAILED } }
            assertEquals("Worker failed: ${ready.error}", OfflinePackPhase.READY, ready.phase)
            assertNotNull(ready.metadata)
            observations.cancel()
            val elapsed = SystemClock.elapsedRealtime() - start
            val metrics = Bundle().apply {
                putLong("download_to_ready_ms", elapsed)
                putLong("download_and_local_sources_ms", (indexStarted ?: start) - start)
                putLong("index_and_activation_ms", SystemClock.elapsedRealtime() - (indexStarted ?: start))
                putLong("database_bytes", ready.metadata!!.installedBytes)
                putLong("source_bytes", ready.metadata!!.downloadBytes)
            }
            val catalogue = withContext(Dispatchers.IO) { OfflineTransitData(repository.store).catalogue() }
            assertTrue(catalogue.first.isNotEmpty() && catalogue.second.isNotEmpty())
            metrics.putString("stream", "OFFLINE_FULL_DOWNLOAD $metrics\n")
            instrumentation.sendStatus(0, metrics)
        } finally { scope.cancel() }
    }

    @Test fun importedPackServesSearchBoardsLineDetailsMapAndRoutingWithoutHttp() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Push the official feeds and pass -e realOfflinePack true", args.getString("realOfflinePack") == "true")
        val app = instrumentation.targetContext
        val feeds = File(args.getString("feedDirectory") ?: File(app.getExternalFilesDir(null), "offline-feeds").path)
        assertTrue(File(feeds, "volan.zip").isFile)
        assertTrue(File(feeds, "bkk.zip").isFile)
        val root = File(app.cacheDir, "offline-real-test").apply { deleteRecursively(); mkdirs() }
        val context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = root
        }
        val manual = app.getSharedPreferences("offline", 0).getBoolean("enabled", false)
        val store = OfflinePackStore(context)
        val metrics = Bundle()
        val first = args.getString("feedDate")?.let(LocalDate::parse) ?: LocalDate.now(TripPlanner.zone)
        val started = SystemClock.elapsedRealtime()
        try {
            val stage = store.staging()
            onOfflineDatabaseThread {
                GtfsImporter.create(stage).use { db ->
                    db.beginTransaction()
                    try {
                        var previous: OfflineImportStep? = null
                        val importer = GtfsImporter(db, first.minusDays(2), first.plusDays(30)) { step ->
                            if (previous != step.step) {
                                android.util.Log.i("OfflineRealPack", "${step.step}: ${SystemClock.elapsedRealtime() - started} ms")
                                previous = step.step
                            }
                        }
                        OfflineSource.defaults.forEach { importer.import(File(feeds, "${it.id}.zip"), it) }
                        importer.finish()
                        db.setTransactionSuccessful()
                    } finally { db.endTransaction() }
                }
            }
            metrics.putLong("import_ms", SystemClock.elapsedRealtime() - started)
            val counts = android.database.sqlite.SQLiteDatabase.openDatabase(stage.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
                fun count(table: String) = db.rawQuery("SELECT count(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }
                count("stops") to count("trips")
            }
            val digest = OfflinePackStore.digest(stage)
            val metadata = OfflinePackMetadata(UUID.randomUUID().toString(), Instant.now(), first, first.plusDays(29), stage.length(),
                OfflineSource.defaults.sumOf { File(feeds, "${it.id}.zip").length() }, counts.first, counts.second,
                OfflineSource.defaults.map { it.name }, digest)
            store.activate(stage, metadata, digest)
            metrics.putLong("ready_ms", SystemClock.elapsedRealtime() - started)
            metrics.putLong("database_bytes", metadata.installedBytes)
            assertTrue(metadata.installedFootprintBytes <= OFFLINE_MAX_BYTES)
            NetworkAccessPolicy.setOffline(app, true)
            val data = OfflineTransitData(store)
            var mark = SystemClock.elapsedRealtime()
            val catalogue = data.catalogue()
            metrics.putLong("catalogue_ms", SystemClock.elapsedRealtime() - mark)
            assertTrue(catalogue.first.size > 10_000)
            mark = SystemClock.elapsedRealtime()
            val index = TransitSearch.indexStops(emptyList(), catalogue.first)
            assertTrue(TransitSearch.stops(index, TransitSearch.query("Kelenföld"), null, emptySet()).isNotEmpty())
            assertTrue(TransitSearch.stops(index, TransitSearch.query("Biatorbágy"), null, emptySet()).isNotEmpty())
            metrics.putLong("search_ms", SystemClock.elapsedRealtime() - mark)
            val endpoints = store.read { db, _ ->
                db.rawQuery("""SELECT sa.source_id,sa.name,sa.lat,sa.lon,sb.source_id,sb.name,sb.lat,sb.lon
                    FROM stops sa JOIN stop_times a ON a.stop=sa.id JOIN trips t ON t.id=a.trip
                    JOIN service_days d ON d.service=t.service JOIN stop_times b ON b.trip=a.trip AND b.seq>a.seq
                    JOIN stops sb ON sb.id=b.stop WHERE sa.name LIKE '%Kelenföld vasútállomás%'
                    AND sb.name LIKE '%Biatorbágy%' AND d.day=? AND a.departure>=25200 AND a.pickup!=1 AND b.dropoff!=1 LIMIT 1""",
                    arrayOf(first.toEpochDay().toString())).use { c ->
                    assertTrue("Real feed must include a Kelenföld–Biatorbágy bus", c.moveToFirst())
                    PlannerEndpoint(stopAppId(c.getString(0)), c.getString(1), "", GeoPoint(c.getDouble(2), c.getDouble(3))) to
                        PlannerEndpoint(stopAppId(c.getString(4)), c.getString(5), "", GeoPoint(c.getDouble(6), c.getDouble(7)))
                }
            }
            val ready = first.atTime(7, 0).atZone(TripPlanner.zone).toInstant()
            mark = SystemClock.elapsedRealtime()
            val boards = data.boards(listOf(listOf(endpoints.first.id), listOf(endpoints.second.id), listOf(endpoints.first.id)), ready, 120)
            metrics.putLong("boards_ms", SystemClock.elapsedRealtime() - mark)
            assertTrue(boards.first().isNotEmpty())
            assertSame(boards[0], boards[2])
            mark = SystemClock.elapsedRealtime()
            val line = data.line(boards.first().first().routeId)
            assertNotNull(line)
            assertTrue(line!!.patterns.isNotEmpty())
            metrics.putLong("line_ms", SystemClock.elapsedRealtime() - mark)
            mark = SystemClock.elapsedRealtime()
            val plan = OfflineTripPlanner(store).plan(endpoints.first, endpoints.second, ready, ready)
            assertTrue(plan.itineraries.isNotEmpty())
            assertEquals(metadata.id, plan.offlinePack)
            assertTrue(plan.itineraries.flatMap { it.legs }.none { it.realtime })
            metrics.putLong("plan_ms", SystemClock.elapsedRealtime() - mark)
            assertTrue(OfflineBasemap.style(context, true).contains("pmtiles"))
            assertTrue(OfflineBasemap.style(context, false).contains("pmtiles"))
            metrics.putString("stream", "OFFLINE_REAL_PACK $metrics\n")
            instrumentation.sendStatus(0, metrics)
        } finally {
            store.delete()
            NetworkAccessPolicy.onPackAvailabilityChanged(OfflinePackStore(app).metadata() != null)
            NetworkAccessPolicy.setOffline(app, manual)
            root.deleteRecursively()
        }
    }
}
