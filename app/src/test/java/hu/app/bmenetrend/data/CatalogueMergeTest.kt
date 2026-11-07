package hu.app.bmenetrend.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.random.Random

class CatalogueMergeTest {

    private fun makeStop(
        id: String,
        name: String = "Stop $id",
        lat: Double = 47.5,
        lon: Double = 19.0,
        routeIds: List<String> = listOf("R1")
    ) = TransitStop(
        id = id,
        name = name,
        position = GeoPoint(lat, lon),
        routeIds = routeIds,
        providerId = "viabusz"
    )

    private fun makeRoute(
        id: String,
        number: String = id,
        name: String = "Line $id"
    ) = TransitRoute(
        id = id,
        number = number,
        name = name,
        directions = emptyList(),
        providerId = "viabusz"
    )

    // --- replaceStops tests ---

    @Test
    fun replaceStops_emptyReplacements_returnsSameInstance() {
        val existing = listOf(makeStop("1"), makeStop("2"))
        val result = CatalogueMerge.replaceStops(existing, emptyMap())
        assertSame(existing, result)
    }

    @Test
    fun replaceStops_noMatchingIds_returnsSameInstance() {
        val existing = listOf(makeStop("1"), makeStop("2"))
        val replacements = mapOf("3" to makeStop("3", "Unrelated"))
        val result = CatalogueMerge.replaceStops(existing, replacements)
        assertSame(existing, result)
    }

    @Test
    fun replaceStops_matchingIdsWithEqualStops_returnsSameInstance() {
        val existing = listOf(makeStop("1", "Same"), makeStop("2", "Same2"))
        val replacements = mapOf("1" to makeStop("1", "Same"))
        val result = CatalogueMerge.replaceStops(existing, replacements)
        assertSame(existing, result)
    }

    @Test
    fun replaceStops_matchingIdWithChangedStop_returnsNewListWithReplacement() {
        val s1 = makeStop("1", "Old Name")
        val s2 = makeStop("2", "Station 2")
        val existing = listOf(s1, s2)
        val s1New = makeStop("1", "New Name")
        val replacements = mapOf("1" to s1New)

        val result = CatalogueMerge.replaceStops(existing, replacements)
        assertNotSame(existing, result)
        assertEquals(listOf(s1New, s2), result)
    }

    @Test
    fun replaceStops_multipleReplacements_replacesOnlyDifferencesAndPreservesOrder() {
        val s1 = makeStop("1", "A")
        val s2 = makeStop("2", "B")
        val s3 = makeStop("3", "C")
        val existing = listOf(s1, s2, s3)

        val s2New = makeStop("2", "B modified")
        val replacements = mapOf(
            "1" to makeStop("1", "A"), // equal
            "2" to s2New,             // changed
            "99" to makeStop("99")     // not present
        )

        val result = CatalogueMerge.replaceStops(existing, replacements)
        assertNotSame(existing, result)
        assertEquals(listOf(s1, s2New, s3), result)
    }

    // --- upsertStops tests ---

    @Test
    fun upsertStops_emptyIncoming_returnsSameInstance() {
        val existing = listOf(makeStop("1"), makeStop("2"))
        val result = CatalogueMerge.upsertStops(existing, emptyList()) { _, _ -> true }
        assertSame(existing, result)
    }

    @Test
    fun upsertStops_allIncomingRejected_returnsSameInstance() {
        val existing = listOf(makeStop("1"), makeStop("2"))
        val incoming = listOf(makeStop("3"), makeStop("1", "Changed"))
        val result = CatalogueMerge.upsertStops(existing, incoming) { _, _ -> false }
        assertSame(existing, result)
    }

    @Test
    fun upsertStops_acceptedIncomingEqualToExisting_returnsSameInstance() {
        val existing = listOf(makeStop("1", "A"), makeStop("2", "B"))
        val incoming = listOf(makeStop("1", "A"))
        val result = CatalogueMerge.upsertStops(existing, incoming) { _, _ -> true }
        assertSame(existing, result)
    }

