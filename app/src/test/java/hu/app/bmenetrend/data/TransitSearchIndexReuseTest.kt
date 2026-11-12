package hu.app.bmenetrend.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TransitSearchIndexReuseTest {
    private fun stop(id: String, name: String, lat: Double = 47.0) =
        TransitStop(id, name, GeoPoint(lat, 19.0), emptyList(), "bkk")

    private fun search(index: List<TransitSearch.StopEntry>, name: String) =
        TransitSearch.stops(index, TransitSearch.query(name), null, emptySet())

    @Test fun changedStopNameAndAddedStopAreIndexedWhileUnchangedEntryIsReused() {
        val unchanged = stop("reuse-stable", "Zafirkert", 47.0)
        val original = stop("reuse-renamed", "Borostyankert", 48.0)
        val first = TransitSearch.indexStops(listOf(unchanged, original))
        val renamed = original.copy(name = "Rubinkert")
        val added = stop("reuse-added", "Topazkert", 49.0)
        val second = TransitSearch.indexStops(listOf(unchanged, renamed, added))

        assertSame(first.single { it.stop.id == unchanged.id }, second.single { it.stop.id == unchanged.id })
        assertEquals(listOf(unchanged), search(second, unchanged.name))
        assertEquals(listOf(renamed), search(second, renamed.name))
        assertTrue(search(second, original.name).isEmpty())
        assertEquals(listOf(added), search(second, added.name))
        assertEquals(setOf(unchanged, renamed, added), second.map { it.stop }.toSet())
        second.forEach { entry ->
            assertEquals(TransitSearch.normalize(entry.stop.name), entry.name)
            assertTrue(entry.text.contains(TransitSearch.normalize(entry.stop.name)))
        }
    }

    @Test fun clusteredEntryIsReusedByValueWhenAnUnrelatedStopIsAdded() {
        val platforms = listOf(
            stop("reuse-cluster-a", "Gyemantliget", 47.2),
            stop("reuse-cluster-b", "Gyemantliget", 47.2001),
        )
        val first = TransitSearch.indexStops(platforms)
        val cluster = first.single { it.isMultiStop }
        val second = TransitSearch.indexStops(platforms + stop("reuse-unrelated", "Opallig et", 49.2))
        val rebuiltCluster = second.single { it.isMultiStop }

        assertSame(cluster, rebuiltCluster)
        assertEquals(platforms.map { it.id }.toSet(), rebuiltCluster.stop.sourceStopIds.toSet())
        assertEquals(listOf(rebuiltCluster.stop), search(second, "Gyemantliget"))
    }

    @Test fun exactSameLocalAndNationalListsReturnTheCachedIndex() {
        val local = listOf(stop("reuse-cache-local", "Ametisztkert"))
        val national = listOf(stop("reuse-cache-national", "Akvamarinkert", 49.0))
        val first = TransitSearch.indexStops(local, national)
        assertSame(first, TransitSearch.indexStops(local, national))
    }

    @Test fun renamedRouteMatchesNewNameAndDropsOldNameAcrossNewLists() {
        val original = TransitRoute("reuse-route", "987", "Zafirjarat", emptyList(), "bkk")
        val unchanged = TransitRoute("reuse-route-stable", "986", "Topazjarat", emptyList(), "bkk")
        val first = listOf(original, unchanged)
        assertEquals(listOf(original), TransitSearch.routes(first, TransitSearch.query("Zafirjarat")))
        val renamed = original.copy(name = "Rubinjarat")
        val second = listOf(renamed, unchanged)
        assertEquals(listOf(renamed), TransitSearch.routes(second, TransitSearch.query("Rubinjarat")))
        assertTrue(TransitSearch.routes(second, TransitSearch.query("Zafirjarat")).isEmpty())
        assertEquals(listOf(unchanged), TransitSearch.routes(second, TransitSearch.query("Topazjarat")))
    }

    @Test fun movedVehicleRetainsSearchTextAndChangedDestinationReplacesIt() {
        val original = Vehicle(
            id = "reuse-vehicle", position = GeoPoint(47.0, 19.0), observedAt = Instant.EPOCH,
            registration = "X-ZAFIR", destination = "Borostyanvegallomas",
        )
        assertEquals(listOf(original), TransitSearch.vehicles(listOf(original), TransitSearch.query("x")))
        val moved = original.copy(position = GeoPoint(48.0, 20.0))
        assertEquals(listOf(moved), TransitSearch.vehicles(listOf(moved), TransitSearch.query("x")))
        val changed = moved.copy(destination = "Rubinvegallomas")
        val third = listOf(changed)
        assertEquals(listOf(changed), TransitSearch.vehicles(third, TransitSearch.query("Rubinvegallomas")))
        assertTrue(TransitSearch.vehicles(third, TransitSearch.query("Borostyanvegallomas")).isEmpty())
    }

    @Test fun cleanNameRepeatedCallsPreserveKnownStationCleaning() {
        repeat(3) {
            assertEquals("kelet", StopClusters.cleanName("Budapest-Keleti pályaudvar"))
        }
    }
}
