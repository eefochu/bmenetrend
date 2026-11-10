package hu.app.bmenetrend.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
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
    @Test fun failedStreetRoutingNeverCreatesStraightLineLocalAccessOrTransfers() = runTest {
        val backend = FakeBackend().apply { noWalks = true }
        val result = HybridTripPlanner(backend).plan(a.endpoint(),e.endpoint(),ready,ready,catalogue,{network()})
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

    @Test fun localResultsDoNotWaitForSlowNationalQueries() = runTest {
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
        assertEquals(2_500L, firstResultAt)
        assertTrue(result.itineraries.any { it.legs.any { leg -> leg.route?.providerId == "viabusz" } })
    }

    @Test fun lateLocalPrefixesArePrunedAndIdenticalGatewayRequestsAreShared() = runTest {
        val calls = mutableListOf<Pair<Pair<GeoPoint, GeoPoint>, Boolean>>()
        val direct = coach(ready.plusSeconds(60)).copy(from = a.point(), to = c.point())
        val backend = object : PlannerBackend by FakeBackend() {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                calls += (from.position to to.position) to busesOnly
                return if (from.position == a.position && to.position == c.position)
                    listOf(TripItinerary("direct", direct.start, direct.end, listOf(direct))) else emptyList()
            }
        }
        // The ViaBusz prefix reaches the interchange 40 minutes after ready. The
        // national journey ends at 11 minutes; even the maximum 20-minute allowance
        // cannot make a journey using this prefix selectable.
        val late = network().copy(trips = network().trips.map { trip ->
            if (trip.routeId == "via") trip.copy(stopTimes = listOf(StopTime("a", 422), StopTime("b", 460))) else trip
        })
        val result = HybridTripPlanner(backend, StandardTestDispatcher(testScheduler)).plan(
            a.endpoint(), c.endpoint(), ready, ready, catalogue, { late })
        assertTrue(result.itineraries.isNotEmpty())
        assertTrue(calls.none { it.first.first == b.position })
        // Origin -> destination and origin -> the same local gateway share their
        // unrestricted request. The independent bus-only request must still run.
        assertEquals(listOf(false, true), calls.filter { it.first == a.position to c.position }.map { it.second }.sorted())
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

    @Test fun directRemoteRouteDoesNotAwaitSlowLocalEnrichment() = runTest {
        val delegate = FakeBackend()
        val directRoute = coach(ready.plusSeconds(5 * 60)).copy(from = a.point(), to = b.point())
        val backend = object : PlannerBackend by delegate {
            override suspend fun routes(from: PlannerEndpoint, to: PlannerEndpoint, ready: Instant, busesOnly: Boolean): List<TripItinerary> {
                delay(500)
                return if (from.id == a.id && to.id == b.id) {
                    listOf(TripItinerary("direct", directRoute.start, directRoute.end, listOf(directRoute)))
                } else emptyList()
            }
            override suspend fun walking(pairs: List<Pair<PlanPoint, PlanPoint>>, ready: Instant): WalkingResult {
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
        assertTrue("Healthy direct route should complete quickly without awaiting 30s local enrichment, took $completedAt ms", completedAt <= 1_000L)
        assertTrue(result.itineraries.any { it.legs.any { leg -> leg.route?.providerId == "volan" } })
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