    @Test
    fun upsertStops_acceptedIncomingDifferent_replacesInPlaceAndReturnsNewList() {
        val s1 = makeStop("1", "Original")
        val s2 = makeStop("2", "Keep")
        val existing = listOf(s1, s2)
        val s1Updated = makeStop("1", "Updated")

        val result = CatalogueMerge.upsertStops(existing, listOf(s1Updated)) { _, _ -> true }
        assertNotSame(existing, result)
        assertEquals(listOf(s1Updated, s2), result)
    }

    @Test
    fun upsertStops_acceptedIncomingNewId_appendsAndReturnsNewList() {
        val s1 = makeStop("1", "One")
        val s2 = makeStop("2", "Two")
        val existing = listOf(s1, s2)
        val s3 = makeStop("3", "Three")

        val result = CatalogueMerge.upsertStops(existing, listOf(s3)) { _, _ -> true }
        assertNotSame(existing, result)
        assertEquals(listOf(s1, s2, s3), result)
    }

    @Test
    fun upsertStops_existingHasDuplicateIds_collapsesToLastValueFirstPositionAndReturnsNewList() {
        val s1Early = makeStop("1", "Early")
        val s2 = makeStop("2", "Mid")
        val s1Late = makeStop("1", "Late")
        val existing = listOf(s1Early, s2, s1Late)

        // Incoming is empty, but existing has duplicates -> collapses to last value, first position
        val result = CatalogueMerge.upsertStops(existing, emptyList()) { _, _ -> true }
        assertNotSame(existing, result)
        assertEquals(listOf(s1Late, s2), result)
    }

    @Test
    fun upsertStops_multipleIncomingWithDuplicates_lastAcceptedWins() {
        val s1 = makeStop("1", "First")
        val existing = listOf(s1)
        val inc1 = makeStop("2", "Two A")
        val inc2 = makeStop("2", "Two B")

        val result = CatalogueMerge.upsertStops(existing, listOf(inc1, inc2)) { _, _ -> true }
        assertNotSame(existing, result)
        assertEquals(listOf(s1, inc2), result)
    }

    // --- mergeRoutes tests ---

    @Test
    fun mergeRoutes_emptyIncoming_returnsSameInstance() {
        val existing = listOf(makeRoute("1"), makeRoute("2"))
        val resultTrue = CatalogueMerge.mergeRoutes(existing, emptyList(), preferIncoming = true)
        assertSame(existing, resultTrue)

        val resultFalse = CatalogueMerge.mergeRoutes(existing, emptyList(), preferIncoming = false)
        assertSame(existing, resultFalse)
    }

    @Test
    fun mergeRoutes_preferIncoming_identicalRoutes_returnsSameInstance() {
        val r1 = makeRoute("1", "1", "Line 1")
        val r2 = makeRoute("2", "2", "Line 2")
        val existing = listOf(r1, r2)
        val incoming = listOf(makeRoute("1", "1", "Line 1"))

        val result = CatalogueMerge.mergeRoutes(existing, incoming, preferIncoming = true)
        assertSame(existing, result)
    }

    @Test
    fun mergeRoutes_preferIncoming_changedRoute_replacesExistingAndReturnsNewList() {
        val r1 = makeRoute("1", "1", "Line 1 Old")
        val r2 = makeRoute("2", "2", "Line 2")
        val existing = listOf(r1, r2)
        val r1New = makeRoute("1", "1", "Line 1 New")
        val incoming = listOf(r1New)

        val result = CatalogueMerge.mergeRoutes(existing, incoming, preferIncoming = true)
        assertNotSame(existing, result)
        val expectedMap = (incoming + existing).distinctBy { it.id }.associateBy { it.id }
        assertEquals(expectedMap, result.associateBy { it.id })
        assertEquals(r1New, result.first { it.id == "1" })
    }

    @Test
    fun mergeRoutes_preferIncoming_newRoute_addedAndReturnsNewList() {
        val r1 = makeRoute("1")
        val existing = listOf(r1)
        val r2 = makeRoute("2")
        val incoming = listOf(r2)

        val result = CatalogueMerge.mergeRoutes(existing, incoming, preferIncoming = true)
        assertNotSame(existing, result)
        val expectedMap = (incoming + existing).distinctBy { it.id }.associateBy { it.id }
        assertEquals(expectedMap, result.associateBy { it.id })
    }

