package hu.app.bmenetrend

import hu.app.bmenetrend.data.*
import org.junit.Assert.*
import org.junit.Test

class NearestPlatformTest {
    @Test fun nearestStopSelectsAnActualBoardingPointInsteadOfAStationAggregate() {
        val location=GeoPoint(47.5,19.0)
        val parent=TransitStop("parent","Interchange",location,emptyList(),station=true)
        val trolley=parent.copy(id="trolley",station=false,position=GeoPoint(47.5001,19.0),transportMode="TROLLEYBUS")
        val metro=parent.copy(id="metro",station=false,position=GeoPoint(47.5002,19.0),transportMode="SUBWAY")
        assertEquals("trolley",nearestStop(listOf(parent,trolley,metro),location))
        assertNull(nearestStop(listOf(parent),location))
    }
}
