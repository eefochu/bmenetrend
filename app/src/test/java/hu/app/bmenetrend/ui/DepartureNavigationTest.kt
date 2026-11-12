package hu.app.bmenetrend.ui

import hu.app.bmenetrend.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DepartureNavigationTest {
    private val now=Instant.parse("2026-11-04T12:00:00Z")
    private val a=TransitStop("a","Budapest",GeoPoint(47.5,19.0),emptyList())
    private val b=a.copy(id="b",name="Biatorbágy")
    private val c=a.copy(id="c",name="Etyek, Botpuszta")
    private val route=TransitRoute("route","760","Bus",emptyList(),"volan")
    private val out=LinePattern("out",setOf("out-code"),c.name,listOf(a,b,c),emptyList(),directionId="0")
    private val inbound=LinePattern("in",setOf("in-code"),a.name,listOf(c,b,a),emptyList(),directionId="1")
    private val details=LineDetails(route,listOf(inbound,out))
    private val departure=Departure(route.id,"760",c.name,b.id,now,now,ArrivalKind.LIVE,directionId="trip-out")

    @Test fun exactVehicleAndTripIdsDoNotSelectAnotherBusOnTheSameLine() {
        val wrong=Vehicle("wrong",b.position,now,routeId=route.id,tripId="another-trip")
        val exact=wrong.copy(id="emma:correct",tripId="trip-out")
        assertEquals(exact,departureVehicle(departure,listOf(wrong,exact)))
        assertEquals(exact,departureVehicle(departure.copy(vehicleId="correct",directionId=null),listOf(wrong,exact)))
        assertNull(departureVehicle(departure,listOf(wrong)))
    }
    @Test fun bkkBoardDepartureMatchesMergedVehiclesBkkTripId() {
        val board = departure.copy(directionId="volan:volan_ABC",vehicleId=null)
        val wrong = Vehicle("wrong",b.position,now,tripId="emma:other",bkkTripId="volan:volan_OTHER")
        val merged = wrong.copy(id="merged",tripId="emma:trip",bkkTripId="volan:volan_ABC")
        assertEquals(merged,departureVehicle(board,listOf(wrong,merged)))
        assertNull(departureVehicle(board,listOf(wrong)))
        assertNull(departureVehicle(board,emptyList()))
    }

    @Test fun bkkBoardDepartureStillMatchesPlainTripId() {
        val board = departure.copy(directionId="volan:volan_ABC",vehicleId=null)
        val exact = Vehicle("exact",b.position,now,tripId="volan:volan_ABC")
        assertEquals(exact,departureVehicle(board,listOf(exact)))
    }

    @Test fun vehicleIdTakesPrecedenceOverBothTripIdMatches() {
        val board = departure.copy(directionId="volan:volan_ABC",vehicleId="correct")
        val plain = Vehicle("plain",b.position,now,tripId="volan:volan_ABC")
        val merged = plain.copy(id="merged",tripId="emma:trip",bkkTripId="volan:volan_ABC")
        val exact = plain.copy(id="emma:correct",tripId="another-trip")
        assertEquals(exact,departureVehicle(board,listOf(plain,merged,exact)))
    }

    @Test fun exactPatternWinsRegardlessOfListOrder() {
        assertEquals("out",departureDirection(departure.copy(patternCode="out-code"),route,details))
        assertEquals("in",departureDirection(departure.copy(patternCode="in-code"),route,details))
    }
    @Test fun stopDestinationAndSourceDirectionResolveTheBoardedService() {
        assertEquals("out",departureDirection(departure.copy(sourceDirectionId="0"),route,details))
        assertEquals("out",departureDirection(departure,route,details))
        assertNull(departureDirection(departure.copy(sourceDirectionId="1"),route,details))
        assertNull(departureDirection(departure.copy(destination="Unknown terminus"),route,details))
        assertNull(departureDirection(departure.copy(destination=""),route,details))
    }
    @Test fun aLocalDepartureOpensItsKnownDirection() {
        val local=route.copy(directions=listOf(RouteDirection("local-out",c.name,listOf(a.id,b.id,c.id)),
            RouteDirection("local-in",a.name,listOf(c.id,b.id,a.id))))
        assertEquals("local-out",departureDirection(departure.copy(directionId="local-out"),local,null))
    }
    @Test fun tripPatternResolvesShortWorkingOutsideMainPatterns() {
        val trip="volan:volan_2811_21_20261102"
        val board=departure.copy(directionId=trip)
        val mainDetails=details.copy(patterns=listOf(inbound.copy(stops=listOf(c,a)),out.copy(stops=listOf(a,c))))
        val variant=LinePattern("trip:$trip",setOf(trip),c.name,listOf(b,c),emptyList())
        assertEquals("trip:$trip",departureDirection(board,route,mainDetails.copy(patterns=mainDetails.patterns+variant)))
    }

    @Test fun unmatchedShortWorkingWithoutTripPatternReturnsNull() {
        val board=departure.copy(directionId="volan:volan_2811_21_20261102")
        val mainDetails=details.copy(patterns=listOf(inbound.copy(stops=listOf(c,a)),out.copy(stops=listOf(a,c))))
        assertNull(departureDirection(board,route,mainDetails))
    }

}
