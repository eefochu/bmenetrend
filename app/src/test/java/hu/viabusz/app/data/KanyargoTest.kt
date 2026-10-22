package hu.viabusz.app.data

import org.junit.Assert.*
import org.junit.Test
import org.jsoup.Jsoup
import java.time.Instant
import java.time.LocalDate

class KanyargoTest {
    private fun resource(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }
    private fun kanyargo() = NetworkJson.decode(resource("kanyargo-network.json"))
    private fun viabusz() = NetworkJson.decode(resource("network.json"))
    private val now = Instant.parse("2026-10-22T12:57:50Z")
    private fun verified(date: String): TransitNetwork {
        val instant = LocalDate.parse(date).atTime(12, 0).atZone(ViabuszParser.zone).toInstant()
        return KanyargoParser.validateNetwork(kanyargo(), resource("kanyargo-timetable.html"), KanyargoParser.SCHOOL_SHA, KanyargoParser.BREAK_SHA, instant)
    }

    @Test fun `reviewed official imports include public and explicitly restricted services`() {
        val network = kanyargo()
        assertEquals(37, network.stops.size)
        assertEquals(KanyargoParser.routeNumbers, network.routes.map { it.number }.toSet())
        assertEquals(37, network.trips.count { it.calendar == "school_term" })
        assertEquals(19, network.trips.count { it.calendar == "school_break" })
        assertEquals(6, network.routes.sumOf { route -> route.directions.count { it.restrictedService } })
        assertTrue(network.stops.all { it.providerId == "kanyargo" && it.id.startsWith("kanyargo:") })
        assertTrue(network.routes.all { it.providerId == "kanyargo" && it.id.startsWith("kanyargo:") })
        assertEquals(network, NetworkJson.decode(NetworkJson.encode(network)))
        // 1MT is the label on the official PDF, despite the HTML stop board shortening it to 1M.
        assertEquals(1, network.trips.count { it.routeId == "kanyargo:1MT" })
    }

    @Test fun `every imported school clock exactly matches the independent official HTML stop board`() {
        val clock = Regex("^(1[A-Z]+) (\\d{2}):(\\d{2})")
        val html = Jsoup.parse(resource("kanyargo-timetable.html"))
        val published = html.select("[id^=timeTableMain]").flatMap { section ->
            val stop = section.id().removePrefix("timeTableMain")
            section.select(".timeth").mapNotNull { cell ->
                clock.find(cell.text())?.let { Triple(stop, it.groupValues[1], it.groupValues[2].toInt() * 60 + it.groupValues[3].toInt()) }
            }
        }.toSet()
        val network = kanyargo()
        val imported = network.trips.filter { it.calendar == "school_term" }.flatMap { trip ->
            val label = network.routes.first { it.id == trip.routeId }.number.replace("1MT", "1M")
            trip.stopTimes.map { Triple(it.stopId.removePrefix("kanyargo:"), label, it.minuteOfDay) }
        }.toSet()
        assertTrue(published.isNotEmpty())
        assertEquals(published, imported)
    }

    @Test fun `provider IDs cannot collide with viabusz stop and route IDs`() {
        val merged = NetworkMerger.merge(listOf(viabusz(), kanyargo()))
        assertEquals(105, merged.stops.size)
        assertEquals(111, merged.stops.flatMap { it.sourceStopIds }.distinct().size)
        assertEquals(13, merged.routes.size)
        assertEquals(merged.stops.size, merged.stops.map { it.id }.toSet().size)
        assertNotEquals(merged.stops.first { it.id == "1" }.name, merged.stops.first { it.id == "kanyargo:1" }.name)
        assertEquals(merged, NetworkJson.decode(NetworkJson.encode(merged)))
    }

    @Test fun `school calendar chooses term and break without guessing public holidays`() {
        assertEquals("school_term", KanyargoCalendar.activeCalendar(LocalDate.parse("2026-10-22")))
        assertNull(KanyargoCalendar.activeCalendar(LocalDate.parse("2026-10-23")))
        assertEquals("school_break", KanyargoCalendar.activeCalendar(LocalDate.parse("2026-10-26")))
        assertEquals("school_term", KanyargoCalendar.activeCalendar(LocalDate.parse("2026-11-02")))
        assertEquals("school_term", KanyargoCalendar.activeCalendar(LocalDate.parse("2026-12-18")))
        assertEquals("school_break", KanyargoCalendar.activeCalendar(LocalDate.parse("2026-12-21")))
        assertNull(KanyargoCalendar.activeCalendar(LocalDate.parse("2026-12-24")))
        assertNull(KanyargoCalendar.activeCalendar(LocalDate.parse("2027-01-04")))
    }

    @Test fun `term and holiday tables are never mixed on the same day`() {
        fun departures(date: String, clock: String) = DepartureCalculator.departures(verified(date), null, "kanyargo:2",
            Instant.parse("${date}T${clock}Z"), 180)
        val school = departures("2026-10-22", "04:00:00")
        val holiday = departures("2026-10-26", "05:00:00")
        assertTrue(school.any { it.routeNumber == "1MT" })
        assertTrue(holiday.any { it.routeNumber == "1MV" })
        assertFalse(holiday.any { it.routeNumber in setOf("1MT", "1M", "1D", "1J") })
        assertTrue(school.all { it.directionId != null && it.kind == ArrivalKind.SCHEDULED })
    }

