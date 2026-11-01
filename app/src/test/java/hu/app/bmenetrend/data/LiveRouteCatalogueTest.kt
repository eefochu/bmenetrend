package hu.app.bmenetrend.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/**
 * Behavioural coverage for [LiveRouteCatalogue], the ViewModel-side generation cache that keeps
 * route metadata and the search catalogue stable while only vehicle position/time moves.
 *
 * The catalogue is expected to:
 *  - return the *same list reference* when only positions/observation times changed,
 *  - regenerate when any route-metadata field of a vehicle actually changes,
 *  - drop routes that only existed because a vehicle referenced them,
 *  - keep source precedence (stop routes, then lines, then bundled) and derived order,
 *  - stay bounded across vehicle churn.
 */
class LiveRouteCatalogueTest {

    private val now = Instant.parse("2026-11-01T10:00:00Z")

    private fun route(id: String, name: String, number: String = id, provider: String = "bkk") = TransitRoute(
        id = id,
        number = number,
        name = name,
        directions = emptyList(),
        providerId = provider,
    )

    private fun vehicle(
        index: Int,
        routeId: String?,
        routeNumber: String? = "10$index",
        routeName: String? = "Vehicle Route $index",
        destination: String? = "Destination $index",
        providerId: String = "bkk",
        transportMode: String? = "BUS",
        markerColor: String? = "AABBCC",
    ) = Vehicle(
        id = "emma:vehicle:$index",
        position = GeoPoint(47.5, 19.0),
        observedAt = now,
        routeId = routeId,
        providerId = providerId,
        routeNumber = routeNumber,
        destination = destination,
        routeName = routeName,
        transportMode = transportMode,
        markerColor = markerColor,
    )

    private val noStopRoutes = emptyList<TransitRoute>()
    private val noLineRoutes = emptyList<TransitRoute>()
    private val noBundledRoutes = emptyList<TransitRoute>()

    // --- Position/time-only updates ---

    @Test
    fun freshVehiclePositionsAndTimesReuseTheSameCatalogueList() {
        val catalogue = LiveRouteCatalogue()
        val stopRoutes = List(200) { route("stop:$it", "Stop Route $it") }
        val vehicles = List(300) { v -> vehicle(v, routeId = stopRoutes[v % stopRoutes.size].id) }

        val first = catalogue.update(vehicles, stopRoutes, noLineRoutes, noBundledRoutes)
        assertEquals(200, first.size)

        repeat(50) { tick ->
            // Every poll produces brand-new Vehicle instances with new positions and timestamps,
            // while all catalogue-relevant metadata stays identical.
            val refreshed = vehicles.map {
                it.copy(position = GeoPoint(47.5 + tick * 0.0001, 19.0 + tick * 0.0001), observedAt = now.plusSeconds(tick.toLong()))
            }
            val next = catalogue.update(refreshed, stopRoutes, noLineRoutes, noBundledRoutes)
            assertSame("position/time-only update #$tick must reuse the catalogue list", first, next)
        }
    }

    @Test
    fun listOrderOfVehiclesAloneDoesNotInvalidateCatalogue() {
        val catalogue = LiveRouteCatalogue()
        val stopRoutes = List(10) { route("stop:$it", "Stop Route $it") }
        val vehicles = List(10) { v -> vehicle(v, routeId = stopRoutes[v].id) }

        val first = catalogue.update(vehicles, stopRoutes, noLineRoutes, noBundledRoutes)
        val reversed = vehicles.reversed()
        val next = catalogue.update(reversed, stopRoutes, noLineRoutes, noBundledRoutes)
        assertSame(first, next)
        assertEquals(10, next.size)
    }

    // --- Meaningful metadata changes invalidate ---

