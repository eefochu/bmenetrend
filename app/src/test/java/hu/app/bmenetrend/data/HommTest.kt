package hu.app.bmenetrend.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class HommTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }
    private fun network() = NetworkJson.decode(resource("homm-network.json"))
    private fun at(date: String, hour: Int, minute: Int = 0) = LocalDate.parse(date).atTime(hour, minute).atZone(ViabuszParser.zone).toInstant()
    private fun verified(date: String) = HommParser.validateNetwork(network(), resource("homm-timetable.html"),
        HommParser.SCHOOL_SHA, HommParser.BREAK_SHA, at(date, 6))

    @Test fun `official import preserves both calendars and every complete route sequence`() {
        val n = network()
        assertEquals(78, n.stops.size)
        assertEquals(setOf("288", "289"), n.routes.map { it.number }.toSet())
        assertEquals(93, n.trips.count { it.calendar == "school_term" })
        assertEquals(56, n.trips.count { it.calendar == "school_break" })
        assertEquals(setOf(39, 20, 26, 18, 4), n.routes.flatMap { it.directions }.map { it.stopIds.size }.toSet())
        assertTrue(n.stops.all { it.providerId == "homm" && it.id.startsWith("homm:") })
        assertEquals(n, NetworkJson.decode(NetworkJson.encode(n)))
    }

    @Test fun `short school trip starts at Rozsa and never invents an earlier origin departure`() {
        val n = network()
        val short = n.trips.single { it.directionId == "homm:289:2:from-23" }
        assertEquals("school_term", short.calendar)
        assertEquals(listOf("homm:42", "homm:43", "homm:44", "homm:20"), short.stopTimes.map { it.stopId })
        assertEquals(listOf(419, 420, 421, 422), short.stopTimes.map { it.minuteOfDay })
        assertFalse(n.trips.filter { it.calendar == "school_break" }.any { it.directionId == short.directionId })
    }

    @Test fun `HOMM calendar includes weekends and holidays using the published break table`() {
        fun calendar(date: String) = HommCalendar.activeCalendar(LocalDate.parse(date))
        assertEquals("school_term", calendar("2026-10-22"))
        assertEquals("school_break", calendar("2026-10-17"))
        assertEquals("school_break", calendar("2026-10-18"))
        assertEquals("school_break", calendar("2026-10-23"))
        assertEquals("school_break", calendar("2026-10-26"))
        assertEquals("school_term", calendar("2026-11-02"))
        assertEquals("school_term", calendar("2026-12-18"))
        assertEquals("school_break", calendar("2026-12-21"))
        assertEquals("school_break", calendar("2026-12-24"))
        assertEquals("school_break", calendar("2026-12-25"))
        // The HOMM heading explicitly says Saturdays; it does not say working days.
        assertEquals("school_break", calendar("2026-12-12"))
        assertNull(calendar("2026-08-31"))
        assertNull(calendar("2027-01-04"))
    }

    @Test fun `school break and term departures never mix and HOMM is always scheduled`() {
        fun departures(date: String) = DepartureCalculator.departures(verified(date), null, "homm:21", at(date, 6), 180)
        val term = departures("2026-10-22")
        assertEquals(at("2026-10-22", 6), term.first { it.routeNumber == "288" }.expectedAt)
        assertTrue(term.any { it.routeNumber == "288" && it.expectedAt == at("2026-10-22", 6, 55) })
        for (date in listOf("2026-10-17", "2026-10-23", "2026-10-26")) {
            val holiday = departures(date)
            assertEquals(at(date, 7), holiday.first { it.routeNumber == "288" }.expectedAt)
            assertEquals(at(date, 6, 45), holiday.first { it.routeNumber == "289" }.expectedAt)
            assertFalse(holiday.any { it.routeNumber == "288" && it.expectedAt == at(date, 6, 55) })
            assertTrue(holiday.all { it.kind == ArrivalKind.SCHEDULED && it.directionId != null })
        }
    }

    @Test fun `common municipal stop records become one stop while opposite platforms stay distinct`() {
        val n = network()
        val stop = n.stops.single { it.id == "homm:38" }
        assertEquals(setOf("homm:288", "homm:289"), stop.routeIds.toSet())
        val out = n.routes.flatMap { it.directions }.filter { it.id in setOf("homm:288:4", "homm:289:2") }
        assertTrue(out.all { "homm:38" in it.stopIds })
        val opposite = n.stops.single { it.id == "homm:6" }
        assertEquals(stop.name, opposite.name)
        assertNotEquals(stop.position, opposite.position)
        assertTrue(n.stops.none { it.id == "homm:69" || it.id == "homm:94" })
    }

    @Test fun `terminal arrivals and transfer helper rows are not boarding departures`() {
        val n = verified("2026-10-22")
        assertTrue(DepartureCalculator.departures(n, null, "homm:83", at("2026-10-22", 6), 180).isEmpty())
        assertTrue(DepartureCalculator.departures(n, null, "homm:20", at("2026-10-22", 6), 180).isEmpty())
        assertFalse(n.stops.any { it.name.contains("289 - re") || it.name.contains("288 - ra") })
        assertEquals(20, n.routes.single { it.number == "288" }.directions.single { it.id == "homm:288:3" }.stopIds.size)
    }

    @Test(expected = IllegalArgumentException::class) fun `new PDF bytes disable imported timetable`() {
        HommParser.validateNetwork(network(), resource("homm-timetable.html"), "changed", HommParser.BREAK_SHA, at("2026-10-22", 6))
    }

    @Test(expected = IllegalArgumentException::class) fun `new linked timetable disables imported timetable`() {
        val html = resource("homm-timetable.html").replace(HommParser.SCHOOL_PATH, "www/menetrend-budaors/new-school.pdf")
        HommParser.validateNetwork(network(), html, HommParser.SCHOOL_SHA, HommParser.BREAK_SHA, at("2026-10-22", 6))
    }

    @Test fun `encoded relative links validate and unreviewed years keep only route browsing`() {
        val html = "<a href='/" + HommParser.SCHOOL_PATH.replace(" ", "%20") + "'>School</a>" +
            "<a href='/" + HommParser.BREAK_PATH.replace(" ", "%20") + "'>Break</a>"
        val n = HommParser.validateNetwork(network(), html, HommParser.SCHOOL_SHA, HommParser.BREAK_SHA, at("2027-01-04", 6))
        assertNull(n.serviceDate)
        assertTrue(n.routes.isNotEmpty())
        assertTrue(DepartureCalculator.departures(n, null, "homm:21", at("2027-01-04", 6), 180).isEmpty())
    }
}
