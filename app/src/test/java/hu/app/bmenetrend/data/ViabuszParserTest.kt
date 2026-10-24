package hu.app.bmenetrend.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ViabuszParserTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }
    private val now = Instant.parse("2026-10-22T12:06:00Z")
    private fun network() = NetworkJson.decode(resource("network.json"))

    @Test fun `real provider pages produce complete network and preserve loops`() {
        val pages = ViabuszParser.routeNumbers.keys.map { ViabuszParser.parseRoutePage(resource("route-$it.html"), it) }
        val result = ViabuszParser.mergeRoutePages(pages, now)
        assertEquals(74, result.stops.size)
        assertEquals(49, result.trips.size)
        assertEquals(listOf("1", "1P", "2", "3", "4"), result.routes.map { it.number })
        val pecato = result.routes.first { it.number == "1P" }.directions.single()
        assertEquals(44, pecato.stopIds.size)
        assertEquals(2, pecato.stopIds.count { it == "38" })
        assertEquals("1", pecato.stopIds.first()); assertEquals("1", pecato.stopIds.last())
        assertEquals("2", result.routes.first { it.number == "2" }.directions.single().id)
        assertEquals(LocalDate.parse("2026-10-22"), result.serviceDate)
    }

    @Test fun `snapshot survives JSON roundtrip without discarding stop occurrences`() {
        val result = network()
        assertEquals(result, NetworkJson.decode(NetworkJson.encode(result)))
    }

    @Test fun `stale invalid and distant vehicles are not presented as live`() {
        val result = ViabuszParser.parseVehicles(resource("vehicles.txt"), now)
        assertEquals(3, result.size)
        assertTrue(result.all { it.routeId == null })
        assertEquals(0, ViabuszParser.parseVehicles(resource("vehicles.txt"), now.plusSeconds(300)).size)
        val unsafe = "OUTSIDE;2026-10-22 14:06:00;47.45;18.97;9;;;Test#BAD;not-time;47.4;18.8;9#ZERO;2026-10-22 14:06:00;0;0;9"
        assertTrue(ViabuszParser.parseVehicles(unsafe, now).isEmpty())
    }

    @Test fun `aggregate arrivals are unassigned and never claimed GPS predictions`() {
        val parsed = ViabuszParser.parseAggregateArrivals(resource("arrivals.txt"))
        assertTrue(parsed.isNotEmpty())
        assertTrue(parsed.all { !it.isVerifiedRealtime && it.routeId == null && it.minutes > 0 })
        assertTrue(ViabuszParser.parseAggregateArrivals("1;0;1#2;-3;1#Error;failure#3;garbage;1").isEmpty())
    }

    @Test fun `empty stop ids and zero minutes cannot generate live arrivals`() {
        val body = resource("vehicles.txt")
        assertTrue(ViabuszParser.parseVehicleArrivals(body, ViabuszParser.parseVehicles(body, now)).isEmpty())
        val bodyWithPredictions = "ROW-417;2026-10-22 14:06:00;47.479;18.812;1;1|2|3;5'|0'|bad;Test"
        val result = ViabuszParser.parseVehicleArrivals(bodyWithPredictions, ViabuszParser.parseVehicles(bodyWithPredictions, now))
        assertEquals(1, result.size); assertTrue(result.single().isVerifiedRealtime)
        assertEquals("ROW-417", result.single().vehicleId); assertNull(result.single().routeId)
    }

    @Test fun `unassigned predictions do not overwrite numbered timetable departures`() {
        val result = network()
        val live = LiveSnapshot(emptyList(), listOf(LiveArrival("1", 1, "1", isVerifiedRealtime = true)), now)
        val departures = DepartureCalculator.departures(result, live, "1", now, 90)
        assertTrue(departures.isNotEmpty())
        assertTrue(departures.all { it.kind == ArrivalKind.SCHEDULED })
        assertEquals(Instant.parse("2026-10-22T12:13:00Z"), departures.first().expectedAt)
        // Vasútállomás at 14:15 is the END of route 2, not an extra boarding departure.
        assertFalse(departures.any { it.routeNumber == "2" && it.expectedAt == Instant.parse("2026-10-22T12:15:00Z") })
    }

    @Test fun `timetable cannot be reused on next day holidays or outside reviewed calendar`() {
        val result = network()
        assertTrue(DepartureCalculator.departures(result, null, "1", now.plusSeconds(86400)).isEmpty())
        assertFalse(ServiceCalendar.isConfirmedWorkingDay(LocalDate.parse("2026-10-23")))
        assertFalse(ServiceCalendar.isConfirmedWorkingDay(LocalDate.parse("2026-12-24")))
        assertFalse(ServiceCalendar.isConfirmedWorkingDay(LocalDate.parse("2026-04-06")))
        assertTrue(ServiceCalendar.isConfirmedWorkingDay(LocalDate.parse("2026-12-12")))
        assertFalse(ServiceCalendar.isConfirmedWorkingDay(LocalDate.parse("2027-01-04")))
    }

    @Test fun `departure window filters times and does not advance terminal service to tomorrow`() {
        val result = DepartureCalculator.departures(network(), null, "1", now, 10)
        assertEquals(1, result.size)
        assertEquals("3", result.single().routeNumber)
        assertTrue(DepartureCalculator.departures(network(), null, "1", Instant.parse("2026-10-22T21:00:00Z")).isEmpty())
    }

    @Test fun `Gregorian holidays can be computed without inventing annual transfers`() {
        val holidays = ServiceCalendar.publicHolidays(2027)
        assertTrue(LocalDate.parse("2027-03-26") in holidays)
        assertTrue(LocalDate.parse("2027-03-29") in holidays)
        assertTrue(LocalDate.parse("2027-05-17") in holidays)
        assertFalse(LocalDate.parse("2027-12-24") in holidays)
        // Public holidays are known, but no official 2027 workday-transfer decree was verified.
        assertNull(ServiceCalendar.serviceDate(LocalDate.parse("2027-01-04"), "Munkanapokon"))
    }

    @Test fun `Budapest midnight invalidates previous service day before UTC midnight`() {
        val before = Instant.parse("2026-10-22T21:59:59Z")
        val after = Instant.parse("2026-10-22T22:00:00Z")
        assertEquals(LocalDate.parse("2026-10-22"), before.atZone(ViabuszParser.zone).toLocalDate())
        assertEquals(LocalDate.parse("2026-10-23"), after.atZone(ViabuszParser.zone).toLocalDate())
        assertTrue(DepartureCalculator.departures(network(), null, "1", after).isEmpty())
    }
}