    @Test
    fun eachMeaningfulVehicleMetadataChangeRegeneratesTheCatalogue() {
        val catalogue = LiveRouteCatalogue()
        val base = vehicle(1, routeId = "live:a", routeNumber = "10", routeName = "Alpha",
            destination = "Alpha Dest", providerId = "bkk", transportMode = "BUS", markerColor = "111111")

        var previous = catalogue.update(listOf(base), noStopRoutes, noLineRoutes, noBundledRoutes)
        assertEquals("live:a", previous.single().id)

        data class Mutation(val label: String, val vehicle: Vehicle, val check: (TransitRoute) -> Unit)

        val mutations = listOf(
            Mutation("routeId", base.copy(routeId = "live:b"), { assertEquals("live:b", it.id) }),
            Mutation("routeNumber", base.copy(routeNumber = "99"), { assertEquals("99", it.number) }),
            Mutation("routeName", base.copy(routeName = "Beta"), { assertEquals("Beta", it.name) }),
            Mutation("destination", base.copy(routeName = null, destination = "Other Dest"), { assertEquals("Other Dest", it.name) }),
            Mutation("providerId", base.copy(providerId = "volan"), { assertEquals("volan", it.providerId) }),
            Mutation("transportMode", base.copy(transportMode = "TRAM"), { assertEquals("TRAM", it.transportMode) }),
            Mutation("markerColor", base.copy(markerColor = "222222"), { assertEquals("222222", it.color) }),
        )

        mutations.forEach { mutation ->
            val next = catalogue.update(listOf(mutation.vehicle), noStopRoutes, noLineRoutes, noBundledRoutes)
            assertNotSame("${mutation.label} change must regenerate the catalogue", previous, next)
            assertEquals("${mutation.label} change must not leave stale routes behind", 1, next.size)
            mutation.check(next.single())
            previous = next
        }
    }

    @Test
    fun metadataChangeOnSharedSourceRouteStillFillsOnlyMissingFields() {
        val catalogue = LiveRouteCatalogue()
        val official = route("bkk:1", "Official Name", number = "1").copy(transportMode = null, color = null)
        val base = vehicle(1, routeId = official.id, transportMode = "BUS", markerColor = "111111", routeName = "Live Name")

        val first = catalogue.update(listOf(base), listOf(official), noLineRoutes, noBundledRoutes).single()
        assertEquals("Official Name", first.name)
        assertEquals("BUS", first.transportMode)
        assertEquals("111111", first.color)

        // Changing only the fill-in values is meaningful: the result gains a new mode/colour.
        val next = catalogue.update(
            listOf(base.copy(transportMode = "TRAM", markerColor = "222222")),
            listOf(official), noLineRoutes, noBundledRoutes
        ).single()
        assertEquals("Official Name", next.name)
        assertEquals("TRAM", next.transportMode)
        assertEquals("222222", next.color)

        // Changing a value that the official route already provides is not meaningful.
        val officialWithMode = route("bkk:1", "Official Name", number = "1").copy(transportMode = "BUS", color = "333333")
        val stable = catalogue.update(listOf(base), listOf(officialWithMode), noLineRoutes, noBundledRoutes).single()
        assertEquals("BUS", stable.transportMode)
        assertEquals("333333", stable.color)
        val stableAgain = catalogue.update(listOf(base.copy(transportMode = "TRAM", markerColor = "999999")),
            listOf(officialWithMode), noLineRoutes, noBundledRoutes)
        assertEquals("BUS", stableAgain.single().transportMode)
        assertEquals("333333", stableAgain.single().color)
    }

    // --- Stale vehicle-derived routes ---

    @Test
    fun vehicleRouteIdChangeRemovesTheOldDerivedRoute() {
        val catalogue = LiveRouteCatalogue()
        val vehicles = listOf(vehicle(1, routeId = "live:a"), vehicle(2, routeId = "live:b"))
        val first = catalogue.update(vehicles, noStopRoutes, noLineRoutes, noBundledRoutes)
        assertEquals(listOf("live:a", "live:b"), first.map { it.id })

        val next = catalogue.update(
            listOf(vehicles[0].copy(routeId = "live:c"), vehicles[1]),
            noStopRoutes, noLineRoutes, noBundledRoutes
        )
        assertEquals(listOf("live:c", "live:b"), next.map { it.id })
    }

    @Test
    fun disappearingVehiclesDropTheirDerivedRoutes() {
        val catalogue = LiveRouteCatalogue()
        val stopRoutes = listOf(route("stop:1", "Stop One"))
        val vehicles = List(3) { v -> vehicle(v, routeId = "live:$v") }

        val first = catalogue.update(vehicles, stopRoutes, noLineRoutes, noBundledRoutes)
        assertEquals(listOf("stop:1", "live:0", "live:1", "live:2"), first.map { it.id })

        val next = catalogue.update(vehicles.take(2), stopRoutes, noLineRoutes, noBundledRoutes)
        assertEquals(listOf("stop:1", "live:0", "live:1"), next.map { it.id })

        val empty = catalogue.update(emptyList(), stopRoutes, noLineRoutes, noBundledRoutes)
        assertEquals(listOf("stop:1"), empty.map { it.id })

        // Empty vehicles reuse the previous result, so the generation stops growing.
        assertSame(empty, catalogue.update(emptyList(), stopRoutes, noLineRoutes, noBundledRoutes))
    }

