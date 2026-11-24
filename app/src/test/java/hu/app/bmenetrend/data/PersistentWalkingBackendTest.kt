package hu.app.bmenetrend.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.time.LocalDate

class PersistentWalkingBackendTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val now = Instant.parse("2026-11-24T12:00:00Z")
    private val p1 = PlanPoint("Point A", GeoPoint(47.491234, 19.041234))
    private val p2 = PlanPoint("Point B", GeoPoint(47.495678, 19.045678))

    private class FakePlannerBackend(
        var partialResult: Boolean = false,
        var secondsPerWalk: Long = 180L,
        var metersPerWalk: Double = 250.0,
    ) : PlannerBackend {
        var callCount = 0
        val requestedPairs = mutableListOf<List<Pair<PlanPoint, PlanPoint>>>()

        override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> = emptyList()

        override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
            callCount++
            requestedPairs += pairs
            val links = pairs.associate { (a, b) ->
                (a.position to b.position) to WalkingLink(a, b, secondsPerWalk, metersPerWalk)
            }
            return WalkingResult(links, partial = partialResult)
        }
    }

    @Test
    fun secondCallForSamePairsMakesNoDelegateCallAndReturnsEqualSecondsMeters() = runBlocking {
        val file = File(tempFolder.newFolder(), "walks.tsv")
        val fake = FakePlannerBackend()
        val store = WalkingLinkStore(file, io = Dispatchers.Unconfined)
        val backend = PersistentWalkingBackend(fake, store)

        val pairs = listOf(p1 to p2)
        val first = backend.walking(pairs, now)
        assertEquals(1, fake.callCount)
        val link1 = first.links[p1.position to p2.position]
        assertNotNull(link1)
        assertEquals(180L, link1!!.seconds)
        assertEquals(250.0, link1.meters, 1e-4)

        val second = backend.walking(pairs, now)
        assertEquals(1, fake.callCount)
        val link2 = second.links[p1.position to p2.position]
        assertNotNull(link2)
        assertEquals(link1.seconds, link2!!.seconds)
        assertEquals(link1.meters, link2.meters, 1e-4)
    }

    @Test
    fun newStoreInstanceOnSameFileServesThemSurvivingRestart() = runBlocking {
        val file = File(tempFolder.newFolder(), "walks.tsv")
        val fake1 = FakePlannerBackend()
        val store1 = WalkingLinkStore(file, io = Dispatchers.Unconfined)
        val backend1 = PersistentWalkingBackend(fake1, store1)

        val pairs = listOf(p1 to p2)
        val res1 = backend1.walking(pairs, now)
        assertEquals(1, fake1.callCount)
        val link1 = res1.links[p1.position to p2.position]!!

        // Simulate app restart with a fresh store and fake delegate
        val fake2 = FakePlannerBackend()
        val store2 = WalkingLinkStore(file, io = Dispatchers.Unconfined)
        val backend2 = PersistentWalkingBackend(fake2, store2)

        val res2 = backend2.walking(pairs, now)
        assertEquals(0, fake2.callCount)
        val link2 = res2.links[p1.position to p2.position]
        assertNotNull(link2)
        assertEquals(link1.seconds, link2!!.seconds)
        assertEquals(link1.meters, link2.meters, 1e-4)
    }

    @Test
    fun entriesOlderThan60DaysAreRefetched() = runBlocking {
        val file = File(tempFolder.newFolder(), "walks.tsv")
        val fake = FakePlannerBackend()
        var currentDate = LocalDate.of(2026, 1, 1)
        val store = WalkingLinkStore(file, io = Dispatchers.Unconfined, today = { currentDate })
        val backend = PersistentWalkingBackend(fake, store)

        val pairs = listOf(p1 to p2)
        backend.walking(pairs, now)
        assertEquals(1, fake.callCount)

        // Advance 59 days -> within 60 days limit (MAX_AGE_DAYS = 60L)
        currentDate = currentDate.plusDays(59)
        backend.walking(pairs, now)
        assertEquals(1, fake.callCount)

        // Advance to 61 days older than initial save -> expired, must re-fetch from delegate
        currentDate = LocalDate.of(2026, 1, 1).plusDays(61)
        backend.walking(pairs, now)
        assertEquals(2, fake.callCount)
    }

    @Test
    fun partialResultsFromDelegateArePassedThroughAsPartial() = runBlocking {
        val file = File(tempFolder.newFolder(), "walks.tsv")
        val fake = FakePlannerBackend(partialResult = true)
        val store = WalkingLinkStore(file, io = Dispatchers.Unconfined)
        val backend = PersistentWalkingBackend(fake, store)

        val pairs = listOf(p1 to p2)
        val result = backend.walking(pairs, now)
        assertTrue("Partial result from delegate must pass through as partial", result.partial)
        assertEquals(1, fake.callCount)
    }
}
