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
}
