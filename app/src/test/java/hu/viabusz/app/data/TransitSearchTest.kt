package hu.viabusz.app.data

import org.junit.Assert.*
import org.junit.Test

class TransitSearchTest {
    private fun route(id: String, number: String, mode: String, provider: String = "bkk") =
        TransitRoute(id, number, "Budapest – Bécsi út", emptyList(), provider, transportMode = mode)
    private val routes = listOf(route("tram11", "11", "TRAM"), route("bus1", "1", "BUS"), route("tram1", "1", "TRAM"), route("metro1", "M1", "SUBWAY"))
    @Test fun hungarianAndEnglishTransportQueriesAreOrderIndependentAndMatchExactRouteNumber() {
        listOf("1 villamos", "villamos 1", "1 tram", "BKK 1 villamos", "1-es villamos").forEach {
            assertEquals(listOf("tram1"), TransitSearch.routes(routes, TransitSearch.query(it)).map { route -> route.id })
        }
        assertEquals(listOf("metro1"), TransitSearch.routes(routes, TransitSearch.query("M1 metró")).map { it.id })
        assertEquals(listOf("bus1"), TransitSearch.routes(routes, TransitSearch.query("1 busz")).map { it.id })
    }
    @Test fun exactNumbersRankAheadOfPrefixesWithoutConflatingTransportModes() {
        val result = TransitSearch.routes(routes, TransitSearch.query("1"))
        assertEquals(setOf("bus1", "tram1"), result.take(2).map { it.id }.toSet())
        assertEquals("tram11", result.last().id)
        assertEquals(listOf("tram11", "tram1").toSet(), TransitSearch.routes(routes, TransitSearch.query("villamos")).map { it.id }.toSet())
    }
    @Test fun cachedStopIndexMatchesUnaccentedOutOfOrderWordsAndRanksNearbyStops() {
        val far = TransitStop("far", "Budapest, Kelenföld vasútállomás", GeoPoint(47.49, 19.0), emptyList(), "bkk")
        val near = far.copy(id = "near", position = GeoPoint(47.46, 19.03))
        val index = TransitSearch.indexStops(listOf(far, near))
        assertEquals("near", TransitSearch.stops(index, TransitSearch.query("vasutallomas kelenfold"), GeoPoint(47.46, 19.03), emptySet()).first().id)
        assertTrue(TransitSearch.stops(index, TransitSearch.query("1 villamos"), null, emptySet()).isEmpty())
        assertTrue(TransitSearch.stops(index, TransitSearch.query("volan kelenfold"), null, emptySet()).isEmpty())
    }
}
