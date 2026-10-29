package hu.app.bmenetrend.map

import android.database.sqlite.SQLiteDatabase
import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.MapBounds
import hu.app.bmenetrend.data.StopClusters
import hu.app.bmenetrend.data.TransitStop
import hu.app.bmenetrend.offline.OfflineTransitData
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.*
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class StopMapBenchmarkAndParityTest {

    private val packFile = File("/tmp/bmenetrend-offline-pack.db")
    private var realStops: List<TransitStop> = emptyList()
    private var nationalStops: List<TransitStop> = emptyList()
    private val realBearings: Map<String, List<Double>> = emptyMap()

    @Before
    fun setup() {
        if (realStops.isEmpty() && packFile.exists() && packFile.length() > 0) {
            try {
                SQLiteDatabase.openDatabase(packFile.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    // Load Budapest metro area stops (up to 6,000 real stops)
                    db.rawQuery(
                        "SELECT id,source_id,name,lat,lon,platform,parent,station,feed FROM stops WHERE lat BETWEEN 47.4 AND 47.6 AND lon BETWEEN 18.9 AND 19.2 ORDER BY id LIMIT 6000",
                        null
                    ).use { cursor ->
                        val list = mutableListOf<TransitStop>()
                        while (cursor.moveToNext()) {
                            list.add(OfflineTransitData.stop(cursor))
                        }
                        realStops = list
                    }

                    // Load full national stops across Hungary (up to all 68,395 stops)
                    db.rawQuery(
                        "SELECT id,source_id,name,lat,lon,platform,parent,station,feed FROM stops WHERE lat BETWEEN 45.7 AND 48.6 AND lon BETWEEN 16.1 AND 22.9 ORDER BY id",
                        null
                    ).use { cursor ->
                        val list = mutableListOf<TransitStop>()
                        while (cursor.moveToNext()) {
                            list.add(OfflineTransitData.stop(cursor))
                        }
                        nationalStops = list
                    }
                }
            } catch (e: Throwable) {
                println("Could not load /tmp/bmenetrend-offline-pack.db: ${e.message}")
            }
        }
    }

    private fun stationAnchor(members: List<TransitStop>): TransitStop = members.first().let { first ->
        first.copy(position = (members.firstOrNull { it.providerId == "volan" } ?: first).position)
    }

    private fun generateSyntheticMultiOperatorStops(count: Int = 1000, seed: Long = 42L): Pair<List<TransitStop>, Map<String, List<Double>>> {
        val rnd = Random(seed)
        val operators = listOf("bkk", "volan", "viabusz", "kanyargo", "homm", "hev")
        val modes = listOf("BUS", "COACH", "TRAM", "TROLLEYBUS", "RAIL", null)
        val stops = mutableListOf<TransitStop>()
        val bearings = mutableMapOf<String, List<Double>>()

        val focalPoints = (0 until 20).map { i ->
            GeoPoint(47.45 + (i / 5) * 0.04 + rnd.nextDouble(-0.01, 0.01), 19.0 + (i % 5) * 0.04 + rnd.nextDouble(-0.01, 0.01))
        }

        var idCounter = 100
        for (focal in focalPoints) {
            val stationName = "Focal Station ${focal.lat.toString().take(6)}"
            val parentId = "parent_station_${idCounter++}"
            stops.add(TransitStop(parentId, stationName, focal, emptyList(), "volan", station = true))

            val platformCount = rnd.nextInt(15, 26)
            for (p in 0 until platformCount) {
                val pId = "stop_${idCounter++}"
                val operator = operators[rnd.nextInt(operators.size)]
                val mode = modes[rnd.nextInt(modes.size)]
                val lat = focal.lat + rnd.nextDouble(-0.0008, 0.0008)
                val lon = focal.lon + rnd.nextDouble(-0.0008, 0.0008)
                val isOpposite = (p % 2 == 1)
                val name = if (isOpposite) "$stationName (Ellenirany)" else stationName
                val stopBear = if (isOpposite) listOf(180.0) else listOf(0.0)
                bearings[pId] = stopBear
                stops.add(
                    TransitStop(
                        id = pId,
                        name = name,
                        position = GeoPoint(lat, lon),
                        routeIds = emptyList(),
                        providerId = operator,
                        providerIds = listOf(operator),
                        transportMode = mode,
                        parentStationId = if (rnd.nextBoolean()) parentId else null,
                        station = false
                    )
                )
            }
        }

        stops.add(TransitStop("5", "Vasut utca", GeoPoint(47.471394, 18.824603), emptyList(), "viabusz", providerIds = listOf("viabusz")))
        stops.add(TransitStop("81", "Vasut utca (Ellenirany)", GeoPoint(47.47140816, 18.82467514), emptyList(), "viabusz", providerIds = listOf("viabusz")))
        stops.add(TransitStop("emma:hkir:hkir_777139_1", "Biatorbagy, Vasut utca", GeoPoint(47.471243, 18.825238), emptyList(), "volan", providerIds = listOf("volan"), transportMode = "COACH"))
        stops.add(TransitStop("emma:hkir:hkir_777139_2", "Biatorbagy, Vasut utca", GeoPoint(47.471223, 18.825772), emptyList(), "volan", providerIds = listOf("volan"), transportMode = "COACH"))
        bearings["5"] = listOf(232.0)
        bearings["81"] = listOf(358.0)
        bearings["emma:hkir:hkir_777139_1"] = listOf(230.0)
        bearings["emma:hkir:hkir_777139_2"] = listOf(355.0)

        while (stops.size < count) {
            val sId = "synth_${idCounter++}"
            val op = operators[rnd.nextInt(operators.size)]
            stops.add(
                TransitStop(
                    id = sId,
                    name = "Stop $sId",
                    position = GeoPoint(rnd.nextDouble(47.3, 47.7), rnd.nextDouble(18.8, 19.3)),
                    routeIds = emptyList(),
                    providerId = op,
                    providerIds = listOf(op)
                )
            )
        }

        return stops to bearings
    }

    // --- 4 ORIGINAL EXACT PARITY TESTS ---

    @Test
    fun exactSemanticParityOnSyntheticMultiOperatorDataset() {
        val (stops, bearings) = generateSyntheticMultiOperatorStops(count = 800)
        val bounds = MapBounds(
            stops.minOf { it.position.lon },
            stops.minOf { it.position.lat },
            stops.maxOf { it.position.lon },
            stops.maxOf { it.position.lat }
        )

        val refGroups = StopMapClusteringReference.groups(stops, bounds, 16.0, bearings)
        val optGroups = StopMapClustering.groups(stops, bounds, 16.0, bearings)

        assertEquals("Group count must match exactly", refGroups.size, optGroups.size)

        for (i in refGroups.indices) {
            val refIds = refGroups[i].map { it.id }
            val optIds = optGroups[i].map { it.id }
            assertEquals("Group $i members must match exactly", refIds, optIds)
        }

        val refAnchors = refGroups.map(::stationAnchor)
        val optAnchors = optGroups.map(::stationAnchor)
        assertEquals("Station anchors count must match", refAnchors.size, optAnchors.size)
        for (i in refAnchors.indices) {
            assertEquals("Anchor $i ID must match", refAnchors[i].id, optAnchors[i].id)
            assertEquals("Anchor $i lat must match", refAnchors[i].position.lat, optAnchors[i].position.lat, 1e-9)
            assertEquals("Anchor $i lon must match", refAnchors[i].position.lon, optAnchors[i].position.lon, 1e-9)
        }

        val refOffsets = StopMarkerLayoutReference.offsets(refAnchors)
        val optOffsets = StopMarkerLayout.offsets(optAnchors)
        assertEquals("Offsets key count must match", refOffsets.size, optOffsets.size)
        for ((id, refOff) in refOffsets) {
            val optOff = optOffsets[id]
            assertNotNull("Offset for $id must exist", optOff)
            assertEquals("Offset x for $id must match", refOff.x, optOff!!.x, 1e-6)
            assertEquals("Offset y for $id must match", refOff.y, optOff.y, 1e-6)
        }
    }

    @Test
    fun exactParityWithCachedPreviousOffsetsAndNegativeCoords() {
        val (stops, bearings) = generateSyntheticMultiOperatorStops(count = 300)
        val bounds = MapBounds(18.0, 47.0, 20.0, 48.0)
        val groups = StopMapClustering.groups(stops, bounds, 16.0, bearings)
        val anchors = groups.map(::stationAnchor)

        val previousOffsets = anchors.take(anchors.size / 2).associate {
            it.id to StopMarkerLayout.Offset(10.0, -12.0)
        }

        val refPrevOffsets = previousOffsets.mapValues {
            StopMarkerLayoutReference.Offset(it.value.x, it.value.y)
        }

        val refOffsets = StopMarkerLayoutReference.offsets(anchors, refPrevOffsets)
        val optOffsets = StopMarkerLayout.offsets(anchors, previousOffsets)

        assertEquals(refOffsets.size, optOffsets.size)
        for ((id, refOff) in refOffsets) {
            val optOff = optOffsets[id]
            assertNotNull(optOff)
            assertEquals("Offset x for $id must match", refOff.x, optOff!!.x, 1e-6)
            assertEquals("Offset y for $id must match", refOff.y, optOff.y, 1e-6)
        }

        val extremeStops = listOf(
            TransitStop("ext_north", "North", GeoPoint(84.9, -179.9), emptyList(), "volan"),
            TransitStop("ext_south", "South", GeoPoint(-84.9, 179.9), emptyList(), "bkk"),
            TransitStop("ext_greenwich_w", "Greenwich West", GeoPoint(0.0, -0.001), emptyList(), "viabusz"),
            TransitStop("ext_greenwich_e", "Greenwich East", GeoPoint(0.0, 0.001), emptyList(), "viabusz")
        )
        val extRef = StopMarkerLayoutReference.offsets(extremeStops)
        val extOpt = StopMarkerLayout.offsets(extremeStops)
        assertEquals(extRef.size, extOpt.size)
        for ((id, rOff) in extRef) {
            val oOff = extOpt[id]!!
            assertEquals(rOff.x, oOff.x, 1e-6)
            assertEquals(rOff.y, oOff.y, 1e-6)
        }
    }

    @Test
    fun exactParityOppositePlatformBearingRulesAndParentStationFilter() {
        val parent = TransitStop("parent_1", "Main Hub", GeoPoint(47.5, 19.0), emptyList(), "volan", station = true)
        val child1 = TransitStop("c1", "Platform 1", GeoPoint(47.50005, 19.00005), emptyList(), "bkk", parentStationId = "parent_1")
        val child2 = TransitStop("c2", "Platform 2", GeoPoint(47.50006, 19.00006), emptyList(), "volan", parentStationId = "parent_1")
        val opposite = TransitStop("opp", "Platform 1 (Ellenirany)", GeoPoint(47.5001, 19.0001), emptyList(), "volan")

        val bearings = mapOf(
            "c1" to listOf(45.0),
            "c2" to listOf(45.0),
            "opp" to listOf(225.0)
        )

        val stops = listOf(parent, child1, child2, opposite)
        val bounds = MapBounds(18.9, 47.4, 19.1, 47.6)

        val refGroups = StopMapClusteringReference.groups(stops, bounds, 16.0, bearings)
        val optGroups = StopMapClustering.groups(stops, bounds, 16.0, bearings)

        assertTrue("Parent station must not appear in reference groups", refGroups.none { g -> g.any { it.id == "parent_1" } })
        assertTrue("Parent station must not appear in optimized groups", optGroups.none { g -> g.any { it.id == "parent_1" } })

        assertEquals(refGroups.size, optGroups.size)
        assertEquals(refGroups.map { it.map { s -> s.id } }, optGroups.map { it.map { s -> s.id } })

        val refHighlighted = StopMapClusteringReference.groups(stops, bounds, 16.0, bearings, highlightedStopIds = setOf("c1"))
        val optHighlighted = StopMapClustering.groups(stops, bounds, 16.0, bearings, highlightedStopIds = setOf("c1"))
        assertEquals(refHighlighted.size, optHighlighted.size)
        assertEquals(refHighlighted.map { it.map { s -> s.id } }, optHighlighted.map { it.map { s -> s.id } })
    }

    @Test
    fun exactParityOnRealNationalStopsIfAvailable() {
        if (realStops.isEmpty()) {
            println("Real stops pack not available, skipping national stops test")
            return
        }

        val bounds = MapBounds(
            realStops.minOf { it.position.lon },
            realStops.minOf { it.position.lat },
            realStops.maxOf { it.position.lon },
            realStops.maxOf { it.position.lat }
        )

        val refGroups = StopMapClusteringReference.groups(realStops, bounds, 16.0, realBearings)
        val optGroups = StopMapClustering.groups(realStops, bounds, 16.0, realBearings)

        assertEquals("Real stops group count must match", refGroups.size, optGroups.size)
        for (i in refGroups.indices) {
            val refIds = refGroups[i].map { it.id }
            val optIds = optGroups[i].map { it.id }
            assertEquals("Group $i in real stops must match", refIds, optIds)
        }

        val refAnchors = refGroups.map(::stationAnchor)
        val optAnchors = optGroups.map(::stationAnchor)
        val refOffsets = StopMarkerLayoutReference.offsets(refAnchors)
        val optOffsets = StopMarkerLayout.offsets(optAnchors)

        assertEquals(refOffsets.size, optOffsets.size)
        for ((id, refOff) in refOffsets) {
            val optOff = optOffsets[id]!!
            assertEquals("Offset x for real stop $id", refOff.x, optOff.x, 1e-6)
            assertEquals("Offset y for real stop $id", refOff.y, optOff.y, 1e-6)
        }
    }

    // --- EDGE PARITY TESTS ---

    @Test
    fun exactParityRandomizedGridBoundaries() {
        val baseLats = listOf(0.0, 47.4979, -33.8688, 59.9139, 84.0)
        val baseLons = listOf(0.0, 19.0402, 151.2093, 10.7522, -120.0)

        for (idx in baseLats.indices) {
            val baseLat = baseLats[idx]
            val baseLon = baseLons[idx]
            val latStep = 0.0009

            val bounds = MapBounds(
                west = baseLon - 0.015,
                south = baseLat - 0.015,
                east = baseLon + 0.015,
                north = baseLat + 0.015
            )

            val stops = mutableListOf<TransitStop>()
            // Generate stops crossing right at grid cell boundaries
            for (i in 0 until 60) {
                // Placed near integer multiple of latStep
                val yMult = (baseLat / latStep).toInt() + (i % 7) - 3
                val boundaryLat = yMult * latStep
                val offsetLat = if (i % 2 == 0) boundaryLat - 1e-6 else boundaryLat + 1e-6
                val lon = baseLon + (i - 30) * 0.0004
                val op = if (i % 2 == 0) "bkk" else "volan"
                stops.add(
                    TransitStop(
                        id = "boundary_stop_${idx}_$i",
                        name = "Boundary Station ${i / 2}",
                        position = GeoPoint(offsetLat, lon),
                        routeIds = emptyList(),
                        providerId = op,
                        providerIds = listOf(op)
                    )
                )
            }

            val refGroups = StopMapClusteringReference.groups(stops, bounds, 16.0)
            val optGroups = StopMapClustering.groups(stops, bounds, 16.0)

            assertEquals("Randomized grid boundaries group count at idx $idx", refGroups.size, optGroups.size)
            for (g in refGroups.indices) {
                assertEquals("Boundary group $g members at idx $idx", refGroups[g].map { it.id }, optGroups[g].map { it.id })
            }

            val refAnchors = refGroups.map(::stationAnchor)
            val optAnchors = optGroups.map(::stationAnchor)
            val refOffsets = StopMarkerLayoutReference.offsets(refAnchors)
            val optOffsets = StopMarkerLayout.offsets(optAnchors)

            assertEquals("Boundary offsets size at idx $idx", refOffsets.size, optOffsets.size)
            for ((id, refOff) in refOffsets) {
                val optOff = optOffsets[id]!!
                assertEquals("Offset x at idx $idx for $id", refOff.x, optOff.x, 1e-6)
                assertEquals("Offset y at idx $idx for $id", refOff.y, optOff.y, 1e-6)
            }
        }
    }

    @Test
    fun exactParityDenseCrossProviderBearings() {
        val providers = listOf("bkk", "volan", "viabusz", "mav", "kanyargo", "homm")
        val basePoint = GeoPoint(47.498, 19.040)
        val stops = mutableListOf<TransitStop>()
        val bearings = mutableMapOf<String, List<Double>>()

        // Hub 1: Matching bearings (< 45 deg) -> should cluster across providers up to 95m
        for (i in 0 until providers.size) {
            val id = "hub1_${providers[i]}"
            stops.add(
                TransitStop(
                    id = id,
                    name = "Central Hub",
                    position = GeoPoint(basePoint.lat + i * 0.00015, basePoint.lon), // ~16m apart each
                    routeIds = emptyList(),
                    providerId = providers[i],
                    providerIds = listOf(providers[i])
                )
            )
            bearings[id] = listOf(90.0 + (i * 5.0)) // 90, 95, 100, 105, 110, 115 -> all pairwise < 45
        }

        // Hub 2: Conflicting bearings (180 deg opposite) -> must NEVER cluster
        for (i in 0 until 4) {
            val id = "hub2_opp_$i"
            val heading = if (i % 2 == 0) 0.0 else 180.0
            stops.add(
                TransitStop(
                    id = id,
                    name = "Opposite Avenue",
                    position = GeoPoint(basePoint.lat + 0.005 + (i * 0.00005), basePoint.lon + 0.005), // ~5m apart
                    routeIds = emptyList(),
                    providerId = providers[i],
                    providerIds = listOf(providers[i])
                )
            )
            bearings[id] = listOf(heading)
        }

        // Hub 3: Empty bearings -> only cluster within 30m
        for (i in 0 until 3) {
            val id = "hub3_nobear_$i"
            stops.add(
                TransitStop(
                    id = id,
                    name = "Quiet Square",
                    position = GeoPoint(basePoint.lat + 0.010 + (i * 0.00020), basePoint.lon + 0.010), // ~22m apart
                    routeIds = emptyList(),
                    providerId = providers[i],
                    providerIds = listOf(providers[i])
                )
            )
        }

        val bounds = MapBounds(19.03, 47.49, 19.06, 47.52)
        val refGroups = StopMapClusteringReference.groups(stops, bounds, 16.0, bearings)
        val optGroups = StopMapClustering.groups(stops, bounds, 16.0, bearings)

        assertEquals("Cross provider groups count", refGroups.size, optGroups.size)
        for (i in refGroups.indices) {
            assertEquals("Cross provider group $i ids", refGroups[i].map { it.id }, optGroups[i].map { it.id })
        }

        val refAnchors = refGroups.map(::stationAnchor)
        val optAnchors = optGroups.map(::stationAnchor)
        val refOffsets = StopMarkerLayoutReference.offsets(refAnchors)
        val optOffsets = StopMarkerLayout.offsets(optAnchors)

        assertEquals(refOffsets.size, optOffsets.size)
        for ((id, refOff) in refOffsets) {
            val optOff = optOffsets[id]!!
            assertEquals("Offset x for $id", refOff.x, optOff.x, 1e-6)
            assertEquals("Offset y for $id", refOff.y, optOff.y, 1e-6)
        }
    }

    @Test
    fun exactParitySameNameAliases() {
        val stops = listOf(
            TransitStop(
                id = "s1",
                name = "Deak Ferenc ter M",
                position = GeoPoint(47.4975, 19.0540),
                routeIds = emptyList(),
                providerId = "bkk",
                providerIds = listOf("bkk"),
                aliases = listOf("Deak ter", "Budapest, Deak Ferenc ter")
            ),
            TransitStop(
                id = "s2",
                name = "Budapest, Deak Ferenc ter",
                position = GeoPoint(47.4976, 19.0541),
                routeIds = emptyList(),
                providerId = "volan",
                providerIds = listOf("volan"),
                aliases = listOf("Deak ter metroallomas")
            ),
            TransitStop(
                id = "s3",
                name = "Erzsebet ter",
                position = GeoPoint(47.4980, 19.0535),
                routeIds = emptyList(),
                providerId = "bkk",
                providerIds = listOf("bkk"),
                aliases = listOf("Deak Ferenc ter")
            ),
            TransitStop(
                id = "s4",
                name = "Nyugati palyaudvar M",
                position = GeoPoint(47.5100, 19.0550),
                routeIds = emptyList(),
                providerId = "bkk",
                providerIds = listOf("bkk"),
                aliases = listOf("Nyugati pu.", "Budapest-Nyugati")
            ),
            TransitStop(
                id = "s5",
                name = "Budapest-Nyugati",
                position = GeoPoint(47.5101, 19.0551),
                routeIds = emptyList(),
                providerId = "mav",
                providerIds = listOf("mav"),
                aliases = emptyList()
            )
        )

        val bounds = MapBounds(19.050, 47.490, 19.060, 47.520)
        val refGroups = StopMapClusteringReference.groups(stops, bounds, 16.0)
        val optGroups = StopMapClustering.groups(stops, bounds, 16.0)

        assertEquals("Aliases group count must match", refGroups.size, optGroups.size)
        for (i in refGroups.indices) {
            assertEquals("Aliases group $i match", refGroups[i].map { it.id }, optGroups[i].map { it.id })
        }
    }

    @Test
    fun exactParityAmbiguousNearestNegativeExtremeCoordinates() {
        // Test nearest 2 candidates where secondDistance - closestDistance < 4m
        // -> Should NOT merge with closest group and form own group
        val g1Stop = TransitStop("g1", "Main Street", GeoPoint(47.5000, 19.0000), emptyList(), "bkk", providerIds = listOf("bkk"))
        val g2Stop = TransitStop("g2", "Main Street", GeoPoint(47.5000, 19.0003), emptyList(), "volan", providerIds = listOf("volan"))
        val ambiguousStop = TransitStop("ambig", "Main Street", GeoPoint(47.50012, 19.00015), emptyList(), "viabusz", providerIds = listOf("viabusz"))

        val stops = listOf(g1Stop, g2Stop, ambiguousStop)
        val bounds = MapBounds(18.99, 47.49, 19.01, 47.51)

        val refGroups = StopMapClusteringReference.groups(stops, bounds, 16.0)
        val optGroups = StopMapClustering.groups(stops, bounds, 16.0)

        assertEquals("Ambiguous nearest group count", refGroups.size, optGroups.size)
        for (i in refGroups.indices) {
            assertEquals("Ambiguous nearest group $i", refGroups[i].map { it.id }, optGroups[i].map { it.id })
        }

        // Test Negative and extreme coordinates
        val southernWesternStops = listOf(
            TransitStop("sw1", "Southern Stop A", GeoPoint(-34.6037, -58.3816), emptyList(), "bkk", providerIds = listOf("bkk")),
            TransitStop("sw2", "Southern Stop A", GeoPoint(-34.6038, -58.3817), emptyList(), "volan", providerIds = listOf("volan")),
            TransitStop("extreme_n1", "Arctic Station", GeoPoint(84.95, -170.00), emptyList(), "viabusz", providerIds = listOf("viabusz")),
            TransitStop("extreme_n2", "Arctic Station", GeoPoint(84.9501, -170.001), emptyList(), "volan", providerIds = listOf("volan"))
        )

        val worldBounds = MapBounds(-180.0, -85.0, 180.0, 85.0)
        val swRef = StopMapClusteringReference.groups(southernWesternStops, worldBounds, 16.0)
        val swOpt = StopMapClustering.groups(southernWesternStops, worldBounds, 16.0)
        assertEquals("Southern/Western group count", swRef.size, swOpt.size)
        for (i in swRef.indices) {
            assertEquals("Southern/Western group $i", swRef[i].map { it.id }, swOpt[i].map { it.id })
        }
    }

    @Test
    fun highLatitudeCell95mProofAndCoverage() {
        // High latitude proof:
        // A cell is at least 100m on both axes at ANY latitude up to 89.999 deg.
        // Therefore, any 2 stops <= 95m apart can NEVER be > 1 cell apart on dy or dx.
        val testLatitudes = listOf(47.5, 60.0, 70.0, 80.0, 85.0)

        for (lat in testLatitudes) {
            val bounds = MapBounds(
                west = 10.0,
                south = lat - 0.05,
                east = 10.2,
                north = lat + 0.05
            )

            val extremeLatitude = maxOf(abs(bounds.south), abs(bounds.north)).coerceAtMost(89.999)
            val latitudeStep = 0.0009
            val longitudeStep = latitudeStep / cos(Math.toRadians(extremeLatitude)).coerceAtLeast(0.00001)

            // Latitude cell height in meters
            val latCellMeters = StopClusters.distanceMeters(GeoPoint(lat, 10.0), GeoPoint(lat + latitudeStep, 10.0))
            // Longitude cell width in meters at extreme latitude
            val lonCellMetersAtExtreme = StopClusters.distanceMeters(GeoPoint(extremeLatitude, 10.0), GeoPoint(extremeLatitude, 10.0 + longitudeStep))

            assertTrue("Latitude cell size must be >= 100m at lat $lat (was $latCellMeters)", latCellMeters >= 99.9)
            assertTrue("Longitude cell size must be >= 100m at extreme lat $extremeLatitude (was $lonCellMetersAtExtreme)", lonCellMetersAtExtreme >= 99.9)

            // Test two stops 94.5m apart (should cluster if names match and different providers)
            val deltaLat94m = 94.5 / 111195.0
            val s1 = TransitStop("high_lat_1", "Polar Hub", GeoPoint(lat, 10.0), emptyList(), "bkk", providerIds = listOf("bkk"))
            val s2 = TransitStop("high_lat_2", "Polar Hub", GeoPoint(lat + deltaLat94m, 10.0), emptyList(), "volan", providerIds = listOf("volan"))
            val dist = StopClusters.distanceMeters(s1.position, s2.position)
            assertTrue("Distance must be <= 95m (was $dist)", dist <= 95.0)

            val pairStops = listOf(s1, s2)
            val bearings = mapOf(s1.id to listOf(0.0), s2.id to listOf(0.0))
            val refG = StopMapClusteringReference.groups(pairStops, bounds, 16.0, bearings)
            val optG = StopMapClustering.groups(pairStops, bounds, 16.0, bearings)

            assertEquals("At lat $lat, 94.5m stops must cluster identically", refG.size, optG.size)
            assertEquals(1, optG.size)
            assertEquals(2, optG[0].size)

            // Test two stops 96.0m apart (must NOT cluster)
            val deltaLat96m = 96.0 / 111195.0
            val s3 = TransitStop("high_lat_3", "Polar Hub", GeoPoint(lat + deltaLat96m, 10.0), emptyList(), "volan", providerIds = listOf("volan"))
            val pairStopsFar = listOf(s1, s3)
            val bearingsFar = mapOf(s1.id to listOf(0.0), s3.id to listOf(0.0))
            val refGFar = StopMapClusteringReference.groups(pairStopsFar, bounds, 16.0, bearingsFar)
            val optGFar = StopMapClustering.groups(pairStopsFar, bounds, 16.0, bearingsFar)

            assertEquals("At lat $lat, 96.0m stops must NOT cluster", refGFar.size, optGFar.size)
            assertEquals(2, optGFar.size)
        }
    }

    // --- BENCHMARK ---

    private fun benchmarkSet(testStops: List<TransitStop>, label: String) {
        val bounds = MapBounds(
            testStops.minOf { it.position.lon },
            testStops.minOf { it.position.lat },
            testStops.maxOf { it.position.lon },
            testStops.maxOf { it.position.lat }
        )

        // Warm up JIT (5 iterations each)
        for (w in 1..5) {
            val gRef = StopMapClusteringReference.groups(testStops, bounds, 16.0, realBearings)
            StopMarkerLayoutReference.offsets(gRef.map(::stationAnchor))
            val gOpt = StopMapClustering.groups(testStops, bounds, 16.0, realBearings)
            StopMarkerLayout.offsets(gOpt.map(::stationAnchor))
        }

        val runs = 7
        val refClusteringNanos = mutableListOf<Long>()
        val refLayoutNanos = mutableListOf<Long>()
        val refTotalNanos = mutableListOf<Long>()

        for (run in 1..runs) {
            val t0 = System.nanoTime()
            val groups = StopMapClusteringReference.groups(testStops, bounds, 16.0, realBearings)
            val t1 = System.nanoTime()
            val anchors = groups.map(::stationAnchor)
            StopMarkerLayoutReference.offsets(anchors)
            val t2 = System.nanoTime()

            val cNanos = t1 - t0
            val lNanos = t2 - t1
            refClusteringNanos.add(cNanos)
            refLayoutNanos.add(lNanos)
            refTotalNanos.add(cNanos + lNanos)
        }

        val optClusteringNanos = mutableListOf<Long>()
        val optLayoutNanos = mutableListOf<Long>()
        val optTotalNanos = mutableListOf<Long>()

        for (run in 1..runs) {
            val t0 = System.nanoTime()
            val groups = StopMapClustering.groups(testStops, bounds, 16.0, realBearings)
            val t1 = System.nanoTime()
            val anchors = groups.map(::stationAnchor)
            StopMarkerLayout.offsets(anchors)
            val t2 = System.nanoTime()

            val cNanos = t1 - t0
            val lNanos = t2 - t1
            optClusteringNanos.add(cNanos)
            optLayoutNanos.add(lNanos)
            optTotalNanos.add(cNanos + lNanos)
        }

        refClusteringNanos.sort()
        refLayoutNanos.sort()
        refTotalNanos.sort()
        optClusteringNanos.sort()
        optLayoutNanos.sort()
        optTotalNanos.sort()

        val mid = runs / 2
        val medianRefClusteringNs = refClusteringNanos[mid]
        val medianRefLayoutNs = refLayoutNanos[mid]
        val medianRefTotalNs = refTotalNanos[mid]

        val medianOptClusteringNs = optClusteringNanos[mid]
        val medianOptLayoutNs = optLayoutNanos[mid]
        val medianOptTotalNs = optTotalNanos[mid]

        // High resolution nanoseconds without truncation
        val speedupClustering = medianRefClusteringNs.toDouble() / medianOptClusteringNs.toDouble()
        val speedupLayout = medianRefLayoutNs.toDouble() / medianOptLayoutNs.toDouble()
        val speedupTotal = medianRefTotalNs.toDouble() / medianOptTotalNs.toDouble()

        val refCMs = medianRefClusteringNs / 1_000_000.0
        val optCMs = medianOptClusteringNs / 1_000_000.0
        val refLMs = medianRefLayoutNs / 1_000_000.0
        val optLMs = medianOptLayoutNs / 1_000_000.0
        val refTMs = medianRefTotalNs / 1_000_000.0
        val optTMs = medianOptTotalNs / 1_000_000.0

        println("=== [HOST UNIT BENCHMARK RESULTS: $label ($runs runs median)] ===")
        println("Dataset: ${testStops.size} stops")
        println("Clustering (Before / After / Speedup): ${"%.2f".format(refCMs)} ms / ${"%.2f".format(optCMs)} ms / ${"%.2f".format(speedupClustering)}x")
        println("Layout     (Before / After / Speedup): ${"%.2f".format(refLMs)} ms / ${"%.2f".format(optLMs)} ms / ${"%.2f".format(speedupLayout)}x")
        println("Total      (Before / After / Speedup): ${"%.2f".format(refTMs)} ms / ${"%.2f".format(optTMs)} ms / ${"%.2f".format(speedupTotal)}x")
        println("Details Before ms: Clustering=${refClusteringNanos.map { "%.2f".format(it / 1e6) }}, Layout=${refLayoutNanos.map { "%.2f".format(it / 1e6) }}, Total=${refTotalNanos.map { "%.2f".format(it / 1e6) }}")
        println("Details After  ms: Clustering=${optClusteringNanos.map { "%.2f".format(it / 1e6) }}, Layout=${optLayoutNanos.map { "%.2f".format(it / 1e6) }}, Total=${optTotalNanos.map { "%.2f".format(it / 1e6) }}")
        println("================================================================")
    }

    @Test
    fun runCheckpointBenchmark() {
        if (realStops.isNotEmpty()) {
            benchmarkSet(realStops.take(3000), "3000 REAL stops (Budapest core)")
            benchmarkSet(realStops, "${realStops.size} REAL stops (Budapest metro area)")
        }
        if (nationalStops.isNotEmpty()) {
            benchmarkSet(nationalStops, "${nationalStops.size} REAL stops (Full national pack)")
        } else if (realStops.isEmpty()) {
            val (synthetic, _) = generateSyntheticMultiOperatorStops(count = 2500)
            benchmarkSet(synthetic, "${synthetic.size} SYNTHETIC dense stops")
        }
    }
}
