package hu.app.bmenetrend.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/**
 * Regression tests for user complaint: missing vehicles after recent BKK integration.
 *
 * Root causes investigated:
 * 1. Partial/empty BKK coverage: Fresh EMMA vehicles inside nominal BKK area dropped when BKK
 *    omits them or returns an empty list for the covered viewport.
 * 2. Stale BKK records superseding fresh EMMA records: Stale BKK positions/timestamps overwrite
 *    fresh EMMA observations, and if BKK age > 180s, LiveVehicleFeed drops the vehicle entirely.
 * 3. Transient feed failures: Empty or partial BKK snapshots suppressing EMMA fallback.
 */
class BkkVehicleRegressionTest {

    private val now = Instant.parse("2026-11-11T12:00:00Z")
    private val tFresh = now.minusSeconds(5)       // 5 seconds ago (fresh live telemetry)
    private val tStale = now.minusSeconds(120)     // 2 minutes ago (stale observation)
    private val tExpired = now.minusSeconds(200)   // > 180 seconds ago (exceeds LiveVehicleFeed age limit)

    // Nominal BKK service area: Budapest & agglomeration
    private val bkkCoveredArea = MapBounds(19.00, 47.45, 19.10, 47.55)

    private fun testVehicle(
        id: String,
        lat: Double = 47.49,
        lon: Double = 19.05,
        providerId: String = "bkk",
        observedAt: Instant = tFresh,
        heading: Double? = 90.0,
        routeId: String? = "route1",
        routeNumber: String? = "7",
        tripId: String? = "trip1",
        destination: String? = "Albertfalva",
        nextStopId: String? = "stop1",
        nextStopName: String? = "Stop 1",
        trainNumber: String? = null,
        positionSource: DataSource? = null,
        detailsSource: DataSource? = null,
    ) = Vehicle(
        id = id,
        position = GeoPoint(lat, lon),
        observedAt = observedAt,
        heading = heading,
        providerId = providerId,
        routeId = routeId,
        routeNumber = routeNumber,
        tripId = tripId,
        destination = destination,
        nextStopId = nextStopId,
        nextStopName = nextStopName,
        trainNumber = trainNumber,
        positionSource = positionSource,
        detailsSource = detailsSource,
    )

    private fun feedInputs(
        local: List<Vehicle> = emptyList(),
        remote: List<Vehicle> = emptyList(),
        bkk: BkkWorkerRepository.VehicleSnapshot? = null,
        stops: List<TransitStop> = emptyList(),
        nationwide: Boolean = true,
    ) = LiveVehicleFeed.Inputs(
        local = local,
        emma = EmmaRepository.Result(remote, 0, now),
        bkk = bkk,
        networkStops = emptyList(),
        emmaStops = stops,
        nationwide = nationwide,
        bundledRoutes = emptyList(),
    )

    // =========================================================================
    // REGRESSION 1: Partial / Empty BKK Coverage Drops Fresh EMMA Vehicles
    // =========================================================================

    /**
     * REGRESSION TEST (Expected RED on unpatched production code):
     * When BKK returns an empty vehicle list for a covered area (e.g. transient failure,
     * network glitch, or no vehicles reported by BKK), fresh EMMA vehicles inside that
     * area must NOT be dropped as "stale relays".
     */
    @Test
    fun regression_emptyBkkCoverage_mustNotDropFreshEmmaVehiclesInsideCoveredArea() {
        val freshEmmaBus = testVehicle(
            id = "emma:BKK:bus7",
            lat = 47.49,
            lon = 19.05,
            providerId = "bkk",
            observedAt = tFresh,
        )

        // BKK worker returns EMPTY list of vehicles for coveredArea
        val workerVehicles = emptyList<WorkerVehicle>()

        val merged = LiveVehicleMerge.merge(workerVehicles, bkkCoveredArea, listOf(freshEmmaBus))

        assertTrue(
            "Fresh EMMA vehicle inside nominal BKK area must NOT be dropped when BKK coverage is empty! " +
                "Actual merged IDs: ${merged.map { it.id }}",
            merged.any { it.id == "emma:BKK:bus7" },
        )
    }

