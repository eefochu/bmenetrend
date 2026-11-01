package hu.app.bmenetrend.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/**
 * Regression + host-JVM benchmark coverage for [EmmaParser.routeCatalogue].
 *
 * Goals:
 *  1. Pin the current metadata semantics with a literal reference implementation, so a future
 *     catalogue-reuse optimisation cannot silently change route names, colours, modes or order.
 *  2. Prove the parser never mutates the caller's `local` routes or `vehicles` lists.
 *  3. Record the host-JVM cost of the *current* full rebuild on a synthetic ~10k route / 5k vehicle
 *     catalogue. Timings are JVM-only diagnostics, not a device measurement.
 *
 * The reuse assertion (`assertSame(local, result)` when nothing changes) is now pinned: the
 * catalogue optimisation must return the caller's list when no vehicle contributes new metadata.
 */
class EmmaRouteCatalogueRegressionTest {

    // --- Reference parity: literal copy of the pre-optimisation semantics ---

    private fun referenceRouteCatalogue(vehicles: List<Vehicle>, local: List<TransitRoute>): List<TransitRoute> {
        val routes = local.associateBy { it.id }.toMutableMap()
        vehicles.forEach { vehicle ->
            val id = vehicle.routeId ?: return@forEach
            val existing = routes[id]
            val name = existing?.name?.takeIf { it.isNotBlank() && it != existing.number }
                ?: vehicle.routeName ?: vehicle.destination.orEmpty()
            routes[id] = existing?.copy(
                name = name.ifBlank { existing.name },
                transportMode = existing.transportMode ?: vehicle.transportMode,
                color = existing.color ?: vehicle.markerColor
            ) ?: TransitRoute(
                id, vehicle.routeNumber ?: "?", name, emptyList(), vehicle.providerId,
                transportMode = vehicle.transportMode, color = vehicle.markerColor
            )
        }
        return routes.values.toList()
    }

    private fun assertSameSemantics(expected: List<TransitRoute>, actual: List<TransitRoute>, hint: String) {
        assertEquals("$hint: catalogue size", expected.size, actual.size)
        assertEquals("$hint: catalogue id order", expected.map { it.id }, actual.map { it.id })
        expected.indices.forEach { i ->
            assertEquals("$hint: route[$i] (${expected[i].id}) metadata", expected[i], actual[i])
        }
    }

    // --- Fixtures ---

    private val baseInstant = Instant.parse("2026-11-01T10:00:00Z")

    private fun localRoute(
        index: Int,
        id: String = "local:$index",
        number: String = (100 + index).toString(),
        name: String = "Local Route $index",
        providerId: String = "bkk",
        sourceUrl: String? = "https://example.invalid/route/$index",
        transportMode: String? = if (index % 3 == 0) "BUS" else null,
        color: String? = if (index % 4 == 0) "112233" else null
    ) = TransitRoute(
        id = id,
        number = number,
        name = name,
        directions = listOf(
            RouteDirection("$id:0", "$name ->", listOf("$id:s0", "$id:s1")),
            RouteDirection("$id:1", "$name <-", listOf("$id:s1", "$id:s0"), restrictedService = index % 5 == 0)
        ),
        providerId = providerId,
        sourceUrl = sourceUrl,
        transportMode = transportMode,
        color = color
    )

    private fun vehicle(
        index: Int,
        routeId: String?,
        routeNumber: String? = (100 + index).toString(),
        routeName: String? = "Vehicle Route $index",
        destination: String? = "Destination $index",
        providerId: String = "bkk",
        transportMode: String? = "TRAM",
        markerColor: String? = "AABBCC"
    ) = Vehicle(
        id = "emma:vehicle:$index",
        position = GeoPoint(47.5, 19.0),
        observedAt = baseInstant,
        routeId = routeId,
        providerId = providerId,
        routeNumber = routeNumber,
        destination = destination,
        routeName = routeName,
        transportMode = transportMode,
        markerColor = markerColor
    )

