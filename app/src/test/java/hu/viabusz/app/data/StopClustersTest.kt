package hu.viabusz.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class StopClustersTest {
    private val lakotelep1 = TransitStop(
        id = "homm:16",
        name = "Budaörsi lakótelep",
        position = GeoPoint(47.4560856, 18.9405799),
        routeIds = listOf("289"),
        providerId = "homm",
    )
    private val lakotelep2 = TransitStop(
        id = "homm:21",
        name = "Budaörsi lakótelep",
        position = GeoPoint(47.4559986, 18.9410841),
        routeIds = listOf("288", "289"),
        providerId = "homm",
    )
    private val lakotelep3 = TransitStop(
        id = "homm:103",
        name = "Budaörsi lakótelep",
        position = GeoPoint(47.4560421, 18.9402956),
        routeIds = listOf("288"),
        providerId = "homm",
    )
    private val nearbyDifferentStop = TransitStop(
        id = "homm:22",
        name = "Patkó utca",
        position = GeoPoint(47.4590091, 18.9430),
        routeIds = listOf("288"),
        providerId = "homm",
    )
    private val farAwaySameName = TransitStop(
        id = "other:1",
        name = "Budaörsi lakótelep",
        position = GeoPoint(47.60, 19.10),
        routeIds = listOf("999"),
        providerId = "volan",
    )

    @Test
    fun multiPlatformStopsAtSameLocationAreClustered() {
        val stops = listOf(lakotelep1, lakotelep2, lakotelep3, nearbyDifferentStop, farAwaySameName)
        val cluster = StopClusters.clusterFor("homm:16", stops)
        assertEquals(3, cluster.size)
        assertEquals(setOf("homm:16", "homm:21", "homm:103"), cluster.map { it.id }.toSet())
        assertTrue(StopClusters.isMultiStop("homm:16", stops))
        assertTrue(StopClusters.isMultiStop("homm:21", stops))
        assertTrue(StopClusters.isMultiStop("homm:103", stops))
    }

    @Test
    fun nearbyStopsWithDifferentNamesAreNotClustered() {
        val stops = listOf(lakotelep1, lakotelep2, nearbyDifferentStop)
        val cluster = StopClusters.clusterFor("homm:22", stops)
        assertEquals(1, cluster.size)
        assertEquals("homm:22", cluster.single().id)
        assertFalse(StopClusters.isMultiStop("homm:22", stops))
    }

    @Test
    fun distantStopsWithMatchingNamesAreNotClustered() {
        val stops = listOf(lakotelep1, farAwaySameName)
        val cluster = StopClusters.clusterFor("homm:16", stops)
        assertEquals(1, cluster.size)
        assertEquals("homm:16", cluster.single().id)
        assertFalse(StopClusters.isMultiStop("homm:16", stops))
    }

    @Test
    fun kelenfoldVariationsWithPrefixesAndModesAreClustered() {
        val bkkKelenfold = TransitStop(
            id = "emma:BKK:F01234",
            name = "Kelenföld vasútállomás M",
            position = GeoPoint(47.4645, 19.0210),
            routeIds = listOf("bkk:49"),
            providerId = "bkk",
            transportMode = "TRAM",
        )
        val volanKelenfold = TransitStop(
            id = "emma:hkir:5678",
            name = "Budapest, Kelenföld vasútállomás",
            position = GeoPoint(47.4650, 19.0215),
            routeIds = listOf("volan:760"),
            providerId = "volan",
            transportMode = "COACH",
        )
        val stops = listOf(bkkKelenfold, volanKelenfold)
        val cluster = StopClusters.clusterFor(bkkKelenfold.id, stops)
        assertEquals(2, cluster.size)
        assertTrue(StopClusters.isMultiStop(bkkKelenfold.id, stops))
        assertTrue(StopClusters.isMultiStop(volanKelenfold.id, stops))
    }

    @Test
    fun searchIndexConsolidatesMultiplePlatformsIntoSingleResult() {
        val stops = listOf(lakotelep1, lakotelep2, lakotelep3, nearbyDifferentStop)
        val index = TransitSearch.indexStops(stops)
        // lakótelep 1, 2, 3 should be merged into 1 consolidated stop, plus Patkó utca = 2 entries
        assertEquals(2, index.size)

        val consolidated = index.first { it.stop.name == "Budaörsi lakótelep" }.stop
        assertTrue(consolidated.isMultiStop)
        assertEquals(setOf("288", "289"), consolidated.routeIds.toSet())
        assertEquals(listOf("homm"), consolidated.providerIds)

        // Searching for "lakotelep" returns only 1 entry
        val results = TransitSearch.stops(index, TransitSearch.query("lakotelep"), null, emptySet())
        assertEquals(1, results.size)
        assertEquals("Budaörsi lakótelep", results.single().name)
        assertTrue(results.single().isMultiStop)
    }

    @Test
    fun stopDisplayNameAppendsMultiStopLabelWhenMultiStop() {
        assertEquals("Budaörsi lakótelep (több megálló)", StopClusters.stopDisplayName("Budaörsi lakótelep", true, "(több megálló)"))
        assertEquals("Budaörsi lakótelep (multiple stations)", StopClusters.stopDisplayName("Budaörsi lakótelep", true, "(multiple stations)"))
        // Single stop remains unchanged
        assertEquals("Patkó utca", StopClusters.stopDisplayName("Patkó utca", false, "(több megálló)"))
        // Already formatted name does not get duplicate label
        assertEquals("Budaörsi lakótelep (több megálló)", StopClusters.stopDisplayName("Budaörsi lakótelep (több megálló)", true, "(több megálló)"))
    }

    @Test
    fun combineBoardsMergesAndSortsDeparturesChronologically() {
        val now = Instant.ofEpochSecond(1_700_000_000)
        val dep1 = Departure("routeA", "1", "Dest A", "stop1", now.plusSeconds(300), now.plusSeconds(300), ArrivalKind.SCHEDULED)
        val dep2 = Departure("routeB", "2", "Dest B", "stop2", now.plusSeconds(100), now.plusSeconds(100), ArrivalKind.SCHEDULED)
        val dep3 = Departure("routeC", "3", "Dest C", "stop1", now.plusSeconds(200), now.plusSeconds(200), ArrivalKind.SCHEDULED)

        val board1 = EmmaStopsParser.Board("stop1", listOf(dep1, dep3), EmmaStopsParser.Catalogue(emptyList(), emptyList()), now, false)
        val board2 = EmmaStopsParser.Board("stop2", listOf(dep2), EmmaStopsParser.Catalogue(emptyList(), emptyList()), now, false)

        val combined = EmmaStopsParser.combineBoards("stop1", listOf(board1, board2))
        assertEquals(3, combined.departures.size)
        // Chronological order: 100s, 200s, 300s
        assertEquals(listOf("routeB", "routeC", "routeA"), combined.departures.map { it.routeId })
        assertEquals("stop1", combined.stopId)
    }
}
