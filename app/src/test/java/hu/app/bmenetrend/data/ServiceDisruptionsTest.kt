package hu.app.bmenetrend.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ServiceDisruptionsTest {
    private val now = Instant.parse("2026-11-14T21:20:00Z")
    private val context = DisruptionContext(true, now, now.plusSeconds(300), route = "0150", type = 3,
        stops = setOf("F01037"), trip = "D123", date = "20261114", direction = 0)
    private fun selector(route: String? = "0150", stop: String? = null, trip: AlertTrip? = null) = AlertSelector(null, route, null, stop, null, trip)
    private fun alert(selectors: List<AlertSelector> = listOf(selector()), periods: List<AlertPeriod> = emptyList(), effect: Int = 9) =
        ServiceAlert("a", periods, selectors, listOf(AlertText("hu", "Megállóáthelyezés")), emptyList(), emptyList(), effect, 1)
    private fun snapshot(alert: ServiceAlert = alert()) = DisruptionSnapshot(alerts = listOf(alert), alertsAt = now, changesAt = now, attempted = true)

    @Test fun `selectors use AND within an entry and OR between entries`() {
        val a = alert(listOf(selector(stop = "other"), selector(route = "unrelated", stop = "F01037")))
        assertTrue(snapshot(a).warnings(listOf(context), now).isEmpty())
        val b = a.copy(selectors = a.selectors + selector(stop = "F01037"))
        assertEquals(1, snapshot(b).warnings(listOf(context), now).size)
        assertTrue(snapshot(b).warnings(listOf(context.copy(bkk = false)), now).isEmpty())
        assertTrue(snapshot(alert(emptyList())).warnings(listOf(context), now).isEmpty())
    }
    @Test fun `every optional selector field is respected without guessing missing identity`() {
        val base = selector()
        for (s in listOf(base.copy(agency = "other"), base.copy(type = 0), base.copy(direction = 1),
            base.copy(trip = AlertTrip("D123", "20261113", null, null, null)),
            base.copy(trip = AlertTrip("D123", null, "12:00:00", null, null)),
            base.copy(trip = AlertTrip("other", null, null, null, null)),
            base.copy(trip = AlertTrip("D123", null, null, "other", null)))) {
            assertTrue(snapshot(alert(listOf(s))).warnings(listOf(context), now).isEmpty())
        }
        assertEquals(1,snapshot(alert(listOf(base.copy(agency = "BKK", type = 3, direction = 0,
            trip = AlertTrip("D123", "20261114", null, "0150", 0))))).warnings(listOf(context), now).size)
    }
    @Test fun `active periods include future changes during the journey and exclude expired changes`() {
        val past = AlertPeriod(now.epochSecond - 600, now.epochSecond)
        assertTrue(snapshot(alert(periods = listOf(past))).warnings(listOf(context), now).isEmpty())
        val future = AlertPeriod(now.epochSecond + 120, now.epochSecond + 240)
        assertEquals(1,snapshot(alert(periods = listOf(future))).warnings(listOf(context), now).size)
        assertTrue(snapshot(alert(periods = listOf(future))).warnings(listOf(context.copy(until = now)), now).isEmpty())
    }
    @Test fun `outage preserves clearly stale notices but never stale live cancellations`() {
        val c = TripChange("c", AlertTrip("D123", "20261114", null, "0150", null), true, emptySet(), now.minusSeconds(3600))
        val fresh = snapshot().copy(changes = listOf(c))
        // Cancellation remains valid when a fresh full feed repeats an older event.
        assertEquals(2,fresh.warnings(listOf(context), now).size)
        val failed = fresh.copy(alertsUnavailable = true, changesUnavailable = true)
        assertEquals(1,failed.warnings(listOf(context), now).size)
        assertTrue(failed.warnings(listOf(context), now).single().stale)
        assertEquals(1,fresh.warnings(listOf(context), now, offline = true).size)
        assertTrue(fresh.copy(alertsAt = now.minusSeconds(21601), changesAt = now.minusSeconds(181)).warnings(listOf(context), now).isEmpty())
    }
    @Test fun `daily cancellation identity must include explicit date and frequency start time`() {
        val trip = AlertTrip("D123", "20261114", null, "0150", null)
        val change = TripChange("c", trip, true, emptySet(), now)
        val state = DisruptionSnapshot(changes = listOf(change), changesAt = now)
        assertEquals(1,state.warnings(listOf(context), now).size)
        assertTrue(state.warnings(listOf(context.copy(date = null)), now).isEmpty())
        assertTrue(state.warnings(listOf(context.copy(date = "20261115")), now).isEmpty())
        assertTrue(state.copy(changes = listOf(change.copy(trip = trip.copy(time = "26:10:00")))).warnings(listOf(context), now).isEmpty())
        assertTrue(state.copy(changes = listOf(change.copy(trip = trip.copy(date = null)))).warnings(listOf(context), now).isEmpty())
    }
    @Test fun `skipped stop requires the exact trip and an affected stop`() {
        val change = TripChange("s", AlertTrip("D123","20261114",null,"0150",null),false,setOf("F01037"),now)
        val state = DisruptionSnapshot(changes = listOf(change), changesAt = now)
        assertEquals(1,state.warnings(listOf(context),now).size)
        assertTrue(state.warnings(listOf(context.copy(stops = setOf("other"))),now).isEmpty())
        assertTrue(state.warnings(listOf(context.copy(trip = "other")),now).isEmpty())
    }
    @Test fun `HTML is plain text, translations have a fallback, only official HTTPS links can open`() {
        assertEquals("Új megálló",ServiceDisruptionsParser.plain("<p>Új <strong>megálló</strong></p><script>alert(1)</script>"))
        assertEquals("Magyar",ServiceDisruptionsParser.localized(listOf(AlertText("hu","Magyar"),AlertText("en","English")),"de"))
        assertEquals("English",ServiceDisruptionsParser.localized(listOf(AlertText("hu","Magyar"),AlertText("en","English")),"en"))
        assertNotNull(ServiceDisruptionsParser.safeUrl("https://bkk.hu/bkk-info#!i=123"))
        for (url in listOf("javascript:alert(1)","http://bkk.hu","https://bkk.hu.evil.test","https://evil.test","https://user@bkk.hu"))
            assertNull(url, ServiceDisruptionsParser.safeUrl(url))
    }
    @Test fun `partial failure retains previous feed and an empty successful full snapshot clears it`() {
        val previous = snapshot()
        val failed = ServiceDisruptionsParser.parse("""{"status":"OK","alerts":{"unavailable":true},"tripChanges":{"timestamp":${now.epochSecond},"items":[]}}""",previous)
        assertEquals(previous.alerts,failed.alerts); assertTrue(failed.alertsUnavailable); assertFalse(failed.changesUnavailable)
        val cleared = ServiceDisruptionsParser.parse("""{"status":"OK","alerts":{"timestamp":${now.epochSecond},"items":[]},"tripChanges":{"unavailable":true}}""",failed)
        assertTrue(cleared.alerts.isEmpty()); assertFalse(cleared.alertsUnavailable); assertTrue(cleared.changesUnavailable)
    }
    @Test fun `feed parser preserves exact selector and change identity`() {
        val state = ServiceDisruptionsParser.parse("""{"status":"OK","alerts":{"timestamp":${now.epochSecond},"items":[{"id":"a","effect":9,"severity":3,"periods":[{"start":${now.epochSecond}}],"selectors":[{"routeId":"0150","stopId":"F01037"}],"title":[{"language":"hu","text":"<b>Figyelem</b>"}]}]},"tripChanges":{"timestamp":${now.epochSecond},"items":[{"id":"c","trip":{"tripId":"D123","startDate":"20261114","routeId":"0150"},"cancelled":true,"skippedStops":[]}]}}""")
        assertEquals("Figyelem",state.alerts.single().title.single().text)
        assertEquals(2,state.warnings(listOf(context),now).size)
        assertTrue(state.warnings(listOf(context),now).all { it.important })
    }
    @Test fun `namespace mapping never matches an unrelated operators line number`() {
        assertEquals("0150",DisruptionScope.route("bkk:BKK:0150"))
        assertNull(DisruptionScope.route("mav:1:0150")); assertNull(DisruptionScope.route("volan:hkir:0150"))
        assertEquals("F01037",DisruptionScope.stop("emma:BKK:F01037"))
        assertNull(DisruptionScope.stop("emma:1:F01037")); assertNull(DisruptionScope.trip("1:D123"))
        assertEquals("D123",DisruptionScope.trip("BKK:D123"))
    }
    @Test fun `planner preserves explicit GTFS trip date and stops through routing validation`() {
        val start = now.plusSeconds(120).toEpochMilli(); val end = now.plusSeconds(720).toEpochMilli()
        val text = """{"status":"OK","data":{"entry":{"plan":{"itineraries":[{"legs":[{"mode":"BUS","startTime":$start,"endTime":$end,"distance":2000,"routeId":"BKK_0150","routeShortName":"15","tripId":"BKK_D123","serviceDate":"20261114","from":{"name":"A","lat":47.5,"lon":19.05,"stopId":"BKK_F01037"},"to":{"name":"B","lat":47.51,"lon":19.05,"stopId":"BKK_F01038"},"intermediateStops":[{"name":"Middle","lat":47.505,"lon":19.05,"stopId":"BKK_F01039"}]}]}]}}}}"""
        val trip = BkkWorkerParser.plan(text, now).single()
        val scope = DisruptionScope.itinerary(trip).single()
        assertEquals("D123",scope.trip); assertEquals("20261114",scope.date)
        assertEquals(setOf("F01037","F01038","F01039"),scope.stops)
        assertEquals("0150",scope.route); assertEquals("15 → B",scope.label)
        val change = TripChange("c",AlertTrip("D123","20261114",null,"0150",null),true,emptySet(),now)
        assertEquals("15 → B",snapshot().copy(changes = listOf(change)).warnings(listOf(scope),now).first().serviceLabel)
    }

}
