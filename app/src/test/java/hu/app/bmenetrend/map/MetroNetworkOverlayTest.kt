package hu.app.bmenetrend.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MetroNetworkOverlayTest {
    @Test fun persistentTracksStayBelowEveryStationAndVehicleLayer() {
        for (marker in listOf("station-symbols","station-selected","vehicle-dots-rail","vehicle-dots-road","vehicle-symbols")) {
            assertEquals(marker,MetroNetworkOverlay.lineAnchor(listOf("basemap",marker,"selected-line-casing")))
        }
        assertEquals("selected-line-casing",MetroNetworkOverlay.lineAnchor(listOf("basemap","selected-line-casing","station-symbols")))
        val annotations=org.maplibre.android.constants.MapLibreConstants.LAYER_ID_ANNOTATIONS
        assertEquals(annotations,MetroNetworkOverlay.lineAnchor(listOf("basemap",annotations)))
    }
    @Test fun bundledMetroAndHevHaveOnlyRealTrackShapesAndNoStationFeatures() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val features=FeatureCollection.fromJson(MetroNetworkOverlay.load(context)).features()!!
        assertEquals(setOf("M1","M2","M3","M4","H5","H6","H7","H8","H9"),features.map { it.getStringProperty("line") }.toSet())
        assertEquals(18,features.size)
        assertTrue(features.all { it.geometry() is LineString && (it.geometry() as LineString).coordinates().size>10 })
        assertTrue(features.groupBy { it.getStringProperty("line") }.values.all { it.size==2 })
        assertEquals(setOf("#FFD800","#E41B13","#075DA4","#46A43B","#7B116B","#824B00","#EF7800","#EB6482"),
            features.map { it.getStringProperty("color") }.toSet())
    }
    @Test fun preparationRejectsStationPointsAndUnrequestedRoutes() {
        val json="""{"type":"FeatureCollection","features":[
          {"type":"Feature","properties":{"line":"M1"},"geometry":{"type":"LineString","coordinates":[[19,47],[19.1,47.1]]}},
          {"type":"Feature","properties":{"line":"H5"},"geometry":{"type":"Point","coordinates":[19,47]}},
          {"type":"Feature","properties":{"line":"19"},"geometry":{"type":"LineString","coordinates":[[19,47],[19.1,47.1]]}}
        ]}"""
        val features=FeatureCollection.fromJson(MetroNetworkOverlay.prepare(json)).features()!!
        assertEquals(1,features.size)
        assertEquals("M1",features.single().getStringProperty("line"))
    }
}