    /**
     * REGRESSION TEST (Expected RED on unpatched production code):
     * When BKK returns partial coverage (e.g. reports tram4, but omits bus7),
     * fresh EMMA telemetry for bus7 inside the covered area must NOT be dropped.
     */
    @Test
    fun regression_partialBkkCoverage_mustPreserveFreshEmmaVehiclesMissingFromBkk() {
        // BKK worker reports only tram 4
        val bkkTram = testVehicle(
            id = "emma:BKK:tram4",
            lat = 47.50,
            lon = 19.06,
            providerId = "bkk",
            observedAt = tFresh,
        )
        val workerVehicles = listOf(WorkerVehicle(bkkTram, numbers = setOf("tram4"), fallback = false))

        // EMMA reports both tram 4 and bus 7 (both fresh, both inside coveredArea)
        val emmaTram = testVehicle(
            id = "emma:BKK:tram4",
            lat = 47.50,
            lon = 19.06,
            providerId = "bkk",
            observedAt = tFresh,
        )
        val emmaBus = testVehicle(
            id = "emma:BKK:bus7",
            lat = 47.49,
            lon = 19.05,
            providerId = "bkk",
            observedAt = tFresh,
        )

        val merged = LiveVehicleMerge.merge(workerVehicles, bkkCoveredArea, listOf(emmaTram, emmaBus))

        val mergedIds = merged.map { it.id }.toSet()
        assertTrue("BKK tram4 must be present", mergedIds.contains("emma:BKK:tram4"))
        assertTrue(
            "Fresh EMMA bus7 missing from partial BKK response must NOT be dropped! " +
                "Actual merged IDs: $mergedIds",
            mergedIds.contains("emma:BKK:bus7"),
        )
    }

    // =========================================================================
    // REGRESSION 2: Stale BKK Records Superseding Fresh EMMA Records
    // =========================================================================

    /**
     * REGRESSION TEST (Expected RED on unpatched production code):
     * When BKK returns a stale observation (e.g. 120s old) and EMMA has a fresh observation
     * (e.g. 5s old) for the same BKK vehicle, the fresh EMMA observation (position & timestamp)
     * must NOT be overwritten by the stale BKK record.
     */
    @Test
    fun regression_staleBkkRecord_mustNotOverrideFreshEmmaObservation() {
        // Stale BKK observation from 2 minutes ago at an old location
        val staleBkkVehicle = testVehicle(
            id = "emma:BKK:1234",
            lat = 47.4800,
            lon = 19.0400,
            providerId = "bkk",
            observedAt = tStale,
            heading = 180.0,
        )
        val workerCandidate = WorkerVehicle(staleBkkVehicle, numbers = setOf("1234"), fallback = false)

        // Fresh EMMA observation from 5 seconds ago at the updated location
        val freshEmmaVehicle = testVehicle(
            id = "emma:BKK:1234",
            lat = 47.4950,
            lon = 19.0550,
            providerId = "bkk",
            observedAt = tFresh,
            heading = 90.0,
        )

        val merged = LiveVehicleMerge.merge(listOf(workerCandidate), bkkCoveredArea, listOf(freshEmmaVehicle))
        assertEquals(1, merged.size)
        val result = merged[0]

        assertEquals("emma:BKK:1234", result.id)
        // Fresh EMMA must win when BKK is stale!
        assertEquals("Fresh observation timestamp must be preserved", tFresh, result.observedAt)
        assertEquals("Fresh position latitude must be preserved", 47.4950, result.position.lat, 1e-4)
        assertEquals("Fresh position longitude must be preserved", 19.0550, result.position.lon, 1e-4)
    }

