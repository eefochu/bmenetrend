package hu.viabusz.app.data

import org.junit.Assert.*
import org.junit.Test

class NearbyDeparturesTest {
    private val center = GeoPoint(47.5, 19.04)
    private val bounds = MapBounds(19.02, 47.49, 19.06, 47.51)
    private fun stop(id: String, name: String, lat: Double = 47.5, lon: Double = 19.04) =
        TransitStop(id, name, GeoPoint(lat, lon), emptyList(), providerId = "bkk")

    @Test fun panningAwayFromLocationUsesMapCenter() {
        assertEquals(center, NearbyDepartures.anchor(center, bounds, GeoPoint(47.473, 18.825)))
        assertEquals(center, NearbyDepartures.anchor(center, bounds, null))
    }

    @Test fun locationInsideViewedNeighbourhoodTakesPriority() {
        val location = GeoPoint(47.501, 19.041)
        assertEquals(location, NearbyDepartures.anchor(center, bounds, location))
        assertEquals(center, NearbyDepartures.anchor(center, MapBounds(19.039,47.499,19.0405,47.5005), location))
    }

    @Test fun nearbyPlatformsStaySeparateEvenWhenTheyShareAName() {
        val a = stop("a", "Széll Kálmán tér")
        val b = stop("b", "Széll Kálmán tér", lon = 19.0403)
        val c = stop("c", "Másik megálló", lat = 47.502)
        val selected = NearbyDepartures.select(listOf(c, b, a), center, bounds)
        assertEquals(3, selected.size)
        assertEquals("a", selected.first().stop.id)
        assertEquals(listOf("a"), selected.first().platforms.map { it.id })
        assertEquals(listOf("b"), selected[1].platforms.map { it.id })
        assertEquals("c", selected.last().stop.id)
    }

    @Test fun nearbyButOffscreenAndDistantStationsAreExcluded() {
        val here = stop("here", "Here")
        val offscreen = stop("off", "Offscreen", lat = 47.511)
        val distant = stop("far", "Far", lat = 47.6)
        assertEquals(listOf("here"), NearbyDepartures.select(listOf(here, offscreen, distant), center, bounds).map { it.stop.id })
        assertTrue(NearbyDepartures.select(listOf(distant), center, null).isEmpty())
    }

    @Test fun stationParentsDoNotDuplicateIndividualPlatforms() {
        val platform = stop("platform", "Station")
        val parent = platform.copy(id = "parent", station = true)
        assertEquals(listOf("platform"), NearbyDepartures.select(listOf(parent, platform), center, bounds).single().platforms.map { it.id })
    }

    @Test fun denseCityCentreHasBoundedStationAndPlatformRequests() {
        val stops = (0..10).flatMap { station -> (0..9).map { platform ->
            stop("$station-$platform", "Station $station", 47.5 + station * 0.0005, 19.04 + platform * 0.00001)
                .copy(parentStationId = "parent-$station")
        } }
        val selected = NearbyDepartures.select(stops, center, bounds)
        assertEquals(3, selected.size)
        assertTrue(selected.all { it.platforms.size <= 4 })
        assertEquals(selected.flatMap { it.platforms }.size, selected.flatMap { it.platforms }.distinctBy { it.id }.size)
    }
}
