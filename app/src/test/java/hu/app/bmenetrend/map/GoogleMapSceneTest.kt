package hu.app.bmenetrend.map

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.MapBounds
import hu.app.bmenetrend.data.RouteDirection
import hu.app.bmenetrend.data.TransitRoute
import hu.app.bmenetrend.data.TransitStop
import hu.app.bmenetrend.data.Vehicle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GoogleMapSceneTest {

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
        observedAt = Instant.parse("2026-11-11T12:00:00Z"),
        routeId = routeId,
        providerId = provider,
        routeNumber = routeNumber,
        transportMode = mode,
        destination = "Terminal",
        heading = heading,
    )

    private fun stop(
        id: String,
        name: String,
        lat: Double,
        lon: Double,
        provider: String = "bkk",
        mode: String? = null,
        routes: List<String> = emptyList(),
        parentStationId: String? = null,
    ) = TransitStop(
        id = id,
        name = name,
        position = GeoPoint(lat, lon),
        routeIds = routes,
        providerId = provider,
        transportMode = mode,
        parentStationId = parentStationId,
    )

    @Test
    fun regionalRetentionRetainsAllModesAndLinesWhileFocusIsActive() = runBlocking {
        // Bounding region around central Budapest
        val region = MapBounds(19.00, 47.45, 19.10, 47.55)

        // Vehicles of diverse modes and lines within region
        val matchingTram = vehicle("tram-1", "1", "TRAM", "bkk", 47.49, 19.04, routeId = "bkk:1")
        val otherTram = vehicle("tram-2", "2", "TRAM", "bkk", 47.50, 19.05, routeId = "bkk:2")
        val bus7 = vehicle("bus-7", "7", "BUS", "bkk", 47.48, 19.03, routeId = "bkk:7")
        val railS30 = vehicle("rail-s30", "S30", "RAIL", "mav", 47.47, 19.02, routeId = "mav:s30")
        val metroM4 = vehicle("metro-m4", "M4", "SUBWAY", "bkk", 47.46, 19.01, routeId = "bkk:m4")

        // Unselected vehicle outside the regional bounds
        val outsideBus = vehicle("bus-100e", "100E", "BUS", "bkk", 47.42, 19.25, routeId = "bkk:100e")

        // Active line focus on Tram 1
        val focusTram1 = LineMapFocus(
            key = "focus:bkk:1",
            routeId = "bkk:1",
            providerId = "bkk",
            number = "1",
            mode = "TRAM",
            paths = emptyList(),
            stops = emptyList(),
            selectedVehicleId = matchingTram.id,
        )

        // Verify focus filter baseline: only matchingTram satisfies focus.includes
        assertTrue("matchingTram must match line focus", focusTram1.includes(matchingTram))
        assertFalse("otherTram must not match line focus", focusTram1.includes(otherTram))
        assertFalse("bus7 must not match line focus", focusTram1.includes(bus7))
        assertFalse("railS30 must not match line focus", focusTram1.includes(railS30))
        assertFalse("metroM4 must not match line focus", focusTram1.includes(metroM4))

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = listOf(matchingTram, otherTram, bus7, railS30, metroM4, outsideBus),
                region = region,
                selectedVehicleId = matchingTram.id,
                stops = emptyList(),
                focus = focusTram1,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 14.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = false,
                cached = emptySet(),
                pending = pending,
            )

            assertEquals("focus:bkk:1", scene.focusKey)

            // Extract vehicle marker IDs
            val vehicleMarkerIds = scene.markers
                .mapNotNull { it.vehicle?.id }
                .toSet()

            // Regional retention: all vehicles within region must enter marker pool across all modes & lines
            assertEquals(5, vehicleMarkerIds.size)
            assertTrue("matchingTram must be in scene markers", vehicleMarkerIds.contains("tram-1"))
            assertTrue("otherTram must be in scene markers", vehicleMarkerIds.contains("tram-2"))
            assertTrue("bus7 must be in scene markers", vehicleMarkerIds.contains("bus-7"))
            assertTrue("railS30 must be in scene markers", vehicleMarkerIds.contains("rail-s30"))
            assertTrue("metroM4 must be in scene markers", vehicleMarkerIds.contains("metro-m4"))

            // Vehicle outside the region must not enter the pool
            assertFalse("outsideBus must not enter the marker pool", vehicleMarkerIds.contains("bus-100e"))

            // Selection state on marker spec
            val matchingSpec = scene.markers.first { it.vehicle?.id == "tram-1" }
            assertTrue(matchingSpec.selected)
            val otherSpec = scene.markers.first { it.vehicle?.id == "tram-2" }
            assertFalse(otherSpec.selected)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun selectedVehicleOutsideRegionIsRetainedWithSelectionState() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.10, 47.55)

        val insideVeh = vehicle("veh-inside", "5", "BUS", lat = 47.49, lon = 19.05)
        val selectedOutsideVeh = vehicle("veh-outside-sel", "11", "BUS", lat = 47.40, lon = 19.30)
        val unselectedOutsideVeh = vehicle("veh-outside-unsel", "12", "BUS", lat = 47.38, lon = 19.35)

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = listOf(insideVeh, selectedOutsideVeh, unselectedOutsideVeh),
                region = region,
                selectedVehicleId = selectedOutsideVeh.id,
                stops = emptyList(),
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 13.5,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = false,
                cached = emptySet(),
                pending = pending,
            )

            val markerMap = scene.markers.associateBy { it.id }

            // Inside vehicle is retained
            assertTrue(markerMap.containsKey("vehicle:veh-inside"))
            assertFalse(markerMap.getValue("vehicle:veh-inside").selected)

            // Selected vehicle outside region is retained and flagged as selected
            assertTrue("Selected vehicle outside region must be retained in scene", markerMap.containsKey("vehicle:veh-outside-sel"))
            val selectedSpec = markerMap.getValue("vehicle:veh-outside-sel")
            assertTrue("Marker spec for selected vehicle must have selected=true", selectedSpec.selected)
            assertEquals(selectedOutsideVeh.position, selectedSpec.point)
            assertEquals(selectedOutsideVeh, selectedSpec.vehicle)

            // Unselected vehicle outside region is excluded
            assertFalse("Unselected vehicle outside region must not be retained", markerMap.containsKey("vehicle:veh-outside-unsel"))
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun focusStopsGroupingAndSelection() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.10, 47.55)

        // Two rail platform stops sharing parentStationId (within clustering distance)
        val stopKeleti1 = stop("stop-keleti-1", "Keleti pályaudvar", 47.5001, 19.0838, provider = "mav", mode = "RAIL", routes = listOf("mav:s30"), parentStationId = "station-keleti")
        val stopKeleti2 = stop("stop-keleti-2", "Keleti pályaudvar", 47.5005, 19.0842, provider = "mav", mode = "RAIL", routes = listOf("mav:s30"), parentStationId = "station-keleti")
        // Another station stop further away along the line
        val stopKelenfold = stop("stop-kelenfold", "Kelenföld vasútállomás", 47.4645, 19.0210, provider = "mav", mode = "RAIL", routes = listOf("mav:s30"), parentStationId = "station-kelenfold")

        // Unrelated general network stop outside the line focus
        val unrelatedStop = stop("stop-unrelated", "Unrelated Stop", 47.4800, 19.0300, mode = "BUS")

        val focus = LineMapFocus(
            key = "focus:mav:s30",
            routeId = "mav:s30",
            providerId = "mav",
            number = "S30",
            mode = "RAIL",
            paths = emptyList(),
            stops = listOf(stopKeleti1, stopKeleti2, stopKelenfold),
        )

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = emptyList(),
                region = region,
                selectedVehicleId = null,
                stops = listOf(stopKeleti1, stopKeleti2, stopKelenfold, unrelatedStop),
                focus = focus,
                highlightedStopId = stopKeleti1.id,
                routes = mapOf(
                    "mav:s30" to TransitRoute(
                        id = "mav:s30",
                        number = "S30",
                        name = "S30 Line",
                        directions = listOf(RouteDirection("0", "Kelenföld", listOf("stop-keleti-1", "stop-keleti-2", "stop-kelenfold"))),
                        providerId = "mav",
                        transportMode = "RAIL",
                        color = "#0066AA",
                    ),
                ),
                bearings = emptyMap(),
                zoom = 14.5,
                dark = false,
                routeColor = 0xFF0066AA.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
            )

            assertEquals("focus:mav:s30", scene.focusKey)

            val stopMarkers = scene.markers.filter { it.id.startsWith("stop:") }
            assertEquals("Expected 2 grouped stop markers (Keleti station group + Kelenföld)", 2, stopMarkers.size)

            // Verify unrelated general network stop is NOT present
            assertFalse(
                "Unrelated background stop must not be included under line focus",
                stopMarkers.any { marker -> marker.stops.any { it.id == "stop-unrelated" } }
            )

            // Verify clustering/grouping: Keleti platforms should be merged into one station marker
            val keletiGroupMarker = stopMarkers.firstOrNull { marker ->
                marker.stops.any { it.id == "stop-keleti-1" }
            }
            assertNotNull("Keleti platform group marker must exist", keletiGroupMarker)
            assertTrue("Keleti marker must group both platform stops", keletiGroupMarker!!.stops.any { it.id == "stop-keleti-2" })
            assertEquals(2, keletiGroupMarker.stops.size)

            // Verify selection state: highlightedStopId (stop-keleti-1) is in keletiGroupMarker
            assertTrue("Group containing highlightedStopId must be selected", keletiGroupMarker.selected)

            // Verify Kelenföld marker is not selected
            val kelenfoldMarker = stopMarkers.first { marker -> marker.stops.any { it.id == "stop-kelenfold" } }
            assertFalse("Kelenföld marker must not be selected", kelenfoldMarker.selected)
            assertEquals(1, kelenfoldMarker.stops.size)

            // Scale reflects focused layout
            val expectedScale = StopMarkerLayout.diameterDp(14.5, highlighted = true).toFloat() / StopMarkerLayout.DIAMETER_DP.toFloat()
            assertEquals(expectedScale, keletiGroupMarker.scale, 0.001f)
            assertEquals(expectedScale, kelenfoldMarker.scale, 0.001f)

            // Also verify that when focus is active, displayStops are shown even if showStops=false
            val sceneNoStops = GoogleMapScene.prepare(
                context = app,
                vehicles = emptyList(),
                region = region,
                selectedVehicleId = null,
                stops = listOf(stopKeleti1, stopKeleti2, stopKelenfold, unrelatedStop),
                focus = focus,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 14.5,
                dark = false,
                routeColor = 0xFF0066AA.toInt(),
                showStops = false,
                cached = emptySet(),
                pending = pending,
            )
            val stopMarkersNoStops = sceneNoStops.markers.filter { it.id.startsWith("stop:") }
            assertEquals("Focus stops must be displayed even when showStops is false", 2, stopMarkersNoStops.size)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun denseStopFixture_matchesOldMapStopGroupingAndPreservesAllMemberIdentitiesAndVehicles() = runBlocking {
        // Overview region across Budapest metropolitan area
        val region = MapBounds(19.00, 47.45, 19.20, 47.60)

        // Dense fixture: >2000 distinct stop locations visible within this region
        val denseStops = (1..2100).map { i ->
            val lat = 47.452 + (i % 70) * (0.145 / 70.0)
            val lon = 19.002 + (i / 70) * (0.195 / 30.0)
            stop(
                id = "dense-stop-$i",
                name = "Stop $i",
                lat = lat,
                lon = lon,
                provider = if (i % 2 == 0) "bkk" else "volan",
                mode = when (i % 4) {
                    0 -> "BUS"
                    1 -> "TRAM"
                    2 -> "SUBWAY"
                    else -> "TROLLEYBUS"
                },
                routes = listOf("route-${i % 20}")
            )
        }
        assertEquals(2100, denseStops.size)

        // Vehicles of all nearby types present in region
        val nearbyVehicles = listOf(
            vehicle("v-bus", "7", "BUS", lat = 47.48, lon = 19.05),
            vehicle("v-tram", "4", "TRAM", lat = 47.49, lon = 19.06),
            vehicle("v-subway", "M3", "SUBWAY", lat = 47.50, lon = 19.07),
            vehicle("v-rail", "S10", "RAIL", provider = "mav", lat = 47.47, lon = 19.02)
        )

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = nearbyVehicles,
                region = region,
                selectedVehicleId = null,
                stops = denseStops,
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 14.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
            )

            val stopMarkers = scene.markers.filter { it.stops.isNotEmpty() }
            val vehicleMarkers = scene.markers.mapNotNull { it.vehicle }

            // 1. Compare Google stopgroup membership with actual old map groups + Rail merge for the same input
            val expectedGroups = RailStationMarkers.mergePlatforms(
                StopMapClustering.groups(denseStops, region, 14.0, emptyMap())
            )
            val actualGroups = stopMarkers.map { it.stops.map { s -> s.id }.toSet() }.toSet()
            val expectedGroupIds = expectedGroups.map { g -> g.map { s -> s.id }.toSet() }.toSet()
            assertEquals("Stop grouping must match old MapLibre StopMapClustering + RailStationMarkers exactly", expectedGroupIds, actualGroups)

            // 2. No numbered cluster IDs; all stop marker IDs start with stop:
            assertTrue(
                "All stop markers must have ID starting with stop:, never cluster:",
                stopMarkers.all { it.id.startsWith("stop:") && !it.id.startsWith("cluster:") }
            )

            // 3. Distant distinct platforms are NOT merged
            val firstStopMarker = stopMarkers.first { m -> m.stops.any { it.id == denseStops.first().id } }
            val lastStopMarker = stopMarkers.first { m -> m.stops.any { it.id == denseStops.last().id } }
            assertNotEquals("Distant stops at opposite ends of region must not be merged", firstStopMarker.id, lastStopMarker.id)
            assertFalse(firstStopMarker.stops.any { it.id == denseStops.last().id })

            // 4. All member identities preserved at detail zoom
            val recoveredStopIds = stopMarkers.flatMap { it.stops }.map { it.id }.toSet()
            val expectedStopIds = denseStops.map { it.id }.toSet()
            assertEquals(
                "All 2100 stop member identities must remain recoverable across stop markers",
                expectedStopIds,
                recoveredStopIds
            )
            assertEquals("Total member stop count must equal input stops count without drop or duplicate", 2100, stopMarkers.sumOf { it.stops.size })

            // 5. Vehicles of all nearby types are retained
            assertEquals("All 4 nearby vehicles must be present in scene", 4, vehicleMarkers.size)
            val retainedModes = vehicleMarkers.map { it.transportMode }.toSet()
            assertEquals(setOf("BUS", "TRAM", "SUBWAY", "RAIL"), retainedModes)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun focusedRouteStops_stayIndividuallyMeaningful_withOnlyColocatedGrouped_andVehiclesRetained() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.20, 47.60)

        // Focused route with stops along the line (10 distinct locations, including 1 co-located pair)
        val stop1a = stop("route-stop-1a", "Keleti pu M", 47.5001, 19.0838, provider = "mav", mode = "RAIL", parentStationId = "st-keleti")
        val stop1b = stop("route-stop-1b", "Keleti pu M", 47.5005, 19.0842, provider = "bkk", mode = "RAIL", parentStationId = "st-keleti")
        val otherStops = (2..10).map { i ->
            stop("route-stop-$i", "Station $i", 47.45 + i * 0.01, 19.01 + i * 0.015, mode = "RAIL")
        }
        val routeStops = listOf(stop1a, stop1b) + otherStops

        // Dense background stops (>2000) that should not interfere with route focus
        val backgroundStops = (1..2100).map { i ->
            stop("bg-stop-$i", "BG Stop $i", 47.451 + (i % 70) * (0.145 / 70.0), 19.001 + (i / 70) * (0.195 / 30.0))
        }

        val focus = LineMapFocus(
            key = "focus:mav:s10",
            routeId = "mav:s10",
            providerId = "mav",
            number = "S10",
            mode = "RAIL",
            paths = emptyList(),
            stops = routeStops,
        )

        val nearbyVehicles = listOf(
            vehicle("v-bus", "7", "BUS", lat = 47.48, lon = 19.05),
            vehicle("v-tram", "4", "TRAM", lat = 47.49, lon = 19.06),
            vehicle("v-subway", "M3", "SUBWAY", lat = 47.50, lon = 19.07),
            vehicle("v-rail", "S10", "RAIL", provider = "mav", lat = 47.47, lon = 19.02)
        )

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = nearbyVehicles,
                region = region,
                selectedVehicleId = null,
                stops = routeStops + backgroundStops,
                focus = focus,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 13.0,
                dark = false,
                routeColor = 0xFF0066AA.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
            )

            val stopMarkers = scene.markers.filter { it.id.startsWith("stop:") }
            val vehicleMarkers = scene.markers.mapNotNull { it.vehicle }

            // Focused route stops must stay individually meaningful:
            // exactly 10 markers (1a + 1b merged into 1 co-located group, plus 9 other individual stops)
            assertEquals("Focused route stops must not be grid clustered; only co-located platforms merge", 10, stopMarkers.size)

            val keletiGroup = stopMarkers.firstOrNull { marker -> marker.stops.any { it.id == "route-stop-1a" } }
            assertNotNull("Co-located group must exist", keletiGroup)
            assertEquals(2, keletiGroup!!.stops.size)
            assertTrue(keletiGroup.stops.any { it.id == "route-stop-1b" })

            // Vehicles of all nearby types retained
            assertEquals(4, vehicleMarkers.size)
            assertEquals(setOf("BUS", "TRAM", "SUBWAY", "RAIL"), vehicleMarkers.map { it.transportMode }.toSet())
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun stopGrouping_matchesOldMapGrouping_withNoNumberedClustersAndFullIdentityPreserved() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.20, 47.60)
        // 300 stops spread across region
        val stops = (1..300).map { i ->
            stop("stop-identity-$i", "Stop $i", 47.452 + (i % 20) * 0.007, 19.002 + (i / 20) * 0.013)
        }

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = emptyList(),
                region = region,
                selectedVehicleId = null,
                stops = stops,
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 14.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
            )

            val stopMarkers = scene.markers.filter { it.stops.isNotEmpty() }

            // 1. Compare Google stopgroup membership with actual old map groups + Rail merge for the same input
            val expectedGroups = RailStationMarkers.mergePlatforms(
                StopMapClustering.groups(stops, region, 14.0, emptyMap())
            )
            val actualGroups = stopMarkers.map { it.stops.map { s -> s.id }.toSet() }.toSet()
            val expectedGroupIds = expectedGroups.map { g -> g.map { s -> s.id }.toSet() }.toSet()
            assertEquals("Stop groups must match old MapLibre clustering + Rail merge exactly", expectedGroupIds, actualGroups)

            // 2. All stop marker IDs start with stop: (no numbered cluster IDs like cluster:)
            assertTrue("Every stop marker ID must start with stop:", stopMarkers.all { it.id.startsWith("stop:") })
            assertFalse("No marker ID may start with cluster:", stopMarkers.any { it.id.startsWith("cluster:") })

            // 3. Distant distinct platforms are NOT merged into artificial clusters
            val stop1 = stops[0]
            val stopFar = stops[299]
            val marker1 = stopMarkers.first { m -> m.stops.any { it.id == stop1.id } }
            val markerFar = stopMarkers.first { m -> m.stops.any { it.id == stopFar.id } }
            assertNotEquals("Distant stops must not be merged into the same cluster", marker1.id, markerFar.id)
            assertFalse(marker1.stops.any { it.id == stopFar.id })

            // 4. Full identity coverage: all 300 stop member identities must be recovered across all stop markers
            val recoveredStopIds = stopMarkers.flatMap { it.stops }.map { it.id }.toSet()
            val expectedStopIds = stops.map { it.id }.toSet()
            assertEquals("All 300 member identities must remain covered across markers", expectedStopIds, recoveredStopIds)
            assertEquals("Total member stop count must equal candidate stops without drop or duplicate", 300, stopMarkers.sumOf { it.stops.size })
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun singletonSamePlatformGroup_matchesOldMapGroupingWithoutClusters() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.10, 47.55)

        // Two platform stops at the same station (merged by platform grouping), plus one singleton stop elsewhere
        val platform1 = stop("p-1", "Keleti pu", 47.5001, 19.0838, provider = "mav", mode = "RAIL", parentStationId = "st-keleti")
        val platform2 = stop("p-2", "Keleti pu", 47.5005, 19.0842, provider = "mav", mode = "RAIL", parentStationId = "st-keleti")
        val singleton = stop("s-1", "Astoria", 47.4943, 19.0601, mode = "BUS")

        val stops = listOf(platform1, platform2, singleton)
        val expectedGroups = RailStationMarkers.mergePlatforms(
            StopMapClustering.groups(stops, region, 14.0, emptyMap())
        )

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = emptyList(),
                region = region,
                selectedVehicleId = null,
                stops = stops,
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 14.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
            )

            val stopMarkers = scene.markers.filter { it.stops.isNotEmpty() }
            assertEquals("Platform stops merge into 1 marker + 1 singleton marker = 2 markers", 2, stopMarkers.size)

            val actualGroups = stopMarkers.map { it.stops.map { s -> s.id }.toSet() }.toSet()
            val expectedGroupIds = expectedGroups.map { g -> g.map { s -> s.id }.toSet() }.toSet()
            assertEquals("Actual groups must match expected old map groups", expectedGroupIds, actualGroups)

            val platformGroupMarker = stopMarkers.first { marker -> marker.stops.any { it.id == "p-1" } }
            assertEquals("Platform group contains both platforms", 2, platformGroupMarker.stops.size)
            assertTrue("Platform group marker ID must start with stop:, not cluster:", platformGroupMarker.id.startsWith("stop:"))
            assertFalse("Platform group marker ID must not start with cluster:", platformGroupMarker.id.startsWith("cluster:"))

            val singletonMarker = stopMarkers.first { marker -> marker.stops.any { it.id == "s-1" } }
            assertEquals(1, singletonMarker.stops.size)
            assertTrue("Singleton marker ID must start with stop:, not cluster:", singletonMarker.id.startsWith("stop:"))
            assertFalse("Singleton marker ID must not start with cluster:", singletonMarker.id.startsWith("cluster:"))
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun focusRoute_remainsIndividual_matchesOldMapGroupingWithoutClusters() = runBlocking {
        val region = MapBounds(19.00, 47.40, 19.30, 47.70)

        // 30 route stops along a transit line
        val routeStops = (1..30).map { i ->
            stop("focus-stop-$i", "Stop $i", 47.41 + i * 0.009, 19.01 + i * 0.008, mode = "BUS")
        }
        // 300 background stops which should be ignored under line focus
        val backgroundStops = (1..300).map { i ->
            stop("bg-stop-$i", "BG $i", 47.42 + (i % 20) * 0.01, 19.02 + (i / 20) * 0.01, mode = "BUS")
        }

        val focus = LineMapFocus(
            key = "focus:bkk:7",
            routeId = "bkk:7",
            providerId = "bkk",
            number = "7",
            mode = "BUS",
            paths = emptyList(),
            stops = routeStops,
        )

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = emptyList(),
                region = region,
                selectedVehicleId = null,
                stops = routeStops + backgroundStops,
                focus = focus,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 13.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
            )

            val stopMarkers = scene.markers.filter { it.stops.isNotEmpty() }
            assertEquals("All 30 route stops must remain individual markers", 30, stopMarkers.size)
            assertTrue("All focused route stop marker IDs must start with stop:", stopMarkers.all { it.id.startsWith("stop:") })
            assertFalse("No focused route stop marker may have cluster: prefix", stopMarkers.any { it.id.startsWith("cluster:") })

            val recoveredIds = stopMarkers.flatMap { it.stops }.map { it.id }.toSet()
            assertEquals("All 30 route stop identities must be preserved", routeStops.map { it.id }.toSet(), recoveredIds)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun googlePrepare_overviewPreparesFocusedStopsAndOnlyRailWithoutFocus() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.10, 47.55)
        val s1 = stop("stop-1", "Stop 1", 47.49, 19.04, mode = "BUS")
        val s2 = stop("stop-2", "Stop 2", 47.50, 19.05, mode = "RAIL")
        val sOrdinary = stop("stop-ord", "Stop Ordinary", 47.51, 19.06, mode = "BUS")
        val v1 = vehicle("veh-1", "7", "BUS", lat = 47.49, lon = 19.04)

        val focus = LineMapFocus(
            key = "focus:bkk:7",
            routeId = "bkk:7",
            providerId = "bkk",
            number = "7",
            mode = "BUS",
            paths = emptyList(),
            stops = listOf(s1, s2),
        )

        val pending = linkedMapOf<String, Bitmap>()
        try {
            // Case 1: zoom = 12.5 with focus != null -> ONLY focused stops prepared, ordinary stops hidden
            val sceneWithFocus = GoogleMapScene.prepare(
                context = app,
                vehicles = listOf(v1),
                region = region,
                selectedVehicleId = null,
                stops = listOf(s1, s2, sOrdinary),
                focus = focus,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 12.5,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
            )
            val stopMarkersWithFocus = sceneWithFocus.markers.filter { it.stops.isNotEmpty() }
            assertEquals("Only focused stops prepared when zoom < 13 with focus", 2, stopMarkersWithFocus.sumOf { it.stops.size })
            val preparedIds = stopMarkersWithFocus.flatMap { it.stops }.map { it.id }.toSet()
            assertEquals(setOf("stop-1", "stop-2"), preparedIds)
            assertFalse("Ordinary stops remain hidden below 13", preparedIds.contains("stop-ord"))

            // At overview zoom only rail stops are prepared without focus.
            val sceneNoFocus = GoogleMapScene.prepare(
                context = app,
                vehicles = listOf(v1),
                region = region,
                selectedVehicleId = null,
                stops = listOf(s1, s2, sOrdinary),
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 10.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
            )
            val stopMarkersNoFocus = sceneNoFocus.markers.filter { it.stops.isNotEmpty() }
            assertEquals(setOf("stop-2"), stopMarkersNoFocus.flatMap { it.stops }.map { it.id }.toSet())
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun lifecycle_focusedOverview_dismissedSameOverview_normalDetail_showsNoLingeringFocusedMarkers() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.20, 47.60)

        val stop1 = stop("focus-stop-1", "Focus Stop 1", 47.48, 19.04, mode = "BUS", routes = listOf("bkk:7"))
        val stop2 = stop("focus-stop-2", "Focus Stop 2", 47.50, 19.06, mode = "BUS", routes = listOf("bkk:7"))
        val focusedStops = listOf(stop1, stop2)

        val bgStop1 = stop("bg-stop-1", "Background Stop 1", 47.47, 19.02, mode = "BUS")
        val bgStop2 = stop("bg-stop-2", "Background Stop 2", 47.53, 19.10, mode = "TRAM")
        val allStops = focusedStops + listOf(bgStop1, bgStop2)

        val vehicle1 = vehicle("veh-focused", "7", "BUS", lat = 47.49, lon = 19.05, routeId = "bkk:7")
        val vehicle2 = vehicle("veh-other", "4", "TRAM", lat = 47.50, lon = 19.06, routeId = "bkk:4")
        val vehicles = listOf(vehicle1, vehicle2)

        val focus = LineMapFocus(
            key = "focus:bkk:7",
            routeId = "bkk:7",
            providerId = "bkk",
            number = "7",
            mode = "BUS",
            paths = listOf(focusedStops.map { it.position }),
            stops = focusedStops,
        )

        val pending = linkedMapOf<String, Bitmap>()
        val cachedIcons = mutableSetOf<String>()
        try {
            // Step 1: Focused overview at zoom 12.0
            val sceneFocusedOverview = GoogleMapScene.prepare(
                context = app,
                vehicles = vehicles,
                region = region,
                selectedVehicleId = vehicle1.id,
                stops = allStops,
                focus = focus,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 12.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = cachedIcons,
                pending = pending,
            )
            cachedIcons.addAll(pending.keys)

            val focusedStopMarkers = sceneFocusedOverview.markers.filter { it.stops.isNotEmpty() }
            assertEquals("Focused overview at zoom 12.0 must retain exactly 2 focused stops", 2, focusedStopMarkers.sumOf { it.stops.size })
            val focusedIds = focusedStopMarkers.flatMap { it.stops }.map { it.id }.toSet()
            assertEquals(setOf("focus-stop-1", "focus-stop-2"), focusedIds)
            assertFalse("Background stops must be hidden at focused overview", focusedIds.contains("bg-stop-1") || focusedIds.contains("bg-stop-2"))
            assertEquals("focusKey must match active focus", "focus:bkk:7", sceneFocusedOverview.focusKey)
            assertTrue("Focused vehicles visible by renderer gate at overview", TransitMapVisibility.vehicle("BUS", 12.0, focused = true))
            assertTrue("Focus includes matching route vehicle", focus.includes(vehicle1))
            assertFalse("Focus excludes unrelated route vehicle", focus.includes(vehicle2))

            // Step 2: Dismissed at SAME overview (zoom 12.0, focus = null)
            val sceneDismissedOverview = GoogleMapScene.prepare(
                context = app,
                vehicles = vehicles,
                region = region,
                selectedVehicleId = null,
                stops = allStops,
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 12.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = cachedIcons,
                pending = pending,
            )
            cachedIcons.addAll(pending.keys)

            val dismissedStopMarkers = sceneDismissedOverview.markers.filter { it.stops.isNotEmpty() }
            assertTrue("Dismissed same overview at zoom 12.0 must have NO stop markers (no lingering focused markers)", dismissedStopMarkers.isEmpty())
            assertNull("focusKey must be null after dismiss", sceneDismissedOverview.focusKey)
            assertFalse("Vehicles hidden by renderer gate below 15 without focus", TransitMapVisibility.vehicle("BUS", 12.0, focused = false))
            val vehicle1VisibleAtOverviewDismissed = (null as LineMapFocus?)?.includes(vehicle1) != false &&
                TransitMapVisibility.vehicle(vehicle1.transportMode, 12.0, focused = false)
            assertFalse("Dismissing focus at same overview hides vehicles again", vehicle1VisibleAtOverviewDismissed)

            // Step 3: Transition to normal detail (zoom 16.0, focus = null)
            val sceneNormalDetail = GoogleMapScene.prepare(
                context = app,
                vehicles = vehicles,
                region = region,
                selectedVehicleId = null,
                stops = allStops,
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 16.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = cachedIcons,
                pending = pending,
            )
            cachedIcons.addAll(pending.keys)

            val normalDetailStopMarkers = sceneNormalDetail.markers.filter { it.stops.isNotEmpty() }
            val normalDetailStopIds = normalDetailStopMarkers.flatMap { it.stops }.map { it.id }.toSet()
            assertEquals("Normal detail must show all 4 stops in region", 4, normalDetailStopIds.size)
            assertTrue("Normal detail includes background stops", normalDetailStopIds.contains("bg-stop-1") && normalDetailStopIds.contains("bg-stop-2"))
            assertTrue("Normal detail includes route stops without focus styling", normalDetailStopIds.contains("focus-stop-1") && normalDetailStopIds.contains("focus-stop-2"))
            assertTrue("Former focused stops must have selected=false in normal detail", normalDetailStopMarkers.none { it.selected })
            assertNull("focusKey must be null in normal detail", sceneNormalDetail.focusKey)

            assertTrue("Vehicles visible by renderer gate at zoom 16.0", TransitMapVisibility.vehicle("BUS", 16.0, focused = false))
            val vehicleMarkers = sceneNormalDetail.markers.filter { it.vehicle != null }
            assertEquals("Normal detail at zoom 16.0 must show both vehicles", 2, vehicleMarkers.size)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun googlePrepare_stopsAtZoom14AndVehicleDtosRetainedWithRailVisible() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.10, 47.55)
        val s1 = stop("stop-1", "Stop 1", 47.49, 19.04, mode = "BUS")
        val s2 = stop("stop-2", "Stop 2", 47.50, 19.05, mode = "RAIL")
        val v1 = vehicle("veh-1", "7", "BUS", lat = 47.49, lon = 19.04)
        val v2 = vehicle("veh-2", "S30", "RAIL", provider = "mav", lat = 47.50, lon = 19.05)

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = listOf(v1, v2),
                region = region,
                selectedVehicleId = null,
                stops = listOf(s1, s2),
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 14.5,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
            )

            val stopMarkers = scene.markers.filter { it.stops.isNotEmpty() }
            val vehicleMarkers = scene.markers.filter { it.vehicle != null }

            // Stops are prepared and visible at 14.5
            assertEquals("2 stops must be prepared at zoom 14.5", 2, stopMarkers.sumOf { it.stops.size })
            assertTrue("TransitMapVisibility.stop must be true at 14.5", TransitMapVisibility.stop("BUS", 14.5))

            // Vehicle DTOs are still stored in scene.markers (Window / GeoJSON retention)
            assertEquals("Vehicle specs must still be stored in scene.markers", 2, vehicleMarkers.size)
            val storedVehicleIds = vehicleMarkers.mapNotNull { it.vehicle?.id }.toSet()
            assertEquals(setOf("veh-1", "veh-2"), storedVehicleIds)

            // Non-rail vehicles remain hidden while trains are visible.
            assertFalse("TransitMapVisibility.vehicle must be false at 14.5 for BUS", TransitMapVisibility.vehicle("BUS", 14.5))
            assertTrue("Rail vehicles are visible at 14.5", TransitMapVisibility.vehicle("RAIL", 14.5))
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun googlePrepare_detailZoom14AndAbove_retainsFullStopsAndVehicleSpecs() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.10, 47.55)
        val s1 = stop("stop-1", "Stop 1", 47.49, 19.04, mode = "BUS")
        val s2 = stop("stop-2", "Stop 2", 47.50, 19.05, mode = "RAIL")
        val v1 = vehicle("veh-1", "7", "BUS", lat = 47.49, lon = 19.04)
        val v2 = vehicle("veh-2", "S30", "RAIL", provider = "mav", lat = 47.50, lon = 19.05)

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = listOf(v1, v2),
                region = region,
                selectedVehicleId = null,
                stops = listOf(s1, s2),
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 15.5,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
            )

            val stopMarkers = scene.markers.filter { it.stops.isNotEmpty() }
            val vehicleMarkers = scene.markers.filter { it.vehicle != null }

            // Detail zoom >= 14: full stops AND full vehicles retention
            assertEquals("Full stops retained at zoom 15.5", 2, stopMarkers.sumOf { it.stops.size })
            assertEquals("Full vehicles retained at zoom 15.5", 2, vehicleMarkers.size)

            // Stop visibility is active at zoom 15.5, vehicle visibility is active from 15.0
            assertTrue("TransitMapVisibility.stop must be true at 15.5", TransitMapVisibility.stop("BUS", 15.5))
            assertFalse("TransitMapVisibility.vehicle must be false at 14.99 (hidden below 15)", TransitMapVisibility.vehicle("BUS", 14.99))
            assertTrue("TransitMapVisibility.vehicle must be true at 15.0", TransitMapVisibility.vehicle("BUS", 15.0))
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun optionalViewport_excludesFarStopWithinBroadRetainedRegion_whileRetainingVehicles() = runBlocking {
        val broadRegion = MapBounds(19.00, 47.40, 19.30, 47.70)
        val streetViewport = MapBounds(19.14, 47.49, 19.16, 47.51)

        val nearStop = stop("stop-near", "Near Viewport Stop", 47.50, 19.15, mode = "BUS")
        // Far stop: inside broadRegion, but well outside streetViewport + 10%
        val farStop = stop("stop-far", "Far Regional Stop", 47.41, 19.01, mode = "BUS")

        val nearVehicle = vehicle("veh-near", "7", "BUS", lat = 47.50, lon = 19.15)
        val farVehicle = vehicle("veh-far", "7", "BUS", lat = 47.41, lon = 19.01)

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = listOf(nearVehicle, farVehicle),
                region = broadRegion,
                selectedVehicleId = null,
                stops = listOf(nearStop, farStop),
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 16.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
                viewport = streetViewport,
            )

            val stopIds = scene.markers.flatMap { it.stops }.map { it.id }.toSet()
            assertTrue("Near stop inside viewport must be included in scene", stopIds.contains("stop-near"))
            assertFalse("Far stop outside viewport (even if within broad region) must be excluded", stopIds.contains("stop-far"))

            val vehicleIds = scene.markers.mapNotNull { it.vehicle?.id }.toSet()
            assertTrue("Near vehicle must be included", vehicleIds.contains("veh-near"))
            assertTrue("Far vehicle inside broad retained region must be retained", vehicleIds.contains("veh-far"))
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun vehiclesRetainedRegional_allTypesRetainedWithinRegion() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.10, 47.55)

        val bus = vehicle("v-bus", "5", "BUS", lat = 47.49, lon = 19.04)
        val tram = vehicle("v-tram", "4", "TRAM", lat = 47.50, lon = 19.05)
        val subway = vehicle("v-subway", "M2", "SUBWAY", lat = 47.51, lon = 19.06)
        val rail = vehicle("v-rail", "S30", "RAIL", provider = "mav", lat = 47.48, lon = 19.03)
        val trolley = vehicle("v-trolley", "72", "TROLLEYBUS", lat = 47.52, lon = 19.07)
        val ferry = vehicle("v-ferry", "D11", "FERRY", lat = 47.53, lon = 19.04)

        // Vehicles outside region of various modes
        val outsideBus = vehicle("out-bus", "100E", "BUS", lat = 47.30, lon = 19.30)
        val outsideTrain = vehicle("out-rail", "IC", "RAIL", lat = 47.20, lon = 19.40)

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = listOf(bus, tram, subway, rail, trolley, ferry, outsideBus, outsideTrain),
                region = region,
                selectedVehicleId = null,
                stops = emptyList(),
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 13.5,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = false,
                cached = emptySet(),
                pending = pending,
            )

            val retainedVehicles = scene.markers.mapNotNull { it.vehicle }
            val retainedIds = retainedVehicles.map { it.id }.toSet()

            assertEquals("All 6 inside vehicles of all types must be retained", 6, retainedVehicles.size)
            assertTrue(retainedIds.containsAll(listOf("v-bus", "v-tram", "v-subway", "v-rail", "v-trolley", "v-ferry")))
            assertFalse(retainedIds.contains("out-bus"))
            assertFalse(retainedIds.contains("out-rail"))

            val retainedModes = retainedVehicles.map { it.transportMode }.toSet()
            assertEquals(setOf("BUS", "TRAM", "SUBWAY", "RAIL", "TROLLEYBUS", "FERRY"), retainedModes)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun adjacentStopsReceiveAntiOverlapOffsetsMatchingStopMarkerLayout_whilePreservingPointsAndVehiclesAndFocusPolicy() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.10, 47.55)

        // Several same-provider distinct adjacent stops
        val stop1 = stop("stop-adj-1", "Platform 1", 47.50000, 19.05000, provider = "bkk", mode = "BUS")
        val stop2 = stop("stop-adj-2", "Platform 2", 47.50005, 19.05005, provider = "bkk", mode = "BUS")
        val stop3 = stop("stop-adj-3", "Platform 3", 47.50010, 19.05010, provider = "bkk", mode = "BUS")
        val stops = listOf(stop1, stop2, stop3)

        val bus = vehicle("bus-1", "7", "BUS", provider = "bkk", lat = 47.5000, lon = 19.0600)

        // 1. Preconditions: strict StopMapClustering leaves same-provider distinct adjacent stops separate
        val bounds = MapBounds(
            stops.minOf { it.position.lon },
            stops.minOf { it.position.lat },
            stops.maxOf { it.position.lon },
            stops.maxOf { it.position.lat },
        )
        val oldMapGroups = RailStationMarkers.mergePlatforms(StopMapClustering.groups(stops, bounds, 14.0, emptyMap()))
        assertEquals("strict StopMapClustering must leave same-provider adjacent stops separate", 3, oldMapGroups.size)

        val oldMapAnchors = oldMapGroups.map(::stationAnchor)
        val expectedOffsets = StopMarkerLayout.offsets(oldMapAnchors)
        assertTrue(
            "StopMarkerLayout.offsets must displace adjacent stops with at least one non-zero offset",
            expectedOffsets.values.any { it.x != 0.0 || it.y != 0.0 },
        )

        val pending = linkedMapOf<String, Bitmap>()
        try {
            // 2. Focused route policy: under old Native focused policy, focus route stops have zero offset
            val focus = LineMapFocus(
                key = "focus:bkk:7",
                routeId = "bkk:7",
                providerId = "bkk",
                number = "7",
                mode = "BUS",
                paths = emptyList(),
                stops = stops,
            )
            val focusedScene = GoogleMapScene.prepare(
                context = app,
                vehicles = listOf(bus),
                region = region,
                selectedVehicleId = null,
                stops = stops,
                focus = focus,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 15.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
            )
            val focusedStopMarkers = focusedScene.markers.filter { it.stops.isNotEmpty() }
            assertEquals(3, focusedStopMarkers.size)
            for (marker in focusedStopMarkers) {
                assertEquals(
                    "Focus route stop marker offset must be zero as old Native focused policy",
                    StopMarkerLayout.Offset(),
                    marker.offset,
                )
                val anchor = stationAnchor(marker.stops)
                assertEquals(
                    "Focus route stop marker point must remain original geographic coordinate",
                    anchor.position,
                    marker.point,
                )
            }
            for (marker in focusedScene.markers.filter { it.vehicle != null }) {
                assertEquals(
                    "Vehicle offset in focused scene must be zero",
                    StopMarkerLayout.Offset(),
                    marker.offset,
                )
            }

            // 3. Unfocused scene: anti-overlap stop offsets must match StopMarkerLayout.offsets on old map anchors
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = listOf(bus),
                region = region,
                selectedVehicleId = null,
                stops = stops,
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 15.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
            )

            // Vehicles offset zero & spec.point unchanged
            val vehicleMarkers = scene.markers.filter { it.vehicle != null }
            assertEquals(1, vehicleMarkers.size)
            for (marker in vehicleMarkers) {
                assertEquals(
                    "Vehicle marker offset must be zero",
                    StopMarkerLayout.Offset(),
                    marker.offset,
                )
                assertEquals(
                    "Vehicle marker point must remain original geographic coordinate",
                    marker.vehicle!!.position,
                    marker.point,
                )
            }

            // Stop markers: spec.point unchanged (display offset must not fake geographic coordinate)
            val stopMarkers = scene.markers.filter { it.stops.isNotEmpty() }
            assertEquals(3, stopMarkers.size)
            for (marker in stopMarkers) {
                val anchor = stationAnchor(marker.stops)
                assertEquals(
                    "Stop marker point must remain original geographic coordinate (display offset must not fake point)",
                    anchor.position,
                    marker.point,
                )
            }

            // Expected Google scene offsets by anchor ID equal actual shared StopMarkerLayout.offsets on old map anchors
            val sceneOffsetsByAnchorId = stopMarkers.associate {
                stationAnchor(it.stops).id to it.offset
            }
            assertEquals(
                "Expected Google scene offsets by anchor ID must equal actual shared StopMarkerLayout.offsets on old map anchors",
                expectedOffsets,
                sceneOffsetsByAnchorId,
            )
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun previousOffsetsSurviveViewportSubsetsReorderedStopsAndReturnFromRouteFocus() = runBlocking {
        val broadRegion = MapBounds(19.00, 47.45, 19.10, 47.55)

        // Multiple close stops that produce non-zero offsets
        val stop1 = stop("stop-s1", "Station Plat 1", 47.50000, 19.05000, provider = "bkk", mode = "BUS")
        val stop2 = stop("stop-s2", "Station Plat 2", 47.50004, 19.05004, provider = "bkk", mode = "BUS")
        val stop3 = stop("stop-s3", "Station Plat 3", 47.50008, 19.05008, provider = "bkk", mode = "BUS")
        val stop4 = stop("stop-s4", "Station Plat 4", 47.50012, 19.05012, provider = "bkk", mode = "BUS")
        val allStops = listOf(stop1, stop2, stop3, stop4)

        val pending = linkedMapOf<String, Bitmap>()
        try {
            // 1. Initial baseline prepare without previous offsets
            val initialScene = GoogleMapScene.prepare(
                context = app,
                vehicles = emptyList(),
                region = broadRegion,
                selectedVehicleId = null,
                stops = allStops,
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 15.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
            )

            val initialOffsets = initialScene.offsets
            assertEquals("Initial scene must compute offsets for all 4 distinct anchor stops", 4, initialOffsets.size)
            assertTrue(
                "Initial offsets must contain non-zero anti-overlap displacement",
                initialOffsets.values.any { it.x != 0.0 || it.y != 0.0 },
            )
            for (marker in initialScene.markers.filter { it.stops.isNotEmpty() }) {
                val anchorId = stationAnchor(marker.stops).id
                assertEquals(initialOffsets[anchorId], marker.offset)
            }

            // 2. Viewport subset: Viewport only includes stop1 and stop2
            // stop3 and stop4 lie outside this narrow viewport
            val narrowViewport = MapBounds(19.04990, 47.49990, 19.05006, 47.50006)
            val subsetScene = GoogleMapScene.prepare(
                context = app,
                vehicles = emptyList(),
                region = broadRegion,
                selectedVehicleId = null,
                stops = allStops,
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 15.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
                viewport = narrowViewport,
                previousOffsets = initialOffsets,
            )

            val subsetMarkers = subsetScene.markers.filter { it.stops.isNotEmpty() }
            assertTrue("Subset viewport must retain a subset of stops", subsetMarkers.size in 1..3)
            for (marker in subsetMarkers) {
                val anchorId = stationAnchor(marker.stops).id
                assertEquals(
                    "Stop $anchorId in viewport subset must retain its exact previous offset without recalculation jitter",
                    initialOffsets[anchorId],
                    marker.offset,
                )
                assertEquals(
                    "subsetScene.offsets must match initial offset for $anchorId",
                    initialOffsets[anchorId],
                    subsetScene.offsets[anchorId],
                )
            }

            // 3. Reordered stops: Input stops in reversed order, but passing previousOffsets
            val reorderedScene = GoogleMapScene.prepare(
                context = app,
                vehicles = emptyList(),
                region = broadRegion,
                selectedVehicleId = null,
                stops = allStops.reversed(),
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 15.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
                previousOffsets = initialOffsets,
            )

            val reorderedMarkers = reorderedScene.markers.filter { it.stops.isNotEmpty() }
            assertEquals(4, reorderedMarkers.size)
            for (marker in reorderedMarkers) {
                val anchorId = stationAnchor(marker.stops).id
                assertEquals(
                    "Reordered stop $anchorId must strictly preserve its previous offset",
                    initialOffsets[anchorId],
                    marker.offset,
                )
            }
            assertEquals("All scene offsets must match initial offsets when previousOffsets is supplied", initialOffsets, reorderedScene.offsets)

            // 4. Return from route focus:
            // First simulate route focus: route focus returns emptyMap() in scene.offsets and zero offset on markers
            val routeFocus = LineMapFocus(
                key = "focus:bkk:test",
                routeId = "bkk:test",
                providerId = "bkk",
                number = "TEST",
                mode = "BUS",
                paths = emptyList(),
                stops = allStops,
            )

            val focusedScene = GoogleMapScene.prepare(
                context = app,
                vehicles = emptyList(),
                region = broadRegion,
                selectedVehicleId = null,
                stops = allStops,
                focus = routeFocus,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 15.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
                previousOffsets = initialOffsets,
            )
            assertTrue("Focused scene returns empty offsets map", focusedScene.offsets.isEmpty())
            assertTrue("Focused scene markers have zero offsets", focusedScene.markers.all { it.offset == StopMarkerLayout.Offset() })

            // In GoogleTransitMap / GoogleMapRenderer, cachedOffsets are retained across focus.
            // When returning from route focus (focus = null), the cached previousOffsets are passed back:
            val restoredScene = GoogleMapScene.prepare(
                context = app,
                vehicles = emptyList(),
                region = broadRegion,
                selectedVehicleId = null,
                stops = allStops,
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 15.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
                previousOffsets = initialOffsets,
            )

            val restoredMarkers = restoredScene.markers.filter { it.stops.isNotEmpty() }
            assertEquals(4, restoredMarkers.size)
            for (marker in restoredMarkers) {
                val anchorId = stationAnchor(marker.stops).id
                assertEquals(
                    "Stop $anchorId must regain its exact original anti-overlap offset upon return from route focus",
                    initialOffsets[anchorId],
                    marker.offset,
                )
            }
            assertEquals(
                "Restored scene offsets must match initial offsets after returning from route focus",
                initialOffsets,
                restoredScene.offsets,
            )
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun focusedRouteOverview_retainsFocusedStopsAndVehiclesAcrossZoomTransitions_whileHidingUnfocused() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.20, 47.60)

        // Focused route stops with distinct locations along route
        val stop1 = stop("focus-stop-1", "Focus Stop 1", 47.48, 19.04, mode = "BUS", routes = listOf("bkk:7"))
        val stop2 = stop("focus-stop-2", "Focus Stop 2", 47.50, 19.06, mode = "BUS", routes = listOf("bkk:7"))
        val stop3 = stop("focus-stop-3", "Focus Stop 3", 47.52, 19.08, mode = "BUS", routes = listOf("bkk:7"))
        val focusedStops = listOf(stop1, stop2, stop3)
        val routeGeometry = listOf(focusedStops.map { it.position })

        // Background stops that must not leak into focused scene
        val bgStop1 = stop("bg-stop-1", "Background 1", 47.47, 19.02, mode = "BUS")
        val bgStop2 = stop("bg-stop-2", "Background 2", 47.53, 19.10, mode = "TRAM")
        val allStops = focusedStops + listOf(bgStop1, bgStop2)

        val vehicle1 = vehicle("veh-focused", "7", "BUS", lat = 47.49, lon = 19.05, routeId = "bkk:7")
        val vehicle2 = vehicle("veh-other", "4", "TRAM", lat = 47.50, lon = 19.06, routeId = "bkk:4")
        val vehicles = listOf(vehicle1, vehicle2)

        val focus = LineMapFocus(
            key = "focus:bkk:7",
            routeId = "bkk:7",
            providerId = "bkk",
            number = "7",
            mode = "BUS",
            paths = routeGeometry,
            stops = focusedStops,
        )

        val pending = linkedMapOf<String, Bitmap>()
        try {
            // 1. Detail zoom 15: baseline prepare
            val sceneDetail1 = GoogleMapScene.prepare(
                context = app,
                vehicles = vehicles,
                region = region,
                selectedVehicleId = vehicle1.id,
                stops = allStops,
                focus = focus,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 15.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
            )

            val detailStopMarkers1 = sceneDetail1.markers.filter { it.stops.isNotEmpty() }
            assertEquals("Detail zoom 15 must retain 3 stop markers", 3, detailStopMarkers1.size)
            val detailStopIds = detailStopMarkers1.flatMap { it.stops }.map { it.id }.toSet()
            assertEquals("Detail zoom 15 must contain ONLY focused stops", setOf("focus-stop-1", "focus-stop-2", "focus-stop-3"), detailStopIds)
            assertFalse("Unrelated background stops must be absent at detail zoom", detailStopMarkers1.any { m -> m.stops.any { it.id.startsWith("bg-stop") } })
            for (marker in detailStopMarkers1) {
                val orig = focusedStops.first { it.id == marker.stops.first().id }
                assertEquals("Original geographic position must be preserved at detail zoom", orig.position, marker.point)
            }

            // Normal unfocused overview baseline: zoom 12.0 without focus retains NO stops
            val unfocusedOverviewScene = GoogleMapScene.prepare(
                context = app,
                vehicles = vehicles,
                region = region,
                selectedVehicleId = null,
                stops = allStops,
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 12.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
            )
            assertTrue(
                "Normal unfocused overview at zoom 12.0 must have NO stops",
                unfocusedOverviewScene.markers.none { it.stops.isNotEmpty() },
            )

            // Vehicles visibility policy: focused vehicles visible at overview; unfocused remain hidden below 14
            assertTrue(
                "Vehicles visible at overview when focused (zoom 12.0)",
                TransitMapVisibility.vehicle("BUS", 12.0, focused = true),
            )
            assertTrue(
                "Vehicles visible at overview when focused (zoom 11.5)",
                TransitMapVisibility.vehicle("BUS", 11.5, focused = true),
            )
            assertFalse(
                "Unfocused vehicles remain hidden below 14 (zoom 12.0)",
                TransitMapVisibility.vehicle("BUS", 12.0, focused = false),
            )
            assertFalse(
                "Unfocused vehicles remain hidden below 14 (zoom 11.5)",
                TransitMapVisibility.vehicle("BUS", 11.5, focused = false),
            )

            // 2. Route overview zoom 12.0 (and 11.5): active LineMapFocus must retain ONLY focused stops
            val sceneOverview = GoogleMapScene.prepare(
                context = app,
                vehicles = vehicles,
                region = region,
                selectedVehicleId = vehicle1.id,
                stops = allStops,
                focus = focus,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 12.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
            )

            val overviewStopMarkers = sceneOverview.markers.filter { it.stops.isNotEmpty() }
            val detailCount = detailStopMarkers1.size
            val overviewCount = overviewStopMarkers.size

            println("Detail zoom 15 stop count: $detailCount, Overview zoom 12 stop count: $overviewCount")

            // Minimal RED regression assertion: focused stops must be retained at overview zoom 12.0
            assertEquals(
                "Active LineMapFocus must retain focused stops at route overview zoom 12.0 (detail count: $detailCount, overview count: $overviewCount)",
                3,
                overviewCount,
            )
            val overviewStopIds = overviewStopMarkers.flatMap { it.stops }.map { it.id }.toSet()
            assertEquals("Overview zoom 12 must contain ONLY focused stops", setOf("focus-stop-1", "focus-stop-2", "focus-stop-3"), overviewStopIds)
            assertFalse("Unrelated background stops must be absent at overview zoom", overviewStopMarkers.any { m -> m.stops.any { it.id.startsWith("bg-stop") } })
            for (marker in overviewStopMarkers) {
                val orig = focusedStops.first { it.id == marker.stops.first().id }
                assertEquals("Original geographic position must be preserved at overview zoom", orig.position, marker.point)
            }

            // 3. Detail zoom 15.0 again: transition back retains focused stops
            val sceneDetail2 = GoogleMapScene.prepare(
                context = app,
                vehicles = vehicles,
                region = region,
                selectedVehicleId = vehicle1.id,
                stops = allStops,
                focus = focus,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 15.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
            )
            val detailStopMarkers2 = sceneDetail2.markers.filter { it.stops.isNotEmpty() }
            assertEquals("Detail zoom 15 after transition must retain 3 stop markers", 3, detailStopMarkers2.size)

            // 4. showStops = false still retains focus stops at overview zoom 12.0
            val sceneNoStopsOverview = GoogleMapScene.prepare(
                context = app,
                vehicles = vehicles,
                region = region,
                selectedVehicleId = vehicle1.id,
                stops = allStops,
                focus = focus,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 12.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = false,
                cached = emptySet(),
                pending = pending,
            )
            val noStopsMarkers = sceneNoStopsOverview.markers.filter { it.stops.isNotEmpty() }
            assertEquals("Active focus must retain stops even when showStops is false at overview zoom 12.0", 3, noStopsMarkers.size)

            // 5. Renderer second gate
            assertTrue(
                "Focused TransitMapVisibility.stop must return true at overview zoom 12.0",
                TransitMapVisibility.stop("BUS", 12.0, focused = true),
            )
            assertTrue(
                "Focused TransitMapVisibility.stop must return true at overview zoom 11.5",
                TransitMapVisibility.stop("BUS", 11.5, focused = true),
            )
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun focusedTransitMapVisibility_stopReturnsTrueAtOverview_rendererSecondGate() {
        assertTrue(
            "Focused TransitMapVisibility.stop must return true at overview zoom 12.0 for BUS",
            TransitMapVisibility.stop("BUS", 12.0, focused = true),
        )
        assertTrue(
            "Focused TransitMapVisibility.stop must return true at overview zoom 11.5 for BUS",
            TransitMapVisibility.stop("BUS", 11.5, focused = true),
        )
        assertTrue(
            "Focused TransitMapVisibility.stop must return true at overview zoom 12.0 for null mode",
            TransitMapVisibility.stop(null, 12.0, focused = true),
        )
    }

    // =========================================================================
    // Targeted Seam Regressions: Focus Overview, Snapshot Retention, Live Priority
    // =========================================================================
    @Test
    fun overviewTrainFocus_visibilityAbove0_includesOppositeDirection_andRetainsAllRegionalRecords() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.10, 47.55)

        val selectedTrain = vehicle("train-sel", "S30", "RAIL", provider = "mav", lat = 47.49, lon = 19.04, routeId = "mav:s30")
        val oppositeTrain = vehicle("train-opp", "S30", "RAIL", provider = "mav", lat = 47.50, lon = 19.05, routeId = "mav:s30")
        val unrelatedTrain = vehicle("train-unrel", "G43", "RAIL", provider = "mav", lat = 47.48, lon = 19.03, routeId = "mav:g43")
        val unrelatedBus = vehicle("bus-unrel", "7", "BUS", provider = "bkk", lat = 47.51, lon = 19.06, routeId = "bkk:7")
        val otherProviderTrain = vehicle("train-foreign", "S30", "RAIL", provider = "other", lat = 47.47, lon = 19.02, routeId = "other:s30")

        val focus = LineMapFocus(
            key = "focus:mav:s30",
            routeId = "mav:s30",
            providerId = "mav",
            number = "S30",
            mode = "RAIL",
            paths = emptyList(),
            stops = emptyList(),
            selectedVehicleId = selectedTrain.id,
        )

        // 1. LineMapFocus includes selected and opposite direction train on same route, excludes unrelated
        assertTrue("Focus must include selected train", focus.includes(selectedTrain))
        assertTrue("Focus must include opposite direction train on same route", focus.includes(oppositeTrain))
        assertFalse("Focus must exclude unrelated train on different route", focus.includes(unrelatedTrain))
        assertFalse("Focus must exclude unrelated bus", focus.includes(unrelatedBus))
        assertFalse("Focus must preserve provider isolation", focus.includes(otherProviderTrain))

        // Focus extends train visibility below the overview gate.
        assertTrue("Unfocused overview train is visible at zoom 12.0", TransitMapVisibility.vehicle("RAIL", 12.0, focused = false))
        assertFalse("Unfocused overview bus must remain hidden at zoom 12.0", TransitMapVisibility.vehicle("BUS", 12.0, focused = false))
        assertTrue("Unfocused train is visible below the non-rail threshold", TransitMapVisibility.vehicle("RAIL", 14.99, focused = false))
        assertEquals("Global unfocused min zoom must remain 15", 15f, TransitMapVisibility.VEHICLE_MIN_ZOOM)
        assertEquals("vehicleMinZoom(focused = false) must be 15", 15f, TransitMapVisibility.vehicleMinZoom(focused = false))
        assertEquals("vehicleMinZoom(focused = true) must be 0", 0f, TransitMapVisibility.vehicleMinZoom(focused = true))

        assertTrue(
            "TransitMapVisibility.vehicle must show matching focused vehicle at zoom 12.0",
            TransitMapVisibility.vehicle("RAIL", 12.0, focused = true),
        )
        assertTrue(
            "TransitMapVisibility.vehicle must show matching focused vehicle above zoom 0 (e.g. zoom 5.0)",
            TransitMapVisibility.vehicle("RAIL", 5.0, focused = true),
        )
        assertTrue(
            "TransitMapVisibility.vehicle must show matching focused vehicle above zoom 0 (e.g. zoom 1.0)",
            TransitMapVisibility.vehicle("RAIL", 1.0, focused = true),
        )

        // 3. GoogleMapScene retains all regional records regardless of focus; visibility filter is separate
        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = listOf(selectedTrain, oppositeTrain, unrelatedTrain, unrelatedBus),
                region = region,
                selectedVehicleId = selectedTrain.id,
                stops = emptyList(),
                focus = focus,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 12.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = false,
                cached = emptySet(),
                pending = pending,
                selectedVehicle = selectedTrain,
            )

            val vehicleMarkers = scene.markers.filter { it.vehicle != null }
            assertEquals("Google scene retains all regional records regardless of focus", 4, vehicleMarkers.size)
            val vehicleIds = vehicleMarkers.mapNotNull { it.vehicle?.id }.toSet()
            assertEquals(setOf(selectedTrain.id, oppositeTrain.id, unrelatedTrain.id, unrelatedBus.id), vehicleIds)
            assertTrue("Selected vehicle marker has selected = true", vehicleMarkers.first { it.vehicle?.id == selectedTrain.id }.selected)
            assertFalse("Opposite train marker has selected = false", vehicleMarkers.first { it.vehicle?.id == oppositeTrain.id }.selected)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun googlePrepare_selectedTrainSnapshotAbsentFromFeed_isStillInSceneMarkers_evenOutsideRegion() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.10, 47.55)
        val insideBus = vehicle("v-bus", "7", "BUS", lat = 47.50, lon = 19.05)
        val outsideSnapshotTrain = vehicle("mav-snap-train", "S30", "RAIL", provider = "mav", lat = 47.10, lon = 18.50)

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = listOf(insideBus),
                region = region,
                selectedVehicleId = outsideSnapshotTrain.id,
                stops = emptyList(),
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 12.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = false,
                cached = emptySet(),
                pending = pending,
                selectedVehicle = outsideSnapshotTrain,
            )

            val vehicleMarkers = scene.markers.filter { it.vehicle != null }
            assertTrue(
                "Selected train snapshot absent from feed must be retained in scene markers even outside region",
                vehicleMarkers.any { it.vehicle?.id == outsideSnapshotTrain.id },
            )
            val marker = vehicleMarkers.first { it.vehicle?.id == outsideSnapshotTrain.id }
            assertEquals("Snapshot marker must retain original coordinates", outsideSnapshotTrain.position, marker.point)
            assertTrue("Snapshot marker must have selected = true", marker.selected)
            assertEquals("Snapshot marker offset must be zero", StopMarkerLayout.Offset(), marker.offset)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun googlePrepare_freshLiveVehicleSameIdWinsOverCachedSnapshot_withoutDuplicate() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.10, 47.55)
        val liveTrain = vehicle("train-1", "S30", "RAIL", provider = "mav", lat = 47.50, lon = 19.05)
        val cachedSnapshot = vehicle("train-1", "S30", "RAIL", provider = "mav", lat = 47.45, lon = 19.00)

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = listOf(liveTrain),
                region = region,
                selectedVehicleId = "train-1",
                stops = emptyList(),
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 14.5,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = false,
                cached = emptySet(),
                pending = pending,
                selectedVehicle = cachedSnapshot,
            )

            val vehicleMarkers = scene.markers.filter { it.vehicle?.id == "train-1" }
            assertEquals("Exactly one marker for the vehicle ID (no duplicate)", 1, vehicleMarkers.size)
            assertEquals("Fresh live vehicle position must win over cached snapshot", liveTrain.position, vehicleMarkers.first().point)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun googlePrepare_dismissedSelection_noSnapshotRetained() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.10, 47.55)
        val insideBus = vehicle("v-bus", "7", "BUS", lat = 47.50, lon = 19.05)

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = listOf(insideBus),
                region = region,
                selectedVehicleId = null,
                stops = emptyList(),
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 14.5,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = false,
                cached = emptySet(),
                pending = pending,
                selectedVehicle = null,
            )

            val vehicleIds = scene.markers.mapNotNull { it.vehicle?.id }
            assertEquals(listOf("v-bus"), vehicleIds)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun googlePrepare_snapshotChangingPositionSameId_usesLatestSnapshot_andDismissDoesNotRetainFallback() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.10, 47.55)
        val insideBus = vehicle("v-bus", "7", "BUS", lat = 47.50, lon = 19.05)
        val feed = listOf(insideBus)

        val pos1 = GeoPoint(47.10, 18.50)
        val pos2 = GeoPoint(47.12, 18.52)
        val snapshot1 = vehicle("train-snap-1", "S30", "RAIL", provider = "mav", lat = pos1.lat, lon = pos1.lon)
        val snapshot2 = vehicle("train-snap-1", "S30", "RAIL", provider = "mav", lat = pos2.lat, lon = pos2.lon)

        val pending = linkedMapOf<String, Bitmap>()
        try {
            // 1. Initial prepare with snapshot1
            val scene1 = GoogleMapScene.prepare(
                context = app,
                vehicles = feed,
                region = region,
                selectedVehicleId = snapshot1.id,
                stops = emptyList(),
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 12.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = false,
                cached = emptySet(),
                pending = pending,
                selectedVehicle = snapshot1,
            )
            val marker1 = scene1.markers.firstOrNull { it.vehicle?.id == "train-snap-1" }
            assertNotNull("Snapshot 1 must be present in scene markers", marker1)
            assertEquals("Snapshot 1 position must match pos1", pos1, marker1?.point)

            // 2. Position updates with same id: prepare with snapshot2 (latest position)
            val scene2 = GoogleMapScene.prepare(
                context = app,
                vehicles = feed,
                region = region,
                selectedVehicleId = snapshot2.id,
                stops = emptyList(),
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 12.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = false,
                cached = emptySet(),
                pending = pending,
                selectedVehicle = snapshot2,
            )
            val marker2 = scene2.markers.firstOrNull { it.vehicle?.id == "train-snap-1" }
            assertNotNull("Snapshot 2 must be present in scene markers", marker2)
            assertEquals("Position updates with same id must use latest snapshot position", pos2, marker2?.point)
            assertEquals("Exactly one marker for snapshot id", 1, scene2.markers.count { it.vehicle?.id == "train-snap-1" })

            // 3. Selection dismissed: no fallback snapshot retained
            val sceneDismissed = GoogleMapScene.prepare(
                context = app,
                vehicles = feed,
                region = region,
                selectedVehicleId = null,
                stops = emptyList(),
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 12.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = false,
                cached = emptySet(),
                pending = pending,
                selectedVehicle = null,
            )
            assertNull(
                "Dismissed selection must not retain fallback snapshot in scene markers",
                sceneDismissed.markers.firstOrNull { it.vehicle?.id == "train-snap-1" },
            )
            val dismissedIds = sceneDismissed.markers.mapNotNull { it.vehicle?.id }
            assertEquals(listOf("v-bus"), dismissedIds)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun googlePrepare_customCenterColor_emitsSpriteCenterMatchingSuppliedColor_coordsAndDirectionsUnaffected() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.10, 47.55)
        val stopPos = GeoPoint(47.50, 19.05)
        val myStop = stop("stop-custom-center", "Custom Center Stop", stopPos.lat, stopPos.lon, mode = "BUS", routes = listOf("bkk:7"))
        val route = TransitRoute("bkk:7", "7", "7", emptyList(), providerId = "bkk", transportMode = "BUS")
        val customCenter = StationSprite.deepCenterColor(0xFF199DCF.toInt())
        val bearings = mapOf("stop-custom-center" to listOf(45.0))

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = emptyList(),
                region = region,
                selectedVehicleId = null,
                stops = listOf(myStop),
                focus = null,
                highlightedStopId = null,
                routes = mapOf("bkk:7" to route),
                bearings = bearings,
                zoom = 15.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = true,
                cached = emptySet(),
                pending = pending,
                centerColor = customCenter,
            )

            val stopMarkers = scene.markers.filter { it.stops.isNotEmpty() }
            assertEquals(1, stopMarkers.size)
            val marker = stopMarkers.first()
            assertEquals("Stop marker coordinate must remain original position", stopPos, marker.point)
            assertEquals("Stop marker ID must match anchor", "stop:stop-custom-center", marker.id)

            val bitmap = pending[marker.icon]
            assertNotNull("Sprite bitmap must be emitted in pending", bitmap)
            val c = bitmap!!.width / 2
            assertEquals("Emitted sprite center pixel must match custom centerColor", customCenter, bitmap.getPixel(c, c))
            assertEquals("Emitted sprite outer ring must remain white", Color.WHITE, bitmap.getPixel(c + 35, c))
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun googlePrepare_parityCopiesHeadingAndArrowKeysCoordsAndSelectedSnapshot() = runBlocking {
        val region = MapBounds(19.00, 47.45, 19.10, 47.55)
        val pos1 = GeoPoint(47.49, 19.04)
        val pos2 = GeoPoint(47.50, 19.05)
        val pos3 = GeoPoint(47.51, 19.06)

        val v1 = vehicle("veh-bus", "7", "BUS", provider = "bkk", lat = pos1.lat, lon = pos1.lon, heading = 90.0)
        val v2 = vehicle("veh-metro", "M4", "SUBWAY", provider = "bkk", lat = pos2.lat, lon = pos2.lon, heading = 0.0)
        val v3 = vehicle("veh-train", "S30", "RAIL", provider = "mav", lat = pos3.lat, lon = pos3.lon, heading = null)

        val pending = linkedMapOf<String, Bitmap>()
        try {
            val scene = GoogleMapScene.prepare(
                context = app,
                vehicles = listOf(v1, v2, v3),
                region = region,
                selectedVehicleId = v3.id,
                stops = emptyList(),
                focus = null,
                highlightedStopId = null,
                routes = emptyMap(),
                bearings = emptyMap(),
                zoom = 15.0,
                dark = false,
                routeColor = 0xFF199DCF.toInt(),
                showStops = false,
                cached = emptySet(),
                pending = pending,
                selectedVehicle = v3,
            )

            val vehicleMarkers = scene.markers.filter { it.vehicle != null }
            assertEquals(3, vehicleMarkers.size)
            val byId = vehicleMarkers.associateBy { it.vehicle?.id }

            // Marker 1: v1 (bus with heading 90)
            val m1 = byId.getValue("veh-bus")
            assertEquals("Heading copied from vehicle to GoogleMarkerSpec", 90f, m1.heading)
            assertEquals("vehicle-heading-${VehicleMarkerStyle.BKK_BUS.color}", m1.arrow)
            assertEquals("Coordinates preserved exactly", pos1, m1.point)
            assertFalse(m1.selected)

            // Marker 2: v2 (M4 subway with heading 0 -> metro line color arrow)
            val m2 = byId.getValue("veh-metro")
            assertEquals("Heading copied from vehicle to GoogleMarkerSpec", 0f, m2.heading)
            val m4Color = MetroLineStyle.forLine("M4", "SUBWAY")!!.color
            assertEquals("vehicle-heading-$m4Color", m2.arrow)
            assertEquals("Coordinates preserved exactly", pos2, m2.point)
            assertFalse(m2.selected)

            // Marker 3: v3 (train without heading -> null heading and arrow, selected = true)
            val m3 = byId.getValue("veh-train")
            assertNull("Missing heading must produce null heading in GoogleMarkerSpec", m3.heading)
            assertNull("Missing heading must produce null arrow in GoogleMarkerSpec", m3.arrow)
            assertEquals("Coordinates preserved exactly", pos3, m3.point)
            assertTrue("Selected snapshot vehicle must have selected = true", m3.selected)
        } finally {
            pending.values.forEach(Bitmap::recycle)
        }
    }

    @Test
    fun googlePrepare_defaultZoom15Threshold_normalHidden14To1499_visible15_focusedOverviewUnchanged() {
        val allModes = listOf("BUS", "COACH", "TRAM", "TROLLEYBUS", "SUBWAY")

        // Unfocused normal vehicles: hidden at 14.0, 14.5, 14.99
        for (zoom in listOf(14.0, 14.5, 14.99)) {
            for (mode in allModes) {
                assertFalse("Normal unfocused vehicle mode $mode must be hidden at zoom $zoom", TransitMapVisibility.vehicle(mode, zoom, focused = false))
            }
        }

        // Unfocused normal vehicles: visible at 15.0, 15.1, 17.0
        for (zoom in listOf(15.0, 15.1, 17.0)) {
            for (mode in allModes) {
                assertTrue("Normal unfocused vehicle mode $mode must be visible at zoom $zoom", TransitMapVisibility.vehicle(mode, zoom, focused = false))
            }
        }

        // Focused overview vehicles: visible from zoom 0.0 upwards (unchanged)
        for (zoom in listOf(0.0, 5.0, 10.0, 12.0, 14.0, 15.0)) {
            for (mode in allModes) {
                assertTrue("Focused vehicle mode $mode must be visible at overview zoom $zoom", TransitMapVisibility.vehicle(mode, zoom, focused = true))
            }
        }

        // Shared helper thresholds
        assertEquals(15f, TransitMapVisibility.VEHICLE_MIN_ZOOM)
        assertEquals(15f, TransitMapVisibility.VEHICLE_SYMBOL_MIN_ZOOM)
        assertEquals(15f, TransitMapVisibility.vehicleMinZoom(focused = false))
        assertEquals(0f, TransitMapVisibility.vehicleMinZoom(focused = true))
    }
}

