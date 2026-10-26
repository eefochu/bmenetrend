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
    @Test fun metroDirectionsKeepTheirSourceIdentitiesInFavoritesWhileConsolidatingSearch() {
        val a=TransitStop("emma:BKK:one","Deák Ferenc tér",GeoPoint(47.497,19.05),emptyList(),"bkk",parentStationId="parent",transportMode="SUBWAY")
        val b=a.copy(id="emma:BKK:two",position=GeoPoint(47.4971,19.0501))
        val trolley=a.copy(id="emma:BKK:trolley",transportMode="TROLLEYBUS")
        val index=TransitSearch.indexStops(listOf(a,b,trolley))
        assertEquals(1, index.size)
        assertTrue(index.single().isMultiStop)
        val favorite=FavoriteDepartures.stations(setOf(a.id),listOf(a,b,trolley)).single()
        assertEquals(listOf(a),favorite.platforms)
    }
    @Test fun sameNameParentAndCoordinatesNeverAddAnotherPlatformOrModeToTheCard() {
        val tram=TransitStop("emma:BKK:north","Example tér",GeoPoint(47.5,19.0),emptyList(),"bkk",parentStationId="station",transportMode="TRAM",platformCode="A")
        val opposite=tram.copy(id="emma:BKK:south",platformCode="B")
        val trolley=tram.copy(id="emma:BKK:trolley",transportMode="TROLLEYBUS")
        val bus=tram.copy(id="emma:hkir:bus",providerId="volan",transportMode="COACH")
        val all=listOf(tram,opposite,trolley,bus)
        all.forEach { selected ->
            assertEquals(listOf(selected),StopBoardScope.platforms(selected.id,emptySet(),all))
        }
    }
    @Test fun sharedMarkerIncludesOnlyItsExplicitMembersAndNotTheOppositeStop() {
        val local=TransitStop("local","Vendel tér",GeoPoint(47.5,19.0),emptyList(),"viabusz",providerIds=listOf("viabusz","kanyargo"))
        val volan=local.copy(id="emma:hkir:volan",providerId="volan",providerIds=listOf("volan"))
        val opposite=local.copy(id="opposite",name="Vendel tér (Ellenirány)")
        assertEquals(listOf(local,volan),StopBoardScope.platforms(local.id,setOf(local.id,volan.id),listOf(local,volan,opposite)))
    }
    @Test fun boardRequestsChangeWhenTheMarkerMembershipChangesEvenIfItsIdStaysTheSame() {
        val single=StopBoardScope.requestKey("local",emptySet(),60)
        val shared=StopBoardScope.requestKey("local",setOf("local","emma:hkir:volan"),60)
        assertNotEquals(single,shared)
        assertEquals(single,StopBoardScope.requestKey("local",setOf("local"),60))
        assertNotEquals(single,StopBoardScope.requestKey("opposite",emptySet(),60))
        assertNotEquals(single,StopBoardScope.requestKey("local",emptySet(),120))
    }

}
