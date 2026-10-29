package hu.app.bmenetrend.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class TransitSearchTest {
    private fun route(id: String, number: String, mode: String, provider: String = "bkk") =
        TransitRoute(id, number, "Budapest – Bécsi út", emptyList(), provider, transportMode = mode)

    private val routes = listOf(
        route("tram11", "11", "TRAM"),
        route("bus1", "1", "BUS"),
        route("tram1", "1", "TRAM"),
        route("metro1", "M1", "SUBWAY")
    )

    @Test fun hungarianAndEnglishTransportQueriesAreOrderIndependentAndMatchExactRouteNumber() {
        listOf("1 villamos", "villamos 1", "1 tram", "BKK 1 villamos", "1-es villamos").forEach {
            assertEquals(listOf("tram1"), TransitSearch.routes(routes, TransitSearch.query(it)).map { route -> route.id })
        }
        assertEquals(listOf("metro1"), TransitSearch.routes(routes, TransitSearch.query("M1 metró")).map { it.id })
        assertEquals(listOf("bus1"), TransitSearch.routes(routes, TransitSearch.query("1 busz")).map { it.id })
    }

    @Test fun exactNumbersRankAheadOfPrefixesWithoutConflatingTransportModes() {
        val result = TransitSearch.routes(routes, TransitSearch.query("1"))
        assertEquals(setOf("bus1", "tram1"), result.take(2).map { it.id }.toSet())
        assertEquals("tram11", result.last().id)
        assertEquals(listOf("tram11", "tram1").toSet(), TransitSearch.routes(routes, TransitSearch.query("villamos")).map { it.id }.toSet())
    }

    @Test fun cachedStopIndexMatchesUnaccentedOutOfOrderWordsAndRanksNearbyStops() {
        val far = TransitStop("far", "Budapest, Kelenföld vasútállomás", GeoPoint(47.49, 19.0), emptyList(), "bkk")
        val near = far.copy(id = "near", position = GeoPoint(47.46, 19.03))
        val index = TransitSearch.indexStops(listOf(far, near))
        assertEquals("near", TransitSearch.stops(index, TransitSearch.query("vasutallomas kelenfold"), GeoPoint(47.46, 19.03), emptySet()).first().id)
        assertTrue(TransitSearch.stops(index, TransitSearch.query("1 villamos"), null, emptySet()).isEmpty())
        assertTrue(TransitSearch.stops(index, TransitSearch.query("volan kelenfold"), null, emptySet()).isEmpty())
    }

    @Test fun queryParserExtractsModesAndAliases() {
        val q1 = TransitSearch.query("M4 metro")
        assertEquals(listOf("m4"), q1.terms)
        assertEquals(setOf("SUBWAY"), q1.modes)

        val q2 = TransitSearch.query("H5 hev")
        assertEquals(listOf("h5"), q2.terms)
        assertEquals(setOf("SUBURBAN_RAILWAY"), q2.modes)

        val q3 = TransitSearch.query("volan 760")
        assertEquals(listOf("760"), q3.terms)
        assertEquals("volan", q3.provider)
    }

    @Test fun stopSearchMatchesNormalizedQueryAndFiltersByProvider() {
        val stops = listOf(
            TransitStop("1", "Kelenföld vasútállomás M", GeoPoint(47.46, 19.02), listOf("1"), "bkk"),
            TransitStop("2", "Kelenföld vasútállomás", GeoPoint(47.46, 19.03), listOf("2"), "volan"),
            TransitStop("near", "Kelenföld vasútállomás", GeoPoint(47.46, 19.025), listOf("3"), "mav")
        )
        val index = TransitSearch.indexStops(stops)

        assertEquals(3, TransitSearch.stops(index, TransitSearch.query("kelenfold"), null, emptySet()).size)
        // Point (47.46, 19.03) has distance 0 to stop "2"
        assertEquals("2", TransitSearch.stops(index, TransitSearch.query("vasutallomas kelenfold"), GeoPoint(47.46, 19.03), emptySet()).first().id)
        // Point (47.46, 19.025) has distance 0 to stop "near"
        assertEquals("near", TransitSearch.stops(index, TransitSearch.query("vasutallomas kelenfold"), GeoPoint(47.46, 19.025), emptySet()).first().id)
        // Mode terms don't match stops
        assertTrue(TransitSearch.stops(index, TransitSearch.query("1 villamos"), null, emptySet()).isEmpty())
        // Provider filters restrict stop search correctly
        assertEquals(listOf("2"), TransitSearch.stops(index, TransitSearch.query("volan kelenfold"), null, emptySet()).map { it.id })
        assertEquals(listOf("1"), TransitSearch.stops(index, TransitSearch.query("bkk kelenfold"), null, emptySet()).map { it.id })
        assertTrue(TransitSearch.stops(index, TransitSearch.query("viabusz kelenfold"), null, emptySet()).isEmpty())
    }

    // --- Independent Reference Implementations Using Full Stable Sort (TimSort) ---

    private class RefScoredRoute(val route: TransitRoute, val score: Int, val length: Int, val number: String) : Comparable<RefScoredRoute> {
        override fun compareTo(other: RefScoredRoute): Int {
            val s = other.score.compareTo(this.score)
            if (s != 0) return s
            val l = this.length.compareTo(other.length)
            if (l != 0) return l
            return this.number.compareTo(other.number)
        }
    }

    private fun referenceRoutes(routes: List<TransitRoute>, query: TransitSearch.Query): List<TransitRoute> {
        val numericRegex = Regex("^[0-9]+[a-zA-Z]?$")
        val numeric = query.terms.firstOrNull { it.matches(numericRegex) }
        val queryText = query.text

        return routes.asSequence().mapNotNull { route ->
            val mode = route.transportMode ?: if (route.providerId in setOf("volan", "viabusz", "kanyargo", "homm")) "BUS" else null
            if (query.modes.isNotEmpty() && mode !in query.modes) return@mapNotNull null
            if (query.provider != null && query.provider != route.providerId) return@mapNotNull null
            val normalizedNum = TransitSearch.normalize(route.number)
            if (numeric != null && query.modes.isNotEmpty() && numeric != normalizedNum) return@mapNotNull null

            val text = TransitSearch.normalize("${route.number} ${route.name.replace('-', ' ')} ${route.name} ${route.providerId}")
            for (term in query.terms) {
                if (term == numeric) {
                    if (!normalizedNum.startsWith(term)) return@mapNotNull null
                } else {
                    if (!text.contains(term)) return@mapNotNull null
                }
            }
            val score = when {
                numeric == normalizedNum -> 100
                normalizedNum == queryText -> 100
                else -> 0
            }
            RefScoredRoute(route, score, route.number.length, route.number)
        }.sorted().take(100).map { it.route }.toList()
    }

    private class RefScoredVehicle(val vehicle: Vehicle, val score: Int, val observedAt: Instant) : Comparable<RefScoredVehicle> {
        override fun compareTo(other: RefScoredVehicle): Int {
            val s = other.score.compareTo(this.score)
            if (s != 0) return s
            return other.observedAt.compareTo(this.observedAt)
        }
    }

    private fun referenceVehicles(vehicles: List<Vehicle>, query: TransitSearch.Query): List<Vehicle> {
        val numericRegex = Regex("^[0-9]+[a-zA-Z]?$")
        val nonAlphanumericRegex = Regex("[^a-zA-Z0-9]")
        val numeric = query.terms.firstOrNull { it.matches(numericRegex) }
        val cleanQueryText = query.text.replace("-", "")
        val termCompacts = query.terms.map { it to it.replace(nonAlphanumericRegex, "") }

        return vehicles.asSequence().mapNotNull { vehicle ->
            val mode = vehicle.transportMode ?: "BUS"
            if (query.modes.isNotEmpty() && mode !in query.modes) return@mapNotNull null
            if (query.provider != null && query.provider != vehicle.providerId) return@mapNotNull null
            val normalizedNumber = TransitSearch.normalize(vehicle.displayRouteNumber.orEmpty())
            if (numeric != null && query.modes.isNotEmpty() && numeric != normalizedNumber && numeric != vehicle.trainNumber) return@mapNotNull null

            val rawText = listOfNotNull(vehicle.registration, vehicle.trainNumber, vehicle.serviceName, vehicle.id, vehicle.displayRouteNumber, vehicle.routeName, vehicle.destination, vehicle.providerId).joinToString(" ")
            val normalizedText = TransitSearch.normalize(rawText)
            val compactText = normalizedText.replace(nonAlphanumericRegex, "")
            for ((term, compactTerm) in termCompacts) {
                if (!normalizedText.contains(term) && !compactText.contains(compactTerm)) return@mapNotNull null
            }
            val cleanReg = TransitSearch.normalize(vehicle.registration.orEmpty()).replace("-", "")
            val score = when {
                cleanReg == cleanQueryText -> 100
                normalizedNumber == query.text -> 50
                else -> 0
            }
            RefScoredVehicle(vehicle, score, vehicle.observedAt)
        }.sorted().take(100).map { it.vehicle }.toList()
    }

    private class RefScoredStop(val stop: TransitStop, val rank: Int, val distSq: Double) : Comparable<RefScoredStop> {
        override fun compareTo(other: RefScoredStop): Int {
            val r = other.rank.compareTo(this.rank)
            if (r != 0) return r
            return this.distSq.compareTo(other.distSq)
        }
    }

    private fun referenceStops(
        index: List<TransitSearch.StopEntry>,
        query: TransitSearch.Query,
        location: GeoPoint?,
        favorites: Set<String>,
    ): List<TransitStop> {
        if (query.modes.isNotEmpty()) return emptyList()
        val hasLocation = location != null
        val locLat = location?.lat ?: 0.0
        val locLon = location?.lon ?: 0.0

        return index.asSequence().mapNotNull { entry ->
            if (query.provider != null && query.provider !in entry.stop.providerIds) return@mapNotNull null
            for (term in query.terms) {
                if (!entry.text.contains(term)) return@mapNotNull null
            }
            val rank = when {
                entry.name == query.text -> 2
                entry.stop.id in favorites -> 1
                else -> 0
            }
            val distSq = if (hasLocation) {
                val dLat = entry.stop.position.lat - locLat
                val dLon = entry.stop.position.lon - locLon
                dLat * dLat + dLon * dLon
            } else 0.0
            RefScoredStop(entry.stop, rank, distSq)
        }.sorted().take(100).map { it.stop }.toList()
    }

    @Test fun exactParityWithIndependentFullSortReferenceForRoutesOver100Matches() {
        val routeInput = (0 until 300).map { i ->
            route(
                id = "r$i",
                number = when {
                    i % 6 == 0 -> "1"
                    i % 6 == 1 -> "1A"
                    i % 6 == 2 -> "10"
                    i % 6 == 3 -> "100"
                    i % 6 == 4 -> "1" // identical number with different ID to test stable encounter order
                    else -> "1${i % 5}"
                },
                mode = if (i % 2 == 0) "TRAM" else "BUS",
                provider = if (i % 3 == 0) "bkk" else "volan"
            )
        }

        // Test 1: Bounded prefix search across >100 matches
        val q1 = TransitSearch.query("1")
        val actual1 = TransitSearch.routes(routeInput, q1)
        val expected1 = referenceRoutes(routeInput, q1)
        assertEquals(100, actual1.size)
        assertEquals(expected1.map { it.id }, actual1.map { it.id })

        // Test 2: Mode filter + provider filter
        val q2 = TransitSearch.query("1 tram volan")
        val actual2 = TransitSearch.routes(routeInput, q2)
        val expected2 = referenceRoutes(routeInput, q2)
        assertTrue(actual2.isNotEmpty())
        assertEquals(expected2.map { it.id }, actual2.map { it.id })
    }

    @Test fun exactParityWithIndependentFullSortReferenceForVehiclesOver100Matches() {
        val vehicleInput = (0 until 300).map { i ->
            Vehicle(
                id = "v$i",
                position = GeoPoint(47.49 + (i % 50) * 0.001, 19.04 + (i % 50) * 0.001),
                observedAt = Instant.ofEpochSecond(1700000000L + (i % 15) * 60), // repeated timestamps to test stable tie order
                providerId = if (i % 2 == 0) "bkk" else "volan",
                routeNumber = if (i % 4 == 0) "1" else "10",
                transportMode = if (i % 3 == 0) "TRAM" else "BUS",
                destination = "Destination $i",
                registration = if (i % 50 == 0) "BKK-001" else "VEH-$i"
            )
        }

        // Test 1: Query matching all 300 vehicles with tie breaking on score, observedAt, and arrival order
        val q1 = TransitSearch.query("destination")
        val actual1 = TransitSearch.vehicles(vehicleInput, q1)
        val expected1 = referenceVehicles(vehicleInput, q1)
        assertEquals(100, actual1.size)
        assertEquals(expected1.map { it.id }, actual1.map { it.id })

        // Test 2: Registration exact match ranks top with newer observedAt breaking ties
        val q2 = TransitSearch.query("bkk001")
        val actual2 = TransitSearch.vehicles(vehicleInput, q2)
        val expected2 = referenceVehicles(vehicleInput, q2)
        assertTrue(actual2.isNotEmpty())
        assertEquals(expected2.map { it.id }, actual2.map { it.id })
        assertEquals(expected2.first().id, actual2.first().id)
    }

    @Test fun exactParityWithIndependentFullSortReferenceForStopsOver100Matches() {
        val stopInput = (0 until 300).map { i ->
            TransitStop(
                id = "s$i",
                name = when {
                    i == 42 -> "Central" // exact name match (rank 2)
                    i == 99 -> "Central"
                    else -> "Central Station $i"
                },
                position = GeoPoint(47.0 + (i % 20) * 0.01, 19.0 + (i % 20) * 0.01), // repeated coords for dist ties
                routeIds = emptyList(),
                providerId = if (i % 2 == 0) "bkk" else "volan",
                station = false
            )
        }
        val stopIndex = TransitSearch.indexStops(stopInput)
        val favorites = setOf("s10", "s20", "s30", "s40")
        val location = GeoPoint(47.05, 19.05)

        // Test 1: Search with location and favorites across >100 candidates
        val q1 = TransitSearch.query("central")
        val actual1 = TransitSearch.stops(stopIndex, q1, location, favorites)
        val expected1 = referenceStops(stopIndex, q1, location, favorites)
        assertEquals(100, actual1.size)
        assertEquals(expected1.map { it.id }, actual1.map { it.id })

        // Check rank priority: exact matches first, then favorites, then distance
        assertEquals("s42", actual1[0].id)
        assertEquals("s99", actual1[1].id)
        assertTrue(actual1.subList(2, 6).map { it.id }.containsAll(favorites))

        // Test 2: Search without location (distSq = 0.0, pure stable encounter order tie-break)
        val actualNoLoc = TransitSearch.stops(stopIndex, q1, null, emptySet())
        val expectedNoLoc = referenceStops(stopIndex, q1, null, emptySet())
        assertEquals(100, actualNoLoc.size)
        assertEquals(expectedNoLoc.map { it.id }, actualNoLoc.map { it.id })

        // Test 3: Provider filter + provider query token
        val qVolan = TransitSearch.query("volan central")
        val actualVolan = TransitSearch.stops(stopIndex, qVolan, null, emptySet())
        val expectedVolan = referenceStops(stopIndex, qVolan, null, emptySet())
        assertEquals(100, actualVolan.size)
        assertEquals(expectedVolan.map { it.id }, actualVolan.map { it.id })
    }

    @Test fun cacheReplacementAndConcurrentDistinctInputsDoNotCrossContaminate() {
        val old = listOf(route("old", "1", "TRAM"))
        val replacement = listOf(route("new", "1", "TRAM"))
        assertEquals(listOf("old"), TransitSearch.routes(old, TransitSearch.query("1 tram")).map { it.id })
        assertEquals(listOf("new"), TransitSearch.routes(replacement, TransitSearch.query("1 tram")).map { it.id })

        val pool = Executors.newFixedThreadPool(6)
        try {
            val inputs = (0 until 24).map { n -> listOf(route("parallel-$n", "$n", "TRAM")) }
            val results = pool.invokeAll(inputs.map { input -> Callable {
                repeat(20) {
                    assertEquals(
                        input.single().id,
                        TransitSearch.routes(input, TransitSearch.query(input.single().number + " tram")).single().id
                    )
                }
                input.single().id
            } }).map { it.get() }
            assertEquals(inputs.map { it.single().id }, results)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun hostBenchmarkBestMatchesVsFullSort() {
        val stops = (0 until 3_000).map { i ->
            TransitStop(
                id = "s$i",
                name = "Station ${i % 200}",
                position = GeoPoint(47.0 + (i % 50) * 0.005, 19.0 + (i % 50) * 0.005),
                routeIds = emptyList(),
                providerId = if (i % 2 == 0) "bkk" else "volan",
                station = false
            )
        }
        val index = TransitSearch.indexStops(stops)
        val query = TransitSearch.query("station")
        val loc = GeoPoint(47.1, 19.1)
        val favs = setOf("s1", "s2", "s3")

        // Warmup
        repeat(30) {
            TransitSearch.stops(index, query, loc, favs)
            referenceStops(index, query, loc, favs)
        }

        val iters = 100
        val t0 = System.nanoTime()
        repeat(iters) {
            TransitSearch.stops(index, query, loc, favs)
        }
        val t1 = System.nanoTime()
        val bestMatchesDurationNanos = (t1 - t0) / iters

        val t2 = System.nanoTime()
        repeat(iters) {
            referenceStops(index, query, loc, favs)
        }
        val t3 = System.nanoTime()
        val fullSortDurationNanos = (t3 - t2) / iters

        println("HOST BENCHMARK (N=3000 stops, top-100): bestMatches=${bestMatchesDurationNanos / 1_000}µs, fullSort=${fullSortDurationNanos / 1_000}µs, speedup=${"%.2f".format(fullSortDurationNanos.toDouble() / bestMatchesDurationNanos)}x")
        assertTrue(bestMatchesDurationNanos > 0)
    }
}