    /**
     * REGRESSION TEST (Expected RED on unpatched production code):
     * For rail (MÁV / HÉV), when BKK returns a stale position (120s old) but EMMA has fresh
     * live telemetry (5s old), BKK must not overwrite EMMA's fresh position with the stale one.
     */
    @Test
    fun regression_staleBkkRailPosition_mustNotOverrideFreshEmmaRailPosition() {
        val emmaTrain = testVehicle(
            id = "emma:1:415020",
            lat = 47.4700,
            lon = 19.0300,
            providerId = "mav",
            observedAt = tFresh,
            heading = 45.0,
            routeId = "mav:route:Z30",
            routeNumber = "Z30",
            tripId = "emma:trip:101",
            trainNumber = "2814",
        )

        // Stale BKK position from 2 minutes ago
        val staleBkkTrain = testVehicle(
            id = "emma:1:415020",
            lat = 47.4550,
            lon = 19.0150,
            providerId = "mav",
            observedAt = tStale,
            heading = 30.0,
        )
        val workerCandidate = WorkerVehicle(staleBkkTrain, numbers = setOf("415020"), fallback = false)

        val merged = LiveVehicleMerge.merge(listOf(workerCandidate), bkkCoveredArea, listOf(emmaTrain))
        assertEquals(1, merged.size)
        val result = merged[0]

        assertEquals("Fresh train observation timestamp must be kept", tFresh, result.observedAt)
        assertEquals("Fresh train latitude must be kept", 47.4700, result.position.lat, 1e-4)
        assertEquals("Fresh train longitude must be kept", 19.0300, result.position.lon, 1e-4)
    }

    // =========================================================================
    // REGRESSION 3: Feed Pipeline Vehicle Loss (LiveVehicleFeed)
    // =========================================================================

    /**
     * REGRESSION TEST (Expected RED on unpatched production code):
     * When BKK returns an expired or very stale vehicle (> 180s old), and EMMA has fresh
     * data for it, LiveVehicleFeed's age filter must NOT drop the vehicle.
     * Currently: LiveVehicleMerge picks BKK's stale record (observedAt = now - 200s),
     * and LiveVehicleFeed filters out vehicles older than 180s, causing the vehicle to VANISH.
     */
    @Test
    fun regression_staleBkkRecordExceedingMaxAge_causesCompleteVehicleLossInFeed() {
        val feed = LiveVehicleFeed()

        // BKK snapshot contains a vehicle with an observation > 180s old (e.g. 200s old)
        val expiredBkkVehicle = testVehicle(
            id = "emma:BKK:bus7",
            lat = 47.480,
            lon = 19.040,
            providerId = "bkk",
            observedAt = tExpired,
        )
        val snapshot = BkkWorkerRepository.VehicleSnapshot(
            vehicles = listOf(WorkerVehicle(expiredBkkVehicle, setOf("bus7"), false)),
            covered = bkkCoveredArea,
            fetchedAt = now, // Snapshot itself was fetched freshly
        )

        // EMMA has the fresh observation (5s old)
        val freshEmmaVehicle = testVehicle(
            id = "emma:BKK:bus7",
            lat = 47.495,
            lon = 19.055,
            providerId = "bkk",
            observedAt = tFresh,
        )

        val inputs = feedInputs(
            remote = listOf(freshEmmaVehicle),
            bkk = snapshot,
        )

        val result = feed.update(inputs, now, emptyList())

        assertFalse(
            "Vehicle must NOT vanish from map due to stale BKK record overriding fresh EMMA! " +
                "Actual vehicles count: ${result.vehicles.size}",
            result.vehicles.isEmpty(),
        )
        assertEquals("emma:BKK:bus7", result.vehicles.single().id)
        assertEquals(tFresh, result.vehicles.single().observedAt)
    }

