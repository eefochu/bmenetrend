package hu.app.bmenetrend.data

import hu.app.bmenetrend.ui.departureDirection
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class G10RailLineTest {
    private val route = TransitRoute("mav:BKK:G10", "G10", "G10", emptyList(), "mav")
    private fun stop(id: String, name: String = id) = TransitStop(id, name, GeoPoint(47.5, 19.0), listOf(route.id), "mav")
    private val deli = stop("deli", "Budapest-Déli")
    private val shared = stop("shared", "Tatabánya")
    private val gyor = stop("gyor", "Győr")
    private val hegyeshalom = stop("hegyeshalom", "Hegyeshalom")
    private fun pattern(id: String, stops: List<TransitStop>) =
        LinePattern(id, emptySet(), stops.last().name, stops, emptyList(), directionId = "-1")
    private val gyorShort = pattern("gyor-12", listOf(deli, shared) + (1..9).map { stop("short-$it") } + gyor)
    private val gyorLong = pattern("gyor-14", listOf(deli, shared) + (1..11).map { stop("long-$it") } + gyor)
    private val inbound = pattern("deli", listOf(hegyeshalom, gyor, shared, deli))
    private val outbound = pattern("hegyeshalom", listOf(deli, shared, gyor, hegyeshalom))
    private val details = LineDetails(route, listOf(inbound, outbound, gyorShort, gyorLong))
    private val departure = Departure(route.id, "G10", "Győr", shared.id, null, Instant.EPOCH,
        ArrivalKind.LIVE, sourceDirectionId = "1", patternCode = null)

    @Test fun noDirectionBoardingMatchesDestinationForEitherBkkDirection() {
        val selection = BoardingSelection(route.id, "Győr", gyorShort)
        for (direction in listOf("0", "1")) {
            assertTrue(selection.matches(departure.copy(sourceDirectionId = direction)))
            assertFalse(selection.matches(departure.copy(sourceDirectionId = direction, destination = "Budapest-Déli")))
        }
    }

    @Test fun noDirectionWithCodesRejectsCodelessDepartureEvenWithMatchingDestination() {
        val selection = BoardingSelection(route.id, "Győr", gyorShort.copy(codes = setOf("emma:pattern")))
        assertFalse(selection.matches(departure))
    }

    @Test fun gyorStopSkippingVariantsResolveToTheirSharedFamily() {
        for (patterns in listOf(details.patterns, details.patterns.reversed())) {
            assertTrue(departureDirection(departure, route, details.copy(patterns = patterns)) in
                setOf(gyorShort.id, gyorLong.id))
        }
    }

    @Test fun deliDestinationResolvesToDeliPatternDespiteBkkSourceDirection() {
        assertEquals(inbound.id, departureDirection(departure.copy(destination = "Budapest-Déli"), route, details))
    }

    @Test fun normalDirectionStillFiltersBySourceDirection() {
        val normal = gyorShort.copy(directionId = "0")
        val normalDetails = LineDetails(route, listOf(normal))
        val selection = BoardingSelection(route.id, "Győr", normal)
        assertEquals(normal.id, departureDirection(departure.copy(sourceDirectionId = "0"), route, normalDetails))
        assertNull(departureDirection(departure, route, normalDetails))
        assertTrue(selection.matches(departure.copy(sourceDirectionId = "0")))
        assertFalse(selection.matches(departure))
    }
}