    @Test
    fun mergeRoutes_preferIncoming_duplicateIncomingIds_firstWins() {
        val r1 = makeRoute("1", "1", "Old")
        val existing = listOf(r1)
        val r1First = makeRoute("1", "1", "Incoming First")
        val r1Second = makeRoute("1", "1", "Incoming Second")
        val incoming = listOf(r1First, r1Second)

        val result = CatalogueMerge.mergeRoutes(existing, incoming, preferIncoming = true)
        assertNotSame(existing, result)
        val matched = result.first { it.id == "1" }
        assertEquals("Incoming First", matched.name)
    }

    @Test
    fun mergeRoutes_preferIncoming_duplicateIncoming_firstEqualsExisting_secondDiffers_returnsSameInstance() {
        // Because distinctBy takes the first occurrence, and the first occurrence equals existing,
        // it should detect that nothing changes and return the same instance.
        val r1 = makeRoute("1", "1", "Original")
        val existing = listOf(r1)
        val incoming = listOf(makeRoute("1", "1", "Original"), makeRoute("1", "1", "Ignored Duplicate"))

        val result = CatalogueMerge.mergeRoutes(existing, incoming, preferIncoming = true)
        assertSame(existing, result)
    }

    @Test
    fun mergeRoutes_preferIncomingFalse_allIncomingIdsExist_returnsSameInstanceEvenIfIncomingDiffers() {
        val r1 = makeRoute("1", "1", "Line 1")
        val existing = listOf(r1)
        val incoming = listOf(makeRoute("1", "1", "Line 1 Different"))

        val result = CatalogueMerge.mergeRoutes(existing, incoming, preferIncoming = false)
        assertSame(existing, result)
        assertEquals("Line 1", result.first { it.id == "1" }.name)
    }

    @Test
    fun mergeRoutes_preferIncomingFalse_newRoute_appendedAndReturnsNewList() {
        val r1 = makeRoute("1")
        val existing = listOf(r1)
        val r2 = makeRoute("2")
        val incoming = listOf(r2)

        val result = CatalogueMerge.mergeRoutes(existing, incoming, preferIncoming = false)
        assertNotSame(existing, result)
        val expectedMap = (existing + incoming).distinctBy { it.id }.associateBy { it.id }
        assertEquals(expectedMap, result.associateBy { it.id })
    }

    @Test
    fun mergeRoutes_preferIncomingFalse_duplicateIncomingIds_firstWins() {
        val r1 = makeRoute("1")
        val existing = listOf(r1)
        val r2First = makeRoute("2", "2", "First")
        val r2Second = makeRoute("2", "2", "Second")
        val incoming = listOf(r2First, r2Second)

        val result = CatalogueMerge.mergeRoutes(existing, incoming, preferIncoming = false)
        assertNotSame(existing, result)
        val matched = result.first { it.id == "2" }
        assertEquals("First", matched.name)
    }

    // --- Randomized property equivalence test ---