    /**
     * REGRESSION TEST (Expected RED on unpatched production code):
     * Transient feed failure where BKK snapshot has empty vehicles list:
     * LiveVehicleFeed must deliver the fresh EMMA vehicles inside the viewport.
     */
    @Test
    fun regression_emptyBkkSnapshot_causesVehicleLossInFeed() {
        val feed = LiveVehicleFeed()

        // BKK snapshot is empty (e.g. transient worker failure returned no vehicles)
        val emptySnapshot = BkkWorkerRepository.VehicleSnapshot(
            vehicles = emptyList(),
            covered = bkkCoveredArea,
            fetchedAt = now,
        )

        val freshEmmaVehicle = testVehicle(
            id = "emma:BKK:bus7",
            lat = 47.49,
            lon = 19.05,
            providerId = "bkk",
            observedAt = tFresh,
        )

        val inputs = feedInputs(
            remote = listOf(freshEmmaVehicle),
            bkk = emptySnapshot,
        )

        val result = feed.update(inputs, now, emptyList())

        assertEquals(
            "Fresh EMMA vehicles must be delivered when BKK snapshot is empty",
            1,
            result.vehicles.size,
        )
        assertEquals("emma:BKK:bus7", result.vehicles.single().id)
    }

    // =========================================================================
    // GREEN BASELINE INVARIANTS: Verify Expected Working Behavior
    // =========================================================================

    /**
     * GREEN TEST:
     * When BKK is actually fresh (newer than or equal to EMMA), BKK wins position and metadata.
     */
    @Test
    fun invariant_freshBkkRecord_winsOverOlderEmmaRelay() {
        val bkkVeh = testVehicle(
            id = "emma:BKK:1234",
            lat = 47.495,
            lon = 19.055,
            providerId = "bkk",
            observedAt = tFresh,
            heading = 180.0,
        )
        val workerCand = WorkerVehicle(bkkVeh, numbers = setOf("1234"), fallback = false)

        val olderEmmaVeh = testVehicle(
            id = "emma:BKK:1234",
            lat = 47.480,
            lon = 19.040,
            providerId = "bkk",
            observedAt = tStale,
            heading = 90.0,
        )

        val merged = LiveVehicleMerge.merge(listOf(workerCand), bkkCoveredArea, listOf(olderEmmaVeh))
        assertEquals(1, merged.size)
        val result = merged[0]
        assertEquals("emma:BKK:1234", result.id)
        assertEquals(47.495, result.position.lat, 1e-4)
        assertEquals(19.055, result.position.lon, 1e-4)
        assertEquals(tFresh, result.observedAt)
        assertEquals(180.0, result.heading!!, 1e-4)
    }

    /**
     * GREEN TEST:
     * EMMA BKK vehicles outside the covered area are always preserved.
     */
    @Test
    fun invariant_emmaVehiclesOutsideCoveredArea_arePreserved() {
        val outsideVehicle = testVehicle(
            id = "emma:BKK:outside",
            lat = 47.80, // Far outside bkkCoveredArea
            lon = 19.30,
            providerId = "bkk",
            observedAt = tFresh,
        )

        val merged = LiveVehicleMerge.merge(emptyList(), bkkCoveredArea, listOf(outsideVehicle))
        assertEquals(1, merged.size)
        assertEquals("emma:BKK:outside", merged[0].id)
    }

    /**
     * GREEN TEST:
     * Volán and MÁV vehicles are preserved even within BKK covered area.
     */
    @Test
    fun invariant_volanAndRailInsideCoveredArea_arePreserved() {
        val volanVeh = testVehicle(
            id = "emma:hkir:hkir_ABC123",
            lat = 47.49,
            lon = 19.05,
            providerId = "volan",
            observedAt = tFresh,
        )
        val railVeh = testVehicle(
            id = "emma:1:415020",
            lat = 47.49,
            lon = 19.05,
            providerId = "mav",
            observedAt = tFresh,
        )

        val merged = LiveVehicleMerge.merge(emptyList(), bkkCoveredArea, listOf(volanVeh, railVeh))
        assertEquals(2, merged.size)
        val ids = merged.map { it.id }.toSet()
        assertTrue(ids.contains("emma:hkir:hkir_ABC123"))
        assertTrue(ids.contains("emma:1:415020"))
    }
}
