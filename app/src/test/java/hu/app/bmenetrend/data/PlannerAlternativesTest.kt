package hu.app.bmenetrend.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PlannerAlternativesTest {
    private val ready = Instant.parse("2026-10-24T09:00:00Z")
    private fun journey(id: String, minutes: Long = 30, transfers: Int = 0, walk: Double = 0.0,
                        provider: String = "bkk", line: String = id, shift: Double = 0.0, restricted: Boolean = false): TripItinerary {
        val from=PlanPoint("A-$id",GeoPoint(47.4+shift,18.8))
        val to=PlanPoint("B-$id",GeoPoint(47.5+shift,18.8))
        val end=ready.plusSeconds(minutes*60)
        val walking=kotlin.math.ceil(walk/TripPlanner.WALK_SPEED).toLong()
        val legs=mutableListOf<PlanLeg>()
        var clock=ready
        if(walk>0) {
            legs+=PlanLeg("WALK",clock,clock.plusSeconds(walking),walk,from,from,null,null,false,false,emptyList())
            clock=clock.plusSeconds(walking)
        }
        clock=clock.plusSeconds(60)
        val rideSeconds=(java.time.Duration.between(clock,end).seconds-transfers*180)/(transfers+1)
        require(rideSeconds>0)
        for(i in 0..transfers) {
            val route=TransitRoute("$provider:$line:$i","$line-$i","route",emptyList(),provider,transportMode="BUS")
            val arrive=if(i==transfers) end else clock.plusSeconds(rideSeconds)
            legs+=PlanLeg("BUS",clock,arrive,1000.0,from,to,route,"B",false,false,emptyList(),restrictedService=restricted)
            clock=arrive.plusSeconds(180)
        }
        return TripItinerary(id,legs.first().start,end,legs)
    }
    private fun select(vararg trips: TripItinerary)=TripPlanner.alternatives(trips.toList(),ready)

    @Test fun varietyIncludesEligibleFamiliesButDoesNotReserveSlotsOutsideWindows() {
        val best=journey("best",provider="mav")
        val result=select(best,journey("volan",minutes=40,provider="volan"),
            journey("via",minutes=45,provider="viabusz"),journey("homm",minutes=50,provider="homm"))
        // At 45 minutes ViaBusz is exactly inside the 15-minute backup window;
        // HOMM is outside it (and shares the same local family).
        assertEquals(listOf("best", "volan", "via"),result.map { it.id })
    }

    @Test fun viableVolanAndViabuszReplaceCheaperSecondBkkVariant() {
        val recommended = journey("recommended")
        val secondBkk = journey("second-bkk", minutes = 31)
        val volan = journey("volan", minutes = 36, provider = "volan")
        val via = journey("via", minutes = 38, walk = 100.0, provider = "viabusz")
        val source = listOf(via, secondBkk, volan, recommended)
        // Second BKK is cheaper than either other family. Volán is outclassed
        // by arrival time, and ViaBusz also walks more; variety still admits both.
        assertEquals(listOf(recommended, secondBkk), select(secondBkk, recommended))
        for (offset in source.indices) {
            val rotated = source.drop(offset) + source.take(offset)
            for (trips in listOf(rotated, rotated.reversed())) {
                val result = PlannerAlternatives.select(trips, ready)
                assertEquals(listOf(recommended, volan, via), result)
                assertEquals(listOf("bkk", "volan", "local"), result.flatMap(PlannerAlternatives::families))
            }
        }
    }

    @Test fun varietyGivesCombinedVolanViabuszACardBesideBkkRecommendation() {
        val recommended = journey("recommended")
        val secondBkk = journey("second-bkk", minutes = 31)
        val base = journey("combined", minutes = 38, transfers = 1, provider = "volan")
        val localLeg = base.legs.last().let { leg ->
            leg.copy(route = leg.route!!.copy(id = "viabusz:local", providerId = "viabusz"))
        }
        val combined = base.copy(legs = listOf(base.legs.first(), localLeg))
        assertEquals(listOf("volan", "local"), PlannerAlternatives.families(combined))
        assertTrue(TripPlanner.feasible(combined.legs, ready))
        // The combination arrives later and adds a change, so ordinary backup
        // selection would reject it. The variety pass must preserve its families.
        for (source in listOf(listOf(combined, secondBkk, recommended), listOf(recommended, secondBkk, combined))) {
            val result = PlannerAlternatives.select(source, ready)
            assertEquals(listOf(recommended, secondBkk, combined), result)
            assertEquals(listOf("bkk", "bkk", "volan", "local"), result.flatMap(PlannerAlternatives::families))
        }
    }

    @Test fun varietyDoesNotAdmitVolanOneSecondOutsideEligibilityWindow() {
        val recommended = journey("recommended")
        val boundary = journey("volan", minutes = 45, provider = "volan")
        val outside = boundary.copy(end = boundary.end.plusSeconds(1),
            legs = boundary.legs.map { it.copy(end = it.end.plusSeconds(1)) })
        assertEquals(listOf(recommended, boundary), select(boundary, recommended))
        assertEquals(listOf(recommended), select(outside, recommended))
    }

    @Test fun varietyStopsAtThreeCardsEvenWhenMoreFamiliesAreEligible() {
        val recommended = journey("recommended")
        val volan = journey("volan", minutes = 31, provider = "volan")
        val via = journey("via", minutes = 32, provider = "viabusz")
        val rail = journey("rail", minutes = 33, provider = "mav")
        val homm = journey("homm", minutes = 34, provider = "homm")
        assertEquals(listOf(recommended, volan, via), select(rail, homm, via, volan, recommended))
    }

    @Test fun varietyPreservesReservedLessWalkingSlotBeforeFillingLastCard() {
        val recommended = journey("recommended", walk = 300.0)
        val nearest = journey("nearest", minutes = 44)
        val volan = journey("volan", minutes = 32, walk = 300.0, provider = "volan")
        val via = journey("via", minutes = 33, walk = 300.0, provider = "viabusz")
        // Nearest saves 261 walking seconds and reserves a slot despite its higher
        // cost. Only the cheaper remaining family fits, and backups sort by cost.
        assertEquals(listOf(recommended, volan, nearest), select(via, nearest, volan, recommended))
    }

    @Test fun varietyDoesNotRescueStrictlyWorseVariantsOfAnAlreadyShownFamily() {
        val recommended = journey("recommended")
        val moreWalking = journey("more-walking", minutes = 31, walk = 100.0)
        val extraTransfer = journey("extra-transfer", minutes = 31, transfers = 1)
        assertEquals(listOf(recommended), select(moreWalking, extraTransfer, recommended))
    }

    @Test fun familiesRecognizeLocalRegionalRailAndCityServices() {
        val cases = listOf(
            Triple("viabusz", "BUS", "local"), Triple("kanyargo", "BUS", "local"),
            Triple("homm", "BUS", "local"), Triple("volan", "COACH", "volan"),
            Triple("mav", "BUS", "rail"), Triple("hev", "BUS", "rail"),
            Triple("other", "RAIL", "rail"), Triple("other", "SUBURBAN_RAILWAY", "rail"),
            Triple("bkk", "BUS", "bkk"))
        for ((provider, mode, expected) in cases) {
            val trip = journey("$provider-$mode", provider = provider).let { original ->
                original.copy(legs = original.legs.map { it.copy(mode = mode) })
            }
            assertEquals("$provider / $mode", listOf(expected), PlannerAlternatives.families(trip))
        }
        val ride = journey("walk").legs.single()
        val walking = TripItinerary("walking-only", ride.start, ride.end,
            listOf(ride.copy(mode = "WALK", route = null)))
        assertTrue(PlannerAlternatives.families(walking).isEmpty())
    }

    @Test fun familiesRetainBoardingOrderAndCollapseRepeatedKinds() {
        val providers = listOf("viabusz", "volan", "homm", "mav", "hev", "bkk")
        val legs = providers.map { journey(it, provider = it).legs.single() }
        val mixed = TripItinerary("mixed", legs.first().start, legs.last().end, legs)
        assertEquals(listOf("local", "volan", "rail", "bkk"), PlannerAlternatives.families(mixed))
    }
    @Test fun bestBusOptionsSurviveWhenTheyActuallySaveWalkingOrTransfers() {
        val rail=journey("rail",transfers=1,walk=600.0,provider="mav")
        val bkk=journey("bkk",minutes=31,transfers=1,walk=300.0)
        val volan=journey("volan",minutes=33,walk=100.0,provider="volan")
        // Costs: volan 2154 < bkk 2682 < rail 3144; rail arrives earliest,
        // so neither shown route outclasses it despite its greater walking and transfers.
        assertEquals(listOf(volan,bkk,rail),select(volan,rail,bkk))
    }
    @Test fun sameLinesAtNearbyBoardingStopsAreOneJourneyDespiteDifferentNamesAndIds() {
        val best=journey("best",line="762",walk=100.0)
        val almost=journey("almost",line="762",minutes=31,walk=120.0,shift=.0003)
        val repeatedDeparture=journey("later",line="762",minutes=40,walk=100.0)
        assertEquals(listOf(best),select(almost,repeatedDeparture,best,best))
    }
    @Test fun routeVariantsWithDistantBoardingPointsAreNotMistakenForDuplicates() {
        val fast=journey("fast",line="762",walk=500.0)
        val lessWalking=journey("nearer",line="762",minutes=32,walk=100.0,shift=.004)
        assertEquals(listOf(lessWalking,fast),select(fast,lessWalking))
    }
    @Test fun atMostThreeUsefulOptionsAreShownWithoutPadding() {
        val best=journey("a")
        // Equal walking/transfers and only 60/120 s later: neither backup is outclassed.
        assertEquals(listOf("a", "b", "c"),select(best,journey("b",minutes=31),journey("c",minutes=32)).map { it.id })
        assertEquals(listOf(best),select(best))
        assertTrue(select().isEmpty())
    }
    @Test fun hugeDetoursAreDroppedEvenIfTheySaveOneTransferOrSomeWalking() {
        val fast=journey("fast",transfers=1,walk=600.0)
        val huge=journey("huge",minutes=90,walk=0.0)
        assertEquals(listOf(fast),select(huge,fast))
    }
    @Test fun lessWalkingLeadsWhenArrivalIsAlmostTheSame() {
        val fast=journey("fast",walk=500.0)
        val marginal=journey("marginal",minutes=31,walk=450.0)
        assertEquals(listOf(marginal,fast),select(fast,marginal))
    }
    @Test fun threeChoicesFavorFewerTransfersAndLessWalking() {
        val fastest=journey("fastest",transfers=2,walk=600.0)
        val almost=journey("almost",minutes=31,transfers=2,walk=350.0)
        val easy=journey("easy",minutes=33,transfers=0,walk=500.0)
        val walk=journey("walk",minutes=34,transfers=1,walk=100.0)
        val source=listOf(walk,almost,easy,fastest)
        // Costs: walk 2514 < easy 2850 < almost 3070 < fastest 3444.
        // Almost arrives earlier than both shown cards, so it earns the third slot.
        assertEquals(listOf(walk,easy,almost),TripPlanner.alternatives(source,ready))
        assertEquals(TripPlanner.alternatives(source,ready),TripPlanner.alternatives(source.reversed(),ready))
    }
    @Test fun aRestrictedSchoolServiceDoesNotEraseAnAvailablePublicJourney() {
        val restricted=journey("school",restricted=true)
        val public=journey("public",minutes=32,line="school")
        assertEquals(listOf(public),select(restricted,public))
    }
    @Test fun arrivalTimeTakesPriorityOverShorterRideDepartingMuchLater() {
        val early=journey("early",walk=100.0)
        val late=journey("late",minutes=90).let { trip -> trip.copy(start=trip.start.plusSeconds(3600),
            legs=trip.legs.map { it.copy(start=it.start.plusSeconds(3600)) }) }
        assertTrue(late.durationSeconds<early.durationSeconds)
        assertEquals(early,select(late,early).first())
    }
    @Test fun saturdayVolanResponseKeepsActualPublicBoardingStopsTimesAndCoachIdentity() {
        val text = javaClass.getResource("/planner/volan-weekend.json")!!.readText()
        val row = JSONObject(text).getJSONObject("data").getJSONObject("plan").getJSONArray("itineraries").getJSONObject(0)
        val from = PlannerEndpoint("from","Budapest, Kamaraerdő","",GeoPoint(47.434,18.975))
        val to = PlannerEndpoint("to","Biatorbágyi Innovatív Technikum","",GeoPoint(47.47959,18.8091914))
        val time = Instant.ofEpochMilli(row.getLong("startTime")).minusSeconds(60)
        val result = TripPlanner.parse(text,from,to,time,time)
        val journey = result.itineraries.single()
        val volan = journey.legs.single { it.route?.providerId == "volan" }
        assertEquals("762",volan.route!!.number)
        assertEquals("COACH",volan.mode)
        assertEquals("volan:hkir:hkir_V-R-200-762",volan.route!!.id)
        assertTrue(TripPlanner.feasible(journey.legs,time))
    }
    @Test fun waitingForBusDropsStrictlyWorseWalkingWhenArrivalIsTheSame() {
        val start = PlanPoint("A", GeoPoint(47.4,18.8))
        val end = PlanPoint("B", GeoPoint(47.5,18.8))
        val walking = TripItinerary("walk", ready, ready.plusSeconds(1800), listOf(
            PlanLeg("WALK", ready, ready.plusSeconds(1800), 1800.0, start, end, null, null, false, false, emptyList())))
        val bus = journey("bus", minutes=30, provider="homm")
        // Same arrival and transfers, but the bus saves 1800 s walking: even the
        // first backup is strictly worse and must not be displayed.
        assertEquals(listOf(bus),select(walking,bus))
        assertEquals(1800L,bus.totalSeconds(ready))
    }
    @Test fun comfortableRecommendationKeepsFastestAndRespectsDelayAndTransferLimits() {
        val fastest=journey("fast",walk=900.0)
        val wait=journey("wait",minutes=32,walk=100.0,provider="viabusz")
        assertEquals(listOf(wait,fastest),select(fastest,wait))
        assertEquals(listOf(wait,fastest),select(wait,fastest))
        val later=journey("later",minutes=33,walk=100.0)
        assertEquals(listOf(later,fastest),select(fastest,later))
        val extraTransfer=journey("transfer",minutes=31,transfers=1,walk=100.0)
        assertEquals(listOf(extraTransfer,fastest),select(fastest,extraTransfer))
        val school=journey("school",minutes=31,walk=100.0,restricted=true)
        assertEquals(fastest,select(fastest,school).first())
    }
    @Test fun busOnlySearchIncludesRegionalCoachAndCityBusAlongsideUnrestrictedTransitQuery() {
        assertTrue(TripPlanner.QUERY.contains("{mode:TRANSIT}"))
        assertTrue(TripPlanner.query(true).contains("{mode:COACH}"))
        assertTrue(TripPlanner.query(true).contains("{mode:BUS}"))
        assertFalse(TripPlanner.QUERY.contains("banned"))
    }
}