    @Test
    fun randomizedPropertyEquivalenceTest() {
        val rng = Random(42)
        val stopIds = (1..8).map { "stop_$it" }
        val routeIds = (1..8).map { "route_$it" }
        val names = listOf("Alpha", "Beta", "Gamma", "Delta")

        for (iteration in 1..500) {
            // 1. replaceStops test
            val existingStopCount = rng.nextInt(0, 10)
            val existingStops = (1..existingStopCount).map {
                makeStop(
                    id = stopIds[rng.nextInt(stopIds.size)],
                    name = names[rng.nextInt(names.size)],
                    routeIds = listOf(routeIds[rng.nextInt(routeIds.size)])
                )
            }
            val replacementCount = rng.nextInt(0, 5)
            val replacements = (1..replacementCount).associate {
                val id = stopIds[rng.nextInt(stopIds.size)]
                id to makeStop(id, names[rng.nextInt(names.size)])
            }

            val replaceRef = existingStops.map { replacements[it.id] ?: it }
            val replaceActual = CatalogueMerge.replaceStops(existingStops, replacements)
            assertEquals("replaceStops content mismatch at iteration $iteration", replaceRef, replaceActual)

            val anyChanged = existingStops.any { stop ->
                replacements[stop.id].let { it != null && it != stop }
            }
            if (replacements.isEmpty() || !anyChanged) {
                assertSame("replaceStops should return same instance at iteration $iteration", existingStops, replaceActual)
            }

            // 2. upsertStops test
            val incomingStopCount = rng.nextInt(0, 6)
            val incomingStops = (1..incomingStopCount).map {
                makeStop(
                    id = stopIds[rng.nextInt(stopIds.size)],
                    name = names[rng.nextInt(names.size)],
                    routeIds = (1..rng.nextInt(1, 4)).map { routeIds[rng.nextInt(routeIds.size)] }
                )
            }
            val predicateMode = rng.nextInt(3)
            val acceptPredicate: (TransitStop?, TransitStop) -> Boolean = when (predicateMode) {
                0 -> { _, _ -> true }
                1 -> { curr, _ -> curr == null }
                else -> { curr, inc -> curr?.name != inc.name }
            }

            // Reference expression for upsertStops:
            val upsertRefMap = existingStops.associateBy { it.id }.toMutableMap()
            for (inc in incomingStops) {
                val curr = upsertRefMap[inc.id]
                if (acceptPredicate(curr, inc)) {
                    upsertRefMap[inc.id] = inc
                }
            }
            val upsertRefList = upsertRefMap.values.toList()

            val upsertActual = CatalogueMerge.upsertStops(existingStops, incomingStops, acceptPredicate)
            assertEquals("upsertStops content mismatch at iteration $iteration", upsertRefList, upsertActual)

            // Identity check
            val existingHasDuplicates = existingStops.associateBy { it.id }.size != existingStops.size
            var expectedUpsertChanged = existingHasDuplicates
            val testMap = existingStops.associateBy { it.id }.toMutableMap()
            for (inc in incomingStops) {
                val curr = testMap[inc.id]
                if (acceptPredicate(curr, inc) && curr != inc) {
                    testMap[inc.id] = inc
                    expectedUpsertChanged = true
                }
            }
            if (!expectedUpsertChanged) {
                assertSame("upsertStops should return same instance at iteration $iteration", existingStops, upsertActual)
            }

            // 3. mergeRoutes test
            val existingRouteCount = rng.nextInt(0, 8)
            val existingRoutes = stopIds.take(existingRouteCount).map { id ->
                makeRoute(id = id, name = names[rng.nextInt(names.size)])
            }
            val incomingRouteCount = rng.nextInt(0, 8)
            val incomingRoutes = (1..incomingRouteCount).map {
                val id = stopIds[rng.nextInt(stopIds.size)]
                makeRoute(id = id, name = names[rng.nextInt(names.size)])
            }
            val preferIncoming = rng.nextBoolean()

            val routeRef = if (preferIncoming) {
                (incomingRoutes + existingRoutes).distinctBy { it.id }
            } else {
                (existingRoutes + incomingRoutes).distinctBy { it.id }
            }

            val routeActual = CatalogueMerge.mergeRoutes(existingRoutes, incomingRoutes, preferIncoming)
            assertEquals(
                "mergeRoutes map mismatch at iteration $iteration (preferIncoming=$preferIncoming)",
                routeRef.associateBy { it.id },
                routeActual.associateBy { it.id }
            )

            // Identity check
            val existingById = existingRoutes.associateBy { it.id }
            val expectedRouteSame = if (incomingRoutes.isEmpty()) {
                true
            } else if (preferIncoming) {
                val seen = HashSet<String>()
                incomingRoutes.all { r -> !seen.add(r.id) || existingById[r.id] == r }
            } else {
                incomingRoutes.all { it.id in existingById }
            }

            if (expectedRouteSame) {
                assertSame(
                    "mergeRoutes should return same instance at iteration $iteration (preferIncoming=$preferIncoming)",
                    existingRoutes,
                    routeActual
                )
            }
        }
    }
}
