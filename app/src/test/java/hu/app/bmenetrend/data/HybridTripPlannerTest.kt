package hu.app.bmenetrend.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HybridTripPlannerTest {
    private val ready = Instant.parse("2026-10-22T05:00:00Z")
    private val a = TransitStop("a","A",GeoPoint(47.40,18.8),listOf("via"))
    private val b = TransitStop("b","B",GeoPoint(47.42,18.8),listOf("via"))
    private val c = TransitStop("c","C",GeoPoint(47.50,18.8),listOf("homm"))
    private val d = TransitStop("d","D",GeoPoint(47.52,18.8),listOf("homm","kanyar"))
    private val e = TransitStop("e","E",GeoPoint(47.54,18.8),listOf("kanyar"))
    private fun TransitStop.endpoint() = PlannerEndpoint(id,name,"",position)
    private fun TransitStop.point() = PlanPoint(name,position)
    private fun route(id:String,provider:String,from:String,to:String) = TransitRoute(id,id,id,
        listOf(RouteDirection(id,to,listOf(from,to))),provider)
    private fun trip(id:String,from:String,to:String,start:Int,end:Int) = ScheduledTrip(id,id,id,
        listOf(StopTime(from,start),StopTime(to,end)),"school_term")
    private fun network() = TransitNetwork(listOf(a,b,c,d,e),listOf(route("via","viabusz","a","b"),
        route("homm","homm","c","d"),route("kanyar","kanyargo","d","e")),
        listOf(trip("via","a","b",422,430),trip("homm","c","d",453,463),trip("kanyar","d","e",466,476)),
        ready,"fixture",verifiedProviderIds = setOf("viabusz","kanyargo","homm"))
    private val catalogue = listOf(b,c).map { it.copy(id="emma:${it.id}",providerId="volan",transportMode="COACH") }
    private fun coach(at:Instant = ready.plusSeconds(20*60)) = PlanLeg("COACH",at,at.plusSeconds(10*60),9_000.0,
        b.point(),c.point(),TransitRoute("volan:760","760","Volán",emptyList(),"volan",transportMode="COACH"),"C",false,false,emptyList())

    private inner class FakeBackend : PlannerBackend {
        val calls = mutableListOf<Pair<String,String>>()
        var middle = coach()
        var fail = false
        var noWalks = false
        override suspend fun stops(bounds:MapBounds) = catalogue
        override suspend fun routes(from:PlannerEndpoint,to:PlannerEndpoint,ready:Instant,busesOnly:Boolean):List<TripItinerary> {
            calls += from.id to to.id
            if(fail) error("offline")
            return if(from.id == "b" && to.id == "c") listOf(TripItinerary("volan",middle.start,middle.end,listOf(middle))) else emptyList()
        }
        override suspend fun walking(pairs:List<Pair<PlanPoint,PlanPoint>>,ready:Instant):WalkingResult =
            if(noWalks) WalkingResult(emptyMap(),true) else WalkingResult(pairs.associate { (from,to) ->
                val meters = StopClusters.distanceMeters(from.position,to.position)
                (from.position to to.position) to WalkingLink(from,to,kotlin.math.ceil(meters/TripPlanner.WALK_SPEED).toLong(),meters)
            })
    }

    private val nextStop = b.copy(id = "next", name = "Nimród köz",
        position = b.position.copy(lat = b.position.lat + 500.0 / 111_195.0))

    private fun samePlaceNetwork(): TransitNetwork {
        val stops = listOf(a, b, nextStop)
        val line = route("via", "viabusz", a.id, nextStop.id).copy(number = "3", directions = listOf(
            RouteDirection("via", nextStop.name, stops.map { it.id })))
        return TransitNetwork(stops, listOf(line), listOf(ScheduledTrip("via", "via", "via",
            listOf(StopTime(a.id, 422), StopTime(b.id, 430), StopTime(nextStop.id, 431)), "school_term")),
            ready, "same-place fixture", verifiedProviderIds = setOf("viabusz"))
    }

    private fun missingWalkBackend(missing: Pair<GeoPoint, GeoPoint>, direct: List<TripItinerary> = emptyList()): PlannerBackend =
        object : PlannerBackend by FakeBackend() {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant,
                                        busesOnly: Boolean) = direct

            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult =
                WalkingResult(pairs.filterNot { (from, to) -> from.position to to.position == missing }
                    .associate { (from, to) ->
                        val meters = StopClusters.distanceMeters(from.position, to.position)
                        (from.position to to.position) to WalkingLink(from, to,
                            kotlin.math.ceil(meters / TripPlanner.WALK_SPEED).toLong(), meters)
                    })
        }

    @Test fun missingZeroLengthEgressAlightsAtDestinationInsteadOfRidingPastAndWalkingBack() = runTest {
        val destination = b.endpoint().copy(id = "destination", name = "Iharos utca")
        val ridePast = PlanLeg("BUS", ready.plusSeconds(120), ready.plusSeconds(660), 0.0,
            a.point(), nextStop.point(), samePlaceNetwork().routes.single(), nextStop.name,
            false, false, listOf(b.point()))
        val walkBack = WalkingLink(nextStop.point(), PlanPoint(destination.name, destination.position),
            420, 500.0).leg(ridePast.end)
        val inferior = TripItinerary("ride-past-and-walk-back", ridePast.start, walkBack.end, listOf(ridePast, walkBack))
        val backend = missingWalkBackend(b.position to destination.position, listOf(inferior))

        val result = HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
            a.endpoint(), destination, ready, ready, emptyList(), { samePlaceNetwork() }, setOf("viabusz"))

        val best = result.itineraries.first()
        assertEquals(b.position, best.legs.last { !it.walking }.to.position)
        val finalWalk = best.legs.last()
        assertTrue(finalWalk.walking)
        assertEquals(b.position, finalWalk.from.position)
        assertEquals(destination.position, finalWalk.to.position)
        assertTrue(finalWalk.distance <= 40.0)
        assertEquals(0L, java.time.Duration.between(finalWalk.start, finalWalk.end).seconds)
        assertTrue("Alighting at the destination must beat the supplied walk-back candidate", best.end < inferior.end)
        assertNotEquals(inferior.id, best.id)
        assertTrue(TripPlanner.feasible(best.legs, ready))
    }

    @Test fun missingZeroLengthAccessSeedsTheOriginStop() = runTest {
        val origin = a.endpoint().copy(id = "origin")
        val result = HybridTripPlanner(missingWalkBackend(origin.position to a.position),
            StandardTestDispatcher(testScheduler)).plan(origin, b.endpoint(), ready, ready,
            emptyList(), { samePlaceNetwork() }, setOf("viabusz"))

        val best = result.itineraries.first()
        assertEquals(a.position, best.legs.first { !it.walking }.from.position)
        val access = best.legs.first()
        assertTrue(access.walking)
        assertEquals(origin.position, access.from.position)
        assertEquals(a.position, access.to.position)
        assertEquals(0.0, access.distance, 0.0)
        assertEquals(ready, access.start)
        assertEquals(ready, access.end)
        assertEquals(b.position, best.legs.last { !it.walking }.to.position)
        assertTrue(TripPlanner.feasible(best.legs, ready))
    }

    @Test fun missingWalksBeyondFortyMetersAreNotSynthesizedForAccessOrEgress() = runTest {
        val origin = a.endpoint().copy(id = "origin", position = a.position.copy(lat = a.position.lat - 41.0 / 111_195.0))
        assertEquals(41.0, StopClusters.distanceMeters(origin.position, a.position), 0.1)
        val noAccess = HybridTripPlanner(missingWalkBackend(origin.position to a.position),
            StandardTestDispatcher(testScheduler)).plan(origin, b.endpoint(), ready, ready,
            emptyList(), { samePlaceNetwork() }, setOf("viabusz"))
        assertTrue("A missing 41 m access link must not create a boarding seed", noAccess.itineraries.isEmpty())

        val destination = b.endpoint().copy(id = "destination", position = b.position.copy(lat = b.position.lat - 41.0 / 111_195.0))
        assertEquals(41.0, StopClusters.distanceMeters(b.position, destination.position), 0.1)
        val noEgress = HybridTripPlanner(missingWalkBackend(b.position to destination.position),
            StandardTestDispatcher(testScheduler)).plan(a.endpoint(), destination, ready, ready,
            emptyList(), { samePlaceNetwork() }, setOf("viabusz"))
        assertTrue("The farther stop still has a backend-provided egress link", noEgress.itineraries.isNotEmpty())
        assertTrue("A missing 41 m egress link must not allow alighting at that stop",
            noEgress.itineraries.all { it.legs.last { leg -> !leg.walking }.to.position == nextStop.position })
    }

    @Test fun walkingPairsPrioritizeNearestAccessAndEgressBeforeShorterTransfers() = runTest {
        val origin = PlannerEndpoint("origin", "Origin", "", GeoPoint(47.40, 18.8))
        val destination = PlannerEndpoint("destination", "Destination", "", GeoPoint(47.44, 18.8))
        fun stop(id: String, lat: Double) = TransitStop(id, id, GeoPoint(lat, 18.8), listOf("via"))
        val accessNear = stop("access-near", 47.404)
        val accessFar = stop("access-far", 47.4045)
        val egressNear = stop("egress-near", 47.442)
        val egressFar = stop("egress-far", 47.4425)
        // Input order opposes proximity; each stop pair is only ~56 m apart,
        // much shorter than any access/egress walk.
        val stops = listOf(accessFar, egressFar, accessNear, egressNear)
        val route = TransitRoute("via", "via", "via", listOf(
            RouteDirection("out", egressFar.name, stops.map { it.id })), "viabusz")
        val localNetwork = TransitNetwork(stops, listOf(route), emptyList(), ready, "walking priority fixture",
            verifiedProviderIds = setOf("viabusz"))
        val walkingCalls = mutableListOf<List<Pair<PlanPoint, PlanPoint>>>()
        val backend = object : PlannerBackend by FakeBackend() {
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
                walkingCalls += pairs.toList()
                return WalkingResult(emptyMap())
            }
        }

        HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
            origin, destination, ready, ready, emptyList(), { localNetwork }, setOf("viabusz"))

        val pairs = walkingCalls.single()
        val originPoint = PlanPoint(origin.name, origin.position)
        val destinationPoint = PlanPoint(destination.name, destination.position)
        val endpointPairs = listOf(egressNear.point() to destinationPoint, egressFar.point() to destinationPoint,
            originPoint to accessNear.point(), originPoint to accessFar.point())
        assertEquals("Access and egress share one nearest-first priority", endpointPairs, pairs.take(4))
        val transfers = pairs.drop(4)
        assertEquals(4, transfers.size)
        assertEquals(setOf(accessNear.point() to accessFar.point(), accessFar.point() to accessNear.point(),
            egressNear.point() to egressFar.point(), egressFar.point() to egressNear.point()), transfers.toSet())
        assertTrue("Even shorter transfer walks must remain after every endpoint walk",
            transfers.maxOf { StopClusters.distanceMeters(it.first.position, it.second.position) } <
                endpointPairs.minOf { StopClusters.distanceMeters(it.first.position, it.second.position) })
    }

    private fun staggeredDirectBackend(walkSeconds: Long): PlannerBackend = object : PlannerBackend by FakeBackend() {
        override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
            if (busesOnly) {
                delay(1_000)
                val bus = coach(ready.plusSeconds(60)).copy(end = ready.plusSeconds(1320),
                    route = TransitRoute("volan:766", "766", "766", emptyList(), "volan", transportMode = "COACH"))
                return listOf(TripItinerary("second-direct", bus.start, bus.end, listOf(bus)))
            }
            val walk = PlanLeg("WALK", ready, ready.plusSeconds(walkSeconds), walkSeconds * TripPlanner.WALK_SPEED,
                b.point(), b.point(), null, null, false, false, emptyList())
            val bus = coach(walk.end.plusSeconds(60))
            return listOf(TripItinerary("first-direct", walk.start, bus.end, listOf(walk, bus)))
        }
    }

    private inner class LocalStageBackend(private val walkingDelayMillis: Long) : PlannerBackend by FakeBackend() {
        private val delegate = FakeBackend()
        var directAnswers = 0
        var localWalkingFinished = false
        override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
            if (from.id != a.id || to.id != b.id) return emptyList()
            delay(1_000)
            directAnswers++
            val provider = if (busesOnly) "volan" else "bkk"
            val mode = if (busesOnly) "COACH" else "BUS"
            val leg = coach(ready.plusSeconds(60)).copy(mode = mode, end = ready.plusSeconds(9 * 60),
                from = a.point(), to = b.point(),
                route = TransitRoute("$provider:direct", provider, provider, emptyList(), provider, transportMode = mode))
            return listOf(TripItinerary("direct-$provider", leg.start, leg.end, listOf(leg)))
        }
        override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
            delay(walkingDelayMillis)
            localWalkingFinished = true
            return delegate.walking(pairs, ready)
        }
    }

    private inner class GatewayStageBackend(private val middleDelayMillis: Long) : PlannerBackend {
        val directCalls = mutableListOf<Triple<String, String, Boolean>>()
        val plainCalls = mutableListOf<Pair<String, String>>()
        var middleCancelled = false
        private val walkingBackend = FakeBackend()

        override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
            directCalls += Triple(from.id, to.id, busesOnly)
            delay(500)
            val ride = coach(ready.plusSeconds(60)).copy(mode = "BUS", from = a.point(), to = c.point(),
                end = ready.plusSeconds(30 * 60),
                route = TransitRoute("bkk:direct", "direct", "BKK", emptyList(), "bkk", transportMode = "BUS"))
            return listOf(TripItinerary("direct-bkk", ride.start, ride.end, listOf(ride)))
        }

        override suspend fun plainRoutes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
            plainCalls += from.id to to.id
            if (from.id != b.id || to.id != c.id) return emptyList()
            try { delay(middleDelayMillis) }
            catch (cancelled: CancellationException) { middleCancelled = true; throw cancelled }
            val middle = coach(ready.plusSeconds(60)).copy(end = this@HybridTripPlannerTest.ready.plusSeconds(35 * 60))
            return listOf(TripItinerary("gateway-volan", middle.start, middle.end, listOf(middle)))
        }

        override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
            delay(1_000)
            return walkingBackend.walking(pairs, ready)
        }
    }

    @Test fun gatewayCompletionKeepsLateViabuszVolanCombinationAndUsesPlainMiddle() = runTest {
        val backend = GatewayStageBackend(middleDelayMillis = 1_500)
        val pending = async {
            HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
                a.endpoint(), c.endpoint(), ready, ready, catalogue, { network() }, localProviders = setOf("viabusz"))
        }
        advanceTimeBy(500)
        runCurrent()
        assertEquals(setOf(Triple("a", "c", false), Triple("a", "c", true)), backend.directCalls.toSet())
        assertFalse(pending.isCompleted)
        advanceTimeBy(500)
        runCurrent()
        assertTrue(backend.plainCalls.contains("b" to "c"))
        assertFalse("Local completion must leave the gateway search running", pending.isCompleted)
        advanceTimeBy(1_499)
        runCurrent()
        assertFalse(pending.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue("Completed search should release without waiting until 4000 ms", pending.isCompleted)
        assertEquals(2_500L, testScheduler.currentTime)
        val result = pending.await()
        assertEquals("direct-bkk", result.itineraries.first().id)
        val combined = result.itineraries.single { PlannerAlternatives.families(it) == listOf("local", "volan") }
        assertEquals(listOf("viabusz", "volan"), combined.legs.filterNot { it.walking }.map { it.route!!.providerId })
        assertTrue(TripPlanner.feasible(combined.legs, ready))
        assertEquals(2, backend.directCalls.size)
        assertEquals(listOf("b" to "c"), backend.plainCalls)
        assertFalse(backend.middleCancelled)
    }

    @Test fun hangingGatewayMiddleReturnsDirectAtThreeSecondsAfterLocalCompletion() = runTest {
        val backend = GatewayStageBackend(middleDelayMillis = 30_000)
        val pending = async {
            HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
                a.endpoint(), c.endpoint(), ready, ready, catalogue, { network() }, localProviders = setOf("viabusz"))
        }
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(listOf("b" to "c"), backend.plainCalls)
        assertFalse(pending.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(pending.isCompleted)
        assertEquals(4_000L, testScheduler.currentTime)
        assertEquals(listOf("direct-bkk"), pending.await().itineraries.map { it.id })
        assertTrue("Bounded return must cancel the outstanding middle request", backend.middleCancelled)
    }

    @Test fun gatewayTimeoutPreservesBothDirectAndLocalJourneys() = runTest {
        val directBackend = LocalStageBackend(walkingDelayMillis = 1_000)
        var middleStarted = false
        var middleCancelled = false
        val backend = object : PlannerBackend by directBackend {
            override suspend fun plainRoutes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                middleStarted = true
                try { delay(30_000) }
                catch (cancelled: CancellationException) { middleCancelled = true; throw cancelled }
                return emptyList()
            }
        }
        val result = HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
            a.endpoint(), b.endpoint(), ready, ready, catalogue, { network() }, localProviders = setOf("viabusz"))
        assertEquals(4_000L, testScheduler.currentTime)
        assertEquals(2, directBackend.directAnswers)
        assertTrue(middleStarted)
        assertTrue(middleCancelled)
        assertEquals(listOf("bkk", "volan", "local"), result.itineraries.flatMap(PlannerAlternatives::families))
        assertTrue(result.itineraries.all { TripPlanner.feasible(it.legs, ready) })
    }

    @Test fun twoDirectAnswersWaitForLocalStageAndIncludeShortViabuszRide() = runTest {
        val backend = LocalStageBackend(walkingDelayMillis = 2_000)
        val pending = async {
            HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
                a.endpoint(), b.endpoint(), ready, ready, catalogue, { network() }, localProviders = setOf("viabusz"))
        }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, backend.directAnswers)
        assertFalse("Two direct answers must not discard pending local timetables", pending.isCompleted)
        advanceTimeBy(999)
        runCurrent()
        assertFalse(pending.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue("Local completion should release the result immediately", pending.isCompleted)
        val result = pending.await()
        assertEquals(2_000L, testScheduler.currentTime)
        assertTrue(backend.localWalkingFinished)
        assertEquals(listOf("bkk", "volan", "local"), result.itineraries.flatMap(PlannerAlternatives::families))
        val local = result.itineraries.single { trip -> trip.legs.any { it.route?.providerId == "viabusz" } }
        assertTrue(TripPlanner.feasible(local.legs, ready))
        assertEquals(ready.plusSeconds(10 * 60), local.end)
    }

    @Test fun pendingLocalStageIsBoundedFromFirstUsableAnswer() = runTest {
        val backend = LocalStageBackend(walkingDelayMillis = 10_000)
        val pending = async {
            HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
                a.endpoint(), b.endpoint(), ready, ready, catalogue, { network() }, localProviders = setOf("viabusz"))
        }
        advanceTimeBy(3_499)
        runCurrent()
        assertEquals(2, backend.directAnswers)
        assertFalse(pending.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(pending.isCompleted)
        val result = pending.await()
        // LOCAL_STARTED at 0 ms precedes the first usable answer at 1000 ms.
        // That answer starts the 2500 ms local allowance, ending at 3500 ms.
        assertEquals(3_500L, testScheduler.currentTime)
        assertEquals(listOf("direct-bkk", "direct-volan"), result.itineraries.map { it.id })
        assertFalse(backend.localWalkingFinished)
    }

    @Test fun disabledLocalProvidersReturnWhenBothDirectAnswersArrive() = runTest {
        val backend = LocalStageBackend(walkingDelayMillis = 10_000)
        val result = HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
            a.endpoint(), b.endpoint(), ready, ready, catalogue,
            { error("Disabled local providers must not load a network") }, localProviders = emptySet())
        assertEquals(2, backend.directAnswers)
        assertEquals(1_000L, testScheduler.currentTime)
        assertEquals(listOf("direct-bkk", "direct-volan"), result.itineraries.map { it.id })
        assertFalse(backend.localWalkingFinished)
    }

    @Test fun longWalkFirstAnswerIncludesSecondDirectAfterNormalSettleWindow() = runTest {
        val result = HybridTripPlanner(staggeredDirectBackend(600), StandardTestDispatcher(testScheduler)).plan(
            b.endpoint(), c.endpoint(), ready, ready, emptyList(), { error("Local providers disabled") },
            localProviders = emptySet())
        // First costs 1260 + 1200 + 60 = 2520; second 1320 + 60 = 1380.
        // Its 60 s later arrival fits the 1200 s walking-saving allowance.
        assertEquals(listOf("second-direct", "first-direct"), result.itineraries.map { it.id })
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test fun shortWalkFirstAnswerExcludesSecondDirectAfterNormalSettleWindow() = runTest {
        val result = HybridTripPlanner(staggeredDirectBackend(599), StandardTestDispatcher(testScheduler)).plan(
            b.endpoint(), c.endpoint(), ready, ready, emptyList(), { error("Local providers disabled") },
            localProviders = emptySet())
        assertEquals(listOf("first-direct"), result.itineraries.map { it.id })
        assertEquals(500L, testScheduler.currentTime)
    }

    @Test fun eightMinuteFinalWalkBelowTenMinutesTotalWaitsFullImprovementWindow() = runTest {
        val firstRide = coach(ready.plusSeconds(60))
        val egress = PlanLeg("WALK", firstRide.end, firstRide.end.plusSeconds(480),
            480 * TripPlanner.WALK_SPEED, c.point(), c.point(), null, null, false, false, emptyList())
        val first = TripItinerary("long-egress", firstRide.start, egress.end, listOf(firstRide, egress))
        var otherCancelled = false
        val backend = object : PlannerBackend by FakeBackend() {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                if (!busesOnly) return listOf(first)
                try { delay(30_000) } finally { otherCancelled = true }
                return emptyList()
            }
        }
        val pending = async {
            HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
                b.endpoint(), c.endpoint(), ready, ready, emptyList(), { error("Local providers disabled") },
                localProviders = emptySet())
        }
        advanceTimeBy(3_999)
        runCurrent()
        assertFalse("Eight-minute final walk needs the connection-search allowance", pending.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(pending.isCompleted)
        assertEquals(listOf(first), pending.await().itineraries)
        assertEquals(4_000L, testScheduler.currentTime)
        assertTrue(otherCancelled)
        assertEquals(480L, first.walkSeconds) // below the old 600-second total-walk trigger
    }

    @Test fun finalWalkImprovementArrivingAfterNormalSettleIsIncludedWithoutWaitingFullFourSeconds() = runTest {
        val ride = coach(ready.plusSeconds(60))
        val walk = PlanLeg("WALK", ride.end, ride.end.plusSeconds(480), 480 * TripPlanner.WALK_SPEED,
            c.point(), c.point(), null, null, false, false, emptyList())
        val first = TripItinerary("long-egress", ride.start, walk.end, listOf(ride, walk))
        val betterRide = ride.copy(end = first.end.plusSeconds(60),
            route = ride.route!!.copy(id = "volan:766", number = "766"))
        val better = TripItinerary("less-walking", betterRide.start, betterRide.end, listOf(betterRide))
        val backend = object : PlannerBackend by FakeBackend() {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                if (!busesOnly) return listOf(first)
                delay(3_000)
                return listOf(better)
            }
        }
        val result = HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
            b.endpoint(), c.endpoint(), ready, ready, emptyList(), { error("Local providers disabled") },
            localProviders = emptySet())
        assertEquals(3_000L, testScheduler.currentTime)
        assertEquals(listOf(better, first), result.itineraries)
    }

    @Test fun localNationalLocalJourneyCanUseViaBuszVolanHommAndKanyargoTogether() = runTest {
        val backend = FakeBackend()
        val result = HybridTripPlanner(backend).plan(a.endpoint(),e.endpoint(),ready,ready,catalogue,{network()})
        val journey = result.itineraries.first()
        assertEquals(listOf("viabusz","volan","homm","kanyargo"),journey.legs.filterNot { it.walking }.map { it.route!!.providerId })
        assertTrue(TripPlanner.feasible(journey.legs,ready))
        assertFalse(result.partial)
        assertTrue(backend.calls.size <= 17)
    }
    @Test fun nationalArrivalAfterLocalDepartureCannotMakeAnImpossibleConnection() = runTest {
        val backend = FakeBackend().apply { middle = coach(ready.plusSeconds(30*60)) }
        val result = HybridTripPlanner(backend).plan(a.endpoint(),e.endpoint(),ready,ready,catalogue,{network()})
        assertTrue(result.itineraries.isEmpty())
    }
    @Test fun localNationalConnectionsWorkBeforeTheMapStopIndexHasLoaded() = runTest {
        val result = HybridTripPlanner(FakeBackend()).plan(a.endpoint(),e.endpoint(),ready,ready,emptyList(),{network()})
        assertTrue(result.itineraries.any { it.legs.any { leg -> leg.route?.providerId == "volan" } })
        assertFalse(result.partial)
    }
    @Test fun failedStreetRoutingNeverCreatesNontrivialStraightLineLocalAccess() = runTest {
        val backend = FakeBackend().apply { noWalks = true }
        // Exact-stop access is deliberately synthesized; a real street walk must still be routed.
        val origin = a.endpoint().copy(position = a.position.copy(lat = a.position.lat - 100.0 / 111_195.0))
        val result = HybridTripPlanner(backend).plan(origin,e.endpoint(),ready,ready,catalogue,{network()})
        assertTrue(result.itineraries.isEmpty())
        assertTrue(result.partial)
    }
    @Test fun localOnlyJourneySurvivesNationalServiceFailureWithPartialCoverageNotice() = runTest {
        val result = HybridTripPlanner(FakeBackend().apply { fail=true }).plan(a.endpoint(),b.endpoint(),ready,ready,catalogue,{network()})
        assertTrue(result.itineraries.any { it.legs.any { leg -> leg.route?.providerId == "viabusz" } })
        assertTrue(result.partial)
    }
    @Test fun localTimetableFailureDoesNotEraseSuccessfulNationalAlternatives() = runTest {
        val backend = object : PlannerBackend {
            override suspend fun routes(from:PlannerEndpoint,to:PlannerEndpoint,ready:Instant,busesOnly:Boolean) = listOf(
                coach(ready.plusSeconds(60)).let { TripItinerary("bus",it.start,it.end,listOf(it)) })
            override suspend fun walking(pairs:List<Pair<PlanPoint,PlanPoint>>,ready:Instant) = error("not needed")
        }
        val result = HybridTripPlanner(backend).plan(b.endpoint(),c.endpoint(),ready,ready,catalogue,{error("source changed")})
        assertEquals("volan",result.itineraries.first().legs.first().route!!.providerId)
        assertTrue(result.partial)
    }
    @Test fun cancellationPropagatesInsteadOfBeingReportedAsEmptyCoverage() = runTest {
        val backend = object : PlannerBackend {
            override suspend fun routes(from:PlannerEndpoint,to:PlannerEndpoint,ready:Instant,busesOnly:Boolean):List<TripItinerary> = throw CancellationException("new request")
            override suspend fun walking(pairs:List<Pair<PlanPoint,PlanPoint>>,ready:Instant) = WalkingResult(emptyMap())
        }
        try {
            HybridTripPlanner(backend).plan(a.endpoint(),e.endpoint(),ready,ready,catalogue,{network()})
            fail("cancellation must propagate")
        } catch (_:CancellationException) { }
    }

    @Test fun localResultsWaitOnlyGatewayWindowForSlowNationalQueries() = runTest {
        val delegate = FakeBackend()
        val backend = object : PlannerBackend by delegate {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                delay(30_000)
                return emptyList()
            }
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
                delay(1_000)
                return delegate.walking(pairs, ready)
            }
        }
        var firstResultAt: Long? = null
        val result = HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
            a.endpoint(), b.endpoint(), ready, ready, catalogue, { delay(1_000); network() }) { progress ->
            if (progress.itineraries.isNotEmpty() && firstResultAt == null) firstResultAt = testScheduler.currentTime
        }
        // Network loading and walking each take 1000 ms. GATEWAY_STARTED then
        // grants the combined search 3000 ms, including its wait for direct queries.
        assertEquals(5_000L, firstResultAt)
        assertTrue(result.itineraries.any { it.legs.any { leg -> leg.route?.providerId == "viabusz" } })
    }

    @Test fun lateLocalPrefixesArePrunedAndPlainGatewayDoesNotReuseEnrichedDirectRequest() = runTest {
        val calls = mutableListOf<Pair<Pair<GeoPoint, GeoPoint>, Boolean>>()
        val plainCalls = mutableListOf<Pair<GeoPoint, GeoPoint>>()
        val direct = coach(ready.plusSeconds(60)).copy(from = a.point(), to = c.point())
        val backend = object : PlannerBackend by FakeBackend() {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                calls += (from.position to to.position) to busesOnly
                return if (from.position == a.position && to.position == c.position)
                    listOf(TripItinerary("direct", direct.start, direct.end, listOf(direct))) else emptyList()
            }
            override suspend fun plainRoutes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                plainCalls += from.position to to.position
                return emptyList()
            }
        }
        // The ViaBusz prefix reaches the interchange 60 minutes after ready. The
        // national journey ends at 11 minutes; even the 45-minute walk-sparing
        // allowance cannot make a journey using this prefix selectable.
        val late = network().copy(trips = network().trips.map { trip ->
            if (trip.routeId == "via") trip.copy(stopTimes = listOf(StopTime("a", 422), StopTime("b", 480))) else trip
        })
        val result = HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
            a.endpoint(), c.endpoint(), ready, ready, catalogue, { late })
        assertTrue(result.itineraries.isNotEmpty())
        assertTrue(calls.none { it.first.first == b.position })
        assertTrue(plainCalls.none { it.first == b.position })
        // The destination is also a local gateway at the same coordinates/time.
        // Its plain middle must not reuse the unrestricted enriched direct call.
        assertEquals(listOf(false, true), calls.filter { it.first == a.position to c.position }.map { it.second }.sorted())
        assertEquals(listOf(a.position to c.position), plainCalls)
    }

    @Test fun localPrefixBetweenTwentyAndFortyFiveMinutesAfterEarliestArrivalIsStillQueried() = runTest {
        val plainCalls = mutableListOf<Pair<String, Instant>>()
        val direct = coach(ready.plusSeconds(60)).copy(from = a.point(), to = c.point())
        val backend = object : PlannerBackend by FakeBackend() {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> =
                listOf(TripItinerary("direct", direct.start, direct.end, listOf(direct)))
            override suspend fun plainRoutes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                plainCalls += from.id to ready
                return emptyList()
            }
        }
        val late = network().copy(trips = network().trips.map { trip ->
            if (trip.routeId == "via") trip.copy(stopTimes = listOf(StopTime("a", 422), StopTime("b", 460))) else trip
        })
        HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
            a.endpoint(), c.endpoint(), ready, ready, catalogue, { late })
        val arrival = ready.plusSeconds(40 * 60)
        assertTrue(arrival > direct.end.plusSeconds(PlannerAlternatives.MAX_ARRIVAL_DELAY_SECONDS))
        assertTrue(arrival < direct.end.plusSeconds(PlannerAlternatives.LESS_WALK_MAX_DELAY_SECONDS))
        assertTrue("A potentially walk-sparing prefix must reach the national router", plainCalls.any {
            it.first == b.id && it.second == arrival.plusSeconds(TripPlanner.TRANSFER_SECONDS - TripPlanner.BOARD_SECONDS)
        })
    }

    @Test fun disablingViabuszExcludesViabuszLegsFromJourney() = runTest {
        val backend = FakeBackend()
        val result = HybridTripPlanner(backend).plan(
            a.endpoint(), e.endpoint(), ready, ready, catalogue, { network() },
            localProviders = setOf("homm", "kanyargo")
        )
        assertTrue(result.itineraries.none { itinerary ->
            itinerary.legs.any { it.route?.providerId == "viabusz" }
        })
    }

    @Test fun disablingHommExcludesHommFromMixedLocalLegsAndFullJourney() = runTest {
        val backend = FakeBackend()
        // Mixed local journey from c to e without national leg
        val defaultMixed = HybridTripPlanner(backend).plan(
            c.endpoint(), e.endpoint(), ready, ready, catalogue, { network() }
        )
        val defaultJourney = defaultMixed.itineraries.first()
        assertEquals(listOf("homm", "kanyargo"), defaultJourney.legs.filterNot { it.walking }.map { it.route!!.providerId })

        // When HOMM is disabled, it must be excluded from mixed local routing
        val disabledMixed = HybridTripPlanner(backend).plan(
            c.endpoint(), e.endpoint(), ready, ready, catalogue, { network() },
            localProviders = setOf("viabusz", "kanyargo")
        )
        assertTrue(disabledMixed.itineraries.none { itinerary ->
            itinerary.legs.any { it.route?.providerId == "homm" }
        })

        // Also excluded from full combined journey
        val fullResult = HybridTripPlanner(backend).plan(
            a.endpoint(), e.endpoint(), ready, ready, catalogue, { network() },
            localProviders = setOf("viabusz", "kanyargo")
        )
        assertTrue(fullResult.itineraries.none { itinerary ->
            itinerary.legs.any { it.route?.providerId == "homm" }
        })
    }

    @Test fun disablingKanyargoExcludesKanyargoFromMixedLocalLegsAndFullJourney() = runTest {
        val backend = FakeBackend()
        // Mixed local journey from c to e when kanyargo is disabled
        val disabledMixed = HybridTripPlanner(backend).plan(
            c.endpoint(), e.endpoint(), ready, ready, catalogue, { network() },
            localProviders = setOf("viabusz", "homm")
        )
        assertTrue(disabledMixed.itineraries.none { itinerary ->
            itinerary.legs.any { it.route?.providerId == "kanyargo" }
        })

        // Also excluded from full combined journey
        val fullResult = HybridTripPlanner(backend).plan(
            a.endpoint(), e.endpoint(), ready, ready, catalogue, { network() },
            localProviders = setOf("viabusz", "homm")
        )
        assertTrue(fullResult.itineraries.none { itinerary ->
            itinerary.legs.any { it.route?.providerId == "kanyargo" }
        })
    }

    @Test fun emptyLocalProvidersSkipsNetworkLoaderWhilePreservingNationalDirect() = runTest {
        val backend = FakeBackend()
        var networkLoaded = false
        val result = HybridTripPlanner(backend).plan(
            b.endpoint(), c.endpoint(), ready, ready, catalogue,
            loadNetwork = {
                networkLoaded = true
                network()
            },
            localProviders = emptySet()
        )
        assertFalse("Network loader should not be called when localProviders is empty", networkLoaded)
        val journey = result.itineraries.first()
        assertEquals(listOf("volan"), journey.legs.filterNot { it.walking }.map { it.route!!.providerId })
        assertFalse(result.partial)
    }

    @Test fun enabledDefaultCombinedUsesAllAvailableLocalProviders() = runTest {
        val backend = FakeBackend()
        val result = HybridTripPlanner(backend).plan(a.endpoint(), e.endpoint(), ready, ready, catalogue, { network() })
        val journey = result.itineraries.first()
        val providers = journey.legs.filterNot { it.walking }.mapNotNull { it.route?.providerId }
        assertTrue(providers.containsAll(listOf("viabusz", "volan", "homm", "kanyargo")))
    }

    @Test fun directRemoteRouteReturnsPromptlyWhileLocalNetworkLoadHangs() = runTest {
        val delegate = FakeBackend()
        var walkingStarted = false
        val directRoute = coach(ready.plusSeconds(5 * 60)).copy(from = a.point(), to = b.point())
        val backend = object : PlannerBackend by delegate {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                delay(500)
                return if (from.id == a.id && to.id == b.id) {
                    listOf(TripItinerary("direct", directRoute.start, directRoute.end, listOf(directRoute)))
                } else emptyList()
            }
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
                walkingStarted = true
                delay(30_000)
                return delegate.walking(pairs, ready)
            }
        }
        val result = HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
            a.endpoint(), b.endpoint(), ready, ready, catalogue,
            loadNetwork = {
                delay(30_000)
                network()
            }
        )
        val completedAt = testScheduler.currentTime
        // Both direct answers arrive at 500 ms; loading alone never starts the local wait.
        assertEquals(500L, completedAt)
        assertFalse(walkingStarted)
        assertTrue(result.itineraries.any { it.legs.any { leg -> leg.route?.providerId == "volan" } })
    }

    @Test fun directRemoteRouteWaitsOnlyTheBoundForStartedLocalWalking() = runTest {
        val delegate = FakeBackend()
        var walkingStarted = false
        val directRoute = coach(ready.plusSeconds(5 * 60)).copy(from = a.point(), to = b.point())
        val backend = object : PlannerBackend by delegate {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                delay(500)
                return if (from.id == a.id && to.id == b.id) {
                    listOf(TripItinerary("direct", directRoute.start, directRoute.end, listOf(directRoute)))
                } else emptyList()
            }
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
                walkingStarted = true
                delay(30_000)
                return delegate.walking(pairs, ready)
            }
        }
        val result = HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
            a.endpoint(), b.endpoint(), ready, ready, catalogue,
            loadNetwork = { network() }
        )
        val completedAt = testScheduler.currentTime
        // LOCAL_STARTED arrives at 0 ms, before either direct answer. The first answer
        // at 500 ms starts (rather than restarts) the 2500 ms local settle timer.
        assertEquals(3_000L, completedAt)
        assertTrue(walkingStarted)
        assertTrue(result.itineraries.any { it.legs.any { leg -> leg.route?.providerId == "volan" } })
    }

    @Test fun localStageStartingAfterFirstAnswerRestartsSettleTimer() = runTest {
        val backend = object : PlannerBackend by staggeredDirectBackend(599) {
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
                delay(30_000)
                return WalkingResult(emptyMap())
            }
        }
        val pending = async {
            HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
                b.endpoint(), c.endpoint(), ready, ready, catalogue,
                { delay(250); network() })
        }
        // First answer at 0 ms starts a 500 ms timer. LOCAL_STARTED at 250 ms
        // restarts it for 2500 ms; the second direct answer at 1000 ms cannot release it.
        advanceTimeBy(2_749)
        runCurrent()
        assertFalse(pending.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(pending.isCompleted)
        assertEquals(2_750L, testScheduler.currentTime)
        assertEquals(listOf("second-direct", "first-direct"), pending.await().itineraries.map { it.id })
    }

    @Test fun planningDoesNotContinuallyReplaceDisplayedItinerariesWhileEnrichmentFinishes() = runTest {
        val delegate = FakeBackend()
        val directRoute = coach(ready.plusSeconds(5 * 60)).copy(from = a.point(), to = b.point())
        val backend = object : PlannerBackend by delegate {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                delay(300)
                return if (from.id == a.id && to.id == b.id) {
                    listOf(TripItinerary("direct", directRoute.start, directRoute.end, listOf(directRoute)))
                } else emptyList()
            }
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
                delay(600)
                return delegate.walking(pairs, ready)
            }
        }
        val progressSnapshots = mutableListOf<TripPlan>()
        HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
            a.endpoint(), b.endpoint(), ready, ready, catalogue,
            loadNetwork = { delay(400); network() },
            onProgress = { progressSnapshots += it }
        )
        // Desired contract: one completed valid planning snapshot quickly, do not continually replace displayed itineraries
        assertTrue("Planning should not continually replace displayed itineraries, got ${progressSnapshots.size} updates",
            progressSnapshots.size <= 1)
    }

    @Test fun doesNotRecommendHundredsOfMinutesLocalBoardingWaitWhenFasterValidJourneyExists() = runTest {
        val delegate = FakeBackend()
        // Fast direct national route available in 10 minutes, arrives at +30m
        val fastDirect = coach(ready.plusSeconds(10 * 60)).copy(from = a.point(), to = b.point())
        val backend = object : PlannerBackend by delegate {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                delay(1_000)
                return if (from.id == a.id && to.id == b.id) {
                    listOf(TripItinerary("direct", fastDirect.start, fastDirect.end, listOf(fastDirect)))
                } else emptyList()
            }
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
                delay(100)
                return delegate.walking(pairs, ready)
            }
        }
        // Local network with ViaBusz trip departing at minute 662 (240 minutes = 4 hours boarding wait from ready)
        val lateViaBuszNetwork = network().copy(trips = listOf(
            trip("via", "a", "b", 662, 670)
        ))
        val displayedPlans = mutableListOf<TripPlan>()
        val result = HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
            a.endpoint(), b.endpoint(), ready, ready, catalogue,
            loadNetwork = { delay(100); lateViaBuszNetwork },
            onProgress = { displayedPlans += it }
        )
        // Any displayed plan recommended to user must not show a hundreds-of-minutes boarding wait when a faster valid journey exists
        val recommendedPlans = (displayedPlans + result).mapNotNull { it.itineraries.firstOrNull() }
        for (itinerary in recommendedPlans) {
            val firstTransit = itinerary.legs.firstOrNull { !it.walking }
            val boardingWaitMinutes = firstTransit?.let { java.time.Duration.between(ready, it.start).toMinutes() } ?: 0L
            assertTrue(
                "Must not recommend an itinerary with $boardingWaitMinutes min boarding wait when faster valid journey exists",
                boardingWaitMinutes < 120
            )
        }
    }

    @Test fun healthyDirectCancelsBlockedBusOnlyAndLocalNetworkAndWalking() = runTest {
        val delegate = FakeBackend()
        val directRoute = coach(ready.plusSeconds(5 * 60)).copy(from = a.point(), to = b.point())
        var busOnlyCancelled = false
        var loadNetworkCancelled = false
        var walkingCancelled = false

        val backend = object : PlannerBackend by delegate {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                return if (!busesOnly && from.id == a.id && to.id == b.id) {
                    listOf(TripItinerary("direct", directRoute.start, directRoute.end, listOf(directRoute)))
                } else {
                    try {
                        delay(60_000)
                        emptyList()
                    } catch (e: CancellationException) {
                        busOnlyCancelled = true
                        throw e
                    }
                }
            }
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
                return try {
                    delay(60_000)
                    delegate.walking(pairs, ready)
                } catch (e: CancellationException) {
                    walkingCancelled = true
                    throw e
                }
            }
        }

        val result = HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
            a.endpoint(), b.endpoint(), ready, ready, catalogue,
            loadNetwork = {
                try {
                    delay(60_000)
                    network()
                } catch (e: CancellationException) {
                    loadNetworkCancelled = true
                    throw e
                }
            }
        )

        assertEquals(1, result.itineraries.size)
        assertEquals("direct", result.itineraries.first().id)
        assertTrue("Bus-only query must be cancelled when direct finishes", busOnlyCancelled)
        assertTrue("Load network must be cancelled when direct finishes", loadNetworkCancelled)
    }

    @Test fun exactlyOneFinalCallbackAndNoPostReturnWorkOrMutation() = runTest {
        val delegate = FakeBackend()
        val directRoute = coach(ready.plusSeconds(5 * 60)).copy(from = a.point(), to = b.point())
        val backend = object : PlannerBackend by delegate {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                delay(100)
                return if (from.id == a.id && to.id == b.id) {
                    listOf(TripItinerary("direct", directRoute.start, directRoute.end, listOf(directRoute)))
                } else emptyList()
            }
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
                delay(300)
                return delegate.walking(pairs, ready)
            }
        }

        val callbacks = mutableListOf<TripPlan>()
        val result = HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
            a.endpoint(), b.endpoint(), ready, ready, catalogue,
            loadNetwork = { delay(200); network() },
            onProgress = { callbacks += it }
        )

        assertEquals("Exactly one callback must be emitted", 1, callbacks.size)
        val snapshotItineraries = result.itineraries.toList()

        testScheduler.advanceTimeBy(10_000)
        assertEquals("No subsequent callbacks after plan return", 1, callbacks.size)
        assertEquals("Result itineraries must remain unchanged", snapshotItineraries, result.itineraries)
    }

    @Test fun localOnlyAndLocalNationalLocalJourneysSupported() = runTest {
        val delegate = FakeBackend()
        val backend = object : PlannerBackend by delegate {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                delay(50)
                return if (from.id == b.id && to.id == c.id) {
                    delegate.routes(from, to, ready, busesOnly)
                } else emptyList()
            }
        }

        val planner = HybridTripPlanner(backend, StandardTestDispatcher(testScheduler))

        // 1. Local only journey: a -> b (viaBusz route)
        val localResult = planner.plan(a.endpoint(), b.endpoint(), ready, ready, catalogue, { network() })
        assertEquals(1, localResult.itineraries.size)
        val localTrip = localResult.itineraries.first()
        assertTrue("Local-only trip must be supported", localTrip.legs.any { it.route?.providerId == "viabusz" })

        // 2. Local-national-local combined: a -> e (local a->b, national b->c, local c->e)
        val combinedResult = planner.plan(a.endpoint(), e.endpoint(), ready, ready, catalogue, { network() })
        assertEquals(1, combinedResult.itineraries.size)
        val combinedTrip = combinedResult.itineraries.first()
        val providers = combinedTrip.legs.filterNot { it.walking }.mapNotNull { it.route?.providerId }
        assertTrue("Combined trip must contain local and national providers",
            providers.contains("viabusz") && providers.contains("volan"))
    }

    @Test fun timeoutBeforeCandidateThrowsUnavailable_timeoutAfterCandidateReturnsPartial() = runTest {
        val backendNeverResponds = object : PlannerBackend {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                delay(60_000)
                return emptyList()
            }
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
                delay(60_000)
                return WalkingResult(emptyMap())
            }
        }

        val plannerShortTimeout = HybridTripPlanner(backendNeverResponds, StandardTestDispatcher(testScheduler), searchTimeoutMillis = 500)
        var threwUnavailable = false
        try {
            plannerShortTimeout.plan(a.endpoint(), b.endpoint(), ready, ready, catalogue, { delay(60_000); network() })
        } catch (e: IllegalStateException) {
            if (e.message?.contains("Planning services unavailable") == true) {
                threwUnavailable = true
            }
        }
        assertTrue("Must throw 'Planning services unavailable' when timing out before candidates", threwUnavailable)

        val localRoute = coach(ready.plusSeconds(5 * 60)).copy(from = a.point(), to = b.point())
        val backendWithEarlyCandidate = object : PlannerBackend {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                delay(100)
                return if (!busesOnly && from.id == a.id && to.id == b.id) {
                    listOf(TripItinerary("early-candidate", localRoute.start, localRoute.end, listOf(localRoute)))
                } else {
                    delay(60_000)
                    emptyList()
                }
            }
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
                delay(60_000)
                return WalkingResult(emptyMap())
            }
        }
        val plannerWithTimeout = HybridTripPlanner(backendWithEarlyCandidate, StandardTestDispatcher(testScheduler), searchTimeoutMillis = 1000)
        val result = plannerWithTimeout.plan(a.endpoint(), b.endpoint(), ready, ready, catalogue, { delay(60_000); network() })
        assertEquals(1, result.itineraries.size)
        assertEquals("early-candidate", result.itineraries.first().id)
    }
}
