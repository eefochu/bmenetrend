package hu.app.bmenetrend.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MetroNetworkOverlayTest {
    @Test fun routeStrokesStayBelowPlatformDotsRegardlessOfEffectCreationOrder() {
        val platformsFirst = listOf("basemap", "station-symbols", "selected-line-casing", "selected-line-stroke", "vehicle-dots-rail")
        assertEquals("A late metro overlay must not cover already-created platform dots", "station-symbols",
            MetroNetworkOverlay.lineAnchor(platformsFirst))
        val routeFirst = listOf("basemap", "selected-line-casing", "selected-line-stroke", "station-symbols", "vehicle-dots-rail")
        assertEquals("selected-line-casing", MetroNetworkOverlay.lineAnchor(routeFirst))
        val overviewFirst = listOf("basemap", MetroNetworkOverlay.STATIONS, "station-symbols", "vehicle-symbols")
        assertEquals(MetroNetworkOverlay.STATIONS, MetroNetworkOverlay.lineAnchor(overviewFirst, selectedRoute = true))
        assertEquals("station-symbols", MetroNetworkOverlay.lineAnchor(platformsFirst, selectedRoute = true))
        val annotations = org.maplibre.android.constants.MapLibreConstants.LAYER_ID_ANNOTATIONS
        assertEquals("Reserve the foreground slot for station/vehicle layers created later", annotations,
            MetroNetworkOverlay.lineAnchor(listOf("basemap", annotations)))
    }
    @Test fun bundledMetroNetworkHasRealShapesAndTappableStationsWithoutAnOfflinePack() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val collection = FeatureCollection.fromJson(MetroNetworkOverlay.load(context))
        val features = collection.features()!!
        val lines = features.filter { it.geometry() is LineString }
        assertEquals(setOf("M1", "M2", "M3", "M4"), lines.map { it.getStringProperty("line") }.toSet())
        assertEquals(8, lines.size)
        assertTrue("Route geometry must retain real curves", lines.all { (it.geometry() as LineString).coordinates().size > 10 })
        assertEquals(setOf("#FFD800", "#E41B13", "#075DA4", "#46A43B"), lines.map { it.getStringProperty("color") }.toSet())
        val stations = features.filter { it.geometry() is Point }
        assertEquals("Every real platform gets a separate overview marker", 103, stations.size)
        val platforms = stations.flatMap(MetroNetworkOverlay::stopIds)
        assertEquals(103, platforms.size)
        assertEquals(platforms.size, platforms.toSet().size)
        assertTrue(platforms.all { it.startsWith("emma:BKK:") })
        assertTrue(stations.all { MetroNetworkOverlay.stopIds(it).size == 1 })
        val byStation = stations.groupBy { it.getStringProperty("station") }
        assertEquals(48, byStation.size)
        val deak = byStation.values.single { group -> group.map { it.getStringProperty("line") }.toSet().size == 3 }
        assertEquals(6, deak.size)
        assertEquals(setOf("#FFD800", "#E41B13", "#075DA4"), deak.map { it.getStringProperty("color") }.toSet())
        assertEquals("Both directions of each line remain distinct", listOf(2, 2, 2),
            deak.groupBy { it.getStringProperty("line") }.values.map { it.size }.sorted())
        assertEquals("Every dot has a separate screen position at an interchange", 6,
            deak.map { it.getProperty("offset").toString() }.toSet().size)
        assertTrue(stations.all { it.getNumberProperty("slot").toInt() in MetroNetworkOverlay.stationLayers.indices })
    }

    @Test fun cityOverviewKeepsVehiclesAndOrdinaryStopsHiddenUntilCloserZoom() {
        for (mode in listOf("BUS", "TRAM", "TROLLEYBUS", "SUBWAY", "RAIL", "SUBURBAN_RAILWAY")) {
            assertFalse("$mode vehicles should not clutter city overview", TransitMapVisibility.vehicle(mode, 12.0))
            assertTrue("An explicitly focused $mode vehicle stays visible", TransitMapVisibility.vehicle(mode, 8.0, true))
            assertTrue("All $mode vehicles return at close zoom", TransitMapVisibility.vehicle(mode, 15.0))
        }
        assertFalse(TransitMapVisibility.stop("BUS", 13.5))
        assertFalse(TransitMapVisibility.stop("SUBWAY", 13.99))
        assertTrue(TransitMapVisibility.stop("SUBWAY", MetroNetworkOverlay.PLATFORMS_MIN_ZOOM.toDouble()))
        assertTrue(TransitMapVisibility.stop("BUS", 14.0))
        assertTrue(TransitMapVisibility.stop("BUS", 8.0, true))
        assertTrue(MetroNetworkOverlay.LINES_MIN_ZOOM < MetroNetworkOverlay.STATIONS_MIN_ZOOM)
        assertTrue(MetroNetworkOverlay.STATIONS_MIN_ZOOM < MetroNetworkOverlay.PLATFORMS_MIN_ZOOM)
    }
}
