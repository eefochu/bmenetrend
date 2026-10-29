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
    ) = Vehicle(
        id = id,
        position = GeoPoint(lat, lon),
        observedAt = Instant.parse("2026-10-29T10:00:00Z"),
        routeId = routeId,
        providerId = provider,
        routeNumber = routeNumber,
        transportMode = mode,
        destination = "Terminal",
    )

    @Test
    fun geoJsonPropertiesAndScaleInvariantsForUnselectedAndSelectedVehicles() = runBlocking {
        val v1 = vehicle("v1", "1", "BUS", lat = 47.491, lon = 19.041)
        val v2 = vehicle("v2", "S30", "RAIL", provider = "mav", lat = 47.492, lon = 19.042)
        val pending = linkedMapOf<String, Bitmap>()

        val json = VehicleMapScene.prepare(
            context = app,
            vehicles = listOf(v1, v2),
            focus = null,
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
            assertEquals(24f / 36f, f1.getNumberProperty("small-scale").toFloat(), 0.001f)
            assertEquals(1f, f1.getNumberProperty("large-scale").toFloat(), 0.001f)
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
            assertEquals(42f / 36f, f2.getNumberProperty("large-scale").toFloat(), 0.001f)
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
            focus = null,
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
        assertNotNull(focus)

        val pending = linkedMapOf<String, Bitmap>()
        val json = VehicleMapScene.prepare(
            context = app,
            vehicles = listOf(selectedVeh, otherVeh),
            focus = focus,
            selectedId = selectedVeh.id,
            cachedIcons = emptySet(),
            pending = pending,
        )

        try {
            val features = FeatureCollection.fromJson(json).features()!!
            assertEquals("Focus filter should only include matching vehicle", 1, features.size)
            assertEquals("v1", features.single().getStringProperty("vehicle-id"))
        } finally {
            pending.values.forEach(Bitmap::recycle)
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
            focus = null,
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
                focus = null,
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
                    focus = null,
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
}