    @Test fun `station arrival never becomes a boarding departure during layover`() {
        val network = verified("2026-10-22")
        val start = Instant.parse("2026-10-22T03:00:00Z")
        assertTrue(DepartureCalculator.departures(network, null, "kanyargo:29", start, 180).isEmpty())
        val inbound = DepartureCalculator.departures(network, null, "kanyargo:30", start, 180)
        assertEquals(listOf("2026-10-22T04:30:00Z", "2026-10-22T04:55:00Z"), inbound.map { it.expectedAt.toString() })
        assertTrue(inbound.all { it.destination.endsWith("Községháza") })
    }

    @Test fun `school eligibility stays attached to each actual departure`() {
        val network = verified("2026-10-22")
        val departures = DepartureCalculator.departures(network, null, "kanyargo:50", Instant.parse("2026-10-22T11:30:00Z"), 120)
        assertTrue(departures.isNotEmpty())
        assertTrue(departures.all { it.restrictedService && it.directionId != null })
        assertTrue(DepartureCalculator.departures(verified("2026-10-26"), null, "kanyargo:50", Instant.parse("2026-10-26T12:30:00Z"), 120).isEmpty())
    }

    @Test fun `one unavailable provider does not blank another provider timetable`() {
        val via = viabusz()
        val kanyar = verified("2026-10-22")
        val merged = NetworkMerger.merge(listOf(via.copy(serviceDate = null), kanyar))
        val at = Instant.parse("2026-10-22T03:00:00Z")
        val sharedBoard = DepartureCalculator.departures(merged, null, "1", at, 180)
        assertTrue(sharedBoard.isNotEmpty())
        assertTrue(sharedBoard.all { it.routeId.startsWith("kanyargo:") })
        assertTrue(DepartureCalculator.departures(merged, null, "kanyargo:2", at, 180).isNotEmpty())
        val other = NetworkMerger.merge(listOf(via, kanyar.copy(serviceDate = null, providerServiceDates = mapOf("kanyargo" to null))))
        assertTrue(DepartureCalculator.departures(other, null, "1", at, 180).isNotEmpty())
        assertTrue(DepartureCalculator.departures(other, null, "kanyargo:2", at, 180).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class) fun `changed official PDF disables imported countdowns`() {
        KanyargoParser.validateNetwork(kanyargo(), resource("kanyargo-timetable.html"), "new timetable bytes", KanyargoParser.BREAK_SHA, now)
    }

    @Test(expected = IllegalArgumentException::class) fun `changed active timetable link disables imported countdowns`() {
        KanyargoParser.validateNetwork(kanyargo(), resource("kanyargo-timetable.html").replace(KanyargoParser.SCHOOL_PATH, "documents/new-timetable.pdf"), KanyargoParser.SCHOOL_SHA, KanyargoParser.BREAK_SHA, now)
    }

    @Test fun `real KanyarGO GPS feed is fresh namespaced and never assigned guessed routes`() {
        val body = resource("kanyargo-vehicles.txt")
        val vehicles = KanyargoParser.parseVehicles(body, now)
        assertEquals(2, vehicles.size)
        assertTrue(vehicles.all { it.providerId == "kanyargo" && it.id.startsWith("kanyargo:") && it.routeId == null })
        assertTrue(KanyargoParser.parseVehicles(body, now.plusSeconds(300)).isEmpty())
        assertTrue(KanyargoParser.parseVehicleArrivals(body, vehicles).isEmpty())
        val aggregate = KanyargoParser.parseAggregateArrivals("2;7;1#42;15;1")
        assertEquals(listOf("kanyargo:2", "kanyargo:42"), aggregate.map { it.stopId })
        assertTrue(aggregate.all { !it.isVerifiedRealtime && it.routeId == null })
    }

    @Test fun `known holiday PDF may replace the school link during verified school break`() {
        val page = resource("kanyargo-timetable.html").replace(KanyargoParser.SCHOOL_PATH, KanyargoParser.BREAK_PATH)
        val result = KanyargoParser.validateNetwork(kanyargo(), page, KanyargoParser.SCHOOL_SHA, KanyargoParser.BREAK_SHA, Instant.parse("2026-10-26T11:00:00Z"))
        assertEquals(LocalDate.parse("2026-10-26"), result.serviceDate)
    }

    @Test fun `current official PDF-only page enables departures without optional HTML stop selector`() {
        val page = resource("kanyargo-timetable-links-only.html")
        assertTrue(Jsoup.parse(page).select("#busStopSelector option").isEmpty())
        for ((date, clock) in listOf("2026-10-22" to "03:00:00", "2026-10-26" to "04:00:00")) {
            val instant = Instant.parse("${date}T${clock}Z")
            val network = KanyargoParser.validateNetwork(kanyargo(), page, KanyargoParser.SCHOOL_SHA, KanyargoParser.BREAK_SHA, instant)
            assertEquals(LocalDate.parse(date), network.providerServiceDates["kanyargo"])
            assertTrue(DepartureCalculator.departures(network, null, "kanyargo:2", instant, 180).isNotEmpty())
        }
    }

    @Test fun `source digest is computed as hexadecimal SHA256`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", KanyargoParser.sha256("abc".toByteArray()))
    }
}
