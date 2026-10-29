package hu.app.bmenetrend.data

import org.junit.Assert.*
import org.junit.Test

class StationRecommendationsTest {
    private val stops = listOf(
        TransitStop("keleti", "Budapest-Keleti pályaudvar", GeoPoint(47.5, 19.08), emptyList(), "mav", transportMode = "RAIL", station = true),
        TransitStop("kelen", "Kelenföld vasútállomás", GeoPoint(47.46, 19.02), emptyList(), "mav", transportMode = "RAIL", station = true),
        TransitStop("nyugati", "Nyugati pályaudvar", GeoPoint(47.51, 19.06), emptyList(), "mav", transportMode = "RAIL", station = true),
        TransitStop("street", "Kelenföldi utca", GeoPoint(47.46, 19.03), emptyList(), "bkk"),
        TransitStop("coach", "Kelenföld vasútállomás", GeoPoint(47.46, 19.04), emptyList(), "volan"),
        TransitStop("literal", "Literal place", GeoPoint(47.4, 19.0), emptyList(), "bkk")
    )
    private val index = TransitSearch.indexStops(stops)
    private fun ids(q: String, provider: String? = null): List<String> {
        val parsed = TransitSearch.query(q)
        return StationRecommendations.stops(index, if (provider == null) parsed else parsed.copy(provider = provider), null, emptySet()).map { it.id }
    }

    @Test fun shorthandFullWordsAndGenericStationTermsRecommendLoadedHubs() {
        assertEquals("kelen", ids("kelen").first())
        assertEquals("kelen", ids("Kelenföld pályaudvar").first())
        assertEquals("kelen", ids("vasutallomas kelenfold").first())
        assertEquals("keleti", ids("palyaudvarsz").first())
        assertEquals("keleti", ids("palyaudvar").first())
    }

    @Test fun typosFiltersAndConservativeEmptyCases() {
        assertTrue(ids("kelenfokd").contains("kelen"))
        assertTrue(ids("gibberish").isEmpty())
        assertTrue(ids("").isEmpty())
        assertTrue(ids("ke").isEmpty())
        assertTrue(StationRecommendations.stops(emptyList(), TransitSearch.query("kelen"), null, emptySet()).isEmpty())
        assertEquals(listOf("coach"), ids("volan kelenfold"))
        assertTrue(ids("kelenfold", "mav").contains("kelen"))
        assertTrue(StationRecommendations.stops(index, TransitSearch.query("1 villamos"), null, emptySet()).isEmpty())
        assertEquals(listOf("literal"), TransitSearch.stops(index, TransitSearch.query("literal"), null, emptySet()).map { it.id })
    }
}
