package hu.viabusz.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class SharedStopsTest {
    private val today = LocalDate.parse("2026-10-22")
    private fun source(name: String): TransitNetwork = NetworkJson.decode(javaClass.classLoader!!
        .getResourceAsStream(name)!!.bufferedReader().use { it.readText() }).let {
        it.copy(serviceDate = today, providerServiceDates = it.routes.map { r -> r.providerId }.distinct().associateWith { today })
    }
    private fun merged() = NetworkMerger.merge(listOf(source("network.json"), source("kanyargo-network.json")))

    @Test fun `verified shared platforms have one marker and searchable original names`() {
        val network = merged()
        assertEquals(105, network.stops.size)
        val station = network.stops.single { it.id == "1" }
        assertEquals(setOf("1", "kanyargo:29", "kanyargo:30"), station.sourceStopIds.toSet())
        assertEquals(setOf("viabusz", "kanyargo"), station.providerIds.toSet())
        assertTrue(station.aliases.containsAll(listOf("Biatorbágy VÁ (érk.)", "Biatorbágy VÁ (in.)")))
        assertTrue(station.routeIds.containsAll(listOf("10", "kanyargo:1MV")))
        assertEquals("2", SharedStops.resolve(network, "kanyargo:50"))
        assertEquals("67", SharedStops.resolve(network, "kanyargo:48"))
        assertEquals("26", SharedStops.resolve(network, "kanyargo:42"))
        assertEquals("10", SharedStops.resolve(network, "kanyargo:51"))
        assertTrue(network.stops.filter { it.id in listOf("85", "20", "69", "66") }.all { it.providerIds == listOf("viabusz") })
    }

    @Test fun `merging preserves trips loop occurrences and JSON roundtrip`() {
        val network = merged()
        assertEquals(network, SharedStops.merge(network))
        assertEquals(network, NetworkJson.decode(NetworkJson.encode(network)))
        val original = source("kanyargo-network.json")
        for (trip in original.trips) {
            val actual = network.trips.single { it.id == trip.id }
            assertEquals(trip.stopTimes.map { it.minuteOfDay }, actual.stopTimes.map { it.minuteOfDay })
            assertEquals(trip.stopTimes.map { SharedStops.canonicalId(it.stopId) }, actual.stopTimes.map { it.stopId })
            assertEquals(actual.stopTimes.map { it.stopId }, network.routes.single { it.id == trip.routeId }
                .directions.single { it.id == trip.directionId }.stopIds)
        }
    }

    @Test fun `shared station departures combine providers without promoting arrivals to boarding`() {
        val via = source("network.json")
        val kanyar = source("kanyargo-network.json")
        val network = NetworkMerger.merge(listOf(via, kanyar))
        val now = Instant.parse("2026-10-22T03:00:00Z")
        fun board(net: TransitNetwork, stop: String) = DepartureCalculator.departures(net, null, stop, now, 180)
            .map { Triple(it.routeId, it.directionId, it.expectedAt) }.toSet()
        val expected = board(via, "1") + board(kanyar, "kanyargo:30")
        assertTrue(expected.any { it.first.startsWith("kanyargo:") })
        assertTrue(expected.any { !it.first.startsWith("kanyargo:") })
        assertEquals(expected, board(network, "1"))
        assertEquals(expected, board(network, "kanyargo:29"))
        assertTrue(board(kanyar, "kanyargo:29").isEmpty())
    }

    @Test fun `unverified provider stays absent from a shared stop board`() {
        val via = source("network.json")
        val kanyar = source("kanyargo-network.json").copy(serviceDate = null, providerServiceDates = mapOf("kanyargo" to null))
        val network = NetworkMerger.merge(listOf(via, kanyar))
        val board = DepartureCalculator.departures(network, null, "1", Instant.parse("2026-10-22T03:00:00Z"), 180)
        assertTrue(board.isNotEmpty())
        assertTrue(board.none { it.routeId.startsWith("kanyargo:") })
    }

    @Test fun `source live stop identifiers resolve to the same shared platform`() {
        val network = merged()
        val now = Instant.parse("2026-10-22T03:00:00Z")
        val route = network.routes.first { it.id == "kanyargo:1MV" }
        val live = LiveSnapshot(emptyList(), listOf(LiveArrival("kanyargo:30", 2, route.directions.first().id,
            vehicleId = "test", routeId = route.id, isVerifiedRealtime = true)), now)
        val board = DepartureCalculator.departures(network, live, "1", now, 30)
        assertEquals("1", board.single { it.kind == ArrivalKind.LIVE }.stopId)
    }
}
