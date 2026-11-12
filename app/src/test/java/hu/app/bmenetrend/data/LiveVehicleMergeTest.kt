package hu.app.bmenetrend.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LiveVehicleMergeTest {

    private val t0 = Instant.parse("2026-11-10T12:00:00Z")
    private val tEarlier = t0.minusSeconds(30)
    private val tLater = t0.plusSeconds(10)

    private val coveredArea = MapBounds(19.00, 47.45, 19.10, 47.55)

    private fun vehicle(
        id: String,
        lat: Double,
        lon: Double,
        providerId: String = "bkk",
        observedAt: Instant = t0,
        heading: Double? = 90.0,
        routeId: String? = "route1",
        routeNumber: String? = "7",
        tripId: String? = "trip1",
        destination: String? = "Albertfalva",
        nextStopId: String? = "stop1",
        nextStopName: String? = "Stop 1",
        trainNumber: String? = null,
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
        trainNumber = trainNumber
    )

    @Test
    fun primaryBkkWinsPositionAndTimestampEvenWhenEmmaIsNewer() {
        // BKK observation: 47.49, 19.05 at tEarlier
        val bkkVeh = vehicle("emma:BKK:1234", 47.49, 19.05, providerId = "bkk", observedAt = tEarlier, heading = 180.0)
        val workerCand = WorkerVehicle(bkkVeh, numbers = setOf("1234"), fallback = false)

        // EMMA relay observation: 47.50, 19.06 at tLater (newer timestamp)
        val emmaVeh = vehicle("emma:BKK:1234", 47.50, 19.06, providerId = "bkk", observedAt = tLater, heading = 90.0)

        val merged = LiveVehicleMerge.merge(listOf(workerCand), coveredArea, listOf(emmaVeh))
        assertEquals(1, merged.size)
        val result = merged[0]
        assertEquals("emma:BKK:1234", result.id)
        // Primary BKK MUST win position and observedAt even though EMMA is newer
        assertEquals(47.49, result.position.lat, 1e-4)
        assertEquals(19.05, result.position.lon, 1e-4)
        assertEquals(tEarlier, result.observedAt)
        assertEquals(180.0, result.heading!!, 1e-4)
    }

    @Test
    fun emmaBkkVehiclesInsideCoveredAreaPreservedWhenAbsentFromBkk() {
        // EMMA has a BKK vehicle inside the covered area
        val emmaInside = vehicle("emma:BKK:9999", 47.48, 19.04, providerId = "bkk", observedAt = t0)
        // EMMA has another BKK vehicle outside the covered area
        val emmaOutside = vehicle("emma:BKK:8888", 47.80, 19.30, providerId = "bkk", observedAt = t0)
        // EMMA has a Volán vehicle inside covered area
        val emmaVolan = vehicle("emma:volan:7777", 47.48, 19.04, providerId = "volan", observedAt = t0)

        // Worker returns NO BKK vehicles
        val merged = LiveVehicleMerge.merge(emptyList(), coveredArea, listOf(emmaInside, emmaOutside, emmaVolan))

        // emmaInside must be kept even though covered area includes it and BKK did not report it
        assertTrue(merged.any { it.id == "emma:BKK:9999" })
        // emmaOutside must be kept because it is outside covered area
        assertTrue(merged.any { it.id == "emma:BKK:8888" })
        // Volán vehicle must be kept regardless of location
        assertTrue(merged.any { it.id == "emma:volan:7777" })
    }

    @Test
    fun bkkTrainsAndHevMatchToEmmaByUnderlyingVehicleNumber() {
        // Direct EMMA train: rich metadata (train number, trip, route, destination, next stop)
        val emmaTrain = vehicle(
            id = "emma:1:415020",
            lat = 47.460,
            lon = 19.020,
            providerId = "mav",
            observedAt = tEarlier,
            heading = 45.0,
            routeId = "mav:route:Z30",
            routeNumber = "Z30",
            tripId = "emma:trip:101",
            destination = "Székesfehérvár",
            nextStopId = "emma:stop:kelenfold",
            nextStopName = "Kelenföld",
            trainNumber = "2814"
        )

        // BKK reports the coupled train (415-020 + 415-021) with fresh live position
        val bkkTrain = vehicle(
            id = "emma:1:415020",
            lat = 47.465,
            lon = 19.025,
            providerId = "mav",
            observedAt = t0,
            heading = 60.0,
            routeNumber = "Z30",
            destination = "Székesfehérvár"
        )
        // Worker candidate numbers includes both coupled numbers
        val workerCand = WorkerVehicle(bkkTrain, numbers = setOf("415020", "415021"), fallback = false)

        val merged = LiveVehicleMerge.merge(listOf(workerCand), coveredArea, listOf(emmaTrain))
        assertEquals(1, merged.size)
        val result = merged[0]

        // Keeps EMMA identity and metadata
        assertEquals("emma:1:415020", result.id)
        assertEquals("2814", result.trainNumber)
        assertEquals("emma:trip:101", result.tripId)
        assertEquals("mav:route:Z30", result.routeId)
        assertEquals("Kelenföld", result.nextStopName)

        // Takes BKK's position, heading, and observedAt
        assertEquals(47.465, result.position.lat, 1e-4)
        assertEquals(19.025, result.position.lon, 1e-4)
        assertEquals(60.0, result.heading!!, 1e-4)
        assertEquals(t0, result.observedAt)
    }

    @Test
    fun bkkTrainMatchesEmmaViaSecondCoupledNumber() {
        // EMMA knows the unit by second unit id 415021
        val emmaTrain = vehicle(
            id = "emma:1:415021",
            lat = 47.460,
            lon = 19.020,
            providerId = "mav",
            observedAt = tEarlier,
            trainNumber = "2814"
        )

        // BKK candidate primary id is 415020, but numbers includes 415021
        val bkkTrain = vehicle(
            id = "emma:1:415020",
            lat = 47.465,
            lon = 19.025,
            providerId = "mav",
            observedAt = t0
        )
        val workerCand = WorkerVehicle(bkkTrain, numbers = setOf("415020", "415021"), fallback = false)

        val merged = LiveVehicleMerge.merge(listOf(workerCand), coveredArea, listOf(emmaTrain))
        assertEquals(1, merged.size)
        val result = merged[0]
        // Merged with EMMA's 415021 record
        assertEquals("emma:1:415021", result.id)
        assertEquals("2814", result.trainNumber)
        assertEquals(47.465, result.position.lat, 1e-4)
    }

    @Test
    fun distinctTrainsAtSamePositionAndNearbyBusesRemainSeparate() {
        // Two distinct trains waiting at the same station track coordinates
        val trainA = vehicle("emma:1:1111", 47.464, 19.021, providerId = "mav", trainNumber = "100")
        val trainB = vehicle("emma:1:2222", 47.464, 19.021, providerId = "mav", trainNumber = "200")

        val candA = WorkerVehicle(trainA, numbers = setOf("1111"), fallback = false)
        val candB = WorkerVehicle(trainB, numbers = setOf("2222"), fallback = false)

        val merged = LiveVehicleMerge.merge(listOf(candA, candB), coveredArea, emptyList())
        assertEquals(2, merged.size)
        assertEquals(setOf("emma:1:1111", "emma:1:2222"), merged.map { it.id }.toSet())

        // Distinct bus and metro vehicles at same junction
        val bus = vehicle("emma:BKK:bus1", 47.497, 19.054, providerId = "bkk")
        val metro = vehicle("emma:BKK:metro1", 47.497, 19.054, providerId = "bkk")
        val mergedTransit = LiveVehicleMerge.merge(
            listOf(WorkerVehicle(bus, setOf("bus1"), false), WorkerVehicle(metro, setOf("metro1"), false)),
            coveredArea,
            emptyList()
        )
        assertEquals(2, mergedTransit.size)
        assertEquals(setOf("emma:BKK:bus1", "emma:BKK:metro1"), mergedTransit.map { it.id }.toSet())
    }

    @Test
    fun fallbackTrainsOnlyFillGapsNotCoveredByBkkOrDirectEmma() {
        val emmaTrain = vehicle("emma:1:1111", 47.46, 19.02, providerId = "mav")
        val workerBkkTrain = vehicle("emma:1:2222", 47.47, 19.03, providerId = "mav")
        val workerFallbackTrainOverlapping = vehicle("emma:1:1111", 47.46, 19.02, providerId = "mav")
        val workerFallbackTrainNew = vehicle("emma:1:3333", 47.48, 19.04, providerId = "mav")

        val workerCandidates = listOf(
            WorkerVehicle(workerBkkTrain, setOf("2222"), fallback = false),
            WorkerVehicle(workerFallbackTrainOverlapping, setOf("1111"), fallback = true),
            WorkerVehicle(workerFallbackTrainNew, setOf("3333"), fallback = true)
        )

        val merged = LiveVehicleMerge.merge(workerCandidates, coveredArea, listOf(emmaTrain))
        assertEquals(3, merged.size)
        val ids = merged.map { it.id }.toSet()
        assertTrue(ids.contains("emma:1:1111")) // kept from direct EMMA
        assertTrue(ids.contains("emma:1:2222")) // from BKK primary
        assertTrue(ids.contains("emma:1:3333")) // from Worker fallback (gap filled)
    }

    @Test
    fun volan_samePlateBkkFirstDedup_preservesNativeTripAndCompatibleRouteAlias() {
        val bkkVolan = Vehicle(
            id = "emma:hkir:hkir_SRD244",
            position = GeoPoint(47.452, 19.059),
            observedAt = tLater,
            heading = 120.0,
            providerId = "volan",
            routeId = "volan:volan_735",
            routeNumber = "735",
            tripId = null,
            destination = "Érd",
            nextStopId = null,
            nextStopName = null,
            bkkTripId = "volan:volan_futar_trip_123",
            positionSource = DataSource.BKK
        )
        val workerCandidate = WorkerVehicle(bkkVolan, setOf("SRD244"), fallback = false)

        val emmaVolan = Vehicle(
            id = "emma:hkir:hkir_SRD244",
            position = GeoPoint(47.450, 19.050),
            observedAt = tEarlier,
            heading = 90.0,
            providerId = "volan",
            routeId = "volan:hkir:hkir_V-R-200-735", // Compatible agglomeration route alias
            routeNumber = "735",
            tripId = "emma:native_trip_456",
            destination = "Érd",
            nextStopId = null,
            nextStopName = null,
            detailsSource = DataSource.EMMA
        )

        val merged = LiveVehicleMerge.merge(listOf(workerCandidate), coveredArea, listOf(emmaVolan))
        assertEquals(1, merged.size)
        val result = merged[0]
        assertEquals("emma:hkir:hkir_SRD244", result.id)
        // BKK wins live movement fields
        assertEquals(47.452, result.position.lat, 1e-4)
        assertEquals(19.059, result.position.lon, 1e-4)
        assertEquals(tLater, result.observedAt)
        assertEquals(120.0, result.heading)
        // Preserves native trip for fallback
        assertEquals("emma:native_trip_456", result.tripId)
        // Preserves compatible native route alias
        assertEquals("volan:hkir:hkir_V-R-200-735", result.routeId)
        // Preserves FUTÁR trip id
        assertEquals("volan:volan_futar_trip_123", result.bkkTripId)
    }

    @Test
    fun volan_distinctPlatesSameLocationPreserved() {
        val veh1 = vehicle("emma:hkir:hkir_SRD244", 47.452, 19.059, providerId = "volan")
        val veh2 = vehicle("emma:hkir:hkir_SRD245", 47.452, 19.059, providerId = "volan")

        val candidates = listOf(
            WorkerVehicle(veh1, setOf("SRD244"), fallback = false),
            WorkerVehicle(veh2, setOf("SRD245"), fallback = false)
        )

        val merged = LiveVehicleMerge.merge(candidates, coveredArea, emptyList())
        assertEquals(2, merged.size)
        assertEquals(setOf("emma:hkir:hkir_SRD244", "emma:hkir:hkir_SRD245"), merged.map { it.id }.toSet())
    }

    @Test
    fun volan_unsupportedNationalIdentitiesKeptOnEmma() {
        // EMMA national Volán vehicle inside coveredArea, not returned by BKK
        val nationalVolan = vehicle("emma:hkir:hkir_NAT001", 47.49, 19.05, providerId = "volan")
        // EMMA BKK vehicle inside coveredArea, not returned by BKK
        val bkkVeh = vehicle("emma:BKK:dropped", 47.49, 19.05, providerId = "bkk")

        val merged = LiveVehicleMerge.merge(emptyList(), coveredArea, listOf(nationalVolan, bkkVeh))
        // Both national Volán vehicle and EMMA BKK vehicle within coveredArea are preserved
        assertEquals(2, merged.size)
        val ids = merged.map { it.id }.toSet()
        assertTrue(ids.contains("emma:hkir:hkir_NAT001"))
        assertTrue(ids.contains("emma:BKK:dropped"))
    }
}