    // --- Correctness parity cases ---

    @Test
    fun emptyInputsProduceEmptyCatalogueAndKeepNoAliasing() {
        val local = listOf(localRoute(0), localRoute(1))
        assertSameSemantics(local, EmmaParser.routeCatalogue(emptyList(), local), "no vehicles")
        assertSameSemantics(emptyList(), EmmaParser.routeCatalogue(emptyList(), emptyList()), "both empty")
        assertSameSemantics(
            emptyList(),
            EmmaParser.routeCatalogue(listOf(vehicle(0, routeId = null)), emptyList()),
            "vehicles without routeId"
        )
    }

    @Test
    fun localMetadataSurvivesVehiclesThatCarryConflictingExtras() {
        val route = localRoute(0, name = "Official Name", transportMode = null, color = null)
        val v = vehicle(0, routeId = route.id, routeNumber = "999", routeName = "Live Name", markerColor = "FF0000", transportMode = "COACH")
        val result = EmmaParser.routeCatalogue(listOf(v), listOf(route)).single()

        assertEquals(route.id, result.id)
        assertEquals(route.number, result.number)
        assertEquals(route.name, result.name)
        assertEquals(route.directions, result.directions)
        assertEquals(route.providerId, result.providerId)
        assertEquals(route.sourceUrl, result.sourceUrl)
        // Only genuinely missing metadata is filled in.
        assertEquals("COACH", result.transportMode)
        assertEquals("FF0000", result.color)
        assertSame(route.directions, result.directions)
    }

    @Test
    fun existingModeAndColourAlwaysWinOverLiveValues() {
        val route = localRoute(1, transportMode = "BUS", color = "00FF00")
        val result = EmmaParser.routeCatalogue(
            listOf(vehicle(1, routeId = route.id, transportMode = "SUBWAY", markerColor = "FF00FF")),
            listOf(route)
        ).single()
        assertEquals("BUS", result.transportMode)
        assertEquals("00FF00", result.color)
    }

    @Test
    fun nameFallbacksFollowExistingNameThenRouteNameThenDestination() {
        val blankName = localRoute(2, name = "   ", number = "202")
        val shortName = localRoute(3, name = "203", number = "203")

        val withRouteName = EmmaParser.routeCatalogue(
            listOf(vehicle(2, routeId = blankName.id, routeName = "From Vehicle")), listOf(blankName)
        ).single()
        assertEquals("From Vehicle", withRouteName.name)

        val destinationOnly = EmmaParser.routeCatalogue(
            listOf(vehicle(3, routeId = shortName.id, routeName = null, destination = "From Destination")),
            listOf(shortName)
        ).single()
        assertEquals("From Destination", destinationOnly.name)

        val nothingAvailable = EmmaParser.routeCatalogue(
            listOf(vehicle(4, routeId = blankName.id, routeName = null, destination = null)),
            listOf(blankName)
        ).single()
        // Existing (non-blank after trim? no: original text) name is preserved by `ifBlank`.
        assertEquals("   ", nothingAvailable.name)
    }

    @Test
    fun unseenVehicleRouteIsCreatedWithProviderMetadataAndQuestionMarkFallback() {
        val created = EmmaParser.routeCatalogue(
            listOf(vehicle(7, routeId = "bkk:new", routeNumber = null, routeName = "New Line",
                providerId = "bkk", transportMode = "TRAM", markerColor = "123456")),
            emptyList()
        ).single()
        assertEquals("bkk:new", created.id)
        assertEquals("?", created.number)
        assertEquals("New Line", created.name)
        assertEquals("bkk", created.providerId)
        assertEquals("TRAM", created.transportMode)
        assertEquals("123456", created.color)
        assertTrue(created.directions.isEmpty())
        assertNull(created.sourceUrl)
    }

