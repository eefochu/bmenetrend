package hu.app.bmenetrend.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ViabuszRouteEstimatorTest {
    // Synthetic GPS replays exercise known identities; they are not measured real-world accuracy.
    private val start = Instant.parse("2026-10-26T08:00:00Z") // Monday in autumn school break, 09:00 Budapest
    private val points = (0..4).map { GeoPoint(47.47,18.80 + it*.003) }
    private val ids = points.indices.map { "s$it" }
    private val road = RouteRoads("r1","out",ids,points.zipWithNext { a,b -> listOf(a,b) })
    private val route = TransitRoute("r1","1","Test",listOf(RouteDirection("out","Station",ids)))
    private val trip = ScheduledTrip("t1","r1","out",ids.mapIndexed { i,id -> StopTime(id,540+i*2) },"school_break")
    private fun network(routes: List<TransitRoute> = listOf(route), trips: List<ScheduledTrip> = listOf(trip)) = TransitNetwork(
        ids.mapIndexed { i,id -> TransitStop(id,id,points[i],listOf("r1")) },routes,trips,start,"test",
        serviceDate = LocalDate.parse("2026-10-26"),verifiedProviderIds = setOf("viabusz"))
    private fun point(fraction: Double) = GeoPoint(points[0].lat,points[0].lon + fraction*.003)
    private fun observe(estimator: ViabuszRouteEstimator, fraction: Double, network: TransitNetwork = network(), age: Long = 0, id: String = "bus", timestamp: Instant = start.plusSeconds((fraction*120).toLong())): Vehicle {
        val vehicle = Vehicle(id,point(fraction),timestamp)
        return estimator.update(listOf(vehicle),network,timestamp.plusSeconds(age)).single()
    }
    private fun replay(estimator: ViabuszRouteEstimator, network: TransitNetwork = network()): Vehicle {
        var result: Vehicle? = null
        for (f in listOf(.6,.85,1.1,1.35)) result=observe(estimator,f,network)
        return result!!
    }
    @Test fun requiresMovementAndSeveralDistinctPacketsBeforeLabeling() {
        val estimator=ViabuszRouteEstimator(listOf(road))
        assertNull(observe(estimator,.6).routeEstimate)
        assertNull(observe(estimator,.85).routeEstimate)
        assertNull(observe(estimator,1.1).routeEstimate) // First confident match; still awaiting confirmation.
        val matched=observe(estimator,1.35)
        assertEquals("1",matched.displayRouteNumber)
        assertTrue(matched.isRouteEstimated)
        assertNull(matched.routeNumber)
        assertNull(matched.routeId)
        assertNull(matched.tripId)
    }
    @Test fun sharedRoadAndTimetableRemainAmbiguousRegardlessOfRouteOrder() {
        val other=route.copy(id="r2",number="2")
        val net=network(listOf(other,route),listOf(trip.copy(id="t2",routeId="r2"),trip))
        val estimator=ViabuszRouteEstimator(listOf(road.copy(routeId="r2"),road))
        assertNull(replay(estimator,net).routeEstimate)
    }
    @Test fun divergingRoadHistoryDistinguishesRoutesEvenAfterTheyJoin() {
        val extra=GeoPoint(points[1].lat+.003,points[1].lon)
        val other=route.copy(id="r2",number="2")
        val alternative=road.copy(routeId="r2",segments=listOf(listOf(points[0],extra),listOf(extra,points[2]))+road.segments.drop(2))
        val net=network(listOf(route,other),listOf(trip,trip.copy(id="t2",routeId="r2")))
        assertEquals("1",replay(ViabuszRouteEstimator(listOf(road,alternative)),net).displayRouteNumber)
    }
    @Test fun duplicateTimestampsAreNotExtraEvidence() {
        val estimator=ViabuszRouteEstimator(listOf(road))
        observe(estimator,.6); observe(estimator,.85); observe(estimator,1.1)
        repeat(8) { assertNull(observe(estimator,1.1).routeEstimate) }
        assertEquals("1",observe(estimator,1.35).displayRouteNumber)
    }
    @Test fun sameTimestampWithDifferentCoordinatesInvalidatesHistory() {
        val estimator=ViabuszRouteEstimator(listOf(road)); replay(estimator)
        assertNull(observe(estimator,1.5,timestamp=start.plusSeconds(162)).routeEstimate)
        assertNull(observe(estimator,1.6).routeEstimate)
    }
    @Test fun backwardsMotionCannotMatchTheForwardRoute() {
        val estimator=ViabuszRouteEstimator(listOf(road))
        for ((i,f) in listOf(2.3,2.05,1.8,1.55).withIndex()) {
            assertNull(observe(estimator,f,timestamp=start.plusSeconds(280+i*30L)).routeEstimate)
        }
    }
    @Test fun staleFeedOrLongGapClearsTheNumber() {
        val estimator=ViabuszRouteEstimator(listOf(road)); assertNotNull(replay(estimator).routeEstimate)
        assertNull(observe(estimator,1.35,age=91).routeEstimate)
        assertNull(observe(estimator,1.6).routeEstimate)
        val another=ViabuszRouteEstimator(listOf(road)); replay(another)
        assertNull(observe(another,2.5,timestamp=start.plusSeconds(300)).routeEstimate)
    }
    @Test fun terminusClearsPreviousAssignmentAndRequiresANewHistory() {
        val estimator=ViabuszRouteEstimator(listOf(road)); replay(estimator)
        assertNull(observe(estimator,3.95).routeEstimate)
        assertNull(observe(estimator,3.7,timestamp=start.plusSeconds(500)).routeEstimate)
    }
    @Test fun routeChangeNeedsFreshConfirmation() {
        val other=route.copy(id="r2",number="2")
        val net=network(listOf(other),listOf(trip.copy(routeId="r2")))
        val estimator=ViabuszRouteEstimator(listOf(road,road.copy(routeId="r2")))
        assertEquals("1",replay(estimator).displayRouteNumber)
        assertNull(observe(estimator,1.6,net).routeEstimate)
        assertEquals("2",observe(estimator,1.85,net).displayRouteNumber)
    }
    @Test fun inactiveCalendarUnverifiedDocumentsAndOffTimetableAllRemainUnknown() {
        for (net in listOf(network().copy(verifiedProviderIds=emptySet()),network(trips=listOf(trip.copy(calendar="school_term"))),
            network(trips=listOf(trip.copy(stopTimes=trip.stopTimes.map { it.copy(minuteOfDay=it.minuteOfDay-60) }))),
            network().copy(fetchedAt=start.minusSeconds(86400)))) {
            assertNull(replay(ViabuszRouteEstimator(listOf(road)),net).routeEstimate)
        }
        val estimator=ViabuszRouteEstimator(listOf(road))
        val saturday=Instant.parse("2026-10-24T07:00:00Z")
        assertNull(observe(estimator,1.0,network().copy(fetchedAt=saturday),timestamp=saturday).routeEstimate)
    }
    @Test fun gpsJumpOffRouteAndStationaryBusNeverCarryOldNumber() {
        val estimator=ViabuszRouteEstimator(listOf(road)); replay(estimator)
        val off=Vehicle("bus",GeoPoint(47.50,18.85),start.plusSeconds(190))
        assertNull(estimator.update(listOf(off),network(),off.observedAt).single().routeEstimate)
        val stopped=ViabuszRouteEstimator(listOf(road)); replay(stopped)
        var result:Vehicle?=null
        for(i in 1..7) result=observe(stopped,1.35,timestamp=start.plusSeconds(162+i*30L))
        assertNull(result!!.routeEstimate)
    }
    @Test fun officialNumbersAndOtherOperatorsAreNotReplacedByEstimates() {
        val estimator=ViabuszRouteEstimator(listOf(road))
        val vehicles=listOf(Vehicle("v",point(1.0),start,routeId="official",routeNumber="4"),
            Vehicle("k",point(1.0),start,providerId="kanyargo"),Vehicle("b",point(1.0),start,providerId="bkk",routeNumber="1"))
        assertEquals(vehicles,estimator.update(vehicles,network(),start))
    }
    @Test fun changedStopSequenceCannotUseOldGeometry() {
        val net=network(trips=listOf(trip.copy(stopTimes=trip.stopTimes.reversed())))
        assertNull(replay(ViabuszRouteEstimator(listOf(road)),net).routeEstimate)
    }
    @Test fun missingVehiclesDoNotLeaveHistoryAttachedToAReusedId() {
        val estimator=ViabuszRouteEstimator(listOf(road)); replay(estimator)
        estimator.update(emptyList(),network(),start.plusSeconds(170))
        assertNull(observe(estimator,1.6).routeEstimate)
    }
}
