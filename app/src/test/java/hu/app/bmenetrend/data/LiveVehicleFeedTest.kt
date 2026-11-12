package hu.app.bmenetrend.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class LiveVehicleFeedTest {
    private val now = Instant.parse("2026-11-01T10:00:00Z")
    private fun vehicle(id: String, provider: String = "bkk", age: Long = 0) =
        Vehicle(id, GeoPoint(47.5, 19.05), now.minusSeconds(age), providerId = provider)
    private fun inputs(local: List<Vehicle> = emptyList(), remote: List<Vehicle> = emptyList(),
        bkk: BkkWorkerRepository.VehicleSnapshot? = null, stops: List<TransitStop> = emptyList(),
        nationwide: Boolean = true) = LiveVehicleFeed.Inputs(local,
        EmmaRepository.Result(remote, 0, now), bkk, emptyList(), stops, nationwide, emptyList())

    @Test fun observationAndEstimateAgeBoundaries() {
        val estimate = VehicleRouteEstimate("route", "7")
        val ages = listOf(-31L, -30L, 90L, 91L, 180L, 181L)
        val result = LiveVehicleFeed().update(inputs(local = ages.map {
            vehicle("$it", age = it).copy(routeEstimate = estimate)
        }), now, emptyList()).vehicles
        assertEquals(listOf("-30", "90", "91", "180"), result.map { it.id })
        assertEquals(listOf(estimate, estimate, null, null), result.map { it.routeEstimate })
    }

    @Test fun equalContentReusesPreviousAndRouteSubsetSurvivesLocalChanges() {
        val feed = LiveVehicleFeed()
        val remote = listOf("volan", "bkk", "hev", "mav", "other").map { vehicle(it, it) }
        val source = inputs(remote = remote)
        val first = feed.update(source, now, emptyList())
        assertEquals(listOf("volan", "bkk", "hev", "mav"), first.routeVehicles.map { it.id })
        val equalPrevious = first.vehicles.map { it.copy() }
        val second = feed.update(source, now, equalPrevious)
        assertSame(equalPrevious, second.vehicles)
        assertSame(first.routeVehicles, second.routeVehicles)
        val third = feed.update(inputs(local = listOf(vehicle("local", "viabusz")), remote = remote), now, second.vehicles)
        assertSame(first.routeVehicles, third.routeVehicles)
    }

    @Test fun localModeFiltersRemoteButKeepsLocalOperators() {
        val remote = listOf(vehicle("localBkk"), vehicle("farBkk").copy(position = GeoPoint(46.0, 20.0)),
            vehicle("volan", "volan"), vehicle("rail", "mav"), vehicle("hev", "hev"))
        val source = inputs(local = listOf(vehicle("own", "viabusz")), remote = remote, nationwide = false)
        assertEquals(listOf("own", "localBkk", "rail", "hev"),
            LiveVehicleFeed().update(source, now, emptyList()).vehicles.map { it.id })
    }

    @Test fun workerWinsRelayAndNamesStopsUntilSnapshotExpires() {
        val feed = LiveVehicleFeed()
        val stop = TransitStop("stop", "Catalogue name", GeoPoint(47.51, 19.06), emptyList())
        val worker = vehicle("emma:BKK:1").copy(nextStopId = stop.id)
        val relay = worker.copy(position = GeoPoint(47.49, 19.04))
        val snapshot = BkkWorkerRepository.VehicleSnapshot(listOf(WorkerVehicle(worker, setOf("1"), false)),
            MapBounds(18.0, 47.0, 20.0, 48.0), now)
        val source = inputs(remote = listOf(relay), bkk = snapshot, stops = listOf(stop))
        val first = feed.update(source, now, emptyList()).vehicles
        assertEquals(worker.position, first.single().position)
        assertEquals(stop.name, first.single().nextStopName)
        assertEquals(stop.position, first.single().nextStopPoint)
        assertSame(first, feed.update(source, now.plusSeconds(30), first).vehicles)
        val expired = feed.update(source, now.plusSeconds(31), first).vehicles.single()
        assertEquals(relay.position, expired.position)
        assertNull(expired.nextStopName)
    }

    @Test fun replacingStopCatalogueInvalidatesMemoizedNaming() {
        val feed = LiveVehicleFeed()
        val worker = vehicle("emma:BKK:1").copy(nextStopId = "stop")
        val snapshot = BkkWorkerRepository.VehicleSnapshot(listOf(WorkerVehicle(worker, setOf("1"), false)),
            MapBounds(18.0, 47.0, 20.0, 48.0), now)
        val stop = TransitStop("stop", "First", worker.position, emptyList())
        val first = feed.update(inputs(bkk = snapshot, stops = listOf(stop)), now, emptyList()).vehicles
        val next = feed.update(inputs(bkk = snapshot, stops = listOf(stop.copy(name = "Second"))), now, first)
        assertEquals("Second", next.vehicles.single().nextStopName)
    }
}
