package hu.app.bmenetrend.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopClustersMemoizationTest {

    /** Pre-change reference logic copied from task description. */
    private fun referenceAreNamesCloselyMatching(nameA: String, nameB: String): Boolean {
        val normA = StopClusters.normalize(nameA)
        val normB = StopClusters.normalize(nameB)
        if (normA == normB) return true

        val cleanA = StopClusters.cleanName(nameA)
        val cleanB = StopClusters.cleanName(nameB)
        if (cleanA.isNotEmpty() && cleanA == cleanB) return true

        val wordsA = cleanA.split(" ").filter { it.isNotBlank() }.toSet()
        val wordsB = cleanB.split(" ").filter { it.isNotBlank() }.toSet()
        if (wordsA.isNotEmpty() && wordsB.isNotEmpty()) {
            if (wordsA.containsAll(wordsB) || wordsB.containsAll(wordsA)) return true
        }

        if (cleanA.length >= 5 && cleanB.length >= 5) {
            val shorter = if (cleanA.length <= cleanB.length) cleanA else cleanB
            val longer = if (cleanA.length <= cleanB.length) cleanB else cleanA
            if (longer.contains(shorter)) return true
        }

        return false
    }

    private val realHungarianNames = listOf(
        "Budaörsi lakótelep",
        "Budaörsi lakótelep (több megálló)",
        "Budaörsi lakótelep (multiple stations)",
        "Patkó utca",
        "Patkó utca (Ellenirány)",
        "Kelenföld vasútállomás M",
        "Budapest, Kelenföld vasútállomás",
        "Kelenföld vasútállomás",
        "Deák Ferenc tér M",
        "Deák Ferenc tér",
        "Széll Kálmán tér M",
        "Budapest, Széll Kálmán tér",
        "Keleti pályaudvar M",
        "Keleti pályaudvar",
        "Nyugati pályaudvar M",
        "Budapest-Nyugati",
        "Déli pályaudvar",
        "Astoria M",
        "Astoria",
        "Móricz Zsigmond körtér M",
        "Móricz Zsigmond körtér",
        "Blaha Lujza tér M",
        "Blaha Lujza tér",
        "Újbuda-központ M",
        "Újbuda-központ",
        "Budaörs, benzinkút",
        "Budaörs, vasútállomás",
        "Biatorbágy, vasútállomás",
        "Biatorbágy",
        "Érd alsó vasútállomás",
        "Érd alsó",
        "Érd felső vasútállomás",
        "Solymár, vasútállomás",
        "Solymár",
        "Szentendre H",
        "Gödöllő H",
        "Dunakeszi gyártelep",
        "Székesfehérvár, vasútállomás",
        "Győr, autóbusz-állomás",
        "Debrecen, Nagyállomás",
        "Pécs, Főpályaudvar",
        "Szeged vasútállomás",
        "Városháza tér",
        "Kossuth Lajos tér M",
        "Batthyány tér M+H",
        "Örs vezér tere M+H",
        "Ferenciek tere M",
        "Kálvin tér M",
        "Corvin-negyed M",
        "Semmelweis Klinikák M",
        "Nagyvárad tér M",
        "Népliget M",
        "Határ út M",
        "Kőbánya-Kispest M",
        "Széchenyi fürdő M",
        "Mexikói út M"
    )

    @Test
    fun repeatedCallsWithSameNames_returnIdenticalResultsInAnyOrder() {
        val pairs = listOf(
            "Kelenföld vasútállomás M" to "Budapest, Kelenföld vasútállomás",
            "Budaörsi lakótelep" to "Patkó utca",
            "Deák Ferenc tér M" to "Deák Ferenc tér",
            "Blaha Lujza tér" to "Nyugati pályaudvar",
            "Astoria" to "Astoria M"
        )

        for ((nameA, nameB) in pairs) {
            // First call (cache miss)
            val first = StopClusters.areNamesCloselyMatching(nameA, nameB)
            // Second call (cache hit)
            val second = StopClusters.areNamesCloselyMatching(nameA, nameB)
            assertEquals("Second call should match first for ($nameA, $nameB)", first, second)

            // Inverted order (nameB, nameA)
            val inverted = StopClusters.areNamesCloselyMatching(nameB, nameA)
            assertEquals("Inverted call should match original for ($nameA, $nameB)", first, inverted)

            // Third call after inverted
            val third = StopClusters.areNamesCloselyMatching(nameA, nameB)
            assertEquals("Third call should match first for ($nameA, $nameB)", first, third)
        }
    }

    @Test
    fun batchOfHungarianStopNames_matchesPreChangeReferenceImplementation() {
        // Cross-compare all pairs from realHungarianNames
        for (i in realHungarianNames.indices) {
            val nameA = realHungarianNames[i]
            for (j in realHungarianNames.indices) {
                val nameB = realHungarianNames[j]

                val expected = referenceAreNamesCloselyMatching(nameA, nameB)
                val actual = StopClusters.areNamesCloselyMatching(nameA, nameB)

                assertEquals(
                    "Mismatch between reference and memoized for pair: '$nameA' <-> '$nameB'",
                    expected,
                    actual
                )
            }
        }
    }

    @Test
    fun lruCacheEviction_resultsRemainCorrectAfterMoreThan8192DistinctNames() {
        val pairMatching = "Budapest, Kelenföld vasútállomás" to "Kelenföld vasútállomás M"
        val pairNonMatching = "Patkó utca" to "Budaörsi lakótelep"

        // Verify initial state
        assertTrue(StopClusters.areNamesCloselyMatching(pairMatching.first, pairMatching.second))
        assertFalse(StopClusters.areNamesCloselyMatching(pairNonMatching.first, pairNonMatching.second))

        // Fill LRU beyond 8192 entries with distinct names to trigger eviction
        val evictionCount = 9000
        for (i in 1..evictionCount) {
            val a = "Egyedi megálló neve A #$i"
            val b = "Egyedi megálló neve B #$i"
            // Call areNamesCloselyMatching which puts both names into nameForms LRU
            StopClusters.areNamesCloselyMatching(a, b)
        }

        // Now test the original pairs again after they have been evicted from LRU
        val matchingAfterEviction = StopClusters.areNamesCloselyMatching(pairMatching.first, pairMatching.second)
        val nonMatchingAfterEviction = StopClusters.areNamesCloselyMatching(pairNonMatching.first, pairNonMatching.second)

        assertTrue(
            "Matching pair should still match after cache eviction",
            matchingAfterEviction
        )
        assertFalse(
            "Non-matching pair should still not match after cache eviction",
            nonMatchingAfterEviction
        )

        // Verify that reference logic still agrees for all test pairs after eviction
        for (name in realHungarianNames.take(10)) {
            for (other in realHungarianNames.take(10)) {
                val ref = referenceAreNamesCloselyMatching(name, other)
                val act = StopClusters.areNamesCloselyMatching(name, other)
                assertEquals(
                    "After eviction, mismatch for '$name' <-> '$other'",
                    ref,
                    act
                )
            }
        }
    }
}