    @Test
    fun vehiclesWithoutRouteIdDoNotPopulateTheCatalogue() {
        val catalogue = LiveRouteCatalogue()
        val result = catalogue.update(
            listOf(vehicle(1, routeId = null), vehicle(2, routeId = null)),
            noStopRoutes, noLineRoutes, noBundledRoutes
        )
        assertEquals(emptyList<TransitRoute>(), result)
        assertSame(result, catalogue.update(emptyList(), noStopRoutes, noLineRoutes, noBundledRoutes))
    }

    // --- Source precedence, order, replacement ---

    @Test
    fun sourcePrecedenceAndOrderAreStable() {
        val catalogue = LiveRouteCatalogue()
        val stopRoutes = listOf(route("shared", "Stop Winner"), route("stop:only", "Stop Only"))
        val lineRoutes = listOf(route("shared", "Line Loser"), route("line:only", "Line Only"))
        val bundledRoutes = listOf(route("shared", "Bundled Loser"), route("bundled:only", "Bundled Only"))
        val vehicles = listOf(vehicle(1, routeId = "live:derived"), vehicle(2, routeId = "stop:only"))

        val result = catalogue.update(vehicles, stopRoutes, lineRoutes, bundledRoutes)

        assertEquals(
            listOf("shared", "stop:only", "line:only", "bundled:only", "live:derived"),
            result.map { it.id }
        )
        // The first source wins for a duplicate id.
        assertEquals("Stop Winner", result.first { it.id == "shared" }.name)
        // A vehicle referencing an existing route cannot rename it.
        assertEquals("Stop Only", result.first { it.id == "stop:only" }.name)
        // Only genuinely new vehicle routes append, in vehicle order.
        assertEquals("Vehicle Route 1", result.last().name)
    }

    @Test
    fun replacingASourceListWithEqualContentKeepsTheCatalogueStable() {
        val catalogue = LiveRouteCatalogue()
        val stopRoutes = listOf(route("stop:1", "Stop One"))
        val first = catalogue.update(emptyList(), stopRoutes, noLineRoutes, noBundledRoutes)

        val equalReplacement = listOf(route("stop:1", "Stop One"))
        val next = catalogue.update(emptyList(), equalReplacement, noLineRoutes, noBundledRoutes)

        // The source list was replaced, but nothing observable changed, so the published
        // generation (and therefore Compose recomposition scope) stays stable.
        assertEquals(first, next)
        assertSame(first, next)
        // The replacement is still installed: a later changed source is visible immediately.
        val changed = catalogue.update(emptyList(), listOf(route("stop:1", "Stop Renamed")), noLineRoutes, noBundledRoutes)
        assertEquals("Stop Renamed", changed.single().name)
    }

    @Test
    fun replacingASourceWithChangedMetadataIsPublished() {
        val catalogue = LiveRouteCatalogue()
        val lineRoutes = listOf(route("line:1", "Old Name"))
        val first = catalogue.update(emptyList(), noStopRoutes, lineRoutes, noBundledRoutes).single()
        assertEquals("Old Name", first.name)

        val changedLines = listOf(route("line:1", "New Name"))
        val next = catalogue.update(emptyList(), noStopRoutes, changedLines, noBundledRoutes).single()
        assertEquals("New Name", next.name)

        // Same source instances and vehicles keep reusing the regenerated list.
        assertSame(catalogue.update(emptyList(), noStopRoutes, changedLines, noBundledRoutes), catalogue.update(emptyList(), noStopRoutes, changedLines, noBundledRoutes))
    }

    // --- Bounded generation ---

