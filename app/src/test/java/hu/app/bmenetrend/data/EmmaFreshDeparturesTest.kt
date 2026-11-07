package hu.app.bmenetrend.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EmmaFreshDeparturesTest {

    private val now = Instant.ofEpochSecond(1_700_000_000)
    private val windowMinutes = 30

    private fun makeDeparture(
        routeId: String,
        expectedAt: Instant,
        stopId: String = "stop1"
    ) = Departure(
        routeId = routeId,
        routeNumber = routeId,
        destination = "Destination",
        stopId = stopId,
        scheduledAt = expectedAt,
        expectedAt = expectedAt,
        kind = ArrivalKind.SCHEDULED
    )

    private fun makeBoard(
        stopId: String = "stop1",
        departures: List<Departure> = emptyList(),
        fetchedAt: Instant = now,
        limitReached: Boolean = false
    ) = EmmaStopsParser.Board(
        stopId = stopId,
        departures = departures,
        catalogue = EmmaStopsParser.Catalogue(emptyList(), emptyList()),
        fetchedAt = fetchedAt,
        limitReached = limitReached
    )

    @Test
    fun freshDepartures_nullBoard_returnsEmpty() {
        val result = EmmaStopsParser.freshDepartures(null, "stop1", now, windowMinutes)
        assertTrue(result.isEmpty())
    }

    @Test
    fun freshDepartures_nullStopId_returnsEmpty() {
        val board = makeBoard(stopId = "stop1", departures = listOf(makeDeparture("R1", now)))
        val result = EmmaStopsParser.freshDepartures(board, null, now, windowMinutes)
        assertTrue(result.isEmpty())
    }

    @Test
    fun freshDepartures_wrongStopId_returnsEmpty() {
        val board = makeBoard(stopId = "stop1", departures = listOf(makeDeparture("R1", now)))
        val result = EmmaStopsParser.freshDepartures(board, "differentStop", now, windowMinutes)
        assertTrue(result.isEmpty())
    }

    @Test
    fun freshDepartures_staleBoardOver60Seconds_returnsEmpty() {
        val dep = makeDeparture("R1", now.plusSeconds(300))
        // 61 seconds old
        val board = makeBoard(stopId = "stop1", departures = listOf(dep), fetchedAt = now.minusSeconds(61))
        val result = EmmaStopsParser.freshDepartures(board, "stop1", now, windowMinutes)
        assertTrue(result.isEmpty())
    }

    @Test
    fun freshDepartures_boardAtExactly60SecondsBoundary_returnsDepartures() {
        val dep = makeDeparture("R1", now.plusSeconds(300))
        // Exactly 60 seconds old
        val board = makeBoard(stopId = "stop1", departures = listOf(dep), fetchedAt = now.minusSeconds(60))
        val result = EmmaStopsParser.freshDepartures(board, "stop1", now, windowMinutes)
        assertEquals(listOf(dep), result)
    }

    @Test
    fun freshDepartures_boardFetchedAtNow_returnsDepartures() {
        val dep = makeDeparture("R1", now.plusSeconds(300))
        val board = makeBoard(stopId = "stop1", departures = listOf(dep), fetchedAt = now)
        val result = EmmaStopsParser.freshDepartures(board, "stop1", now, windowMinutes)
        assertEquals(listOf(dep), result)
    }

    @Test
    fun freshDepartures_futureBoardNegativeDuration_returnsEmpty() {
        val dep = makeDeparture("R1", now.plusSeconds(300))
        // fetchedAt is in future relative to now (Duration.between(board.fetchedAt, now).seconds < 0)
        val board = makeBoard(stopId = "stop1", departures = listOf(dep), fetchedAt = now.plusSeconds(1))
        val result = EmmaStopsParser.freshDepartures(board, "stop1", now, windowMinutes)
        assertTrue(result.isEmpty())
    }

    @Test
    fun freshDepartures_boundaryAtNow_isIncluded() {
        val atNow = makeDeparture("R_NOW", now)
        val board = makeBoard(stopId = "stop1", departures = listOf(atNow))
        val result = EmmaStopsParser.freshDepartures(board, "stop1", now, windowMinutes)
        assertEquals(listOf(atNow), result)
    }

    @Test
    fun freshDepartures_boundaryOneSecondBeforeNow_isExcluded() {
        val beforeNow = makeDeparture("R_BEFORE", now.minusSeconds(1))
        val board = makeBoard(stopId = "stop1", departures = listOf(beforeNow))
        val result = EmmaStopsParser.freshDepartures(board, "stop1", now, windowMinutes)
        assertTrue(result.isEmpty())
    }

    @Test
    fun freshDepartures_boundaryAtNowPlusWindow_isIncluded() {
        val atWindowEnd = makeDeparture("R_END", now.plusSeconds(windowMinutes * 60L))
        val board = makeBoard(stopId = "stop1", departures = listOf(atWindowEnd))
        val result = EmmaStopsParser.freshDepartures(board, "stop1", now, windowMinutes)
        assertEquals(listOf(atWindowEnd), result)
    }

    @Test
    fun freshDepartures_boundaryOneSecondAfterWindow_isExcluded() {
        val afterWindowEnd = makeDeparture("R_AFTER", now.plusSeconds(windowMinutes * 60L + 1))
        val board = makeBoard(stopId = "stop1", departures = listOf(afterWindowEnd))
        val result = EmmaStopsParser.freshDepartures(board, "stop1", now, windowMinutes)
        assertTrue(result.isEmpty())
    }

    @Test
    fun freshDepartures_filtersOnlyDeparturesInsideWindow_preservingOrder() {
        val depBefore = makeDeparture("R0", now.minusSeconds(10))
        val depAtNow = makeDeparture("R1", now)
        val depMid = makeDeparture("R2", now.plusSeconds(600))
        val depAtEnd = makeDeparture("R3", now.plusSeconds(windowMinutes * 60L))
        val depAfter = makeDeparture("R4", now.plusSeconds(windowMinutes * 60L + 10))

        val board = makeBoard(
            stopId = "stop1",
            departures = listOf(depBefore, depAtNow, depMid, depAtEnd, depAfter)
        )

        val result = EmmaStopsParser.freshDepartures(board, "stop1", now, windowMinutes)
        assertEquals(listOf(depAtNow, depMid, depAtEnd), result)
    }
}
