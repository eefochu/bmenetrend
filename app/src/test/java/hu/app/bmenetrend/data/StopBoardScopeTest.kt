package hu.app.bmenetrend.data

import org.junit.Assert.*
import org.junit.Test

class StopBoardScopeTest {
    @Test fun namedInterchangeDoesNotMakeOtherModesBelongToItsPlatforms() {
        listOf("TROLLEYBUS","TRAM","SUBWAY","RAIL","BUS").forEach { selected ->
            listOf("TROLLEYBUS","TRAM","SUBWAY","RAIL","BUS").forEach { route ->
                assertEquals("$selected / $route",selected==route,StopBoardScope.acceptsMode(selected,route))
            }
        }
        assertTrue(StopBoardScope.acceptsMode("BUS","COACH"))
        assertTrue(StopBoardScope.acceptsMode("RAIL","TRAMTRAIN"))
    }
    @Test fun metroDirectionsKeepTheirSourceIdentitiesInSearchAndFavorites() {
        val a=TransitStop("emma:BKK:one","Deák Ferenc tér",GeoPoint(47.497,19.05),emptyList(),"bkk",parentStationId="parent",transportMode="SUBWAY")
        val b=a.copy(id="emma:BKK:two",position=GeoPoint(47.4971,19.0501))
        val trolley=a.copy(id="emma:BKK:trolley",transportMode="TROLLEYBUS")
        val index=TransitSearch.indexStops(listOf(a,b,trolley))
        assertEquals(setOf(a.id,b.id,trolley.id),index.map { it.stop.id }.toSet())
        val favorite=FavoriteDepartures.stations(setOf(a.id),listOf(a,b,trolley)).single()
        assertEquals(listOf(a),favorite.platforms)
    }
}
