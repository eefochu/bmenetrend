package hu.app.bmenetrend.data

import java.io.File
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ViabuszGeometryReplayTest {
    private fun asset(name: String) = File("src/main/assets/$name").readText()
    private val roads = RouteRoads.decode(asset("viabusz-route-geometry.json"))
    private val source = NetworkJson.decode(asset("viabusz-calendar-network.json"))
    @Test fun officialGeometryCoversEveryOrderedStopWithoutLongArtificialConnections() {
        assertEquals(source.routes.size,roads.size)
        val stops=source.stops.associateBy { it.id }
        for (road in roads) {
            val direction=source.routes.first { it.id==road.routeId }.directions.first { it.id==road.directionId }
            assertEquals(direction.stopIds,road.stopIds)
            for ((i,segment) in road.segments.withIndex()) {
                assertTrue(StopClusters.distanceMeters(stops.getValue(road.stopIds[i]).position,segment.first()) < 150)
                assertTrue(StopClusters.distanceMeters(stops.getValue(road.stopIds[i+1]).position,segment.last()) < 150)
                assertTrue(segment.zipWithNext().all { (a,b) -> StopClusters.distanceMeters(a,b) < 700 })
                val roadDistance=segment.zipWithNext().sumOf { (a,b) -> StopClusters.distanceMeters(a,b) }
                val straight=StopClusters.distanceMeters(segment.first(),segment.last())
                assertTrue("${road.routeId} stop $i detour $roadDistance",roadDistance < straight*6+500)
            }
        }
    }
    @Test fun scheduledRoadReplaysNeverAssignAnotherRouteAndIdentifyAllFiveOnDistinctiveSections() {
        val date=LocalDate.parse("2026-10-20")
        val midnight=date.atStartOfDay(ViabuszParser.zone).toInstant()
        val net=source.copy(fetchedAt=midnight,verifiedProviderIds=setOf("viabusz"))
        val detected=mutableSetOf<String>()
        for (road in roads) {
            val trip=source.trips.first { it.routeId==road.routeId && it.calendar=="school_term" }
            val estimator=ViabuszRouteEstimator(roads)
            for (minute in trip.stopTimes.first().minuteOfDay+1 until trip.stopTimes.last().minuteOfDay) {
                val stop=trip.stopTimes.zipWithNext().indexOfFirst { (a,b) -> minute>=a.minuteOfDay && minute<b.minuteOfDay }
                if (stop<0) continue
                val a=trip.stopTimes[stop].minuteOfDay; val b=trip.stopTimes[stop+1].minuteOfDay
                val p=along(road.segments[stop],(minute-a).toDouble()/(b-a))
                // Five-metre deterministic GPS noise, instead of perfect samples only.
                val noisy=GeoPoint(p.lat+if(minute%2==0) .00003 else -.00003,p.lon)
                val time=midnight.plusSeconds(minute*60L)
                val result=estimator.update(listOf(Vehicle("test",noisy,time)),net,time).single()
                result.routeEstimate?.let {
                    assertEquals("${road.routeId}, minute $minute",road.routeId,it.routeId)
                    detected+=it.routeId
                }
            }
        }
        assertEquals(source.routes.map { it.id }.toSet(),detected)
    }
    private fun along(points: List<GeoPoint>,fraction: Double): GeoPoint {
        val lengths=points.zipWithNext { a,b -> StopClusters.distanceMeters(a,b) }
        var remaining=lengths.sum()*fraction
        for(i in lengths.indices) {
            if(remaining<=lengths[i]) {
                val f=remaining/lengths[i]
                return GeoPoint(points[i].lat+(points[i+1].lat-points[i].lat)*f,points[i].lon+(points[i+1].lon-points[i].lon)*f)
            }
            remaining-=lengths[i]
        }
        return points.last()
    }
}
