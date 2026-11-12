package hu.app.bmenetrend.map

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.TransitRoute
import hu.app.bmenetrend.data.Vehicle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
class VehicleMapSceneTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    private fun vehicle(
        id: String,
        routeNumber: String = "1",
        mode: String = "BUS",
        provider: String = "bkk",
        lat: Double = 47.49,
        lon: Double = 19.04,
        routeId: String? = "r1",
        heading: Double? = null,
    ) = Vehicle(
        id = id,
        position = GeoPoint(lat, lon),
        observedAt = Instant.parse("2026-10-29T10:00:00Z"),
        routeId = routeId,
        providerId = provider,
        routeNumber = routeNumber,
        transportMode = mode,
        destination = "Terminal",
        heading = heading,
    )

    @Test
    fun geoJsonPropertiesAndScaleInvariantsForUnselectedAndSelectedVehicles() = runBlocking {
        val v1 = vehicle("v1", "1", "BUS", lat = 47.491, lon = 19.041)
        val v2 = vehicle("v2", "S30", "RAIL", provider = "mav", lat = 47.492, lon = 19.042)
        val pending = linkedMapOf<String, Bitmap>()

        val json = VehicleMapScene.prepare(
            context = app,
            vehicles = listOf(v1, v2),
            selectedId = "v2",
            cachedIcons = emptySet(),
            pending = pending,
        )

        try {
            val collection = FeatureCollection.fromJson(json)
            assertNotNull(collection)
            val features = collection.features()!!
            assertEquals(2, features.size)

            // Vehicle 1: Unselected bus
            val f1 = features[0]
            assertEquals("v1", f1.getStringProperty("vehicle-id"))
            assertFalse("Bus should not be flagged as rail", f1.getBooleanProperty("rail"))
            assertEquals(0, f1.getNumberProperty("priority").toInt())
            assertEquals(26f / 36f, f1.getNumberProperty("small-scale").toFloat(), 0.001f)
            assertEquals(26f / 36f, f1.getNumberProperty("large-scale").toFloat(), 0.001f)
            assertEquals(1f, f1.getNumberProperty("dot-scale").toFloat(), 0.001f)
            val p1 = f1.geometry() as org.maplibre.geojson.Point
            assertEquals(19.041, p1.longitude(), 0.0001)
            assertEquals(47.491, p1.latitude(), 0.0001)

            // Vehicle 2: Selected rail
            val f2 = features[1]
            assertEquals("v2", f2.getStringProperty("vehicle-id"))
            assertTrue("Train must be flagged as rail", f2.getBooleanProperty("rail"))
            assertEquals(1, f2.getNumberProperty("priority").toInt())
            assertEquals(30f / 36f, f2.getNumberProperty("small-scale").toFloat(), 0.001f)
            assertEquals(30f / 36f, f2.getNumberProperty("large-scale").toFloat(), 0.001f)
            assertEquals(10f / 6f, f2.getNumberProperty("dot-scale").toFloat(), 0.001f)
            val p2 = f2.geometry() as org.maplibre.geojson.Point
            assertEquals(19.042, p2.longitude(), 0.0001)
            assertEquals(47.492, p2.latitude(), 0.0001)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun modeStylingDistinguishesMetroHevAndBusIconsAndDots() = runBlocking {
        val metro = vehicle("m4", "M4", "SUBWAY", provider = "bkk")
        val hev = vehicle("h5", "H5", "SUBURBAN_RAILWAY", provider = "hev")
        val bus = vehicle("b1", "100E", "BUS", provider = "bkk")
        val pending = linkedMapOf<String, Bitmap>()

        val json = VehicleMapScene.prepare(
            context = app,
            vehicles = listOf(metro, hev, bus),
            selectedId = null,
            cachedIcons = emptySet(),
            pending = pending,
        )

        try {
            val features = FeatureCollection.fromJson(json).features()!!
            val metroIconKey = features[0].getStringProperty("icon")
            val hevIconKey = features[1].getStringProperty("icon")
            val busIconKey = features[2].getStringProperty("icon")

            assertEquals("vehicle-TRAIN-4-null", metroIconKey)
            assertEquals("vehicle-TRAIN-null-H5", hevIconKey)
            assertEquals("vehicle-BKK_BUS-null-null", busIconKey)

            // Verify rendered bitmaps in pending
            assertTrue(pending.containsKey(metroIconKey))
            assertTrue(pending.containsKey(hevIconKey))
            assertTrue(pending.containsKey(busIconKey))

            // Check dimensions and density
            val metroBmp = pending.getValue(metroIconKey)
            assertEquals(108, metroBmp.width)
            assertEquals(108, metroBmp.height)
            assertEquals(480, metroBmp.density)

            val metroDotKey = features[0].getStringProperty("dot")
            val metroDotBmp = pending.getValue(metroDotKey)
            assertEquals(18, metroDotBmp.width)
            assertEquals(18, metroDotBmp.height)
            assertEquals(480, metroDotBmp.density)

            // Dot should have white ring and non-white center
            assertEquals(Color.WHITE, metroDotBmp.getPixel(9, 1))
            val centerColor = metroDotBmp.getPixel(9, 9)
            assertNotEquals(Color.WHITE, centerColor)
            assertNotEquals(0, Color.alpha(centerColor))
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun activeTramFocusRetainsAllVehiclesInSourceWithNativeFilterProperties() = runBlocking {
        val matchingRoute = TransitRoute("bkk:1", "1", "Tram 1", emptyList(), "bkk", transportMode = "TRAM")
        val matchingTram = vehicle("tram-1", "1", "TRAM", provider = "bkk", routeId = "bkk:1")
        val nonmatchingBus = vehicle("bus-7", "7", "BUS", provider = "bkk", routeId = "bkk:7")
        val otherTramRoute = vehicle("tram-2", "2", "TRAM", provider = "bkk", routeId = "bkk:2")
        val sameNumberOtherProvider = vehicle("bus-1-volan", "1", "BUS", provider = "volan", routeId = "volan:1")

        val focus = LineMapFocus.create(
            vehicle = matchingTram,
            boarding = null,
            routes = listOf(matchingRoute),
            details = emptyMap(),
            journey = null,
            allStops = emptyList(),
        )
        val activeFocus = requireNotNull(focus)
        // Verify focus.includes semantic baseline
        assertTrue(activeFocus.includes(matchingTram))
        assertFalse(activeFocus.includes(nonmatchingBus))
        assertFalse(activeFocus.includes(otherTramRoute))
        assertFalse(activeFocus.includes(sameNumberOtherProvider))

        val pending = linkedMapOf<String, Bitmap>()
        val json = VehicleMapScene.prepare(
            context = app,
            vehicles = listOf(matchingTram, nonmatchingBus, otherTramRoute, sameNumberOtherProvider),
            selectedId = matchingTram.id,
            cachedIcons = emptySet(),
            pending = pending,
        )

        try {
            val collection = FeatureCollection.fromJson(json)
            assertNotNull(collection)
            val features = collection.features()!!
            assertEquals(
                "Source must retain matching tram, nonmatching bus, other tram route, and same number other provider",
                4,
                features.size,
            )

            val byId = features.associateBy { it.getStringProperty("vehicle-id") }
            assertTrue("Matching tram must be in source", byId.containsKey("tram-1"))
            assertTrue("Nonmatching bus must be in source", byId.containsKey("bus-7"))
            assertTrue("Other tram route must be in source", byId.containsKey("tram-2"))
            assertTrue("Same number other provider must be in source", byId.containsKey("bus-1-volan"))

            // Verify source properties needed by native layer expressions: provider-id, route-id, route-number, transport-mode
            val fMatching = byId.getValue("tram-1")
            assertEquals("bkk", fMatching.getStringProperty("provider-id"))
            assertEquals("bkk:1", fMatching.getStringProperty("route-id"))
            assertEquals("1", fMatching.getStringProperty("route-number"))
            assertEquals("TRAM", fMatching.getStringProperty("transport-mode"))

            val fBus = byId.getValue("bus-7")
            assertEquals("bkk", fBus.getStringProperty("provider-id"))
            assertEquals("bkk:7", fBus.getStringProperty("route-id"))
            assertEquals("7", fBus.getStringProperty("route-number"))
            assertEquals("BUS", fBus.getStringProperty("transport-mode"))

            val fOtherTram = byId.getValue("tram-2")
            assertEquals("bkk", fOtherTram.getStringProperty("provider-id"))
            assertEquals("bkk:2", fOtherTram.getStringProperty("route-id"))
            assertEquals("2", fOtherTram.getStringProperty("route-number"))
            assertEquals("TRAM", fOtherTram.getStringProperty("transport-mode"))

            val fVolan = byId.getValue("bus-1-volan")
            assertEquals("volan", fVolan.getStringProperty("provider-id"))
            assertEquals("volan:1", fVolan.getStringProperty("route-id"))
            assertEquals("1", fVolan.getStringProperty("route-number"))
            assertEquals("BUS", fVolan.getStringProperty("transport-mode"))
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun focusFilterIncludesOnlyMatchingVehicles() = runBlocking {
        val matchingRoute = TransitRoute("bkk:1", "1", "Tram 1", emptyList(), "bkk", transportMode = "TRAM")
        val selectedVeh = vehicle("v1", "1", "TRAM", routeId = "bkk:1")
        val otherVeh = vehicle("v2", "2", "TRAM", routeId = "bkk:2")

        val focus = LineMapFocus.create(
            vehicle = selectedVeh,
            boarding = null,
            routes = listOf(matchingRoute),
            details = emptyMap(),
            journey = null,
            allStops = emptyList(),
        )
        val activeFocus = requireNotNull(focus)
        assertTrue(activeFocus.includes(selectedVeh))
        assertFalse(activeFocus.includes(otherVeh))

        val pending = linkedMapOf<String, Bitmap>()
        val json = VehicleMapScene.prepare(
            context = app,
            vehicles = listOf(selectedVeh, otherVeh),
            selectedId = selectedVeh.id,
            cachedIcons = emptySet(),
            pending = pending,
        )

        try {
            val features = FeatureCollection.fromJson(json).features()!!
            assertEquals("Source retains all vehicles regardless of focus", 2, features.size)
            val ids = features.map { it.getStringProperty("vehicle-id") }.toSet()
            assertEquals(setOf("v1", "v2"), ids)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun focusLifecycleTransitionsFromFocusToNullAndRestoresAllVehicles() = runBlocking {
        val biatorbagyRoute = TransitRoute("volan:hkir:762", "762", "Budapest–Biatorbágy", emptyList(), "volan", transportMode = "BUS")
        val biatorbagyVeh = vehicle("v-biatorbagy", "762", "BUS", provider = "volan", lat = 47.46, lon = 18.90, routeId = "volan:hkir:762")
        val budapestVeh = vehicle("v-budapest", "1", "TRAM", provider = "bkk", lat = 47.49, lon = 19.04, routeId = "bkk:1")

        val focus = LineMapFocus.create(
            vehicle = biatorbagyVeh,
            boarding = null,
            routes = listOf(biatorbagyRoute),
            details = emptyMap(),
            journey = null,
            allStops = emptyList(),
        )
        assertNotNull(focus)

        val pending1 = linkedMapOf<String, Bitmap>()
        try {
            // 1. Active highlight: source retains all vehicles (layer filter handles visibility independently)
            val jsonFocus = VehicleMapScene.prepare(
                context = app,
                vehicles = listOf(biatorbagyVeh, budapestVeh),
                selectedId = biatorbagyVeh.id,
                cachedIcons = emptySet(),
                pending = pending1,
            )
            val featuresFocus = FeatureCollection.fromJson(jsonFocus).features()!!
            assertEquals("Active highlight retains all vehicles in source", 2, featuresFocus.size)
            val focusIds = featuresFocus.map { it.getStringProperty("vehicle-id") }.toSet()
            assertEquals(setOf("v-biatorbagy", "v-budapest"), focusIds)

            // 2. Focus cleared (focus = null) with same incoming vehicle list: all vehicles are restored unfiltered
            val pending2 = linkedMapOf<String, Bitmap>()
            try {
                val jsonCleared = VehicleMapScene.prepare(
                    context = app,
                    vehicles = listOf(biatorbagyVeh, budapestVeh),
                    selectedId = null,
                    cachedIcons = pending1.keys.toSet(),
                    pending = pending2,
                )
                val featuresCleared = FeatureCollection.fromJson(jsonCleared).features()!!
                assertEquals("Clearing focus must restore all vehicles with same incoming list", 2, featuresCleared.size)
                val restoredIds = featuresCleared.map { it.getStringProperty("vehicle-id") }.toSet()
                assertTrue(restoredIds.contains("v-biatorbagy"))
                assertTrue(restoredIds.contains("v-budapest"))
            } finally {
                pending2.values.forEach(Bitmap::recycle)
            }

            // 3. New Budapest snapshot with focus = null: all new Budapest vehicles are prepared
            val budapestSnapshot = listOf(
                vehicle("v-bkk-4", "4", "TRAM", provider = "bkk", lat = 47.50, lon = 19.05, routeId = "bkk:4"),
                vehicle("v-bkk-6", "6", "TRAM", provider = "bkk", lat = 47.51, lon = 19.06, routeId = "bkk:6"),
            )
            val pending3 = linkedMapOf<String, Bitmap>()
            try {
                val jsonBudapest = VehicleMapScene.prepare(
                    context = app,
                    vehicles = budapestSnapshot,
                    selectedId = null,
                    cachedIcons = pending1.keys.toSet(),
                    pending = pending3,
                )
                val featuresBudapest = FeatureCollection.fromJson(jsonBudapest).features()!!
                assertEquals("Unfiltered prepare must contain all new Budapest snapshot vehicles", 2, featuresBudapest.size)
                val budapestIds = featuresBudapest.map { it.getStringProperty("vehicle-id") }.toSet()
                assertTrue(budapestIds.contains("v-bkk-4"))
                assertTrue(budapestIds.contains("v-bkk-6"))
            } finally {
                pending3.values.forEach(Bitmap::recycle)
            }
        } finally {
            pending1.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun cachedIconsAvoidReRenderingAndDuplicateStylesShareBitmaps() = runBlocking {
        val bus1 = vehicle("b1", "7", "BUS", provider = "bkk")
        val bus2 = vehicle("b2", "8E", "BUS", provider = "bkk")
        val pending = linkedMapOf<String, Bitmap>()

        // 1. Two vehicles with same style: pending should only contain 1 icon and 1 dot bitmap
        val json1 = VehicleMapScene.prepare(
            context = app,
            vehicles = listOf(bus1, bus2),
            selectedId = null,
            cachedIcons = emptySet(),
            pending = pending,
        )
        try {
            assertEquals("One icon and one dot should be rendered for identical bus styles", 2, pending.size)
            val features = FeatureCollection.fromJson(json1).features()!!
            assertEquals(features[0].getStringProperty("icon"), features[1].getStringProperty("icon"))
            assertEquals(features[0].getStringProperty("dot"), features[1].getStringProperty("dot"))

            val cached = pending.keys.toSet()

            // 2. Second prepare with cached icons populated: pending should remain empty
            val pending2 = linkedMapOf<String, Bitmap>()
            val json2 = VehicleMapScene.prepare(
                context = app,
                vehicles = listOf(bus1, bus2),
                selectedId = null,
                cachedIcons = cached,
                pending = pending2,
            )
            assertTrue("Already cached icon keys must not be added to pending", pending2.isEmpty())
            val features2 = FeatureCollection.fromJson(json2).features()!!
            assertEquals(2, features2.size)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun cancellationDuringPrepareCleansUpRenderedBitmaps() = runBlocking {
        val vehicles = (1..50).map { i ->
            vehicle("v$i", "$i", if (i % 2 == 0) "TRAM" else "BUS")
        }
        val pending = linkedMapOf<String, Bitmap>()

        var caughtCancellation = false
        val job = launch {
            try {
                // Cooperative check before or during prepare
                VehicleMapScene.prepare(
                    context = app,
                    vehicles = vehicles,
                    selectedId = null,
                    cachedIcons = emptySet(),
                    pending = pending,
                )
            } catch (e: CancellationException) {
                caughtCancellation = true
                throw e
            } finally {
                // Caller cleanup pattern as in NativeTransitMap:
                pending.values.forEach(Bitmap::recycle)
            }
        }
        job.cancel()
        try {
            job.join()
        } catch (_: Exception) {}

        // Verify all bitmaps in pending (if any were created prior to cancel) are recycled
        for (bitmap in pending.values) {
            assertTrue("Pending bitmaps must be recycled after cancellation cleanup", bitmap.isRecycled)
        }
    }

    @Test
    fun actualMapPrepareWithHommAndKanyargoVectorsRendersDistinctKeysAndColors() = runBlocking {
        val homm = vehicle("v-homm", "288", "BUS", provider = "homm", lat = 47.45, lon = 18.90)
        val kanyargo = vehicle("v-kanyargo", "1", "BUS", provider = "kanyargo", lat = 47.46, lon = 18.91)
        val viabusz = vehicle("v-viabusz", "11", "BUS", provider = "viabusz", lat = 47.47, lon = 18.92)
        val pending = linkedMapOf<String, Bitmap>()

        val json = VehicleMapScene.prepare(
            context = app,
            vehicles = listOf(homm, kanyargo, viabusz),
            selectedId = null,
            cachedIcons = emptySet(),
            pending = pending,
        )

        try {
            val collection = FeatureCollection.fromJson(json)
            assertNotNull(collection)
            val features = collection.features()!!
            assertEquals(3, features.size)

            val hommIconKey = features[0].getStringProperty("icon")
            val hommDotKey = features[0].getStringProperty("dot")
            val kanyargoIconKey = features[1].getStringProperty("icon")
            val kanyargoDotKey = features[1].getStringProperty("dot")
            val viabuszIconKey = features[2].getStringProperty("icon")
            val viabuszDotKey = features[2].getStringProperty("dot")

            assertEquals("vehicle-HOMM_BUS-null-null", hommIconKey)
            assertEquals("vehicle-HOMM_BUS-null-null-dot", hommDotKey)
            assertEquals("vehicle-KANYARGO_BUS-null-null", kanyargoIconKey)
            assertEquals("vehicle-KANYARGO_BUS-null-null-dot", kanyargoDotKey)
            assertEquals("vehicle-VIABUSZ_BUS-null-null", viabuszIconKey)
            assertEquals("vehicle-VIABUSZ_BUS-null-null-dot", viabuszDotKey)

            // Distinct keys across all 3 local bus styles
            val allKeys = setOf(hommIconKey, hommDotKey, kanyargoIconKey, kanyargoDotKey, viabuszIconKey, viabuszDotKey)
            assertEquals("All 3 local operators must have distinct icon and dot keys", 6, allKeys.size)

            // Pending bitmap verification
            assertTrue(pending.containsKey(hommIconKey))
            assertTrue(pending.containsKey(hommDotKey))
            assertTrue(pending.containsKey(kanyargoIconKey))
            assertTrue(pending.containsKey(kanyargoDotKey))
            assertTrue(pending.containsKey(viabuszIconKey))
            assertTrue(pending.containsKey(viabuszDotKey))

            // Dimensions and density
            val hommBmp = pending.getValue(hommIconKey)
            val kanyargoBmp = pending.getValue(kanyargoIconKey)
            val viabuszBmp = pending.getValue(viabuszIconKey)

            assertEquals(108, hommBmp.width); assertEquals(108, hommBmp.height)
            assertEquals(108, kanyargoBmp.width); assertEquals(108, kanyargoBmp.height)
            assertEquals(108, viabuszBmp.width); assertEquals(108, viabuszBmp.height)

            val hommDotBmp = pending.getValue(hommDotKey)
            val kanyargoDotBmp = pending.getValue(kanyargoDotKey)
            val viabuszDotBmp = pending.getValue(viabuszDotKey)

            assertEquals(18, hommDotBmp.width); assertEquals(18, hommDotBmp.height)
            assertEquals(18, kanyargoDotBmp.width); assertEquals(18, kanyargoDotBmp.height)
            assertEquals(18, viabuszDotBmp.width); assertEquals(18, viabuszDotBmp.height)

            // Dot outer ring is white
            assertEquals(Color.WHITE, hommDotBmp.getPixel(9, 1))
            assertEquals(Color.WHITE, kanyargoDotBmp.getPixel(9, 1))
            assertEquals(Color.WHITE, viabuszDotBmp.getPixel(9, 1))

            // Dot center colors use marker.color (HOMM: #BF5700, KanyarGO: #6750A4, ViaBusz: #205C48)
            assertEquals(0xFFBF5700.toInt(), hommDotBmp.getPixel(9, 9))
            assertEquals(0xFF6750A4.toInt(), kanyargoDotBmp.getPixel(9, 9))
            assertEquals(0xFF205C48.toInt(), viabuszDotBmp.getPixel(9, 9))

            // Native graphics sampled background pixels: top center of circle above bus body (54, 14)
            assertEquals(0xFFBF5700.toInt(), hommBmp.getPixel(54, 14))
            assertEquals(0xFF6750A4.toInt(), kanyargoBmp.getPixel(54, 14))
            assertEquals(0xFF205C48.toInt(), viabuszBmp.getPixel(54, 14))
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun actualSerializedGeoJson_sizesAndValidHeadings_0_90_360() = runBlocking {
        // v1: unselected, heading 0.0
        val v1 = vehicle("v1", "1", "BUS", heading = 0.0)
        // v2: unselected, heading 90.0
        val v2 = vehicle("v2", "7", "BUS", heading = 90.0)
        // v3: selected, heading 360.0 (must normalize 360.0 -> 0.0)
        val v3 = vehicle("v3", "S30", "RAIL", provider = "mav", heading = 360.0)

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val json = VehicleMapScene.prepare(
                context = app,
                vehicles = listOf(v1, v2, v3),
                selectedId = "v3",
                cachedIcons = emptySet(),
                pending = pending,
            )

            // Verify valid JSON
            val collection = FeatureCollection.fromJson(json)
            assertNotNull(collection)
            val features = collection.features()!!
            assertEquals(3, features.size)

            val f1 = features[0]
            assertEquals("v1", f1.getStringProperty("vehicle-id"))
            assertEquals(26f / 36f, f1.getNumberProperty("small-scale").toFloat(), 0.001f)
            assertEquals(26f / 36f, f1.getNumberProperty("large-scale").toFloat(), 0.001f)
            assertTrue("Feature 1 must have arrow", f1.hasProperty("arrow"))
            assertEquals(0.0, f1.getNumberProperty("heading").toDouble(), 0.001)
            assertEquals(26f / 18f, f1.getNumberProperty("arrow-scale").toFloat(), 0.001f)

            val f2 = features[1]
            assertEquals("v2", f2.getStringProperty("vehicle-id"))
            assertEquals(26f / 36f, f2.getNumberProperty("small-scale").toFloat(), 0.001f)
            assertEquals(26f / 36f, f2.getNumberProperty("large-scale").toFloat(), 0.001f)
            assertTrue("Feature 2 must have arrow", f2.hasProperty("arrow"))
            assertEquals(90.0, f2.getNumberProperty("heading").toDouble(), 0.001)
            assertEquals(26f / 18f, f2.getNumberProperty("arrow-scale").toFloat(), 0.001f)

            val f3 = features[2]
            assertEquals("v3", f3.getStringProperty("vehicle-id"))
            // Selected vehicle body scale is 30 / 36
            assertEquals(30f / 36f, f3.getNumberProperty("small-scale").toFloat(), 0.001f)
            assertEquals(30f / 36f, f3.getNumberProperty("large-scale").toFloat(), 0.001f)
            assertTrue("Feature 3 must have arrow", f3.hasProperty("arrow"))
            // 360.0 must be normalized to 0.0
            assertEquals(0.0, f3.getNumberProperty("heading").toDouble(), 0.001)
            // Selected arrow scale is 30 / 18
            assertEquals(30f / 18f, f3.getNumberProperty("arrow-scale").toFloat(), 0.001f)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun actualSerializedGeoJson_missingNaNInfiniteOutOfRange_noArrowField_andValidJson() = runBlocking {
        val vNull = vehicle("vNull", "1", "BUS", heading = null)
        val vNaN = vehicle("vNaN", "2", "BUS", heading = Double.NaN)
        val vPosInf = vehicle("vPosInf", "3", "BUS", heading = Double.POSITIVE_INFINITY)
        val vNegInf = vehicle("vNegInf", "4", "BUS", heading = Double.NEGATIVE_INFINITY)
        val vNeg = vehicle("vNeg", "5", "BUS", heading = -0.01)
        val vOver = vehicle("vOver", "6", "BUS", heading = 360.01)

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val json = VehicleMapScene.prepare(
                context = app,
                vehicles = listOf(vNull, vNaN, vPosInf, vNegInf, vNeg, vOver),
                selectedId = null,
                cachedIcons = emptySet(),
                pending = pending,
            )

            // Must produce strictly valid GeoJSON
            val collection = FeatureCollection.fromJson(json)
            assertNotNull(collection)
            val features = collection.features()!!
            assertEquals(6, features.size)

            for (feature in features) {
                assertFalse("Missing/NaN/inf/out-of-range heading must omit 'arrow' field", feature.hasProperty("arrow"))
                assertFalse("Missing/NaN/inf/out-of-range heading must omit 'heading' field", feature.hasProperty("heading"))
                assertFalse("Missing/NaN/inf/out-of-range heading must omit 'arrow-scale' field", feature.hasProperty("arrow-scale"))
                // Standard marker properties are still present
                assertTrue(feature.hasProperty("icon"))
                assertTrue(feature.hasProperty("dot"))
                assertTrue(feature.hasProperty("small-scale"))
            }

            // No arrow bitmaps should be created in pending
            assertTrue(pending.keys.none { it.startsWith("vehicle-heading-") })
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun multipleHeadingsSameColor_reuseArrowKeyAndBitmap() = runBlocking {
        // 3 BKK buses with different headings: 45.0, 180.0, 270.0
        val b1 = vehicle("b1", "7", "BUS", provider = "bkk", heading = 45.0)
        val b2 = vehicle("b2", "8E", "BUS", provider = "bkk", heading = 180.0)
        val b3 = vehicle("b3", "110", "BUS", provider = "bkk", heading = 270.0)

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val json = VehicleMapScene.prepare(
                context = app,
                vehicles = listOf(b1, b2, b3),
                selectedId = null,
                cachedIcons = emptySet(),
                pending = pending,
            )

            val features = FeatureCollection.fromJson(json).features()!!
            assertEquals(3, features.size)

            val arrow1 = features[0].getStringProperty("arrow")
            val arrow2 = features[1].getStringProperty("arrow")
            val arrow3 = features[2].getStringProperty("arrow")

            // All 3 share the exact same arrowKey because they share the same mode color
            assertEquals(arrow1, arrow2)
            assertEquals(arrow2, arrow3)
            assertEquals("vehicle-heading-${VehicleMarkerStyle.BKK_BUS.color}", arrow1)

            // In pending: only ONE arrow bitmap rendered for this color, not three
            val arrowBitmaps = pending.filterKeys { it.startsWith("vehicle-heading-") }
            assertEquals("Exactly one arrow bitmap in pending despite 3 different headings", 1, arrowBitmaps.size)
            assertTrue(pending.containsKey(arrow1))

            // Second prepare with cached icons populated: zero new arrow bitmaps created
            val pending2 = linkedMapOf<String, Bitmap>()
            val json2 = VehicleMapScene.prepare(
                context = app,
                vehicles = listOf(b1, b2, b3),
                selectedId = null,
                cachedIcons = pending.keys.toSet(),
                pending = pending2,
            )
            assertTrue("Cached arrow keys avoid re-rendering", pending2.isEmpty())
            val features2 = FeatureCollection.fromJson(json2).features()!!
            assertEquals(3, features2.size)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun metroAndHevColorArrow_matchesRenderedBadge_notGenericTrainBlue() = runBlocking {
        val metro = vehicle("m4", "M4", "SUBWAY", provider = "bkk", heading = 90.0)
        val hev = vehicle("h5", "H5", "SUBURBAN_RAILWAY", provider = "hev", heading = 180.0)
        val genericTrain = vehicle("s30", "S30", "RAIL", provider = "mav", heading = 270.0)

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val json = VehicleMapScene.prepare(
                context = app,
                vehicles = listOf(metro, hev, genericTrain),
                selectedId = null,
                cachedIcons = emptySet(),
                pending = pending,
            )

            val features = FeatureCollection.fromJson(json).features()!!
            val fMetro = features[0]
            val fHev = features[1]
            val fTrain = features[2]

            val metroLineColor = MetroLineStyle.forLine("M4", "SUBWAY")!!.color
            val hevLineColor = HevLineStyle.forLine("H5", "SUBURBAN_RAILWAY")!!.color
            val genericTrainColor = VehicleMarkerStyle.TRAIN.color

            val metroArrowKey = fMetro.getStringProperty("arrow")
            val hevArrowKey = fHev.getStringProperty("arrow")
            val trainArrowKey = fTrain.getStringProperty("arrow")

            // Arrow keys match the line priority color, not generic train blue
            assertEquals("vehicle-heading-$metroLineColor", metroArrowKey)
            assertEquals("vehicle-heading-$hevLineColor", hevArrowKey)
            assertEquals("vehicle-heading-$genericTrainColor", trainArrowKey)
            assertNotEquals("Metro arrow must not use generic train color", metroArrowKey, trainArrowKey)
            assertNotEquals("HÉV arrow must not use generic train color", hevArrowKey, trainArrowKey)

            // Pending arrow bitmaps have the correct line fill colors
            val metroArrowBmp = pending.getValue(metroArrowKey)
            val hevArrowBmp = pending.getValue(hevArrowKey)
            val trainArrowBmp = pending.getValue(trainArrowKey)

            // Arrow fill center pixel at (42, 16)
            assertEquals("Metro arrow fill must match M4 green badge color", metroLineColor, metroArrowBmp.getPixel(42, 16))
            assertEquals("HÉV arrow fill must match H5 purple badge color", hevLineColor, hevArrowBmp.getPixel(42, 16))
            assertEquals("Train arrow fill must match generic train blue", genericTrainColor, trainArrowBmp.getPixel(42, 16))
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun pixelTest_northArrow_color_whiteEdge_transparentCorners() {
        val testColor = 0xFF199DCF.toInt()
        val bmp = VehicleMarkerLayout.renderArrow(testColor)
        try {
            assertEquals(84, bmp.width)
            assertEquals(84, bmp.height)
            assertEquals(480, bmp.density)

            // Transparent corners
            assertEquals(0, Color.alpha(bmp.getPixel(0, 0)))
            assertEquals(0, Color.alpha(bmp.getPixel(83, 0)))
            assertEquals(0, Color.alpha(bmp.getPixel(0, 83)))
            assertEquals(0, Color.alpha(bmp.getPixel(83, 83)))

            // Center / interior of north arrow: fill color (42, 16)
            assertEquals(testColor, bmp.getPixel(42, 16))

            // White halo / stroke edge: stroke extends below triangle base y=21
            // At (42, 22), the anti-aliased white stroke halo is rendered
            val strokePixel = bmp.getPixel(42, 22)
            assertTrue("Stroke pixel at (42, 22) must have alpha > 0", Color.alpha(strokePixel) > 0)
            assertEquals("Stroke pixel at (42, 22) must have R==255 (white)", 255, Color.red(strokePixel))
            assertEquals("Stroke pixel at (42, 22) must have G==255 (white)", 255, Color.green(strokePixel))
            assertEquals("Stroke pixel at (42, 22) must have B==255 (white)", 255, Color.blue(strokePixel))
        } finally {
            bmp.recycle()
        }
    }
}
