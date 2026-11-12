package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString

class RouteLineLayerTest {
    @Test fun dropsShortPathsAndPreservesEachRemainingLineAndCoordinateOrder() {
        val a = GeoPoint(47.1, 19.1)
        val b = GeoPoint(47.2, 19.2)
        val c = GeoPoint(47.3, 19.3)
        var checks = 0
        val collection = FeatureCollection.fromJson(RouteLineLayer.geoJson(
            listOf(emptyList(), listOf(a), listOf(a, b), listOf(b, c, a))
        ) { checks++ })
        assertEquals("FeatureCollection", collection.type())
        val features = requireNotNull(collection.features())
        assertEquals(2, features.size)
        assertEquals(2, checks)
        features.zip(listOf(listOf(a, b), listOf(b, c, a))).forEach { (feature, expected) ->
            assertTrue(feature.geometry() is LineString)
            val coordinates = (feature.geometry() as LineString).coordinates()
            assertEquals(expected.map { it.lon }, coordinates.map { it.longitude() })
            assertEquals(expected.map { it.lat }, coordinates.map { it.latitude() })
        }
    }

    @Test fun noUsablePathsProducesAnEmptyFeatureCollection() {
        val collection = FeatureCollection.fromJson(RouteLineLayer.geoJson(listOf(emptyList())) {})
        assertTrue(requireNotNull(collection.features()).isEmpty())
    }
}
