package hu.app.bmenetrend.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class TransitOptimizationRegressionTest {

    private val today = LocalDate.parse("2026-10-22")
    private val baseTime = today.atTime(8, 0).atZone(ViabuszParser.zone).toInstant()

    // -------------------------------------------------------------------------
    // StopClusters Regression Tests
    // -------------------------------------------------------------------------

    @Test
    fun testStopClustersGeographicRejectionAndParentStation() {
        val hubPos = GeoPoint(47.4560, 18.9400)
        val parentStation = "station:hub_budaors"

        val target = TransitStop("hub_target", "Budaörs, Vasút utca", hubPos, emptyList(), "homm", parentStationId = parentStation)
        val nearSameName = TransitStop("near_1", "Budaörs, Vasút utca (1. kocsiállás)", GeoPoint(hubPos.lat + 0.0003, hubPos.lon + 0.0003), emptyList(), "homm")
        val nearDiffName = TransitStop("near_2", "Budaörs, Templom tér", GeoPoint(hubPos.lat + 0.0005, hubPos.lon + 0.0005), emptyList(), "homm")
        val distantSameName = TransitStop("dist_1", "Budaörs, Vasút utca", GeoPoint(47.5316, 21.6273), emptyList(), "homm") // Debrecen (~200km)
        val distantSameParent = TransitStop("dist_2", "Budaörs, MÁV állomás", GeoPoint(47.5200, 21.6000), emptyList(), "homm", parentStationId = parentStation)

        val catalogue = listOf(target, nearSameName, nearDiffName, distantSameName, distantSameParent)
        val cluster = StopClusters.clusterFor(target, catalogue)
        val clusterIds = cluster.map { it.id }.toSet()

        assertTrue("Target stop must be in cluster", clusterIds.contains(target.id))
        assertTrue("Nearby stop (<700m) with same clean name must be in cluster", clusterIds.contains(nearSameName.id))
        assertFalse("Nearby stop with different name must NOT be in cluster", clusterIds.contains(nearDiffName.id))
        assertFalse("Distant stop (>150km) with same clean name must be rejected geographically", clusterIds.contains(distantSameName.id))
        assertTrue("Distant stop with matching parentStationId must be retained in cluster", clusterIds.contains(distantSameParent.id))
    }

    @Test
    fun testStopClustersRegexCleanNameAndNormalization() {
        assertEquals("budapest, deak ferenc ter m", StopClusters.normalize("Budapest, Deák Ferenc tér M"))
        assertEquals("vasut utca", StopClusters.cleanName("Budaörs, Vasút utca (1. kocsiállás)"))
        assertEquals("deak ferenc ter", StopClusters.cleanName("Budapest, Deák Ferenc tér M"))
        assertTrue(StopClusters.areNamesCloselyMatching("Budaörs, Vasút utca", "Budaörs, Vasút utca (1. kocsiállás)"))
        assertEquals("Budapest, Deák Ferenc tér M", StopClusters.stopDisplayName("Budapest, Deák Ferenc tér M"))
        assertTrue(StopClusters.isCleanRouteNumber("40"))
        assertFalse(StopClusters.isCleanRouteNumber("hkir:123"))
    }

    // -------------------------------------------------------------------------
    // DepartureIndex & DepartureCalculator Regression Tests
    // -------------------------------------------------------------------------

    @Test
    fun testDepartureIndexExactPlatformPrecedenceAndAliasFirstMatch() {
        val stop1 = TransitStop("plat_1", "Platform 1", GeoPoint(47.0, 19.0), emptyList(), "viabusz", sourceStopIds = listOf("shared_alias", "alias_only_1"))
        val stop2 = TransitStop("shared_alias", "Platform 2", GeoPoint(47.0, 19.0), emptyList(), "viabusz")
        val stop3 = TransitStop("plat_3", "Platform 3", GeoPoint(47.0, 19.0), emptyList(), "viabusz", sourceStopIds = listOf("alias_only_1"))

        val route = TransitRoute(
            id = "r1", number = "1", name = "R1",
            directions = listOf(RouteDirection("d1", "Dir 1", listOf("plat_1", "shared_alias", "plat_3"))),
            providerId = "viabusz"
        )
        val trip = ScheduledTrip("t1", "r1", "d1", listOf(StopTime("plat_1", 500), StopTime("shared_alias", 510), StopTime("plat_3", 520)), "working_days")

        val net = TransitNetwork(
            stops = listOf(stop1, stop2, stop3),
            routes = listOf(route),
            trips = listOf(trip),
            fetchedAt = baseTime,
            source = "test",
            serviceDate = today,
            providerServiceDates = mapOf("viabusz" to today)
        )

        // Exact ID takes precedence over alias
        val exactDeps = DepartureCalculator.departures(net, null, "shared_alias", baseTime, 60)
        assertEquals(1, exactDeps.size)
        assertEquals("shared_alias", exactDeps.first().stopId)

        // First matching platform wins for shared alias
        val aliasDeps = DepartureCalculator.departures(net, null, "alias_only_1", baseTime, 60)
        assertEquals(1, aliasDeps.size)
        assertEquals("plat_1", aliasDeps.first().stopId)

        // Unknown stop yields empty list
        assertTrue(DepartureCalculator.departures(net, null, "missing_stop", baseTime, 60).isEmpty())
    }

    @Test
    fun testDepartureCalculatorTerminalStopExclusionAndRestrictedDirection() {
        val stopA = TransitStop("stop_A", "Stop A", GeoPoint(47.0, 19.0), emptyList(), "viabusz")
        val stopB = TransitStop("stop_B", "Stop B", GeoPoint(47.0, 19.0), emptyList(), "viabusz")
        val stopTerm = TransitStop("stop_Term", "Terminal", GeoPoint(47.0, 19.0), emptyList(), "viabusz")

        val route = TransitRoute(
            id = "r1", number = "10", name = "Line 10",
            directions = listOf(
                RouteDirection("d_norm", "To Terminal", listOf("stop_A", "stop_B", "stop_Term"), restrictedService = false),
                RouteDirection("d_rest", "Restricted Line", listOf("stop_A", "stop_B", "stop_Term"), restrictedService = true)
            ),
            providerId = "viabusz"
        )
        val trips = listOf(
            ScheduledTrip("t_norm", "r1", "d_norm", listOf(StopTime("stop_A", 485), StopTime("stop_B", 495), StopTime("stop_Term", 505)), "working_days"),
            ScheduledTrip("t_rest", "r1", "d_rest", listOf(StopTime("stop_A", 486), StopTime("stop_B", 496), StopTime("stop_Term", 506)), "working_days")
        )

        val net = TransitNetwork(
            stops = listOf(stopA, stopB, stopTerm),
            routes = listOf(route),
            trips = trips,
            fetchedAt = baseTime,
            source = "test",
            serviceDate = today,
            providerServiceDates = mapOf("viabusz" to today)
        )

        val now = today.atTime(8, 0).atZone(ViabuszParser.zone).toInstant() // 08:00 (minute 480)

        // Terminal stop should have no departures for this trip
        val termDeps = DepartureCalculator.departures(net, null, "stop_Term", now, 60)
        assertTrue("Terminal drop-off stop must be excluded from departures", termDeps.isEmpty())

        // Stop B has 2 departures, with restrictedService matching direction
        val bDeps = DepartureCalculator.departures(net, null, "stop_B", now, 60)
        assertEquals(2, bDeps.size)
        assertTrue(bDeps.any { it.directionId == "d_norm" && !it.restrictedService })
        assertTrue(bDeps.any { it.directionId == "d_rest" && it.restrictedService })
    }

    @Test
    fun testDepartureCalculatorLiveStalenessWindow() {
        val stop = TransitStop("s1", "Stop 1", GeoPoint(47.0, 19.0), emptyList(), "viabusz")
        val route = TransitRoute("r1", "1", "R1", listOf(RouteDirection("d1", "D1", listOf("s1"))), "viabusz")
        val net = TransitNetwork(listOf(stop), listOf(route), emptyList(), baseTime, "test", today, providerServiceDates = mapOf("viabusz" to today))

        val arrival = LiveArrival(stopId = "s1", minutes = 10, directionId = "d1", vehicleId = "bus_1", routeId = "r1", isVerifiedRealtime = true)

        // Fresh live snapshot: within -30s..45s
        val freshLive = LiveSnapshot(emptyList(), listOf(arrival), baseTime)
        val freshDeps = DepartureCalculator.departures(net, freshLive, "s1", baseTime, 60)
        assertEquals(1, freshDeps.size)
        assertEquals(ArrivalKind.LIVE, freshDeps.first().kind)

        // Stale live snapshot: > 45s old
        val staleOld = LiveSnapshot(emptyList(), listOf(arrival), baseTime.minusSeconds(46))
        assertTrue(DepartureCalculator.departures(net, staleOld, "s1", baseTime, 60).isEmpty())

        // Stale live snapshot: < -30s in future
        val staleFuture = LiveSnapshot(emptyList(), listOf(arrival), baseTime.plusSeconds(35))
        assertTrue(DepartureCalculator.departures(net, staleFuture, "s1", baseTime, 60).isEmpty())
    }

    @Test
    fun testDepartureCalculatorNetworkCopyInvalidation() {
        val stop = TransitStop("s1", "Stop 1", GeoPoint(47.0, 19.0), emptyList(), "viabusz")
        val route = TransitRoute("r1", "1", "R1", listOf(RouteDirection("d1", "D1", listOf("s1", "s_end"))), "viabusz")
        val trip1 = ScheduledTrip("t1", "r1", "d1", listOf(StopTime("s1", 490), StopTime("s_end", 500)), "working_days")

        val baseNet = TransitNetwork(listOf(stop), listOf(route), listOf(trip1), baseTime, "test", today, providerServiceDates = mapOf("viabusz" to today))
        assertEquals(1, DepartureCalculator.departures(baseNet, null, "s1", baseTime, 60).size)

        val trip2 = ScheduledTrip("t2", "r1", "d1", listOf(StopTime("s1", 495), StopTime("s_end", 505)), "working_days")
        val updatedNet = baseNet.copy(trips = baseNet.trips + trip2)

        assertNotSame("Copy must create a new TransitNetwork instance with distinct departureIndex", baseNet.departureIndex, updatedNet.departureIndex)
        assertEquals(2, DepartureCalculator.departures(updatedNet, null, "s1", baseTime, 60).size)
        assertEquals(1, DepartureCalculator.departures(baseNet, null, "s1", baseTime, 60).size)
    }
}
