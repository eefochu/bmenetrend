package hu.app.bmenetrend.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneId

class StationTimetableRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var repository: StationTimetableRepository

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("files")
        repository = StationTimetableRepository(baseDirectory = rootDir)
    }

    @Test
    fun `file size formatting handles bytes, kilobytes and megabytes`() {
        assertEquals("500 B", StationTimetableRepository.formatFileSize(500))
        assertEquals("1.5 KB", StationTimetableRepository.formatFileSize(1536))
        assertEquals("2.0 MB", StationTimetableRepository.formatFileSize(2097152))
    }

    @Test
    fun `save and load offline timetable with gzip compression and string pool`() = runBlocking {
        val now = Instant.parse("2026-10-26T10:00:00Z")
        val departures = listOf(
            Departure(
                routeId = "100E",
                routeNumber = "100E",
                destination = "Deák Ferenc tér M",
                stopId = "platform-1",
                scheduledAt = now.plusSeconds(300),
                expectedAt = now.plusSeconds(300),
                kind = ArrivalKind.SCHEDULED,
                directionId = "trip-1",
                platformCode = "1",
            ),
            Departure(
                routeId = "100E",
                routeNumber = "100E",
                destination = "Deák Ferenc tér M",
                stopId = "platform-1",
                scheduledAt = now.plusSeconds(900),
                expectedAt = now.plusSeconds(900),
                kind = ArrivalKind.SCHEDULED,
                directionId = "trip-2",
                platformCode = "1",
            ),
            Departure(
                routeId = "200E",
                routeNumber = "200E",
                destination = "Kőbánya-Kispest M",
                stopId = "platform-2",
                scheduledAt = now.plusSeconds(600),
                expectedAt = now.plusSeconds(600),
                kind = ArrivalKind.SCHEDULED,
                directionId = "trip-3",
                platformCode = "2",
            )
        )

        val routes = listOf(
            TransitRoute(id = "100E", name = "100E Repülőtéri Gyors", number = "100E", directions = emptyList(), color = "#0077CC", providerId = "bkk", transportMode = "BUS"),
            TransitRoute(id = "200E", name = "200E Liszt Ferenc", number = "200E", directions = emptyList(), color = "#0077CC", providerId = "bkk", transportMode = "BUS")
        )

        repository.saveStationTimetableDirect(
            stationId = "station-repter",
            stationName = "Liszt Ferenc Airport",
            platformIds = listOf("platform-1", "platform-2"),
            routes = routes,
            departures = departures,
            now = now,
        )

        // Verify index is updated and stop-to-station map is registered
        assertTrue(repository.isStationDownloaded("platform-1"))
        assertTrue(repository.isStationDownloaded("platform-2"))
        assertTrue(repository.isStationDownloaded("station-repter"))
        assertFalse(repository.isStationDownloaded("unknown-platform"))

        val meta = repository.getMetadata("station-repter")
        assertNotNull(meta)
        assertEquals("station-repter", meta!!.stationId)
        assertEquals("Liszt Ferenc Airport", meta.stationName)
        assertEquals(3, meta.departureCount)
        assertTrue("Compressed size should be lightweight (< 2 KB), was ${meta.fileSizeBytes}", meta.fileSizeBytes in 1..2048)

        // Verify file is genuinely GZIP compressed (magic numbers 0x1f, 0x8b)
        val file = File(rootDir, "station-repter.tt.gz")
        assertTrue(file.exists())
        val magic = ByteArray(2)
        FileInputStream(file).use { it.read(magic) }
        assertEquals(0x1f.toByte(), magic[0])
        assertEquals(0x8b.toByte(), magic[1])

        // Verify loaded data
        val loaded = repository.loadOfflineData("station-repter")
        assertNotNull(loaded)
        assertEquals("station-repter", loaded!!.stationId)
        assertEquals("Liszt Ferenc Airport", loaded.stationName)
        assertEquals(3, loaded.departures.size)
        assertEquals("Deák Ferenc tér M", loaded.departures[0].destination)
        assertEquals("Deák Ferenc tér M", loaded.departures[1].destination)
        assertEquals("Kőbánya-Kispest M", loaded.departures[2].destination)
        assertEquals(2, loaded.routes.size)
    }

    @Test
    fun `getOfflineBoard returns departures in window and projects to future days`() = runBlocking {
        val baseTime = Instant.parse("2026-10-26T08:00:00Z")
        val departures = listOf(
            Departure(routeId = "1", routeNumber = "1", destination = "A", stopId = "p1", scheduledAt = baseTime.plusSeconds(600), expectedAt = baseTime.plusSeconds(600), kind = ArrivalKind.SCHEDULED, directionId = "d1"),
            Departure(routeId = "1", routeNumber = "1", destination = "A", stopId = "p1", scheduledAt = baseTime.plusSeconds(1800), expectedAt = baseTime.plusSeconds(1800), kind = ArrivalKind.SCHEDULED, directionId = "d2"),
            Departure(routeId = "1", routeNumber = "1", destination = "A", stopId = "p1", scheduledAt = baseTime.plusSeconds(3600), expectedAt = baseTime.plusSeconds(3600), kind = ArrivalKind.SCHEDULED, directionId = "d3"),
        )
        val routes = listOf(TransitRoute(id = "1", name = "1", number = "1", directions = emptyList(), providerId = "volan"))

        repository.saveStationTimetableDirect(
            stationId = "st-1",
            stationName = "Station One",
            platformIds = listOf("p1"),
            routes = routes,
            departures = departures,
            now = baseTime,
        )

        // 1. Direct window check within downloaded timeframe
        val boardNow = repository.getOfflineBoard(
            selectedId = "p1",
            platformIds = listOf("p1"),
            now = baseTime,
            windowMinutes = 35,
        )
        assertNotNull(boardNow)
        assertEquals(2, boardNow!!.departures.size) // departures at +600s (10m) and +1800s (30m)
        assertEquals(baseTime.plusSeconds(600), boardNow.departures[0].expectedAt)
        assertEquals(baseTime.plusSeconds(1800), boardNow.departures[1].expectedAt)

        // 2. Future day projection check (e.g. 5 days later offline)
        val futureTime = baseTime.plusSeconds(86400 * 5)
        val boardFuture = repository.getOfflineBoard(
            selectedId = "p1",
            platformIds = listOf("p1"),
            now = futureTime,
            windowMinutes = 35,
        )
        assertNotNull(boardFuture)
        assertEquals(2, boardFuture!!.departures.size)
        val budapestZone = ZoneId.of("Europe/Budapest")
        val expectedLocalTime = departures[0].expectedAt.atZone(budapestZone).toLocalTime()
        val actualLocalTime = boardFuture.departures[0].expectedAt.atZone(budapestZone).toLocalTime()
        assertEquals(expectedLocalTime.hour, actualLocalTime.hour)
        assertEquals(expectedLocalTime.minute, actualLocalTime.minute)
    }

    @Test
    fun `delete and clearAll removes files and metadata`() = runBlocking {
        val now = Instant.now()
        repository.saveStationTimetableDirect("s1", "Station 1", listOf("p1"), emptyList(), emptyList(), now)
        repository.saveStationTimetableDirect("s2", "Station 2", listOf("p2"), emptyList(), emptyList(), now)

        assertTrue(repository.isStationDownloaded("p1"))
        assertTrue(repository.isStationDownloaded("p2"))
        assertEquals(2, repository.getAllDownloadedStationMetadata().size)

        // Delete single station
        repository.deleteStationTimetable("p1")
        assertFalse(repository.isStationDownloaded("p1"))
        assertTrue(repository.isStationDownloaded("p2"))
        assertEquals(1, repository.getAllDownloadedStationMetadata().size)

        // Clear all
        repository.clearAll()
        assertFalse(repository.isStationDownloaded("p2"))
        assertTrue(repository.getAllDownloadedStationMetadata().isEmpty())
    }

    @Test
    fun `restart constructor does not read index from disk until initialize or access`() = runBlocking {
        val now = Instant.now()
        repository.saveStationTimetableDirect("s1", "Station 1", listOf("p1"), emptyList(), emptyList(), now)

        // Create a new instance on the same directory
        val restartedRepo = StationTimetableRepository(baseDirectory = rootDir)

        // Check internal 'initialized' flag via reflection before calling any suspend method
        val initField = StationTimetableRepository::class.java.getDeclaredField("initialized")
        initField.isAccessible = true
        val initializedBefore = initField.getBoolean(restartedRepo)
        assertFalse("Constructor must be cheap and NOT read index immediately", initializedBefore)

        // Synchronous access before initialize() reflects empty in-memory state
        assertEquals(0, restartedRepo.totalStorageBytes())
        assertFalse(restartedRepo.isStationDownloaded("p1"))

        // Explicit initialize() loads the index off the main thread
        restartedRepo.initialize()
        val initializedAfter = initField.getBoolean(restartedRepo)
        assertTrue("Repository must be marked initialized after calling initialize()", initializedAfter)
        assertTrue("Stored metadata must be restored from disk", restartedRepo.isStationDownloaded("p1"))
        assertEquals(1, restartedRepo.allMetadata().size)
        assertTrue(restartedRepo.totalStorageBytes() > 0)
    }

    @Test
    fun `cold disk read loads index and compressed timetable asynchronously without deadlock`() = runBlocking {
        val now = Instant.parse("2026-10-26T12:00:00Z")
        val route = TransitRoute(id = "r1", name = "Route 1", number = "1", directions = emptyList(), providerId = "bkk")
        val dep = Departure(routeId = "r1", routeNumber = "1", destination = "Dest", stopId = "p1", scheduledAt = now.plusSeconds(300), expectedAt = now.plusSeconds(300), kind = ArrivalKind.SCHEDULED, directionId = "d1")
        repository.saveStationTimetableDirect("cold-station", "Cold Station", listOf("p1"), listOf(route), listOf(dep), now)

        // Fresh instance simulates cold start / process restart
        val coldRepo = StationTimetableRepository(baseDirectory = rootDir)

        // Calling loadOfflineData without prior initialize() must initialize and load without deadlock
        val timetable = coldRepo.loadOfflineData("cold-station")
        assertNotNull("Cold read must decompress and load timetable", timetable)
        assertEquals("cold-station", timetable!!.stationId)
        assertEquals(1, timetable.departures.size)

        // Calling getOfflineBoard on another fresh instance must also initialize and return board
        val boardRepo = StationTimetableRepository(baseDirectory = rootDir)
        val board = boardRepo.getOfflineBoard("p1", listOf("p1"), now, 30)
        assertNotNull(board)
        assertEquals("Cold Station", board!!.stationName)
        assertEquals(1, board.departures.size)
    }

    @Test
    fun `concurrent save read and delete operations maintain cache safety`() = runBlocking(Dispatchers.IO) {
        val now = Instant.parse("2026-10-26T12:00:00Z")
        // Initialize base repository
        repository.initialize()

        val workers = (1..12).map { i ->
            async {
                val stationId = "station-$i"
                val platformId = "p-$i"
                val route = TransitRoute(id = "r$i", name = "Route $i", number = "$i", directions = emptyList(), providerId = "bkk")
                val dep = Departure(routeId = "r$i", routeNumber = "$i", destination = "D$i", stopId = platformId, scheduledAt = now.plusSeconds(i * 60L), expectedAt = now.plusSeconds(i * 60L), kind = ArrivalKind.SCHEDULED, directionId = "d$i")

                // Interleaved saves, reads, queries, and deletions
                repository.saveStationTimetableDirect(stationId, "Station $i", listOf(platformId), listOf(route), listOf(dep), now)
                val isSaved = repository.isStationDownloaded(platformId)
                val board = repository.getOfflineBoard(platformId, listOf(platformId), now, 60)
                val loaded = repository.loadOfflineData(stationId)

                // Every saved station should be queryable
                assertTrue(isSaved)
                assertNotNull(board)
                assertNotNull(loaded)

                if (i % 2 == 0) {
                    repository.deleteStationTimetable(platformId)
                    assertFalse(repository.isStationDownloaded(platformId))
                }
            }
        }
        workers.awaitAll()

        // 6 stations should remain (odd numbers)
        val remaining = repository.allMetadata().values
        assertEquals(6, remaining.size)
        for (meta in remaining) {
            val num = meta.stationId.removePrefix("station-").toInt()
            assertTrue(num % 2 != 0)
        }
    }
}