    @Test
    fun duplicateRouteIdsCollapseToLastLocalEntryAndKeepVehicleFilledMetadata() {
        val first = localRoute(8, id = "shared:1", name = "First")
        val duplicate = localRoute(9, id = "shared:1", name = "Duplicate")
        val a = vehicle(8, routeId = "shared:1", routeNumber = "1", routeName = "A", markerColor = "AAAAAA", transportMode = "BUS")
        val b = vehicle(9, routeId = "shared:1", routeNumber = "1", routeName = "B", markerColor = "BBBBBB", transportMode = "COACH")

        val result = EmmaParser.routeCatalogue(listOf(a, b), listOf(first, duplicate))
        val expected = referenceRouteCatalogue(listOf(a, b), listOf(first, duplicate))
        assertSameSemantics(expected, result, "duplicate ids")
        assertEquals(1, result.size)
        // associateBy keeps the last local entry for a duplicate id.
        assertEquals("Duplicate", result.single().name)
        assertEquals("BUS", result.single().transportMode)
        assertEquals("AAAAAA", result.single().color)
    }

    @Test
    fun localOrderIsPreservedAndNewVehicleRoutesAppendInVehicleOrder() {
        val local = listOf(localRoute(10), localRoute(11), localRoute(12))
        val vehicles = listOf(
            vehicle(0, routeId = local[2].id),
            vehicle(1, routeId = "bkk:zzz", routeNumber = "Z", routeName = "Zed"),
            vehicle(2, routeId = local[0].id),
            vehicle(3, routeId = "bkk:aaa", routeNumber = "A", routeName = "Ay")
        )
        val result = EmmaParser.routeCatalogue(vehicles, local)
        assertEquals(
            listOf(local[0].id, local[1].id, local[2].id, "bkk:zzz", "bkk:aaa"),
            result.map { it.id }
        )
        assertSameSemantics(referenceRouteCatalogue(vehicles, local), result, "ordering")
    }

    @Test
    fun randomizedSemanticParityWithReferenceImplementation() {
        val random = Random(20261101)
        repeat(25) { iteration ->
            val localSize = random.nextInt(0, 120)
            val local = List(localSize) { i ->
                val number = (random.nextInt(1, 60)).toString()
                localRoute(
                    index = i,
                    id = "local:$iteration:$i",
                    number = number,
                    name = when (random.nextInt(4)) {
                        0 -> ""
                        1 -> "   "
                        2 -> number
                        else -> "Name $iteration/$i"
                    },
                    providerId = listOf("bkk", "volan", "hev", "mav")[random.nextInt(4)],
                    sourceUrl = if (random.nextBoolean()) "https://example.invalid/$i" else null,
                    transportMode = if (random.nextBoolean()) listOf("BUS", "TRAM", "COACH")[random.nextInt(3)] else null,
                    color = if (random.nextBoolean()) "%06X".format(random.nextInt(0, 0x1000000)) else null
                )
            }
            val vehicleCount = random.nextInt(0, 200)
            val vehicles = List(vehicleCount) { v ->
                val routeId = when (random.nextInt(6)) {
                    0 -> null
                    1, 2 -> "local:$iteration:${random.nextInt(0, localSize.coerceAtLeast(1))}"
                    else -> "live:$iteration:${random.nextInt(0, 40)}"
                }
                vehicle(
                    index = v,
                    routeId = routeId,
                    routeNumber = if (random.nextBoolean()) random.nextInt(1, 90).toString() else null,
                    routeName = if (random.nextBoolean()) "Live $iteration/$v" else null,
                    destination = if (random.nextBoolean()) "Dest $iteration/$v" else null,
                    providerId = listOf("bkk", "volan", "hev", "mav")[random.nextInt(4)],
                    transportMode = if (random.nextBoolean()) listOf("BUS", "TRAM", "SUBWAY")[random.nextInt(3)] else null,
                    markerColor = if (random.nextBoolean()) "%06X".format(random.nextInt(0, 0x1000000)) else null
                )
            }

            assertSameSemantics(
                referenceRouteCatalogue(vehicles, local),
                EmmaParser.routeCatalogue(vehicles, local),
                "randomized iteration $iteration (${local.size} local, ${vehicles.size} vehicles)"
            )
        }
    }

