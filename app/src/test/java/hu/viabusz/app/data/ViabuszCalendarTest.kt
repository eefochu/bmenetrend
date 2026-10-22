package hu.viabusz.app.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class ViabuszCalendarTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }
    private fun network() = NetworkJson.decode(resource("viabusz-calendar-network.json"))
    private fun at(date: String, time: String) = LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(ViabuszParser.zone).toInstant()
    private fun verified(date: String): TransitNetwork = ViabuszTimetableParser.validateNetwork(
        network(), resource("viabusz-timetable-current.html"), ViabuszTimetableParser.SCHOOL_SHA,
        ViabuszTimetableParser.BREAK_SHA, at(date, "12:00"),
    )

    @Test fun `reviewed school and break tables retain original platform IDs and directions`() {
        val legacy = NetworkJson.decode(resource("network.json"))
        val imported = network()
        assertEquals(legacy.stops, imported.stops)
        assertEquals(legacy.routes, imported.routes)
        assertEquals(49, imported.trips.count { it.calendar == "school_term" })
        assertEquals(30, imported.trips.count { it.calendar == "school_break" })
        assertEquals(setOf("1", "1P", "2", "3", "4"), imported.routes.map { it.number }.toSet())
        assertEquals(imported, NetworkJson.decode(NetworkJson.encode(imported)))
    }

    @Test fun `all school table stop times match the independent original HTML trip feed`() {
        val legacy = NetworkJson.decode(resource("network.json"))
        fun signatures(trips: List<ScheduledTrip>) = trips.map { Triple(it.routeId, it.directionId, it.stopTimes) }.toSet()
        assertEquals(signatures(legacy.trips), signatures(network().trips.filter { it.calendar == "school_term" }))
    }

    @Test fun `school break counts and first departures match official table columns`() {
        val trips = network().trips.filter { it.calendar == "school_break" }
        assertEquals(mapOf("10" to 8, "11" to 5, "12" to 10, "14" to 7), trips.groupingBy { it.routeId }.eachCount())
        assertEquals(listOf(295, 355, 440, 715, 805, 925, 1015, 1075), trips.filter { it.routeId == "10" }.map { it.stopTimes.first().minuteOfDay })
        assertEquals(listOf(400, 505, 625, 950, 1135), trips.filter { it.routeId == "11" }.map { it.stopTimes.first().minuteOfDay })
        assertEquals(listOf(380, 475, 535, 655, 775, 895, 985, 1045, 1105, 1195), trips.filter { it.routeId == "12" }.map { it.stopTimes.first().minuteOfDay })
        assertEquals(listOf(430, 573, 693, 813, 933, 1023, 1143), trips.filter { it.routeId == "14" }.map { it.stopTimes.first().minuteOfDay })
    }

    @Test fun `autumn break uses its own times and never operates school only route three`() {
        val school = DepartureCalculator.departures(verified("2026-10-22"), null, "1", at("2026-10-22", "13:20"), 180)
        val holiday = DepartureCalculator.departures(verified("2026-10-26"), null, "1", at("2026-10-26", "13:20"), 180)
        assertEquals(at("2026-10-22", "13:35"), school.first { it.routeNumber == "1" }.expectedAt)
        assertEquals(at("2026-10-26", "13:25"), holiday.first { it.routeNumber == "1" }.expectedAt)
        assertTrue(school.any { it.routeNumber == "3" })
        assertFalse(holiday.any { it.routeNumber == "3" })
        assertTrue(holiday.all { it.kind == ArrivalKind.SCHEDULED && it.directionId != null })
    }

    @Test fun `national holidays weekends and dates outside reviewed coverage do not produce trips`() {
        for (date in listOf("2026-09-30", "2026-10-23", "2026-10-24", "2026-12-24", "2027-01-04")) {
            assertNull(ViabuszTimetableParser.activeCalendar(LocalDate.parse(date)))
            assertNull(verified(date).providerServiceDates["viabusz"])
            assertTrue(DepartureCalculator.departures(verified(date), null, "1", at(date, "06:00"), 180).isEmpty())
        }
        assertEquals("school_term", ViabuszTimetableParser.activeCalendar(LocalDate.parse("2026-11-02")))
        assertEquals("school_break", ViabuszTimetableParser.activeCalendar(LocalDate.parse("2026-12-21")))
    }

    @Test fun `return to starting station does not create a false boarding departure`() {
        val departures = DepartureCalculator.departures(verified("2026-10-26"), null, "1", at("2026-10-26", "05:30"), 10)
        assertTrue(departures.isEmpty()) // 04:55 route 1 arrives 05:33; the next departure is 05:55.
    }

    @Test(expected = IllegalArgumentException::class) fun `new PDF bytes disable the reviewed imported table`() {
        ViabuszTimetableParser.validateNetwork(network(), resource("viabusz-timetable-current.html"), "changed", ViabuszTimetableParser.BREAK_SHA, Instant.parse("2026-10-22T10:00:00Z"))
    }

    @Test(expected = IllegalArgumentException::class) fun `new active menu timetable link disables the reviewed table`() {
        ViabuszTimetableParser.validateNetwork(network(), resource("viabusz-timetable-current.html").replace(ViabuszTimetableParser.BREAK_PATH, "documents/new.pdf"), ViabuszTimetableParser.SCHOOL_SHA, ViabuszTimetableParser.BREAK_SHA, Instant.parse("2026-10-22T10:00:00Z"))
    }

    @Test fun `historical news links cannot override the actual menu timetable identity`() {
        val page = resource("viabusz-timetable-current.html") + "<a href='documents/old-menetrend.pdf'>Régi hír</a>"
        val result = ViabuszTimetableParser.validateNetwork(network(), page, ViabuszTimetableParser.SCHOOL_SHA, ViabuszTimetableParser.BREAK_SHA, Instant.parse("2026-10-22T10:00:00Z"))
        assertEquals(LocalDate.parse("2026-10-22"), result.serviceDate)
    }
}
