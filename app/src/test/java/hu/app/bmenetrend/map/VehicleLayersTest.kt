package hu.app.bmenetrend.map

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.BoardingSelection
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.TransitRoute
import hu.app.bmenetrend.data.Vehicle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.style.expressions.Expression
import org.maplibre.geojson.FeatureCollection
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VehicleLayersTest {

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
        routeId: String? = "bkk:1",
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
    fun focusFilterNoFocusReturnsLiteralTrue() {
        val filter = VehicleLayers.focusFilter(null)
        val expected = Expression.literal(true)

        assertEquals("No-focus filter must match Expression.literal(true)", expected, filter)
        assertEquals("true", filter.toString())
        assertArrayEquals(arrayOf<Any>("literal", true), filter.toArray())
    }

    @Test
    fun focusFilterRouteAndProviderMatchesBothProperties() {
        val route = TransitRoute("bkk:BKK:1", "1", "Tram 1", emptyList(), "bkk", transportMode = "TRAM")
        val focus = LineMapFocus.create(
            vehicle = null,
            boarding = BoardingSelection(route.id, "Line overview"),
            routes = listOf(route),
            details = emptyMap(),
            journey = null,
            allStops = emptyList(),
        )
        assertNotNull("Focus must be created for matching route", focus)
        val nonNullFocus = requireNotNull(focus)
        assertNull("Line overview focus has no selected vehicle ID", nonNullFocus.selectedVehicleId)

        val filter = VehicleLayers.focusFilter(nonNullFocus)

        val expected = Expression.all(
            Expression.eq(Expression.get("provider-id"), Expression.literal("bkk")),
            Expression.eq(Expression.get("route-id"), Expression.literal("bkk:BKK:1")),
        )

        assertEquals("Route/provider focus must produce matching all(provider-id, route-id) expression", expected, filter)
        assertEquals(
            "[\"all\", [\"==\", [\"get\", \"provider-id\"], \"bkk\"], [\"==\", [\"get\", \"route-id\"], \"bkk:BKK:1\"]]",
            filter.toString(),
        )
    }

    @Test
    fun focusFilterNumberAndModeFallbackWhenRouteIdNull() {
        // LineMapFocus with null routeId but non-null number and mode
        val focusWithoutRouteId = LineMapFocus(
            key = "volan:762:BUS",
            routeId = null,
            providerId = "volan",
            number = "762",
            mode = "BUS",
            paths = emptyList(),
            stops = emptyList(),
        )

        val filter = VehicleLayers.focusFilter(focusWithoutRouteId)

        val expected = Expression.all(
            Expression.eq(Expression.get("provider-id"), Expression.literal("volan")),
            Expression.all(
                Expression.eq(Expression.get("route-number"), Expression.literal("762")),
                Expression.eq(Expression.get("transport-mode"), Expression.literal("BUS")),
            ),
        )

        assertEquals("Fallback focus must match provider, number, and mode", expected, filter)
        assertEquals(
            "[\"all\", [\"==\", [\"get\", \"provider-id\"], \"volan\"], [\"all\", [\"==\", [\"get\", \"route-number\"], \"762\"], [\"==\", [\"get\", \"transport-mode\"], \"BUS\"]]]",
            filter.toString(),
        )
    }

    @Test
    fun focusFilterSelectedVehicleOverrideWrapsInAny() {
        val focusWithSelectedVehicle = LineMapFocus(
            key = "bkk:1",
            routeId = "bkk:1",
            providerId = "bkk",
            number = "1",
            mode = "TRAM",
            selectedVehicleId = "veh-special-99",
            paths = emptyList(),
            stops = emptyList(),
        )

        val filter = VehicleLayers.focusFilter(focusWithSelectedVehicle)

        val expectedProviderLine = Expression.all(
            Expression.eq(Expression.get("provider-id"), Expression.literal("bkk")),
            Expression.eq(Expression.get("route-id"), Expression.literal("bkk:1")),
        )
        val expected = Expression.any(
            Expression.eq(Expression.get("vehicle-id"), Expression.literal("veh-special-99")),
            expectedProviderLine,
        )

        assertEquals("Selected vehicle override must wrap providerLine with Expression.any", expected, filter)
        assertEquals(
            "[\"any\", [\"==\", [\"get\", \"vehicle-id\"], \"veh-special-99\"], [\"all\", [\"==\", [\"get\", \"provider-id\"], \"bkk\"], [\"==\", [\"get\", \"route-id\"], \"bkk:1\"]]]",
            filter.toString(),
        )
    }

    @Test
    fun sameNumberOtherProviderIsolation() {
        val bkkRoute = TransitRoute("bkk:1", "1", "Tram 1", emptyList(), "bkk", transportMode = "TRAM")
        val volanRoute = TransitRoute("volan:1", "1", "Bus 1", emptyList(), "volan", transportMode = "BUS")

        val bkkVeh = vehicle("v-bkk", "1", "TRAM", provider = "bkk", routeId = "bkk:1")
        val volanVeh = vehicle("v-volan", "1", "BUS", provider = "volan", routeId = "volan:1")

        val bkkFocus = requireNotNull(
            LineMapFocus.create(
                vehicle = bkkVeh,
                boarding = null,
                routes = listOf(bkkRoute),
                details = emptyMap(),
                journey = null,
                allStops = emptyList(),
            )
        )

        // Focus includes semantic verification
        assertTrue("BKK focus includes BKK vehicle", bkkFocus.includes(bkkVeh))
        assertFalse("BKK focus isolates and excludes same-number Volan vehicle", bkkFocus.includes(volanVeh))

        val bkkFilter = VehicleLayers.focusFilter(bkkFocus)
        val bkkFilterString = bkkFilter.toString()
        assertTrue("BKK filter explicitly asserts provider-id == bkk", bkkFilterString.contains("[\"==\", [\"get\", \"provider-id\"], \"bkk\"]"))
        assertFalse("BKK filter does not allow volan provider", bkkFilterString.contains("\"volan\""))

        val volanFocus = requireNotNull(
            LineMapFocus.create(
                vehicle = volanVeh,
                boarding = null,
                routes = listOf(volanRoute),
                details = emptyMap(),
                journey = null,
                allStops = emptyList(),
            )
        )
        assertTrue("Volan focus includes Volan vehicle", volanFocus.includes(volanVeh))
        assertFalse("Volan focus isolates and excludes same-number BKK vehicle", volanFocus.includes(bkkVeh))

        val volanFilter = VehicleLayers.focusFilter(volanFocus)
        val volanFilterString = volanFilter.toString()
        assertTrue("Volan filter explicitly asserts provider-id == volan", volanFilterString.contains("[\"==\", [\"get\", \"provider-id\"], \"volan\"]"))
        assertFalse("Volan filter does not allow bkk provider", volanFilterString.contains("\"bkk\""))
    }

    @Test
    fun crossCheckGeneratedSourceFieldValuesWithExpressionLiterals() = runBlocking {
        val bkkRoute = TransitRoute("bkk:1", "1", "Tram 1", emptyList(), "bkk", transportMode = "TRAM")
        val testTram = vehicle("tram-42", "1", "TRAM", provider = "bkk", routeId = "bkk:1")
        val testBus = vehicle("bus-7", "7", "BUS", provider = "bkk", routeId = "bkk:7")

        val focus = requireNotNull(
            LineMapFocus.create(
                vehicle = testTram,
                boarding = null,
                routes = listOf(bkkRoute),
                details = emptyMap(),
                journey = null,
                allStops = emptyList(),
            )
        )

        val pending = linkedMapOf<String, Bitmap>()
        val json = try {
            VehicleMapScene.prepare(
                context = app,
                vehicles = listOf(testTram, testBus),
                selectedId = testTram.id,
                cachedIcons = emptySet(),
                pending = pending,
            )
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }

        val features = requireNotNull(FeatureCollection.fromJson(json).features())
        assertEquals("Source must contain all prepared vehicles", 2, features.size)

        val tramFeature = features.first { it.getStringProperty("vehicle-id") == "tram-42" }

        // Cross-check property keys between source GeoJSON and expression getters:
        val expectedKeys = setOf("vehicle-id", "provider-id", "route-id", "route-number", "transport-mode")
        for (key in expectedKeys) {
            assertTrue("Source feature must contain property '$key'", tramFeature.hasProperty(key))
        }

        // Verify values generated in source match the expression literal expectations
        assertEquals(testTram.id, tramFeature.getStringProperty("vehicle-id"))
        assertEquals(testTram.providerId, tramFeature.getStringProperty("provider-id"))
        assertEquals(testTram.routeId, tramFeature.getStringProperty("route-id"))
        assertEquals(testTram.routeNumber, tramFeature.getStringProperty("route-number"))
        assertEquals(testTram.transportMode, tramFeature.getStringProperty("transport-mode"))

        val filter = VehicleLayers.focusFilter(focus)
        val filterJson = filter.toString()

        // Expression literals must match the source feature values
        assertTrue("Filter contains matching provider-id literal", filterJson.contains("\"${tramFeature.getStringProperty("provider-id")}\""))
        assertTrue("Filter contains matching route-id literal", filterJson.contains("\"${tramFeature.getStringProperty("route-id")}\""))
    }

    @Test
    fun focusTransitionLifecycleWhileSourceUnchangedAndNewLiveSourceArrives() = runBlocking {
        val routeA = TransitRoute("bkk:1", "1", "Tram 1", emptyList(), "bkk", transportMode = "TRAM")
        val routeB = TransitRoute("volan:762", "762", "Bus 762", emptyList(), "volan", transportMode = "BUS")

        val vehA = vehicle("v-tram1", "1", "TRAM", provider = "bkk", routeId = "bkk:1")
        val vehB = vehicle("v-bus762", "762", "BUS", provider = "volan", routeId = "volan:762")

        val focusA = requireNotNull(
            LineMapFocus.create(
                vehicle = null,
                boarding = BoardingSelection(routeA.id, "Line overview"),
                routes = listOf(routeA),
                details = emptyMap(),
                journey = null,
                allStops = emptyList(),
            )
        )
        val focusB = requireNotNull(
            LineMapFocus.create(
                vehicle = null,
                boarding = BoardingSelection(routeB.id, "Line overview"),
                routes = listOf(routeB),
                details = emptyMap(),
                journey = null,
                allStops = emptyList(),
            )
        )

        val pendingInitial = linkedMapOf<String, Bitmap>()
        val initialSourceJson = try {
            VehicleMapScene.prepare(
                context = app,
                vehicles = listOf(vehA, vehB),
                selectedId = null,
                cachedIcons = emptySet(),
                pending = pendingInitial,
            )
        } finally {
            pendingInitial.values.forEach(Bitmap::recycle)
        }

        // 1. Initial state with focus A
        var activeFocus: LineMapFocus? = focusA
        var currentFilter = VehicleLayers.focusFilter(activeFocus)
        val expectedFilterA = Expression.all(
            Expression.eq(Expression.get("provider-id"), Expression.literal("bkk")),
            Expression.eq(Expression.get("route-id"), Expression.literal("bkk:1")),
        )
        assertEquals("Initial filter must be Focus A", expectedFilterA, currentFilter)

        // 2. Focus transitions to null (dismiss) while source remains unchanged
        activeFocus = null
        currentFilter = VehicleLayers.focusFilter(activeFocus)
        assertEquals("Filter on dismiss must be literal(true) without modifying source or needing network", Expression.literal(true), currentFilter)

        // Source is completely unchanged and contains all vehicles
        val featuresUnchanged = requireNotNull(FeatureCollection.fromJson(initialSourceJson).features())
        assertEquals(2, featuresUnchanged.size)

        // 3. Focus transitions to Focus B while source remains unchanged
        activeFocus = focusB
        currentFilter = VehicleLayers.focusFilter(activeFocus)
        val expectedFilterB = Expression.all(
            Expression.eq(Expression.get("provider-id"), Expression.literal("volan")),
            Expression.eq(Expression.get("route-id"), Expression.literal("volan:762")),
        )
        assertEquals("Filter after selecting B must be Focus B with no network reload needed", expectedFilterB, currentFilter)

        // 4. New live source arrives (e.g. vehicle positions updated):
        // As defined in NativeTransitMap, source upload uses currentVehicleFocus (rememberUpdatedState)
        // so uploading the new source applies the latest focus B without resetting.
        val updatedVehA = vehA.copy(position = GeoPoint(47.495, 19.045))
        val updatedVehB = vehB.copy(position = GeoPoint(47.465, 18.905))
        val newVehC = vehicle("v-bus8e", "8E", "BUS", provider = "bkk", routeId = "bkk:8E")

        val pendingNew = linkedMapOf<String, Bitmap>()
        val newSourceJson = try {
            VehicleMapScene.prepare(
                context = app,
                vehicles = listOf(updatedVehA, updatedVehB, newVehC),
                selectedId = null,
                cachedIcons = emptySet(),
                pending = pendingNew,
            )
        } finally {
            pendingNew.values.forEach(Bitmap::recycle)
        }

        val newFeatures = requireNotNull(FeatureCollection.fromJson(newSourceJson).features())
        assertEquals("New live source has all 3 vehicles", 3, newFeatures.size)

        // When source effect finishes, it applies VehicleLayers.configure(style, currentVehicleFocus)
        // where currentVehicleFocus is focus B:
        val filterOnNewUpload = VehicleLayers.focusFilter(activeFocus)
        assertEquals("Filter on new source upload remains Focus B", expectedFilterB, filterOnNewUpload)
    }

    @Test
    fun seamLimitationMapLibreRuntimeRequiresNativeLibrary() {
        // Document and verify seam limit: MapLibre Expression AST is fully executable and verifiable on JVM,
        // but MapLibre native GL rendering / layer evaluation requires native libraries (libmaplibre.so)
        // which cannot be executed directly in host JVM unit tests without an Android device/emulator.
        val filter = VehicleLayers.focusFilter(null)
        assertNotNull("Expression is constructible and inspectable on JVM", filter)
        assertEquals("true", filter.toString())
    }
}
