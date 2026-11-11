package hu.app.bmenetrend.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class BkkWorkerParserTest {

    private val now = Instant.parse("2026-11-10T12:00:00Z")

    @Test
    fun appStopId_mapsCorrectlyAcrossAllNamespaces() {
        // Direct EMMA rail stop
        assertEquals("emma:1:005510009", BkkWorkerParser.appStopId("emma:1:005510009"))
        assertEquals("emma:1:005510009_1", BkkWorkerParser.appStopId("emma:1:005510009_1"))

        // BKK rail stop format BKK_00ddddddd[_n]
        assertEquals("emma:1:005510009", BkkWorkerParser.appStopId("BKK_005510009"))
        assertEquals("emma:1:005510009_2", BkkWorkerParser.appStopId("BKK_005510009_2"))

        // Standard BKK stop format BKK_X
        assertEquals("emma:BKK:F01020", BkkWorkerParser.appStopId("BKK_F01020"))
        assertEquals("emma:BKK:00123", BkkWorkerParser.appStopId("BKK_00123"))

        // Invalid or foreign stops
        assertNull(BkkWorkerParser.appStopId(null))
        assertNull(BkkWorkerParser.appStopId(""))
        assertNull(BkkWorkerParser.appStopId("volan_12345"))
        assertNull(BkkWorkerParser.appStopId("BKK_"))
        assertEquals("emma:BKK:1", BkkWorkerParser.appStopId("BKK_1"))
        assertNull(BkkWorkerParser.appStopId("other:123"))
    }

    @Test
    fun workerStopId_filtersOnlyBkkPlatforms() {
        val bkkStop = TransitStop("emma:BKK:F01020", "Deák Ferenc tér", GeoPoint(47.497, 19.054), emptyList(), "bkk")
        assertEquals("BKK_F01020", BkkWorkerParser.workerStopId(bkkStop))

        // Railway MAV stop code maps to BKK stop format BKK_00ddddddd[_n]
        val railStop = TransitStop("emma:1:005510009", "Kelenföld", GeoPoint(47.464, 19.021), emptyList(), "mav")
        assertEquals("BKK_005510009", BkkWorkerParser.workerStopId(railStop))

        val railPlatform = TransitStop("emma:1:005510017_0", "Keleti 1. vágány", GeoPoint(47.500, 19.083), emptyList(), "mav")
        assertEquals("BKK_005510017_0", BkkWorkerParser.workerStopId(railPlatform))

        // HÉV / Volán without proven shared code must NOT invent IDs
        val hevStop = TransitStop("emma:BKK:H0001", "Batthyány tér", GeoPoint(47.506, 19.039), emptyList(), "hev")
        assertNull(BkkWorkerParser.workerStopId(hevStop))

        val volanStop = TransitStop("volan:12345", "Volán", GeoPoint(47.5, 19.0), emptyList(), "volan")
        assertNull(BkkWorkerParser.workerStopId(volanStop))

        val shortStop = TransitStop("emma:BKK:", "Short", GeoPoint(47.5, 19.0), emptyList(), "bkk")
        assertNull(BkkWorkerParser.workerStopId(shortStop))
    }

    @Test
    fun tripId_mappingBetweenAppAndWorker() {
        assertEquals("1:00123", BkkWorkerParser.appTripId("emma:1:00123"))
        assertEquals("BKK:C1234", BkkWorkerParser.appTripId("BKK_C1234"))
        assertNull(BkkWorkerParser.appTripId(null))
        assertNull(BkkWorkerParser.appTripId("BKK_"))
        assertEquals("BKK:1", BkkWorkerParser.appTripId("BKK_1"))
        assertNull(BkkWorkerParser.appTripId("foreign:123"))

        assertEquals("BKK_C1234", BkkWorkerParser.workerTripId("BKK:C1234"))
        assertNull(BkkWorkerParser.workerTripId("1:00123"))
        assertNull(BkkWorkerParser.workerTripId(null))
        assertNull(BkkWorkerParser.workerTripId("BKK:"))
    }

    @Test
    fun sharesCatalogueIdentity_preventsDuplicatedHevAndRailStations() {
        val bkkBus = TransitStop("emma:BKK:F01020", "Bus Stop", GeoPoint(47.5, 19.0), emptyList(), "bkk", transportMode = "BUS")
        assertTrue(BkkWorkerParser.sharesCatalogueIdentity(bkkBus))

        val bkkMetro = TransitStop("emma:BKK:M01020", "Metro Stop", GeoPoint(47.5, 19.0), emptyList(), "bkk", transportMode = "SUBWAY")
        assertTrue(BkkWorkerParser.sharesCatalogueIdentity(bkkMetro))

        // BKK HÉV stop must NOT share catalogue identity (preventing duplication with EMMA HÉV stations)
        val bkkHev = TransitStop("emma:BKK:H01020", "HÉV Stop", GeoPoint(47.5, 19.0), emptyList(), "hev", transportMode = "SUBURBAN_RAILWAY")
        assertFalse(BkkWorkerParser.sharesCatalogueIdentity(bkkHev))

        val bkkHevAsBkk = TransitStop("emma:BKK:H01020", "HÉV Stop", GeoPoint(47.5, 19.0), emptyList(), "bkk", transportMode = "SUBURBAN_RAILWAY")
        assertFalse(BkkWorkerParser.sharesCatalogueIdentity(bkkHevAsBkk))

        // EMMA rail stop
        val emmaRail = TransitStop("emma:1:005510009", "Railway", GeoPoint(47.5, 19.0), emptyList(), "mav", transportMode = "RAIL")
        assertTrue(BkkWorkerParser.sharesCatalogueIdentity(emmaRail))
    }

    @Test
    fun route_parsesBkkHevAndRailRoutes() {
        val busJson = JSONObject().put("id", "BKK_B0070").put("type", "BUS").put("shortName", "7").put("description", "Albertfalva - Újpalota").put("color", "009EE3")
        val busRoute = BkkWorkerParser.route(busJson)
        assertNotNull(busRoute)
        assertEquals("bkk:BKK:B0070", busRoute!!.id)
        assertEquals("7", busRoute.number)
        assertEquals("bkk", busRoute.providerId)
        assertEquals("BUS", busRoute.transportMode)
        assertEquals("009EE3", busRoute.color)

        val hevJson = JSONObject().put("id", "BKK_H0050").put("type", "SUBURBAN_RAILWAY").put("shortName", "H5").put("description", "Szentendrei HÉV")
        val hevRoute = BkkWorkerParser.route(hevJson)
        assertNotNull(hevRoute)
        assertEquals("hev:BKK:H0050", hevRoute!!.id)
        assertEquals("H5", hevRoute.number)
        assertEquals("hev", hevRoute.providerId)
        assertEquals("SUBURBAN_RAILWAY", hevRoute.transportMode)
        assertNotNull(hevRoute.color) // HevSupport assigns color for H5

        val railJson = JSONObject().put("id", "BKK_TRAIN1").put("type", "RAIL").put("shortName", "S10").put("description", "Győri személy")
        val railRoute = BkkWorkerParser.route(railJson)
        assertNotNull(railRoute)
        assertEquals("mav:BKK:TRAIN1", railRoute!!.id)
        assertEquals("mav", railRoute.providerId)
        assertEquals("RAIL", railRoute.transportMode)

        val emmaRailJson = JSONObject().put("id", "emma:1:TRAIN1").put("type", "RAIL").put("shortName", "IC 123").put("description", "InterCity")
        val emmaRailRoute = BkkWorkerParser.route(emmaRailJson)
        assertNotNull(emmaRailRoute)
        assertEquals("mav:1:TRAIN1", emmaRailRoute!!.id)
        assertEquals("mav", emmaRailRoute.providerId)
    }

    @Test
    fun stop_filtersLocationTypeAndMapsParentStation() {
        val routes = mapOf("BKK_B0070" to TransitRoute("bkk:BKK:B0070", "7", "Line 7", emptyList(), "bkk", transportMode = "BUS"))

        // locationType != 0 (e.g. station entrance or parent station row) must be skipped
        val entrance = JSONObject().put("id", "BKK_F01020").put("locationType", 1).put("name", "Entrance").put("lat", 47.49).put("lon", 19.05)
        assertNull(BkkWorkerParser.stop(entrance, routes))

        // Out of Hungary bounds
        val outOfBounds = JSONObject().put("id", "BKK_F01020").put("locationType", 0).put("name", "Far").put("lat", 40.0).put("lon", 10.0)
        assertNull(BkkWorkerParser.stop(outOfBounds, routes))

        // Normal BKK stop
        val normalStop = JSONObject().put("id", "BKK_F01020").put("locationType", 0).put("name", "Deák tér")
            .put("lat", 47.497).put("lon", 19.054).put("type", "BUS").put("routeIds", JSONArray().put("BKK_B0070"))
            .put("parentStationId", "BKK_CS01020").put("platformCode", "1")
        val parsedStop = BkkWorkerParser.stop(normalStop, routes)
        assertNotNull(parsedStop)
        assertEquals("emma:BKK:F01020", parsedStop!!.id)
        assertEquals("Deák tér", parsedStop.name)
        assertEquals("bkk", parsedStop.providerId)
        assertEquals(listOf("bkk:BKK:B0070"), parsedStop.routeIds)
        assertEquals("emma:BKK:CS01020", parsedStop.parentStationId)

        // Railway stop parentStationId mapping
        val railStop = JSONObject().put("id", "BKK_005510009_1").put("locationType", 0).put("name", "Kelenföld 1. vágány")
            .put("lat", 47.464).put("lon", 19.021).put("type", "RAIL")
            .put("parentStationId", "BKK_005510009")
        val parsedRailStop = BkkWorkerParser.stop(railStop, routes)
        assertNotNull(parsedRailStop)
        assertEquals("emma:1:005510009_1", parsedRailStop!!.id)
        assertEquals("emma:1:005510009", parsedRailStop.parentStationId)
    }

    @Test
    fun vehicles_parsesTimestampsInSecondsAndCoupledNumbers() {
        val envelope = JSONObject().put("status", "OK").put("data", JSONObject()
            .put("references", JSONObject().put("routes", JSONObject().put("BKK_B0070", JSONObject().put("id", "BKK_B0070").put("type", "BUS").put("shortName", "7"))))
            .put("list", JSONArray()
                // BKK bus: lastUpdateTime is seconds (e.g. now.epochSecond - 10)
                .put(JSONObject()
                    .put("vehicleId", "BKK_1234")
                    .put("routeId", "BKK_B0070")
                    .put("vehicleRouteType", "BUS")
                    .put("lastUpdateTime", now.epochSecond - 10)
                    .put("location", JSONObject().put("lat", 47.49).put("lon", 19.05))
                    .put("bearing", 90.0)
                    .put("speedKmh", 35.0)
                    .put("licensePlate", "ABC-123")
                    .put("label", "Albertfalva"))
                // BKK train with coupled components (e.g. 415-020-1)
                .put(JSONObject()
                    .put("vehicleId", "BKK_415020-415021")
                    .put("vehicleRouteType", "RAIL")
                    .put("lastUpdateTime", now.epochSecond - 5)
                    .put("location", JSONObject().put("lat", 47.47).put("lon", 19.03))
                    .put("trainNumber", "2814")
                    .put("trainName", "Z30"))
            )
        )

        val vehicles = BkkWorkerParser.vehicles(envelope.toString(), now)
        assertEquals(2, vehicles.size)

        val bus = vehicles[0]
        assertEquals("emma:BKK:1234", bus.vehicle.id)
        assertEquals("bkk", bus.vehicle.providerId)
        assertEquals(now.minusSeconds(10), bus.vehicle.observedAt)
        assertEquals(90.0, bus.vehicle.heading!!, 1e-4)
        assertEquals(35.0, bus.vehicle.speedKmh!!, 1e-4)
        assertTrue(bus.numbers.contains("1234"))

        val train = vehicles[1]
        assertEquals("emma:1:415020", train.vehicle.id)
        assertEquals("mav", train.vehicle.providerId)
        assertEquals("2814", train.vehicle.trainNumber)
        // Coupled vehicle numbers set contains both components
        assertTrue(train.numbers.contains("415020"))
        assertTrue(train.numbers.contains("415021"))
    }

    @Test
    fun vehicles_filtersStaleOrFutureTimestamps() {
        val envelope = JSONObject().put("status", "OK").put("data", JSONObject()
            .put("references", JSONObject())
            .put("list", JSONArray()
                // Stale vehicle (> 180s old)
                .put(JSONObject()
                    .put("vehicleId", "BKK_STALE")
                    .put("vehicleRouteType", "BUS")
                    .put("lastUpdateTime", now.epochSecond - 200)
                    .put("location", JSONObject().put("lat", 47.49).put("lon", 19.05)))
                // Future vehicle (< -30s ahead)
                .put(JSONObject()
                    .put("vehicleId", "BKK_FUTURE")
                    .put("vehicleRouteType", "BUS")
                    .put("lastUpdateTime", now.epochSecond + 60)
                    .put("location", JSONObject().put("lat", 47.49).put("lon", 19.05)))
                // Valid vehicle
                .put(JSONObject()
                    .put("vehicleId", "BKK_VALID")
                    .put("vehicleRouteType", "BUS")
                    .put("lastUpdateTime", now.epochSecond - 15)
                    .put("location", JSONObject().put("lat", 47.49).put("lon", 19.05)))
            )
        )
        val vehicles = BkkWorkerParser.vehicles(envelope.toString(), now)
        assertEquals(1, vehicles.size)
        assertEquals("emma:BKK:VALID", vehicles[0].vehicle.id)
    }

    @Test
    fun boards_handlesExactPlatformCancelledAndDirectionFallback() {
        val targetStop = TransitStop("emma:BKK:F01020", "Deák tér", GeoPoint(47.497, 19.054), emptyList(), "bkk")
        val routeObj = JSONObject().put("id", "BKK_B0070").put("type", "BUS").put("shortName", "7")
        val tripObj = JSONObject().put("routeId", "BKK_B0070").put("tripHeadsign", "Albertfalva").put("directionId", "1")

        val envelope = JSONObject().put("status", "OK").put("data", JSONObject()
            .put("references", JSONObject()
                .put("routes", JSONObject().put("BKK_B0070", routeObj))
                .put("trips", JSONObject().put("BKK_TRIP1", tripObj).put("BKK_TRIP2", tripObj))
                .put("stops", JSONObject().put("BKK_F01020", JSONObject().put("platformCode", "3")))
            )
            .put("entry", JSONObject()
                .put("stopId", "BKK_F01020")
                .put("stopTimes", JSONArray()
                    // Normal departure
                    .put(JSONObject()
                        .put("stopId", "BKK_F01020")
                        .put("tripId", "BKK_TRIP1")
                        .put("departureTime", now.epochSecond + 300)
                        .put("predictedDepartureTime", now.epochSecond + 360)
                        .put("pickupAllowed", true))
                    // Cancelled departure: must be retained with CANCELED realtimeState even if pickupAllowed=false
                    .put(JSONObject()
                        .put("stopId", "BKK_F01020")
                        .put("tripId", "BKK_TRIP2")
                        .put("departureTime", now.epochSecond + 600)
                        .put("canceled", true)
                        .put("pickupAllowed", false))
                    // Terminus arrival-only (no departureTime): must be skipped
                    .put(JSONObject()
                        .put("stopId", "BKK_F01020")
                        .put("tripId", "BKK_TRIP3")
                        .put("arrivalTime", now.epochSecond + 200)
                        .put("pickupAllowed", true))
                )
            )
        )

        val boards = BkkWorkerParser.boards(envelope.toString(), listOf(targetStop), now, windowMinutes = 30)
        val board = boards[targetStop.id]
        assertNotNull(board)
        assertEquals(2, board!!.departures.size)

        val normal = board.departures[0]
        assertEquals(ArrivalKind.LIVE, normal.kind)
        assertEquals("UPDATED", normal.realtimeState)
        assertEquals(Instant.ofEpochSecond(now.epochSecond + 300), normal.scheduledAt)
        assertEquals(Instant.ofEpochSecond(now.epochSecond + 360), normal.expectedAt)
        assertEquals("BKK:TRIP1", normal.directionId)
        assertEquals("1", normal.sourceDirectionId)

        val cancelled = board.departures[1]
        assertEquals("CANCELED", cancelled.realtimeState)
        assertEquals(ArrivalKind.SCHEDULED, cancelled.kind)
    }

    private fun encodePolylinePoint(lat: Double, lon: Double): String {
        fun encodeValue(v: Int): String {
            var n = if (v < 0) (v shl 1).inv() else v shl 1
            val sb = StringBuilder()
            while (n >= 0x20) {
                sb.append(((0x20 or (n and 0x1f)) + 63).toChar())
                n = n shr 5
            }
            sb.append((n + 63).toChar())
            return sb.toString()
        }
        return encodeValue(Math.round(lat * 1e5).toInt()) + encodeValue(Math.round(lon * 1e5).toInt())
    }

    @Test
    fun journey_decodesGeometryAndPreservesStopOrder() {
        val encoded = encodePolylinePoint(47.49791, 19.04023)
        val points = BkkWorkerParser.decodePolyline(encoded)
        assertTrue(points.isNotEmpty())
        for (pt in points) {
            assertTrue(pt.lat in 47.0..48.0)
            assertTrue(pt.lon in 19.0..20.0)
        }

        val envelope = JSONObject().put("status", "OK").put("data", JSONObject()
            .put("references", JSONObject()
                .put("routes", JSONObject().put("BKK_B0070", JSONObject().put("id", "BKK_B0070").put("type", "BUS").put("shortName", "7")))
                .put("trips", JSONObject().put("BKK_TRIP1", JSONObject().put("tripHeadsign", "Albertfalva").put("directionId", "0")))
                .put("stops", JSONObject()
                    .put("BKK_S1", JSONObject().put("id", "BKK_S1").put("name", "Stop 1").put("lat", 47.49).put("lon", 19.05))
                    .put("BKK_S2", JSONObject().put("id", "BKK_S2").put("name", "Stop 2").put("lat", 47.50).put("lon", 19.06))
                )
            )
            .put("entry", JSONObject()
                .put("tripId", "BKK_TRIP1")
                .put("polyline", JSONObject().put("points", encoded))
                .put("stopTimes", JSONArray()
                    .put(JSONObject().put("stopId", "BKK_S1").put("departureTime", now.epochSecond + 100))
                    .put(JSONObject().put("stopId", "BKK_S2").put("arrivalTime", now.epochSecond + 500).put("predictedArrivalTime", now.epochSecond + 620))
                )
            )
        )

        val journey = BkkWorkerParser.journey(envelope.toString(), "BKK:TRIP1")
        assertEquals("BKK:TRIP1", journey.tripId)
        assertEquals("7", journey.routeNumber)
        assertEquals("0", journey.directionId)
        assertEquals(2, journey.stops.size)
        assertEquals("emma:BKK:S1", journey.stops[0].id)
        assertEquals("emma:BKK:S2", journey.stops[1].id)
        assertEquals(2, journey.stopTimes.size)
        // First stop delay is 0 min
        assertEquals(0, journey.stopTimes[0]?.delayMinutes)
        // Second stop delay: (620 - 500) / 60 = 2 min
        assertEquals(2, journey.stopTimes[1]?.delayMinutes)
        assertTrue(journey.stopTimes[1]?.isLive == true)
        assertTrue(journey.geometry.isNotEmpty())
    }

    @Test
    fun plan_parsesMillisecondsTimestampsAndValidatesFeasibility() {
        val startMs = now.toEpochMilli()
        val endMs = now.plusSeconds(600).toEpochMilli()

        val envelope = JSONObject().put("status", "OK").put("data", JSONObject()
            .put("entry", JSONObject().put("plan", JSONObject().put("itineraries", JSONArray().put(JSONObject()
                .put("legs", JSONArray()
                    .put(JSONObject()
                        .put("mode", "WALK")
                        .put("startTime", startMs)
                        .put("endTime", startMs + 120_000)
                        .put("distance", 150.0)
                        .put("from", JSONObject().put("name", "Start").put("lat", 47.49).put("lon", 19.05))
                        .put("to", JSONObject().put("name", "Stop A").put("lat", 47.491).put("lon", 19.051).put("stopId", "BKK_S1")))
                    .put(JSONObject()
                        .put("mode", "BUS")
                        .put("startTime", startMs + 180_000)
                        .put("endTime", endMs)
                        .put("distance", 2500.0)
                        .put("routeId", "BKK_B0070")
                        .put("routeShortName", "7")
                        .put("routeLongName", "Line 7")
                        .put("headsign", "Albertfalva")
                        .put("from", JSONObject().put("name", "Stop A").put("lat", 47.491).put("lon", 19.051).put("stopId", "BKK_S1"))
                        .put("to", JSONObject().put("name", "Stop B").put("lat", 47.51).put("lon", 19.07).put("stopId", "BKK_S2"))
                        .put("intermediateStops", JSONArray().put(JSONObject().put("name", "Mid").put("lat", 47.50).put("lon", 19.06))))
                )
            )))))

        val itineraries = BkkWorkerParser.plan(envelope.toString(), now)
        assertEquals(1, itineraries.size)
        val itinerary = itineraries[0]
        assertEquals(2, itinerary.legs.size)
        assertTrue(itinerary.legs[0].walking)
        assertEquals(Instant.ofEpochMilli(startMs), itinerary.legs[0].start)
        assertEquals(Instant.ofEpochMilli(startMs + 120_000), itinerary.legs[0].end)

        val transit = itinerary.legs[1]
        assertFalse(transit.walking)
        assertEquals("BUS", transit.mode)
        assertEquals("7", transit.route?.number)
        assertEquals(1, transit.stops.size)
        assertEquals("Mid", transit.stops[0].name)
    }

    @Test
    fun plan_compactAndFullEnvelopesProduceIdenticalItineraries() {
        val startMs = now.toEpochMilli()
        val endMs = now.plusSeconds(900).toEpochMilli()

        // Full envelope with references, leg geometry, elevation, and pattern trips
        val fullEnvelope = JSONObject().put("status", "OK")
            .put("data", JSONObject()
                .put("references", JSONObject()
                    .put("routes", JSONObject().put("BKK_B0070", JSONObject().put("id", "BKK_B0070").put("shortName", "7")))
                    .put("trips", JSONObject().put("BKK_T1", JSONObject().put("tripHeadsign", "Albertfalva")))
                )
                .put("entry", JSONObject().put("plan", JSONObject().put("itineraries", JSONArray().put(JSONObject()
                    .put("duration", 900)
                    .put("startTime", startMs)
                    .put("endTime", endMs)
                    .put("walkTime", 120)
                    .put("transitTime", 780)
                    .put("waitingTime", 0)
                    .put("walkDistance", 150.0)
                    .put("transfers", 0)
                    .put("legs", JSONArray()
                        .put(JSONObject()
                            .put("mode", "WALK")
                            .put("startTime", startMs)
                            .put("endTime", startMs + 120_000)
                            .put("distance", 150.0)
                            .put("from", JSONObject().put("name", "Origin").put("lat", 47.49).put("lon", 19.05))
                            .put("to", JSONObject().put("name", "Stop A").put("lat", 47.491).put("lon", 19.051).put("stopId", "BKK_S1"))
                            .put("legGeometry", JSONObject().put("points", "abcd_efgh"))
                            .put("elevation", JSONArray().put(10).put(20)))
                        .put(JSONObject()
                            .put("mode", "BUS")
                            .put("startTime", startMs + 180_000)
                            .put("endTime", endMs)
                            .put("distance", 3200.0)
                            .put("routeId", "BKK_B0070")
                            .put("routeShortName", "7")
                            .put("routeLongName", "Line 7")
                            .put("routeColor", "009EE3")
                            .put("routeTextColor", "FFFFFF")
                            .put("headsign", "Albertfalva")
                            .put("realTime", true)
                            .put("departureDelay", 60)
                            .put("arrivalDelay", 60)
                            .put("tripId", "BKK_T1")
                            .put("from", JSONObject().put("name", "Stop A").put("lat", 47.491).put("lon", 19.051).put("stopId", "BKK_S1"))
                            .put("to", JSONObject().put("name", "Stop B").put("lat", 47.52).put("lon", 19.07).put("stopId", "BKK_S2"))
                            .put("intermediateStops", JSONArray().put(JSONObject().put("name", "Mid").put("lat", 47.505).put("lon", 19.06).put("stopId", "BKK_SMID")))
                            .put("legGeometry", JSONObject().put("points", "ijkl_mnop"))
                            .put("steps", JSONArray().put(JSONObject().put("distance", 100)))
                            .put("patternTripIds", JSONArray().put("BKK_T1").put("BKK_T2"))
                        )
                    )
                ))))
            )

        // Compact envelope stripped as by compactPlan in Worker:
        // empty references, no legGeometry, no elevation, no steps, no patternTripIds
        val compactEnvelope = JSONObject().put("status", "OK")
            .put("data", JSONObject()
                .put("references", JSONObject())
                .put("entry", JSONObject().put("plan", JSONObject().put("itineraries", JSONArray().put(JSONObject()
                    .put("duration", 900)
                    .put("startTime", startMs)
                    .put("endTime", endMs)
                    .put("walkTime", 120)
                    .put("transitTime", 780)
                    .put("waitingTime", 0)
                    .put("walkDistance", 150.0)
                    .put("transfers", 0)
                    .put("legs", JSONArray()
                        .put(JSONObject()
                            .put("mode", "WALK")
                            .put("startTime", startMs)
                            .put("endTime", startMs + 120_000)
                            .put("distance", 150.0)
                            .put("from", JSONObject().put("name", "Origin").put("lat", 47.49).put("lon", 19.05))
                            .put("to", JSONObject().put("name", "Stop A").put("lat", 47.491).put("lon", 19.051).put("stopId", "BKK_S1"))
                            .put("intermediateStops", JSONArray()))
                        .put(JSONObject()
                            .put("mode", "BUS")
                            .put("startTime", startMs + 180_000)
                            .put("endTime", endMs)
                            .put("distance", 3200.0)
                            .put("routeId", "BKK_B0070")
                            .put("routeShortName", "7")
                            .put("routeLongName", "Line 7")
                            .put("routeColor", "009EE3")
                            .put("routeTextColor", "FFFFFF")
                            .put("headsign", "Albertfalva")
                            .put("realTime", true)
                            .put("departureDelay", 60)
                            .put("arrivalDelay", 60)
                            .put("tripId", "BKK_T1")
                            .put("from", JSONObject().put("name", "Stop A").put("lat", 47.491).put("lon", 19.051).put("stopId", "BKK_S1"))
                            .put("to", JSONObject().put("name", "Stop B").put("lat", 47.52).put("lon", 19.07).put("stopId", "BKK_S2"))
                            .put("intermediateStops", JSONArray().put(JSONObject().put("name", "Mid").put("lat", 47.505).put("lon", 19.06).put("stopId", "BKK_SMID")))
                        )
                    )
                ))))
            )

        val fullItineraries = BkkWorkerParser.plan(fullEnvelope.toString(), now)
        val compactItineraries = BkkWorkerParser.plan(compactEnvelope.toString(), now)

        assertEquals("Full and compact envelopes must produce identical itinerary counts", fullItineraries.size, compactItineraries.size)
        val full = fullItineraries[0]
        val compact = compactItineraries[0]

        assertEquals(full.start, compact.start)
        assertEquals(full.end, compact.end)
        assertEquals(full.legs.size, compact.legs.size)

        for (i in full.legs.indices) {
            val fLeg = full.legs[i]
            val cLeg = compact.legs[i]
            assertEquals(fLeg.mode, cLeg.mode)
            assertEquals(fLeg.start, cLeg.start)
            assertEquals(fLeg.end, cLeg.end)
            assertEquals(fLeg.distance, cLeg.distance, 1e-4)
            assertEquals(fLeg.from, cLeg.from)
            assertEquals(fLeg.to, cLeg.to)
            assertEquals(fLeg.route?.id, cLeg.route?.id)
            assertEquals(fLeg.route?.number, cLeg.route?.number)
            assertEquals(fLeg.headsign, cLeg.headsign)
            assertEquals(fLeg.realtime, cLeg.realtime)
            assertEquals(fLeg.stops.size, cLeg.stops.size)
            for (j in fLeg.stops.indices) {
                assertEquals(fLeg.stops[j], cLeg.stops[j])
            }
            assertEquals(fLeg.routerJourney, cLeg.routerJourney)
            assertEquals(fLeg.routerPrevious, cLeg.routerPrevious)
            assertEquals(fLeg.source, cLeg.source)
        }
        assertEquals(full.sources, compact.sources)
    }

    @Test
    fun workerRouteId_mappingAndRejection() {
        // Known BKK routes
        assertEquals("BKK_M2", BkkWorkerParser.workerRouteId("bkk:BKK:M2"))
        assertEquals("BKK_5200", BkkWorkerParser.workerRouteId("bkk:BKK:5200"))
        assertEquals("BKK_B0070", BkkWorkerParser.workerRouteId("bkk:BKK:B0070"))

        // Volán routes: raw explicit and agglomeration mapping
        assertEquals("volan_735", BkkWorkerParser.workerRouteId("volan:volan_735"))
        assertEquals("volan_735", BkkWorkerParser.workerRouteId("volan:hkir:hkir_V-R-200-735"))
        // Unrelated national V-O routes are not guessed
        assertNull(BkkWorkerParser.workerRouteId("volan:hkir:hkir_V-O-100-123"))

        // Known HÉV routes
        assertEquals("BKK_H5", BkkWorkerParser.workerRouteId("hev:BKK:H5"))
        assertEquals("BKK_H0050", BkkWorkerParser.workerRouteId("hev:BKK:H0050"))

        // Known MÁV shared railway routes (mav:BKK:*)
        assertEquals("BKK_S70", BkkWorkerParser.workerRouteId("mav:BKK:S70"))
        assertEquals("BKK_Z30", BkkWorkerParser.workerRouteId("mav:BKK:Z30"))

        // True EMMA railway routes must remain null (no guessed BKK route)
        assertNull(BkkWorkerParser.workerRouteId("mav:1:S70"))
        assertNull(BkkWorkerParser.workerRouteId("mav:1:0055100"))
        assertNull(BkkWorkerParser.workerRouteId("mav:1:rail"))
        assertNull(BkkWorkerParser.workerRouteId("mav:100"))

        // Foreign, empty or invalid routes
        assertNull(BkkWorkerParser.workerRouteId("volan:123"))
        assertNull(BkkWorkerParser.workerRouteId("volan:hkir:760"))
        assertNull(BkkWorkerParser.workerRouteId("bkk:other"))
        assertNull(BkkWorkerParser.workerRouteId("bkk:BKK:"))
        assertNull(BkkWorkerParser.workerRouteId("mav:BKK:"))
        assertNull(BkkWorkerParser.workerRouteId("hev:BKK:"))
        assertNull(BkkWorkerParser.workerRouteId("bkk:BKK:invalid char"))
        assertNull(BkkWorkerParser.workerRouteId(""))
    }

    @Test
    fun routeDetails_parsesM2VariantSchemaWithMultidirectionAndOrderedStops() {
        val poly = encodePolylinePoint(47.500, 19.025) + encodePolylinePoint(47.505, 19.136)
        val json = JSONObject().put("status", "OK").put("data", JSONObject()
            .put("references", JSONObject()
                .put("routes", JSONObject().put("BKK_5200", JSONObject()
                    .put("id", "BKK_5200")
                    .put("type", "SUBWAY")
                    .put("shortName", "M2")
                    .put("description", "Déli pályaudvar / Örs vezér tere")
                    .put("color", "E41F18")
                ))
                .put("stops", JSONObject()
                    .put("BKK_F00950", JSONObject().put("id", "BKK_F00950").put("name", "Déli pályaudvar").put("lat", 47.500).put("lon", 19.025).put("type", "SUBWAY").put("locationType", 0))
                    .put("BKK_F00951", JSONObject().put("id", "BKK_F00951").put("name", "Széll Kálmán tér").put("lat", 47.507).put("lon", 19.025).put("type", "SUBWAY").put("locationType", 0))
                    .put("BKK_F00952", JSONObject().put("id", "BKK_F00952").put("name", "Batthyány tér").put("lat", 47.506).put("lon", 19.039).put("type", "SUBWAY").put("locationType", 0))
                    .put("BKK_F00960", JSONObject().put("id", "BKK_F00960").put("name", "Örs vezér tere").put("lat", 47.505).put("lon", 19.136).put("type", "SUBWAY").put("locationType", 0))
                )
            )
            .put("entry", JSONObject()
                .put("id", "BKK_5200")
                .put("type", "SUBWAY")
                .put("shortName", "M2")
                .put("variants", JSONArray()
                    .put(JSONObject()
                        .put("patternId", "BKK_5200_0")
                        .put("headsign", "Örs vezér tere")
                        .put("direction", "[multidirection]")
                        .put("stopIds", JSONArray().put("BKK_F00950").put("BKK_F00951").put("BKK_F00952").put("BKK_F00960"))
                        .put("polyline", JSONObject().put("points", poly))
                    )
                    .put(JSONObject()
                        .put("patternId", "BKK_5200_1")
                        .put("headsign", "Déli pályaudvar")
                        .put("direction", "[multidirection]")
                        .put("stopIds", JSONArray().put("BKK_F00960").put("BKK_F00952").put("BKK_F00951").put("BKK_F00950"))
                        .put("polyline", JSONObject().put("points", poly))
                    )
                )
            )
        )

        val details = BkkWorkerParser.routeDetails(json.toString(), "bkk:BKK:5200")
        assertEquals(DataSource.BKK, details.source)
        assertEquals("bkk:BKK:5200", details.route.id)
        assertEquals("M2", details.route.number)
        assertEquals("bkk", details.route.providerId)
        assertEquals("SUBWAY", details.route.transportMode)
        assertEquals(2, details.patterns.size)

        val outPattern = details.patterns[0]
        assertEquals("bkk:BKK_5200_0", outPattern.id)
        assertEquals("Örs vezér tere", outPattern.headsign)
        assertNull("BKK metro '[multidirection]' must yield null directionId", outPattern.directionId)
        assertTrue("BKK patterns have empty EMMA codes", outPattern.codes.isEmpty())
        assertEquals(listOf("emma:BKK:F00950", "emma:BKK:F00951", "emma:BKK:F00952", "emma:BKK:F00960"), outPattern.stops.map { it.id })
        assertTrue("Stops must include the line in routeIds", outPattern.stops.all { "bkk:BKK:5200" in it.routeIds })
        assertTrue("Geometry must be decoded", outPattern.geometry.isNotEmpty())

        val inPattern = details.patterns[1]
        assertEquals("bkk:BKK_5200_1", inPattern.id)
        assertEquals("Déli pályaudvar", inPattern.headsign)
        assertNull(inPattern.directionId)
        assertEquals(listOf("emma:BKK:F00960", "emma:BKK:F00952", "emma:BKK:F00951", "emma:BKK:F00950"), inPattern.stops.map { it.id })
    }

    @Test
    fun routeDetails_rejectsMalformedOrUnmappedVariantsAndDoesNotDrawFakePaths() {
        val poly = encodePolylinePoint(47.500, 19.025)
        val json = JSONObject().put("status", "OK").put("data", JSONObject()
            .put("references", JSONObject()
                .put("routes", JSONObject().put("BKK_5200", JSONObject().put("id", "BKK_5200").put("type", "SUBWAY").put("shortName", "M2")))
                .put("stops", JSONObject()
                    .put("BKK_F00950", JSONObject().put("id", "BKK_F00950").put("name", "Déli pályaudvar").put("lat", 47.500).put("lon", 19.025).put("type", "SUBWAY").put("locationType", 0))
                    .put("BKK_F00951", JSONObject().put("id", "BKK_F00951").put("name", "Széll Kálmán tér").put("lat", 47.507).put("lon", 19.025).put("type", "SUBWAY").put("locationType", 0))
                )
            )
            .put("entry", JSONObject()
                .put("id", "BKK_5200")
                .put("type", "SUBWAY")
                .put("shortName", "M2")
                .put("variants", JSONArray()
                    // 1. Unmapped stopId (BKK_UNKNOWN) -> must be rejected/skipped to avoid fake paths
                    .put(JSONObject()
                        .put("patternId", "BAD_1")
                        .put("headsign", "Unmapped Stop")
                        .put("stopIds", JSONArray().put("BKK_F00950").put("BKK_UNKNOWN"))
                    )
                    // 2. Fewer than 2 stops -> must be rejected/skipped
                    .put(JSONObject()
                        .put("patternId", "BAD_2")
                        .put("headsign", "Single Stop")
                        .put("stopIds", JSONArray().put("BKK_F00950"))
                    )
                    // 3. Valid variant
                    .put(JSONObject()
                        .put("patternId", "GOOD_1")
                        .put("headsign", "Valid Variant")
                        .put("direction", "0")
                        .put("stopIds", JSONArray().put("BKK_F00950").put("BKK_F00951"))
                        .put("polyline", JSONObject().put("points", poly))
                    )
                )
            )
        )

        val details = BkkWorkerParser.routeDetails(json.toString(), "bkk:BKK:5200")
        assertEquals("Only the valid variant should be admitted", 1, details.patterns.size)
        assertEquals("bkk:GOOD_1", details.patterns[0].id)
        assertEquals("0", details.patterns[0].directionId)
        assertEquals(2, details.patterns[0].stops.size)

        // All invalid variants -> throws IllegalArgumentException
        val allBadJson = JSONObject().put("status", "OK").put("data", JSONObject()
            .put("references", json.getJSONObject("data").getJSONObject("references"))
            .put("entry", JSONObject()
                .put("id", "BKK_5200")
                .put("type", "SUBWAY")
                .put("variants", JSONArray().put(JSONObject().put("stopIds", JSONArray().put("BKK_UNKNOWN"))))
            )
        )
        assertThrows(IllegalArgumentException::class.java) {
            BkkWorkerParser.routeDetails(allBadJson.toString(), "bkk:BKK:5200")
        }
    }

    @Test
    fun routeDetails_mavKnownBkkRouteVariantKeepsMavIdentityAndRailType() {
        val poly = encodePolylinePoint(47.500, 19.083) + encodePolylinePoint(47.780, 19.130)
        val json = JSONObject().put("status", "OK").put("data", JSONObject()
            .put("references", JSONObject()
                .put("routes", JSONObject().put("BKK_S70", JSONObject()
                    .put("id", "BKK_S70")
                    .put("type", "RAIL")
                    .put("shortName", "S70")
                    .put("description", "Budapest-Nyugati - Vác - Szob")
                ))
                .put("stops", JSONObject()
                    .put("BKK_005510017", JSONObject().put("id", "BKK_005510017").put("name", "Budapest-Nyugati").put("lat", 47.510).put("lon", 19.057).put("type", "RAIL").put("locationType", 0))
                    .put("BKK_005510363", JSONObject().put("id", "BKK_005510363").put("name", "Vác").put("lat", 47.780).put("lon", 19.130).put("type", "RAIL").put("locationType", 0))
                )
            )
            .put("entry", JSONObject()
                .put("id", "BKK_S70")
                .put("type", "RAIL")
                .put("shortName", "S70")
                .put("description", "Budapest-Nyugati - Vác - Szob")
                .put("variants", JSONArray().put(JSONObject()
                    .put("patternId", "BKK_S70_0")
                    .put("headsign", "Szob")
                    .put("direction", "1")
                    .put("stopIds", JSONArray().put("BKK_005510017").put("BKK_005510363"))
                    .put("polyline", JSONObject().put("points", poly))
                ))
            )
        )

        val details = BkkWorkerParser.routeDetails(json.toString(), "mav:BKK:S70")
        assertEquals(DataSource.BKK, details.source)
        assertEquals("mav:BKK:S70", details.route.id)
        assertEquals("mav", details.route.providerId)
        assertEquals("RAIL", details.route.transportMode)
        assertEquals("S70", details.route.number)
        assertEquals(1, details.patterns.size)
        assertEquals("1", details.patterns[0].directionId)
        assertEquals("Vác", details.patterns[0].headsign)
        assertEquals(listOf("emma:1:005510017", "emma:1:005510363"), details.patterns[0].stops.map { it.id })
    }

    @Test
    fun plan_realBkkOtpZeroSlackWalkPrecedingTransit_mustNotBeDroppedByFeasibility() {
        // Real BKK OpenTripPlanner responses calculate the walk leg to end exactly when the transit leg departs (0 slack).
        // TripPlanner.feasible requires Duration.between(arrived, leg.start).seconds >= BOARD_SECONDS (60s).
        // Since arrived is precedingWalk.end, arrived == leg.start -> 0 < 60s -> rejected as infeasible!
        val startMs = now.toEpochMilli()
        val walkEndMs = startMs + 120_000
        val transitEndMs = startMs + 600_000

        val envelope = JSONObject().put("status", "OK").put("data", JSONObject()
            .put("entry", JSONObject().put("plan", JSONObject().put("itineraries", JSONArray().put(JSONObject()
                .put("legs", JSONArray()
                    .put(JSONObject()
                        .put("mode", "WALK")
                        .put("startTime", startMs)
                        .put("endTime", walkEndMs)
                        .put("distance", 150.0)
                        .put("from", JSONObject().put("name", "Keleti").put("lat", 47.5001).put("lon", 19.0807))
                        .put("to", JSONObject().put("name", "Keleti M").put("lat", 47.5002).put("lon", 19.0809).put("stopId", "BKK_F01314")))
                    .put(JSONObject()
                        .put("mode", "BUS")
                        .put("startTime", walkEndMs) // Real BKK OTP output: walk.endTime == bus.startTime (0 slack)
                        .put("endTime", transitEndMs)
                        .put("distance", 2500.0)
                        .put("routeId", "BKK_B0070")
                        .put("routeShortName", "7")
                        .put("routeLongName", "Line 7")
                        .put("headsign", "Albertfalva")
                        .put("from", JSONObject().put("name", "Keleti M").put("lat", 47.5002).put("lon", 19.0809).put("stopId", "BKK_F01314"))
                        .put("to", JSONObject().put("name", "Kosztolányi").put("lat", 47.4748).put("lon", 19.0388).put("stopId", "BKK_F02183")))
                )
            )))))

        val itineraries = BkkWorkerParser.plan(envelope.toString(), now)
        assertEquals("Real BKK OTP itinerary with preceding walk ending at transit departure must not be rejected", 1, itineraries.size)
    }

    @Test
    fun volan_mappings_stopTripAndPlate() {
        // Stop bidirectional mapping: volan_773532_2 <-> emma:hkir:hkir_773532_2
        assertEquals("emma:hkir:hkir_773532_2", BkkWorkerParser.appStopId("volan_773532_2"))
        val volanStop = TransitStop("emma:hkir:hkir_773532_2", "Budaörs", GeoPoint(47.45, 18.96), emptyList(), "volan")
        assertEquals("volan_773532_2", BkkWorkerParser.workerStopId(volanStop))

        // Trip mapping: volan_* <-> volan:volan_*
        val tripRaw = "volan_2735_130_20261102_3707901625"
        val appTrip = "volan:$tripRaw"
        assertEquals(appTrip, BkkWorkerParser.appTripId(tripRaw))
        assertEquals(tripRaw, BkkWorkerParser.workerTripId(appTrip))
    }

    @Test
    fun volan_routeAndStopParsing_busAndCoachModes() {
        val coachJson = JSONObject().put("id", "volan_735").put("type", "COACH").put("shortName", "735").put("description", "Budapest - Érd")
        val coachRoute = BkkWorkerParser.route(coachJson)
        assertNotNull(coachRoute)
        assertEquals("volan:volan_735", coachRoute!!.id)
        assertEquals("735", coachRoute.number)
        assertEquals("volan", coachRoute.providerId)
        assertEquals("COACH", coachRoute.transportMode)

        val busJson = JSONObject().put("id", "volan_756").put("type", "BUS").put("shortName", "756").put("description", "Budapest - Százhalombatta")
        val busRoute = BkkWorkerParser.route(busJson)
        assertNotNull(busRoute)
        assertEquals("volan:volan_756", busRoute!!.id)
        assertEquals("756", busRoute.number)
        assertEquals("volan", busRoute.providerId)
        assertEquals("BUS", busRoute.transportMode)

        val routes = mapOf(
            "volan_735" to coachRoute,
            "volan_756" to busRoute
        )

        val stopJson = JSONObject().put("id", "volan_773532_2").put("locationType", 0).put("name", "Budaörs, benzinkút")
            .put("lat", 47.4516).put("lon", 18.9584).put("type", "BUS").put("routeIds", JSONArray().put("volan_735"))
            .put("platformCode", "2")
        val stop = BkkWorkerParser.stop(stopJson, routes)
        assertNotNull(stop)
        assertEquals("emma:hkir:hkir_773532_2", stop!!.id)
        assertEquals("Budaörs, benzinkút", stop.name)
        assertEquals("volan", stop.providerId)
        assertEquals(listOf("volan:volan_735"), stop.routeIds)
    }

    @Test
    fun volan_search_acceptsStopsRoutesAndVehicles() {
        val searchJson = JSONObject().put("status", "OK").put("data", JSONObject()
            .put("references", JSONObject()
                .put("routes", JSONObject().put("volan_735", JSONObject()
                    .put("id", "volan_735").put("type", "COACH").put("shortName", "735").put("description", "Budapest - Érd")
                ))
                .put("stops", JSONObject().put("volan_773532_2", JSONObject()
                    .put("id", "volan_773532_2").put("name", "Budaörs, benzinkút").put("lat", 47.4516).put("lon", 18.9584)
                    .put("type", "BUS").put("locationType", 0).put("routeIds", JSONArray().put("volan_735"))
                ))
                .put("vehicles", JSONObject().put("volan_SRD244", JSONObject()
                    .put("vehicleId", "volan_SRD244").put("licensePlate", "SRD-244").put("label", "SRD-244")
                    .put("location", JSONObject().put("lat", 47.452).put("lon", 18.959))
                    .put("lastUpdateTime", now.epochSecond).put("vehicleRouteType", "COACH")
                    .put("routeId", "volan_735")
                ))
            )
            .put("entry", JSONObject()
                .put("stopIds", JSONArray().put("volan_773532_2"))
                .put("routeIds", JSONArray().put("volan_735"))
                .put("vehicleIds", JSONArray().put("volan_SRD244"))
            )
        )

        val result = BkkWorkerParser.search(searchJson.toString(), now)
        assertEquals(1, result.catalogue.stops.size)
        val stop = result.catalogue.stops[0]
        assertEquals("emma:hkir:hkir_773532_2", stop.id)
        assertEquals(DataSource.BKK, result.stopSources[stop.id])

        assertEquals(1, result.catalogue.routes.size)
        assertEquals("volan:volan_735", result.catalogue.routes[0].id)
        assertEquals("COACH", result.catalogue.routes[0].transportMode)

        assertEquals(1, result.vehicles.size)
        val vehicle = result.vehicles[0].vehicle
        assertEquals("emma:hkir:hkir_SRD244", vehicle.id)
        assertEquals("volan", vehicle.providerId)
        assertEquals("SRD-244", vehicle.registration)
        assertEquals(DataSource.BKK, vehicle.positionSource)
    }

    @Test
    fun volan_boards_sharedMixedBusPlatform_preservesBothOperatorsAndExcludesCrossMode() {
        val stop = TransitStop("emma:BKK:F01020", "Megálló", GeoPoint(47.497, 19.054), emptyList(), "bkk", transportMode = "BUS")
        val boardJson = JSONObject().put("status", "OK").put("data", JSONObject()
            .put("references", JSONObject()
                .put("routes", JSONObject()
                    .put("BKK_B0070", JSONObject().put("id", "BKK_B0070").put("type", "BUS").put("shortName", "7"))
                    .put("volan_735", JSONObject().put("id", "volan_735").put("type", "COACH").put("shortName", "735"))
                    .put("BKK_S10", JSONObject().put("id", "BKK_S10").put("type", "RAIL").put("shortName", "S10"))
                )
                .put("stops", JSONObject().put("BKK_F01020", JSONObject()
                    .put("id", "BKK_F01020").put("name", "Megálló").put("lat", 47.497).put("lon", 19.054)
                    .put("type", "BUS").put("locationType", 0)
                    .put("routeIds", JSONArray().put("BKK_B0070").put("volan_735").put("BKK_S10"))
                ))
            )
            .put("entry", JSONObject()
                .put("stopId", "BKK_F01020")
                .put("stopTimes", JSONArray()
                    .put(JSONObject().put("stopId", "BKK_F01020").put("tripId", "BKK_TRIP_7").put("departureTime", now.epochSecond + 120).put("pickupAllowed", true).put("routeId", "BKK_B0070"))
                    .put(JSONObject().put("stopId", "BKK_F01020").put("tripId", "volan_TRIP_735").put("departureTime", now.epochSecond + 240).put("pickupAllowed", true).put("routeId", "volan_735"))
                    .put(JSONObject().put("stopId", "BKK_F01020").put("tripId", "BKK_TRIP_S10").put("departureTime", now.epochSecond + 360).put("pickupAllowed", true).put("routeId", "BKK_S10"))
                )
            )
        )

        val boards = BkkWorkerParser.boards(boardJson.toString(), listOf(stop), now, windowMinutes = 30)
        val board = boards[stop.id]
        assertNotNull(board)

        // Both BKK BUS 7 and Volán COACH 735 departures must be preserved; RAIL S10 must not leak
        assertEquals(2, board!!.departures.size)
        assertEquals("7", board.departures[0].routeNumber)
        assertEquals(DataSource.BKK, board.departures[0].source)
        assertEquals("735", board.departures[1].routeNumber)
        assertEquals(DataSource.BKK, board.departures[1].source)

        // Serving routes in catalogue must contain bus and coach, but exclude rail
        val routeNumbers = board.catalogue.routes.map { it.number }.toSet()
        assertTrue(routeNumbers.contains("7"))
        assertTrue(routeNumbers.contains("735"))
        assertFalse("Rail must not leak into bus platform catalogue", routeNumbers.contains("S10"))
    }
}