    @Test
    fun parserCatalogueOutputIsIdempotentAndMatchesAcrossRepeatedCalls() {
        val local = List(40) { localRoute(it) }
        val vehicles = List(120) { v -> vehicle(v, routeId = if (v % 7 == 0) "live:$v" else local[v % local.size].id) }

        val first = EmmaParser.routeCatalogue(vehicles, local)
        val second = EmmaParser.routeCatalogue(vehicles, first)
        val third = EmmaParser.routeCatalogue(vehicles, local)
        assertEquals(first, second)
        assertEquals(first, third)
    }

    @Test
    fun originalInputsAreNeverModified() {
        val local = List(30) { localRoute(it) }
        val vehicles = List(90) { v ->
            vehicle(v, routeId = if (v % 5 == 0) "live:$v" else local[v % local.size].id,
                routeNumber = if (v % 3 == 0) null else v.toString())
        }
        val localSnapshot = local.toList()
        val vehicleSnapshot = vehicles.toList()
        val directionLists = local.flatMap { route -> route.directions.map { it to it.stopIds.toList() } }

        EmmaParser.routeCatalogue(vehicles, local)

        assertEquals(localSnapshot, local)
        assertEquals(vehicleSnapshot, vehicles)
        local.indices.forEach { i -> assertSame("local[$i] must not be replaced", localSnapshot[i], local[i]) }
        vehicles.indices.forEach { i -> assertSame("vehicles[$i] must not be replaced", vehicleSnapshot[i], vehicles[i]) }
        assertEquals(directionLists.size, local.sumOf { it.directions.size })
        directionLists.forEachIndexed { i, (direction, stopIds) ->
            assertSame("direction[$i] must not be replaced", direction, local.flatMap { it.directions }[i])
            assertEquals("direction[$i] stopIds must not be mutated", stopIds, direction.stopIds)
        }
    }

    // --- Reference reuse ---

    @Test
    fun equalCatalogueReusesTheInputListAndRouteInstances() {
        val local = List(500) { localRoute(it) }
        // Vehicles reference existing routes and carry no metadata that would change the catalogue.
        val vehicles = List(500) { v -> vehicle(v, routeId = local[v].id, routeName = null, transportMode = null, markerColor = null) }

        val result = EmmaParser.routeCatalogue(vehicles, local)
        val reusedList = result === local
        val reusedInstances = result.count { r -> local.any { it === r } }

        println("[EmmaParser.routeCatalogue reuse] listReused=$reusedList reusedRouteInstances=$reusedInstances/${local.size}")
        assertEquals(local, result)
        assertSame("an unchanged catalogue must reuse the input list", local, result)
        assertEquals("every unchanged route instance must be reused", local.size, reusedInstances)
        assertSame("repeated calls must keep the same list", result, EmmaParser.routeCatalogue(vehicles, local))
    }

    @Test
    fun additiveMetadataChangeDoesNotReuseTheInputList() {
        val local = List(10) { localRoute(it) }
        // Route 1 has no mode/colour, so the vehicle genuinely adds metadata.
        assertNull(local[1].transportMode)
        assertNull(local[1].color)
        val vehicles = listOf(vehicle(1, routeId = local[1].id, routeName = null, transportMode = "TRAM", markerColor = "FFFFFF"))
        val result = EmmaParser.routeCatalogue(vehicles, local)

        assertNotSame("metadata that fills a gap must produce a new list", local, result)
        assertEquals("TRAM", result[1].transportMode)
        assertEquals("FFFFFF", result[1].color)
        assertSame("untouched route instances stay shared", local[2], result[2])
        assertSame("untouched route instances stay shared", local[0], result[0])
    }

