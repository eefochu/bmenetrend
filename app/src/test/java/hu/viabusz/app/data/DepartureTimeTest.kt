package hu.viabusz.app.data

import hu.viabusz.app.map.TransitMapVisibility
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DepartureTimeTest {
    @Test fun hungarianCountdownsUseCompactHoursAndMinutes() {
        assertEquals("4p",DepartureTime.compact(4,true))
        assertEquals("1ó 3p",DepartureTime.compact(63,true))
        assertEquals("1ó",DepartureTime.compact(60,true))
        assertEquals("5ó",DepartureTime.compact(300,true))
        assertEquals("0p",DepartureTime.compact(-3,true))
        assertEquals("1h 3min",DepartureTime.compact(63,false))
    }
    @Test fun countdownNeverTruncatesAPartialMinuteOrBecomesNegative() {
        val now=Instant.parse("2026-10-23T12:00:00Z")
        assertEquals(63,DepartureTime.minutes(now,now.plusSeconds(62*60+29)))
        assertEquals(0,DepartureTime.minutes(now,now.minusSeconds(1)))
        assertEquals(1,DepartureTime.minutes(now,now.plusMillis(1500)))
        assertEquals(300,DepartureTime.windows.last())
    }
    @Test fun nationalRailRemainsVisibleAtRegionalZoomWithoutChangingOtherModes() {
        assertTrue(TransitMapVisibility.stop("RAIL",9.5))
        assertFalse(TransitMapVisibility.stop("BUS",9.5))
        assertFalse(TransitMapVisibility.stop("RAIL",8.5))
        assertTrue(TransitMapVisibility.vehicle("RAIL",7.5))
        assertFalse(TransitMapVisibility.vehicle("BUS",7.5))
        assertFalse(TransitMapVisibility.vehicle("RAIL",6.5))
        assertTrue(TransitMapVisibility.stop("BUS",5.0,focused=true))
    }
    @Test fun favouriteStationsRetainEverySourcePlatformAndIgnoreMapDistance() {
        val a=TransitStop("emma:1:a_1","Station A",GeoPoint(47.4,19.0),emptyList(),"mav",parentStationId="emma:1:a")
        val b=a.copy(id="emma:1:a_2",platformCode="2",position=GeoPoint(47.4001,19.0001))
        val c=a.copy(id="emma:1:c_1",name="Station C",parentStationId="emma:1:c",position=GeoPoint(46.0,20.0))
        val stations=FavoriteDepartures.stations(setOf(a.id,c.id,"removed"),listOf(a,b,c))
        assertEquals(listOf(a.id,c.id),stations.map { it.stop.id })
        assertEquals(setOf(a.id,b.id),stations.first().platforms.map { it.id }.toSet())
        assertEquals(listOf(c),stations.last().platforms)
        assertTrue(FavoriteDepartures.stations(emptySet(),listOf(a,b,c)).isEmpty())
    }
    @Test fun railAggregateAvoidsQueryingEveryTrackWhileOtherPlatformsStayIndependent() {
        val root=TransitStop("emma:1:005501024_0","Kelenföld",GeoPoint(47.46,19.02),emptyList(),"mav",parentStationId="emma:1:005501024")
        val first=root.copy(id="emma:1:005501024_1",platformCode="1")
        val second=root.copy(id="emma:1:005501024_14",platformCode="14")
        val bus=root.copy(id="emma:BKK:stop",providerId="bkk",parentStationId=null)
        assertEquals(listOf(bus,root),EmmaBoardTargets.select(listOf(root,first,second,bus)))
        assertEquals(listOf(first,second),EmmaBoardTargets.select(listOf(first,second)))
    }
}
