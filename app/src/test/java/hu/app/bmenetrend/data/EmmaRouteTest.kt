package hu.app.bmenetrend.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EmmaRouteTest {
    private fun stop(id: String, lat: Double = 47.46) = JSONObject().put("gtfsId", "BKK:$id").put("name", id).put("lat", lat).put("lon", 19.03)
    private fun pattern(code: String, vararg ids: String) = JSONObject().put("code", "BKK:$code").put("headsign", ids.last()).put("directionId", if (code == "in") "0" else "1")
        .put("stops", JSONArray(ids.map { stop(it) })).put("geometry", JSONArray().put(JSONObject().put("lat", 47.46).put("lon", 19.03))
            .put(JSONObject().put("lat", 47.47).put("lon", 19.04)))
    private fun response() = JSONObject().put("data", JSONObject().put("route", JSONObject().put("gtfsId", "BKK:3010")
        .put("shortName", "1").put("mode", "TRAM").put("color", "FFD800").put("patterns", JSONArray()
            .put(pattern("out:calendar1", "A", "B", "C")).put(pattern("out:calendar2", "A", "B", "C"))
            .put(pattern("in", "C", "B-opposite", "A")).put(pattern("loop", "A", "B", "A", "C")))))
    @Test fun groupsIdenticalCalendarVariantsButPreservesDirectionPlatformsAndLoops() {
        val details = EmmaRouteParser.parse(response().toString(), "bkk:BKK:3010")
        assertEquals(3, details.patterns.size)
        val outbound = details.patterns.single { it.stops.map(TransitStop::id) == listOf("emma:BKK:A", "emma:BKK:B", "emma:BKK:C") }
        assertEquals(setOf("BKK:out:calendar1", "BKK:out:calendar2"), outbound.codes)
        assertEquals(2, outbound.geometry.size)
        assertTrue(details.patterns.any { it.stops.map { stop -> stop.id }.contains("emma:BKK:B-opposite") })
        assertEquals(4, details.patterns.single { it.id == "BKK:loop" }.stops.size)
    }
    @Test fun boardingIncludesConvergingShortStartsButNeverDifferentOnwardStopSequences() {
        val root = response()
        root.getJSONObject("data").getJSONObject("route").getJSONArray("patterns").put(pattern("short", "B", "C"))
        val details = EmmaRouteParser.parse(root.toString(), "bkk:BKK:3010")
        val selected = details.boardingPattern("BKK:out:calendar1", "emma:BKK:B")!!
        assertEquals(setOf("BKK:out:calendar1", "BKK:out:calendar2", "BKK:short"), selected.codes)
        assertFalse("BKK:loop" in selected.codes)
        assertFalse("BKK:in" in selected.codes)
        assertNull(details.boardingPattern("BKK:out:calendar1", "emma:BKK:not-served"))
    }
    @Test fun rejectsWrongRouteAndPartialGraphqlFailure() {
        assertTrue(runCatching { EmmaRouteParser.parse(response().toString(), "bkk:BKK:other") }.isFailure)
        assertTrue(runCatching { EmmaRouteParser.parse(response().put("errors", JSONArray().put("failed")).toString(), "bkk:BKK:3010") }.isFailure)
    }
    @Test fun tripGeometryUsesLongitudeLatitudeWithoutStraightLineFabrication() {
        assertEquals(listOf(GeoPoint(47.46, 19.03), GeoPoint(47.47, 19.04)), EmmaRouteParser.tripGeometry(JSONArray("[[19.03,47.46],[19.04,47.47]]")))
        assertTrue(EmmaRouteParser.tripGeometry(null).isEmpty())
        assertTrue(runCatching { EmmaRouteParser.tripGeometry(JSONArray("[[190,47]]")) }.isFailure)
    }
    @Test fun staticCatalogueKeepsUntrackedLinesAndExcludesUnrelatedOperatorsAndModes() {
        val rows = JSONArray().put(JSONObject().put("gtfsId", "BKK:3010").put("shortName", "1").put("mode", "TRAM").put("color", "FFD800"))
            .put(JSONObject().put("gtfsId", "hkir:760").put("shortName", "760").put("mode", "COACH"))
            .put(JSONObject().put("gtfsId", "1:rail").put("shortName", "S10").put("mode", "RAIL"))
            .put(JSONObject().put("gtfsId", "Other:1").put("shortName", "1").put("mode", "BUS"))
        val parsed = EmmaRouteParser.catalogue(JSONObject().put("data", JSONObject().put("routes", rows)).toString())
        assertEquals(setOf("bkk:BKK:3010", "volan:hkir:760", "mav:1:rail"), parsed.map { it.id }.toSet())
        assertEquals("TRAM", parsed.first().transportMode)
        assertEquals("FFD800", parsed.first().color)
    }
    @Test fun boardingFilterNeverMixesOppositeDirectionsAndAcceptsAllGroupedCalendars() {
        val pattern = EmmaRouteParser.parse(response().toString(), "bkk:BKK:3010").patterns.single { it.codes.size == 2 }
        val filter = BoardingSelection("bkk:BKK:3010", "C", pattern)
        val departure = Departure("bkk:BKK:3010", "1", "C", "stop", null, java.time.Instant.EPOCH, ArrivalKind.LIVE, patternCode = "BKK:out:calendar2")
        assertTrue(filter.matches(departure))
        assertFalse(filter.matches(departure.copy(patternCode = "BKK:in")))
        assertFalse(filter.matches(departure.copy(patternCode = null)))
        assertFalse(filter.matches(departure.copy(routeId = "bkk:BKK:other")))
        val local = BoardingSelection("viabusz:1", "Station", localDirectionId = "out")
        assertTrue(local.matches(departure.copy(routeId = "viabusz:1", directionId = "out")))
        assertFalse(local.matches(departure.copy(routeId = "viabusz:1", directionId = "in")))
    }

    @Test
    fun boardingFilter_bkkVariantWithoutNumericDirection_comparesDestinationAndRejectsOppositeAndRetainsEmmaPriority() {
        val stopA = TransitStop("emma:BKK:F00950", "Déli pályaudvar", GeoPoint(47.500, 19.025), listOf("bkk:BKK:5200"), "bkk")
        val stopB = TransitStop("emma:BKK:F00960", "Örs vezér tere", GeoPoint(47.505, 19.136), listOf("bkk:BKK:5200"), "bkk")

        // 1. BKK metro variant lacking numeric direction (directionId = null, codes = emptySet())
        val bkkPattern = LinePattern(
            id = "bkk:BKK_5200_0",
            codes = emptySet(),
            headsign = "Örs vezér tere",
            stops = listOf(stopA, stopB),
            geometry = emptyList(),
            directionId = null
        )
        val selection = BoardingSelection("bkk:BKK:5200", "Örs vezér tere", bkkPattern)

        // Matching destination name -> matches
        val matchDep = Departure(
            routeId = "bkk:BKK:5200",
            routeNumber = "M2",
            destination = "Örs vezér tere",
            stopId = "emma:BKK:F00950",
            scheduledAt = null,
            expectedAt = java.time.Instant.EPOCH,
            kind = ArrivalKind.LIVE
        )
        assertTrue(selection.matches(matchDep))

        // Whitespace and case-insensitive matching
        assertTrue(selection.matches(matchDep.copy(destination = "  örs vezér tere  ")))

        // Opposite direction -> rejected
        val oppositeDep = matchDep.copy(destination = "Déli pályaudvar")
        assertFalse("Must not admit opposite direction", selection.matches(oppositeDep))

        // Different route -> rejected
        assertFalse(selection.matches(matchDep.copy(routeId = "bkk:BKK:OTHER")))

        // 2. EMMA pattern codes retain priority
        val emmaPattern = LinePattern(
            id = "emma:pattern1",
            codes = setOf("BKK:out:1", "BKK:out:2"),
            headsign = "Örs vezér tere",
            stops = listOf(stopA, stopB),
            geometry = emptyList(),
            directionId = null
        )
        val emmaSelection = BoardingSelection("bkk:BKK:5200", "Örs vezér tere", emmaPattern)

        // Matching pattern code -> matches even if destination text differs
        assertTrue(emmaSelection.matches(matchDep.copy(patternCode = "BKK:out:1", destination = "Something Else")))

        // Wrong pattern code -> rejected even if destination text matches
        assertFalse(emmaSelection.matches(matchDep.copy(patternCode = "BKK:in:1", destination = "Örs vezér tere")))

        // Missing pattern code on EMMA pattern -> rejected (does not fall through to destination match)
        assertFalse(emmaSelection.matches(matchDep.copy(patternCode = null, destination = "Örs vezér tere")))
    }
    @Test fun reversedPatternKeepsStopOrderAndCorrectsAnOppositeHeadsign() {
        val root=response()
        root.getJSONObject("data").getJSONObject("route").getJSONArray("patterns").getJSONObject(2).put("headsign","C")
        val reverse=EmmaRouteParser.parse(root.toString(),"bkk:BKK:3010").patterns.single { "BKK:in" in it.codes }
        assertEquals("A",reverse.headsign)
        assertEquals("C → A",JourneyDirection.endpoints(reverse.stops))
    }

}