    // --- Host-JVM benchmark of the current repeated work ---

    private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]

    private fun p95(values: List<Long>): Long = values.sorted()[((values.size - 1) * 0.95).toInt()]

    private fun buildSyntheticCatalogue(routes: Int = 10_000, vehicles: Int = 5_000): Pair<List<TransitRoute>, List<Vehicle>> {
        val random = Random(424242)
        val local = List(routes) { i ->
            localRoute(
                index = i,
                id = "synthetic:${i % 4}:$i",
                number = (i % 900 + 1).toString(),
                name = "Synthetic Route $i",
                providerId = listOf("bkk", "volan", "hev", "mav")[i % 4],
                transportMode = listOf("BUS", "TRAM", "COACH", "RAIL")[i % 4],
                color = if (i % 6 == 0) "%06X".format(random.nextInt(0, 0x1000000)) else null
            )
        }
        // All vehicles map onto existing routes with no metadata change, so the catalogue stays equal.
        val movingVehicles = List(vehicles) { v ->
            val route = local[v % routes]
            Vehicle(
                id = "emma:synthetic:$v",
                position = GeoPoint(47.4 + (v % 100) * 0.001, 19.0 + (v % 100) * 0.001),
                observedAt = baseInstant.minusSeconds((v % 60).toLong()),
                routeId = route.id,
                providerId = route.providerId,
                routeNumber = route.number,
                destination = null,
                routeName = null,
                transportMode = null,
                markerColor = null
            )
        }
        return local to movingVehicles
    }

    @Test
    fun currentRepeatedWorkBaselineOnLargeSyntheticCatalogue() {
        val (local, vehicles) = buildSyntheticCatalogue()
        assertEquals(10_000, local.size)
        assertEquals(5_000, vehicles.size)

        // Warm up the JIT; result content is stable across calls.
        repeat(5) { EmmaParser.routeCatalogue(vehicles, local) }

        val runs = 9
        val nanos = mutableListOf<Long>()
        repeat(runs) {
            val start = System.nanoTime()
            val result = EmmaParser.routeCatalogue(vehicles, local)
            val elapsed = System.nanoTime() - start
            nanos += elapsed
            assertEquals(local.size, result.size)
        }

        val result = EmmaParser.routeCatalogue(vehicles, local)
        val localIdentities = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<TransitRoute, Boolean>())
        localIdentities.addAll(local)
        val copiedInstances = result.count { r -> r !in localIdentities }
        val listReused = result === local

        val medianMs = median(nanos) / 1_000_000.0
        val p95Ms = p95(nanos) / 1_000_000.0
        val meanMs = nanos.average() / 1_000_000.0

        println("=== [HOST JVM ONLY] EmmaParser.routeCatalogue current work baseline ===")
        println("Dataset: ${local.size} local routes / ${vehicles.size} vehicles (all referencing existing routes, no metadata change)")
        println("Runs: $runs  median=${"%.3f".format(medianMs)} ms  mean=${"%.3f".format(meanMs)} ms  p95=${"%.3f".format(p95Ms)} ms  min=${"%.3f".format(nanos.min() / 1e6)} ms  max=${"%.3f".format(nanos.max() / 1e6)} ms")
        println("Per-run ms: ${nanos.map { "%.3f".format(it / 1e6) }}")
        println("Repeated work today: resultListReused=$listReused, routeCopiesPerCall=$copiedInstances/${local.size}")
        println("NOTE: JVM unit-test timings only; no device or production performance claim.")
        println("======================================================================")

        // Correctness, not wallclock: an unchanged catalogue must reuse the input list, not copy it.
        assertSame("an unchanged synthetic catalogue must reuse the input list", local, result)
        assertEquals(0, copiedInstances)
        assertEquals(local.size, result.size)
    }
}
