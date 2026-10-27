package hu.app.bmenetrend.offline

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import hu.app.bmenetrend.TransitViewModel
import kotlinx.coroutines.*
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class OfflinePackConstructorContentionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: OfflinePackStore
    private val releaseLatch = CountDownLatch(1)

    @Before
    fun setup() {
        try {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        } catch (_: Exception) { }
        store = OfflinePackStore(context)
        store.delete()

        // Create a valid offline pack fixture
        val root = File(context.noBackupFilesDir, "offline-pack").apply { mkdirs() }
        val dbFile = File(root, "contention-test.db")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE meta (key TEXT, value TEXT)")
        }
        val meta = OfflinePackMetadata(
            id = "contention-test",
            builtAt = Instant.now(),
            validFrom = LocalDate.of(2026, 10, 27),
            validUntil = LocalDate.of(2026, 11, 27),
            installedBytes = dbFile.length(),
            downloadBytes = 1000,
            stops = 10,
            trips = 10,
            sources = listOf("Fixture"),
            sha256 = OfflinePackStore.digest(dbFile)
        )
        File(root, "active.json").writeText(meta.json().toString())
    }

    @After
    fun cleanup() {
        releaseLatch.countDown()
        store.delete()
        scope.cancel()
    }

    /**
     * R2 Invariant: Constructor MUST finish BEFORE background reader releases its lock.
     * Furthermore, state reflects explicit loading (INITIALIZING / !loaded) until the
     * background metadata read can safely acquire the lock and populate metadata.
     */
    @Test(timeout = 10_000)
    fun constructorFinishesBeforeReaderRelease_andReflectsLoadingUntilRelease() {
        val holdingLatch = CountDownLatch(1)
        val readStarted = AtomicBoolean(false)

        // 1. Background thread acquires store.read monitor lock
        val bgReader = thread(name = "BackgroundReaderThread") {
            try {
                store.read { _, _ ->
                    readStarted.set(true)
                    holdingLatch.countDown()
                    // Hold lock until releaser signals
                    releaseLatch.await(5, TimeUnit.SECONDS)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        try {
            assertTrue("Background thread must acquire store lock", holdingLatch.await(3, TimeUnit.SECONDS))
            assertTrue(readStarted.get())

            // 2. Instantiate OfflinePackRepository on calling thread (simulates Main Thread)
            val constructorStarted = CountDownLatch(1)
            val constructorFinished = CountDownLatch(1)
            var repository: OfflinePackRepository? = null

            val callerThread = thread(name = "CallerThread") {
                try {
                    constructorStarted.countDown()
                    repository = OfflinePackRepository(context, scope)
                    constructorFinished.countDown()
                } catch (t: Throwable) {
                    println("CALLER_THREW: ${t.javaClass.name}: ${t.message}")
                    t.printStackTrace()
                }
            }

            assertTrue("Constructor thread must start", constructorStarted.await(1, TimeUnit.SECONDS))

            // R2 ASSERTION: Constructor must return promptly (<500ms) BEFORE releaseLatch is triggered!
            val finishedBeforeRelease = constructorFinished.await(500, TimeUnit.MILLISECONDS)
            assertTrue("Constructor MUST finish promptly BEFORE reader releases store lock (R2 requirement)", finishedBeforeRelease)
            assertNotNull("Repository must be instantiated without blocking", repository)

            // State while reader holds the lock must be INITIALIZING / unready, not blocking
            val stateBeforeRelease = repository!!.state.value
            assertEquals("State must be INITIALIZING while reader holds lock", OfflinePackPhase.INITIALIZING, stateBeforeRelease.phase)
            assertFalse("State must not be loaded yet", stateBeforeRelease.loaded)
            assertNull("Metadata must be null until reader completes", stateBeforeRelease.metadata)

            // 3. Now release background reader lock
            releaseLatch.countDown()

            // Wait for background coroutine in repository to fetch metadata and transition to READY
            var stateAfterRelease = repository!!.state.value
            val waitDeadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < waitDeadline && stateAfterRelease.phase == OfflinePackPhase.INITIALIZING) {
                Thread.sleep(25)
                stateAfterRelease = repository!!.state.value
            }

            assertEquals("State must become READY once reader releases lock and metadata loads", OfflinePackPhase.READY, stateAfterRelease.phase)
            assertTrue("State must be loaded", stateAfterRelease.loaded)
            assertNotNull("Metadata must be populated", stateAfterRelease.metadata)
            assertEquals("contention-test", stateAfterRelease.metadata?.id)

            bgReader.join(1000)
            callerThread.join(1000)
        } finally {
            releaseLatch.countDown()
            bgReader.join(500)
        }
    }

    /**
     * R2 Invariant: Calling delete() during an in-flight metadata refresh must NEVER
     * allow the delayed metadata result to resurrect the deleted pack.
     */
    @Test(timeout = 10_000)
    fun deleteDuringMetadataRefresh_doesNotResurrectDeletedPack() = runBlocking {
        val repo = OfflinePackRepository(context, scope)

        // Wait until initial ready state
        var current = repo.state.value
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && !current.loaded) {
            delay(25)
            current = repo.state.value
        }
        assertEquals(OfflinePackPhase.READY, current.phase)

        // Perform delete
        repo.delete()

        // State must immediately be MISSING and metadata null
        val stateAfterDelete = repo.state.value
        assertEquals("Phase must be MISSING after delete", OfflinePackPhase.MISSING, stateAfterDelete.phase)
        assertNull("Metadata must be null after delete", stateAfterDelete.metadata)

        // Even after extra ticks/delay, metadata must NOT resurrect
        delay(200)
        val settledState = repo.state.value
        assertEquals("Phase must remain MISSING after delay", OfflinePackPhase.MISSING, settledState.phase)
        assertNull("Metadata must remain null", settledState.metadata)
    }

    /**
     * R2 Invariant: Rapid offline toggles must not encounter lock deadlocks,
     * UI thread freezes, or state inconsistencies.
     */
    @Test(timeout = 10_000)
    fun rapidOfflineToggle_isThreadSafeAndNonBlocking() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val viewModel = TransitViewModel(app)

        // Rapidly toggle offline mode back and forth
        repeat(20) { i ->
            viewModel.setOfflineMode(i % 2 == 0)
        }

        // Cleanly ensure final disabled state
        viewModel.setOfflineMode(false)
        delay(100)
        assertFalse("Offline mode should settle cleanly", NetworkAccessPolicy.offline.value)
    }
}