    @Test
    fun catalogueSizeStaysBoundedAcrossVehicleChurn() {
        val catalogue = LiveRouteCatalogue()
        val baseRoutes = List(100) { route("base:$it", "Base $it") }
        var result = catalogue.update(emptyList(), baseRoutes, noLineRoutes, noBundledRoutes)
        assertEquals(100, result.size)

        repeat(500) { cycle ->
            val derivedCount = cycle % 25
            val vehicles = List(derivedCount) { v ->
                vehicle(v, routeId = "live:${cycle % 40}:$v")
            }
            result = catalogue.update(vehicles, baseRoutes, noLineRoutes, noBundledRoutes)
            assertEquals("cycle $cycle", 100 + derivedCount, result.size)
            assertEquals("cycle $cycle", (0 until derivedCount).map { "live:${cycle % 40}:$it" },
                result.drop(100).map { it.id })
        }

        val empty = catalogue.update(emptyList(), baseRoutes, noLineRoutes, noBundledRoutes)
        assertEquals(100, empty.size)
        assertEquals(baseRoutes, empty)
        // Once the churn stops, the generation is reused instead of growing.
        assertSame(empty, catalogue.update(emptyList(), baseRoutes, noLineRoutes, noBundledRoutes))
    }

    // --- Host-JVM diagnostic benchmark ---

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    private fun buildSyntheticCatalogue(
        routeCount: Int = 10_000,
        vehicleCount: Int = 5_000,
    ): Pair<List<TransitRoute>, List<Vehicle>> {
        val random = Random(424242)
        val routes = List(routeCount) { i ->
            TransitRoute(
                id = "synthetic:$i",
                number = (i % 900 + 1).toString(),
                name = "Synthetic Route $i",
                directions = emptyList(),
                providerId = listOf("bkk", "volan", "hev", "mav")[i % 4],
                transportMode = listOf("BUS", "TRAM", "COACH", "RAIL")[i % 4],
                color = if (i % 6 == 0) "%06X".format(random.nextInt(0, 0x1000000)) else null,
            )
        }
        val vehicles = List(vehicleCount) { v ->
            val route = routes[(v * 7) % routeCount]
            Vehicle(
                id = "emma:synthetic:$v",
                position = GeoPoint(47.4 + (v % 100) * 0.001, 19.0 + (v % 100) * 0.001),
                observedAt = now.minusSeconds((v % 60).toLong()),
                routeId = route.id,
                providerId = route.providerId,
                routeNumber = route.number,
                destination = null,
                routeName = null,
                transportMode = null,
                markerColor = null,
            )
        }
        return routes to vehicles
    }

    @Test
    fun benchmarkPositionOnlyUpdatesOnLargeCatalogue() {
        val (routes, vehicles) = buildSyntheticCatalogue()
        val catalogue = LiveRouteCatalogue()

        val first = catalogue.update(vehicles, routes, noLineRoutes, noBundledRoutes)
        assertEquals(10_000, first.size)
        assertEquals(10_000, first.map { it.id }.distinct().size)

        // Warm up the comparison path on fresh vehicle lists.
        repeat(5) { tick -> catalogue.update(refreshedVehicles(vehicles, tick), routes, noLineRoutes, noBundledRoutes) }

        val runs = 9
        val nanos = mutableListOf<Long>()
        var last = first
        repeat(runs) { run ->
            val refreshed = refreshedVehicles(vehicles, run + 10)
            val start = System.nanoTime()
            val result = catalogue.update(refreshed, routes, noLineRoutes, noBundledRoutes)
            nanos += System.nanoTime() - start
            assertSame("position-only update #$run must reuse the result list", first, result)
            last = result
        }

        val medianMs = median(nanos) / 1_000_000.0
        println("=== [HOST JVM ONLY] LiveRouteCatalogue position-only update benchmark ===")
        println("Dataset: 10000 routes / 5000 vehicles, fresh Vehicle instances with new position+observedAt each run")
        println("Metadata comparison only; no catalogue rebuild expected.")
        println("Runs: $runs  median=${"%.3f".format(medianMs)} ms  " +
            "mean=${"%.3f".format(nanos.average() / 1e6)} ms  min=${"%.3f".format(nanos.min() / 1e6)} ms  max=${"%.3f".format(nanos.max() / 1e6)} ms")
        println("Per-run ms: ${nanos.map { "%.3f".format(it / 1e6) }}")
        println("Result list reused: ${last === first}")
        println("NOTE: JVM unit-test timings only; no device or production performance claim.")
        println("========================================================================")

        // Correctness, not wallclock: the cached generation must be reused.
        assertSame(first, last)
        assertEquals(10_000, last.size)
    }

    private fun refreshedVehicles(vehicles: List<Vehicle>, tick: Int): List<Vehicle> = vehicles.map {
        it.copy(
            position = GeoPoint(it.position.lat + tick * 0.00001, it.position.lon + tick * 0.00001),
            observedAt = now.plusSeconds(tick.toLong()),
        )
    }
}
