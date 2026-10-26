package hu.app.bmenetrend.data

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
}
