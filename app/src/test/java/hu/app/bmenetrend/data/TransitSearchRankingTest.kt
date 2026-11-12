package hu.app.bmenetrend.data

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class TransitSearchRankingTest {
    private fun index(prefix: String, count: Int = 300): List<TransitSearch.StopEntry> =
        TransitSearch.indexStops(List(count) { i ->
            TransitStop(
                id = "$prefix-$i",
                name = when {
                    i == count - 1 -> "$prefix zqx"
                    i % 17 == 0 -> "$prefix unrelated $i"
                    else -> "$prefix zqx stop $i"
                },
                position = GeoPoint(47.0 + (i % 11) * 0.01, 19.0 + (i % 7) * 0.01),
                routeIds = emptyList(),
                providerId = if (i % 3 == 0) "volan" else "bkk",
            )
        })

    private data class Ranked(val id: String, val rank: Int, val distSq: Double)

    private fun reference(
        index: List<TransitSearch.StopEntry>, query: TransitSearch.Query,
        location: GeoPoint?, favorites: Set<String>,
    ): List<String> {
        if (query.modes.isNotEmpty()) return emptyList()
        return index.asSequence().mapNotNull { entry ->
            if (query.provider != null && query.provider !in entry.stop.providerIds) return@mapNotNull null
            if (query.terms.any { !entry.text.contains(it) }) return@mapNotNull null
            val rank = when {
                entry.name == query.text -> 2
                entry.stop.id in favorites -> 1
                else -> 0
            }
            val distSq = location?.let {
                val lat = entry.stop.position.lat - it.lat
                val lon = entry.stop.position.lon - it.lon
                lat * lat + lon * lon
            } ?: 0.0
            Ranked(entry.stop.id, rank, distSq)
        }.sortedWith(compareByDescending<Ranked> { it.rank }.thenBy { it.distSq })
            .take(100).map { it.id }.toList()
    }

    private fun assertMatchesReference(prefix: String, count: Int, location: GeoPoint?, provider: String = "") {
        val index = index(prefix, count)
        assertEquals(count, index.size)
        val query = TransitSearch.query("$provider $prefix zqx")
        val favorites = setOf("$prefix-${count - 2}", "$prefix-${count - 3}")
        val expected = reference(index, query, location, favorites)
        if (count == 300) assertEquals(100, expected.size)
        else assertTrue(expected.size < 100)
        assertTrue(expected.isNotEmpty())
        assertEquals(expected, TransitSearch.stops(index, query, location, favorites).map { it.id })
    }

    @Test fun boundedRankingMatchesStableSortWithLocationAndDistanceTies() {
        assertMatchesReference("rankinglocated", 300, GeoPoint(47.0, 19.0))
    }

    @Test fun boundedRankingMatchesStableRankOrderWithoutLocation() {
        assertMatchesReference("rankingunlocated", 300, null)
    }

    @Test fun providerFilterMatchesStableSort() {
        assertMatchesReference("rankingprovider", 300, GeoPoint(47.0, 19.0), "bkk")
        assertMatchesReference("rankingprovidernull", 300, null, "bkk")
    }

    @Test fun fewerThanOneHundredMatchesMatchStableSort() {
        assertMatchesReference("rankingsmall", 40, GeoPoint(47.0, 19.0))
        assertMatchesReference("rankingsmallnull", 40, null)
    }

    @Test fun modeQueryReturnsNoStops() {
        val query = TransitSearch.query("busz zqx")
        assertTrue(query.modes.isNotEmpty())
        assertTrue(TransitSearch.stops(index("rankingmode"), query, null, emptySet()).isEmpty())
    }

    @Test fun checkpointIsInvokedForThreeHundredEntries() {
        val index = index("rankingcheckpoint")
        var calls = 0
        TransitSearch.stops(index, TransitSearch.query("zqx"), null, emptySet()) { calls++ }
        assertTrue(calls > 0)
    }

    @Test fun checkpointCancellationPropagates() {
        val index = index("rankingcancel")
        val cancellation = CancellationException("Superseded search")
        try {
            TransitSearch.stops(index, TransitSearch.query("zqx"), null, emptySet()) { throw cancellation }
            fail("Expected checkpoint cancellation to propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }
}
