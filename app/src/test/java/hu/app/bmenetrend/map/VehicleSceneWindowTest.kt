package hu.app.bmenetrend.map

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.MapBounds
import hu.app.bmenetrend.data.Vehicle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.geojson.FeatureCollection
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehicleSceneWindowTest {

    private lateinit var app: Application
    private val now = Instant.parse("2026-11-11T12:00:00Z")

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun testVehicle(
        id: String,
        lat: Double,
        lon: Double,
        routeNumber: String = "1",
        mode: String = "BUS",
        provider: String = "bkk",
        routeId: String? = "r1",
    ) = Vehicle(
        id = id,
        position = GeoPoint(lat, lon),
        observedAt = now,
        routeId = routeId,
        providerId = provider,
        routeNumber = routeNumber,
        transportMode = mode,
        destination = "Terminal",
    )

    // =========================================================================
    // 1. Far-away thousands of vehicles don't enter source
    // =========================================================================
    @Test
    fun farAwayVehicles_doNotEnterSource_whenOutsideRegion() = runBlocking {
        // Budapest metropolitan region
        val budapestRegion = MapBounds(18.90, 47.40, 19.20, 47.60)

        // 3000 vehicles scattered across Hungary outside Budapest
        // (Debrecen, Szeged, Miskolc, Pécs, Győr, Szombathely, etc.)
        val farAwayVehicles = (1..3000).map { i ->
            val lat = 46.0 + (i % 50) * 0.04 // 46.0 to 48.0
            val lon = if (i % 2 == 0) 16.5 + (i % 20) * 0.1 else 20.5 + (i % 20) * 0.1 // well outside [18.9, 19.2]
            testVehicle("far-$i", lat, lon, provider = "volan")
        }

        // 50 vehicles inside Budapest region
        val budapestVehicles = (1..50).map { i ->
            testVehicle("bp-$i", 47.49 + (i % 10) * 0.01, 19.04 + (i % 10) * 0.01, provider = "bkk")
        }

        val allVehicles = farAwayVehicles + budapestVehicles
        assertEquals(3050, allVehicles.size)

        val selected = VehicleSceneWindow.select(allVehicles, budapestRegion, null)

        assertEquals("Only nearby Budapest vehicles must be selected", 50, selected.size)
        assertTrue("No far-away vehicle may enter the selected list", selected.none { it.id.startsWith("far-") })
        assertTrue("All Budapest vehicles must be retained", selected.all { it.id.startsWith("bp-") })

        // Prepare source and verify serialized GeoJSON features
        val pending = linkedMapOf<String, Bitmap>()
        try {
            val json = VehicleMapScene.prepare(app, selected, null, emptySet(), pending)
            val collection = FeatureCollection.fromJson(json)
            assertEquals("Source feature count must match selected vehicle count exactly", 50, collection.features()!!.size)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    // =========================================================================
    // 2. Nearby nonmatching line vehicles retained so dismiss doesn't need source reload
    // =========================================================================
    @Test
    fun nearbyNonmatchingLineVehicles_areRetainedInSource_soDismissDoesNotNeedSourceReload() = runBlocking {
        val region = MapBounds(18.95, 47.45, 19.15, 47.55)

        val tram1 = testVehicle("v-tram1", 47.50, 19.05, routeNumber = "1", mode = "TRAM", routeId = "bkk:1")
        val bus7 = testVehicle("v-bus7", 47.49, 19.06, routeNumber = "7", mode = "BUS", routeId = "bkk:7")
        val bus8E = testVehicle("v-bus8e", 47.48, 19.04, routeNumber = "8E", mode = "BUS", routeId = "bkk:8E")
        val trainS30 = testVehicle("v-s30", 47.47, 19.02, routeNumber = "S30", mode = "RAIL", provider = "mav", routeId = "mav:s30")

        val nearbyVehicles = listOf(tram1, bus7, bus8E, trainS30)

        // When tram1 is focused, VehicleSceneWindow.select still retains ALL nearby vehicles in region
        val selectedUnderFocus = VehicleSceneWindow.select(nearbyVehicles, region, tram1.id)
        assertEquals("VehicleSceneWindow keeps all nearby vehicles regardless of line focus", 4, selectedUnderFocus.size)

        // Prepare source: all 4 vehicles are written to GeoJSON
        val pending = linkedMapOf<String, Bitmap>()
        try {
            val json = VehicleMapScene.prepare(app, selectedUnderFocus, tram1.id, emptySet(), pending)
            val features = FeatureCollection.fromJson(json).features()!!
            assertEquals(4, features.size)
            val idsInSource = features.map { it.getStringProperty("vehicle-id") }.toSet()
            assertEquals(setOf("v-tram1", "v-bus7", "v-bus8e", "v-s30"), idsInSource)

            // When line focus is dismissed (focus -> null), the source already contains all vehicles.
            // Native MapLibre layer expressions (VehicleLayers.focusFilter) handle unmasking immediately
            // without requiring a re-query or source re-tiling.
            val selectedAfterDismiss = VehicleSceneWindow.select(nearbyVehicles, region, null)
            assertEquals("Dismissing focus leaves identical vehicles in region", selectedUnderFocus.size, selectedAfterDismiss.size)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    // =========================================================================
    // 3. Selected outside retained
    // =========================================================================
    @Test
    fun selectedVehicleOutsideRegion_isRetained() = runBlocking {
        // Viewport and region around Budapest
        val budapestRegion = MapBounds(18.95, 47.45, 19.15, 47.55)

        val bp1 = testVehicle("bp-1", 47.50, 19.05, routeNumber = "1")
        val bp2 = testVehicle("bp-2", 47.49, 19.06, routeNumber = "7")
        // User searched for a train in Debrecen (far outside Budapest region)
        val debrecenTrain = testVehicle("mav-debrecen-101", 47.53, 21.62, routeNumber = "IC600", mode = "RAIL", provider = "mav")

        val vehicles = listOf(bp1, bp2, debrecenTrain)

        // With no vehicle selected: Debrecen train is excluded
        val unselectedList = VehicleSceneWindow.select(vehicles, budapestRegion, null)
        assertEquals(2, unselectedList.size)
        assertFalse(unselectedList.any { it.id == debrecenTrain.id })

        // With Debrecen train selected: it is explicitly retained
        val selectedList = VehicleSceneWindow.select(vehicles, budapestRegion, debrecenTrain.id)
        assertEquals(3, selectedList.size)
        assertTrue("Selected vehicle outside region must be retained in selected list", selectedList.any { it.id == debrecenTrain.id })

        // Verify GeoJSON serialization includes the outside vehicle
        val pending = linkedMapOf<String, Bitmap>()
        try {
            val json = VehicleMapScene.prepare(app, selectedList, debrecenTrain.id, emptySet(), pending)
            val features = FeatureCollection.fromJson(json).features()!!
            val debrecenFeature = features.firstOrNull { it.getStringProperty("vehicle-id") == debrecenTrain.id }
            assertNotNull("Selected outside vehicle must be serialized in source", debrecenFeature)
            assertEquals(1, debrecenFeature!!.getNumberProperty("priority").toInt())
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    // =========================================================================
    // 4. Region no change for smallmoves
    // =========================================================================
    @Test
    fun region_noChangeForSmallMoves() {
        val initialViewport = MapBounds(19.00, 47.45, 19.10, 47.55)
        val initialRegion = VehicleSceneWindow.next(null, initialViewport)

        // Small camera pan within the 15% inset boundary
        val smallMoveViewport1 = MapBounds(19.01, 47.46, 19.09, 47.54)
        val regionAfterSmallMove1 = VehicleSceneWindow.next(initialRegion, smallMoveViewport1)
        assertSame("Small move inside insets must return identical MapBounds instance", initialRegion, regionAfterSmallMove1)

        // Another micro-pan
        val smallMoveViewport2 = MapBounds(19.005, 47.455, 19.095, 47.545)
        val regionAfterSmallMove2 = VehicleSceneWindow.next(initialRegion, smallMoveViewport2)
        assertSame("Subsequent micro-moves inside insets must return identical MapBounds instance", initialRegion, regionAfterSmallMove2)
    }

    // =========================================================================
    // Zoom in from large city region shrinks oversized region
    // =========================================================================
    @Test
    fun zoomIn_fromLargeCityRegionToStreetViewport_shrinksOversizedRegion_whileRetainingViewportAndMinPadding() {
        // Large city overview region (e.g. metropolitan Budapest: span 0.40 lon x 0.25 lat)
        val largeCityRegion = MapBounds(18.90, 47.40, 19.30, 47.65)

        // Much smaller street-level viewport located well within the city center (span 0.01 lon x 0.01 lat)
        val streetViewport = MapBounds(19.045, 47.495, 19.055, 47.505)

        val nextRegion = VehicleSceneWindow.next(largeCityRegion, streetViewport)

        // 1. Should shrink oversized region (zooming in must not retain broad region forever)
        assertNotSame("Oversized region must not be retained when zooming in to street viewport", largeCityRegion, nextRegion)
        assertTrue(
            "Region longitude span must shrink compared to large city region (was ${largeCityRegion.east - largeCityRegion.west}, got ${nextRegion.east - nextRegion.west})",
            (nextRegion.east - nextRegion.west) < (largeCityRegion.east - largeCityRegion.west)
        )
        assertTrue(
            "Region latitude span must shrink compared to large city region (was ${largeCityRegion.north - largeCityRegion.south}, got ${nextRegion.north - nextRegion.south})",
            (nextRegion.north - nextRegion.south) < (largeCityRegion.north - largeCityRegion.south)
        )

        // 2. Retain full viewport
        assertTrue("Shrunk region must contain street viewport west", nextRegion.west <= streetViewport.west)
        assertTrue("Shrunk region must contain street viewport east", nextRegion.east >= streetViewport.east)
        assertTrue("Shrunk region must contain street viewport south", nextRegion.south <= streetViewport.south)
        assertTrue("Shrunk region must contain street viewport north", nextRegion.north >= streetViewport.north)

        // 3. Retain min padding (at least 0.12 lon span and 0.08 lat span)
        assertTrue("Shrunk region must retain minimum lon span >= 0.12", (nextRegion.east - nextRegion.west) >= 0.12 - 1e-9)
        assertTrue("Shrunk region must retain minimum lat span >= 0.08", (nextRegion.north - nextRegion.south) >= 0.08 - 1e-9)

        // 4. Small pan reference unchanged within the shrunk region
        val smallPanViewport = MapBounds(
            streetViewport.west + 0.001,
            streetViewport.south + 0.001,
            streetViewport.east + 0.001,
            streetViewport.north + 0.001
        )
        val panRegion = VehicleSceneWindow.next(nextRegion, smallPanViewport)
        assertSame("Small pan reference must remain unchanged within the shrunk region", nextRegion, panRegion)
    }

    // =========================================================================
    // 5. Changing city expands/recenters region preserves viewport allbounds
    // =========================================================================
    @Test
    fun changingCity_expandsOrRecentersRegion_andPreservesViewportAllBounds() {
        val budapestViewport = MapBounds(19.00, 47.45, 19.10, 47.55)
        val budapestRegion = VehicleSceneWindow.next(null, budapestViewport)

        // User jumps to Szeged (~170 km south-east)
        val szegedViewport = MapBounds(20.10, 46.20, 20.20, 46.30)
        val szegedRegion = VehicleSceneWindow.next(budapestRegion, szegedViewport)

        assertNotSame("Changing city must produce new region", budapestRegion, szegedRegion)
        assertNotEquals(budapestRegion, szegedRegion)

        // All 4 viewport bounds must be strictly contained within szegedRegion
        assertTrue("West bound must be within region", szegedRegion.west <= szegedViewport.west)
        assertTrue("East bound must be within region", szegedRegion.east >= szegedViewport.east)
        assertTrue("South bound must be within region", szegedRegion.south <= szegedViewport.south)
        assertTrue("North bound must be within region", szegedRegion.north >= szegedViewport.north)

        // Verify minimum span guarantees
        assertTrue("Region lon span >= 0.12", (szegedRegion.east - szegedRegion.west) >= 0.12 - 1e-9)
        assertTrue("Region lat span >= 0.08", (szegedRegion.north - szegedRegion.south) >= 0.08 - 1e-9)
    }

    // =========================================================================
    // 6. Broad zoomout includes all visiblevehicles
    // =========================================================================
    @Test
    fun broadZoomOut_includesAllVisibleVehicles() {
        // Broad nationwide viewport across Hungary
        val broadViewport = MapBounds(16.20, 45.80, 22.80, 48.60)
        val broadRegion = VehicleSceneWindow.next(null, broadViewport)

        assertTrue("Broad region must strictly contain broad viewport west", broadRegion.west <= broadViewport.west)
        assertTrue("Broad region must strictly contain broad viewport east", broadRegion.east >= broadViewport.east)
        assertTrue("Broad region must strictly contain broad viewport south", broadRegion.south <= broadViewport.south)
        assertTrue("Broad region must strictly contain broad viewport north", broadRegion.north >= broadViewport.north)

        // Test vehicles at the 4 corners of the visible viewport
        val nw = testVehicle("nw", broadViewport.north - 0.01, broadViewport.west + 0.01)
        val ne = testVehicle("ne", broadViewport.north - 0.01, broadViewport.east - 0.01)
        val sw = testVehicle("sw", broadViewport.south + 0.01, broadViewport.west + 0.01)
        val se = testVehicle("se", broadViewport.south + 0.01, broadViewport.east - 0.01)
        val center = testVehicle("center", (broadViewport.south + broadViewport.north) / 2, (broadViewport.west + broadViewport.east) / 2)

        val visibleVehicles = listOf(nw, ne, sw, se, center)
        val selected = VehicleSceneWindow.select(visibleVehicles, broadRegion, null)

        assertEquals("All visible vehicles across nationwide zoom must be retained", 5, selected.size)
    }

    // =========================================================================
    // 7. Zero initialregion waits forviewport production reviewed
    // =========================================================================
    @Test
    fun zeroInitialRegion_waitsForViewport_andProducesPaddedRegionOnFirstUpdate() {
        // Initial state before camera/viewport setup is null (zero initial region).
        val initialCurrent: MapBounds? = null

        // First viewport calculation on camera layout/idle
        val initialViewport = MapBounds(19.04, 47.49, 19.06, 47.51) // span 0.02 x 0.02
        val firstRegion = VehicleSceneWindow.next(initialCurrent, initialViewport)

        assertNotNull("First viewport update produces non-null region", firstRegion)
        // With minspan clamp (.12 lon, .08 lat), region expands around initial small viewport
        assertTrue(firstRegion.east - firstRegion.west >= 0.12 - 1e-9)
        assertTrue(firstRegion.north - firstRegion.south >= 0.08 - 1e-9)
        assertTrue(firstRegion.west <= initialViewport.west)
        assertTrue(firstRegion.east >= initialViewport.east)
        assertTrue(firstRegion.south <= initialViewport.south)
        assertTrue(firstRegion.north >= initialViewport.north)
    }

    // =========================================================================
    // 8. Benchmark real source serialization on fixture country5000 points vs localregion
    //    Verify payloadcount/bytes while not claiming GL latency
    // =========================================================================
    @Test
    fun benchmarkRealSourceSerialization_country5000Points_vs_localRegion_verifyPayloadCountAndBytes() = runBlocking {
        // Generate nationwide fixture of 5000 points across Hungary
        val country5000 = (1..5000).map { i ->
            val isBudapest = i <= 250
            val lat = if (isBudapest) 47.40 + (i % 200) * 0.001 else 45.80 + (i % 280) * 0.01
            val lon = if (isBudapest) 18.95 + (i % 250) * 0.001 else 16.20 + (i % 670) * 0.01
            val mode = when (i % 4) {
                0 -> "BUS"
                1 -> "TRAM"
                2 -> "SUBWAY"
                else -> "RAIL"
            }
            val provider = when (i % 4) {
                0 -> "bkk"
                1 -> "bkk"
                2 -> "bkk"
                else -> "mav"
            }
            testVehicle("veh-$i", lat, lon, routeNumber = "${(i % 100) + 1}", mode = mode, provider = provider, routeId = "r-$i")
        }
        assertEquals(5000, country5000.size)

        // Local Budapest viewport and calculated region
        val localViewport = MapBounds(19.00, 47.45, 19.10, 47.55)
        val localRegion = VehicleSceneWindow.next(null, localViewport)

        // Filter for local region
        val localVehicles = VehicleSceneWindow.select(country5000, localRegion, null)
        assertTrue("Local vehicles must be a small fraction of nationwide feed", localVehicles.size in 150..350)

        // 1. Measure nationwide 5000 points source serialization
        val countryPending = linkedMapOf<String, Bitmap>()
        val countryStart = System.nanoTime()
        val countryJson: String
        try {
            countryJson = VehicleMapScene.prepare(app, country5000, null, emptySet(), countryPending)
        } finally {
            countryPending.values.forEach(Bitmap::recycle)
        }
        val countryTimeMs = (System.nanoTime() - countryStart) / 1_000_000.0
        val countryBytes = countryJson.toByteArray(Charsets.UTF_8).size
        val countryFeatureCount = 5000

        // 2. Measure local region source serialization
        val localPending = linkedMapOf<String, Bitmap>()
        val localStart = System.nanoTime()
        val localJson: String
        try {
            localJson = VehicleMapScene.prepare(app, localVehicles, null, emptySet(), localPending)
        } finally {
            localPending.values.forEach(Bitmap::recycle)
        }
        val localTimeMs = (System.nanoTime() - localStart) / 1_000_000.0
        val localBytes = localJson.toByteArray(Charsets.UTF_8).size
        val localFeatureCount = localVehicles.size

        // Print benchmark statistics (payload count, payload bytes, serialization time)
        println("=== VEHICLE SOURCE SERIALIZATION BENCHMARK ===")
        println("Nationwide (country5000):")
        println("  Feature count: $countryFeatureCount")
        println("  Payload size:  $countryBytes bytes (${countryBytes / 1024} KB)")
        println("  Prepare time:  ${String.format("%.2f", countryTimeMs)} ms")
        println("Local region (Budapest window):")
        println("  Feature count: $localFeatureCount")
        println("  Payload size:  $localBytes bytes (${localBytes / 1024} KB)")
        println("  Prepare time:  ${String.format("%.2f", localTimeMs)} ms")
        println("Reduction:")
        println("  Features:      -${((1.0 - localFeatureCount.toDouble() / countryFeatureCount) * 100).toInt()}%")
        println("  Payload bytes: -${((1.0 - localBytes.toDouble() / countryBytes) * 100).toInt()}%")
        println("NOTE: This benchmark measures source serialization payload and CPU prepare time on JVM.")
        println("It validates payload count and byte reduction without claiming GL latency.")
        println("==============================================")

        // Assertions verifying massive payload and byte reduction
        assertEquals(5000, countryFeatureCount)
        assertTrue("Local payload count must be < 10% of country feed", localFeatureCount < countryFeatureCount * 0.10)
        assertTrue("Local payload bytes must be < 10% of country bytes", localBytes < countryBytes * 0.10)
        assertTrue("Local JSON must be valid GeoJSON FeatureCollection", localJson.startsWith("{\"type\":\"FeatureCollection\",\"features\":["))
        assertTrue("Country JSON must be valid GeoJSON FeatureCollection", countryJson.startsWith("{\"type\":\"FeatureCollection\",\"features\":["))
    }

    // =========================================================================
    // 9. Selected vehicle snapshot absent from feed is retained in source
    // =========================================================================
    @Test
    fun select_whenSelectedVehicleSnapshotAbsentFromFeed_isRetainedInSource_includingOutsideRegion() {
        val region = MapBounds(18.95, 47.45, 19.15, 47.55)
        val insideBus = testVehicle("v-inside", 47.50, 19.05, routeNumber = "7", mode = "BUS", provider = "bkk")
        val feed = listOf(insideBus)

        // Selected train snapshot absent from feed, located outside the region
        val outsideTrain = testVehicle("mav-train-snap", 47.10, 18.50, routeNumber = "S30", mode = "RAIL", provider = "mav")

        val selected = VehicleSceneWindow.select(
            vehicles = feed,
            region = region,
            selectedId = outsideTrain.id,
            selectedVehicle = outsideTrain,
        )

        assertEquals("Feed vehicle inside region and selected vehicle snapshot outside region must both be in source", 2, selected.size)
        assertTrue("Selected train snapshot absent from feed must be retained in source", selected.any { it.id == outsideTrain.id })
        val retained = selected.first { it.id == outsideTrain.id }
        assertEquals("Retained snapshot must preserve original geographic coordinates", outsideTrain.position, retained.position)
        assertEquals("Retained snapshot must preserve original provider", "mav", retained.providerId)
    }

    @Test
    fun select_whenFreshLiveVehicleInFeedHasSameId_freshLiveWinsOverCachedSnapshotWithoutDuplicate() {
        val region = MapBounds(18.95, 47.45, 19.15, 47.55)
        val livePosition = GeoPoint(47.50, 19.05)
        val cachedPosition = GeoPoint(47.48, 19.00)

        val freshLiveTrain = testVehicle("train-1", livePosition.lat, livePosition.lon, routeNumber = "S30", mode = "RAIL", provider = "mav")
        val cachedSnapshotTrain = testVehicle("train-1", cachedPosition.lat, cachedPosition.lon, routeNumber = "S30", mode = "RAIL", provider = "mav")

        val selected = VehicleSceneWindow.select(
            vehicles = listOf(freshLiveTrain),
            region = region,
            selectedId = "train-1",
            selectedVehicle = cachedSnapshotTrain,
        )

        assertEquals("Exactly one vehicle with the ID must be present (no duplicate)", 1, selected.size)
        assertEquals("Fresh live vehicle must win over cached snapshot", livePosition, selected.first().position)
    }

    @Test
    fun select_whenSelectionDismissed_noSnapshotRetained() {
        val region = MapBounds(18.95, 47.45, 19.15, 47.55)
        val insideBus = testVehicle("v-inside", 47.50, 19.05, routeNumber = "7", mode = "BUS", provider = "bkk")
        val feed = listOf(insideBus)

        val selected = VehicleSceneWindow.select(
            vehicles = feed,
            region = region,
            selectedId = null,
            selectedVehicle = null,
        )

        assertEquals(1, selected.size)
        assertEquals("v-inside", selected.first().id)
    }

    @Test
    fun select_snapshotChangingPositionSameId_usesLatestSnapshot_andDismissDoesNotRetainFallback() {
        val region = MapBounds(18.95, 47.45, 19.15, 47.55)
        val insideBus = testVehicle("v-inside", 47.50, 19.05, routeNumber = "7", mode = "BUS", provider = "bkk")
        val feed = listOf(insideBus)

        val pos1 = GeoPoint(47.10, 18.50)
        val pos2 = GeoPoint(47.12, 18.52)
        val snapshot1 = testVehicle("train-1", pos1.lat, pos1.lon, routeNumber = "S30", mode = "RAIL", provider = "mav")
        val snapshot2 = testVehicle("train-1", pos2.lat, pos2.lon, routeNumber = "S30", mode = "RAIL", provider = "mav")

        val selected1 = VehicleSceneWindow.select(
            vehicles = feed,
            region = region,
            selectedId = snapshot1.id,
            selectedVehicle = snapshot1,
        )
        assertEquals(2, selected1.size)
        assertEquals(pos1, selected1.first { it.id == "train-1" }.position)

        val selected2 = VehicleSceneWindow.select(
            vehicles = feed,
            region = region,
            selectedId = snapshot2.id,
            selectedVehicle = snapshot2,
        )
        assertEquals(2, selected2.size)
        assertEquals(pos2, selected2.first { it.id == "train-1" }.position)

        val selectedDismissed = VehicleSceneWindow.select(
            vehicles = feed,
            region = region,
            selectedId = null,
            selectedVehicle = null,
        )
        assertEquals(1, selectedDismissed.size)
        assertEquals("v-inside", selectedDismissed.first().id)
        assertFalse(selectedDismissed.any { it.id == "train-1" })
    }
}
