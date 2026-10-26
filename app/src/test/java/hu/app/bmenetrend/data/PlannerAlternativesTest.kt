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

    @Test fun inferiorOperatorsNoLongerReservePlaces() {
        val best=journey("best",provider="mav")
        val result=select(best,journey("volan",minutes=40,provider="volan"),
            journey("via",minutes=45,provider="viabusz"),journey("homm",minutes=50,provider="homm"))
        assertEquals(listOf(best),result)
    }
    @Test fun bestBusOptionsSurviveWhenTheyActuallySaveWalkingOrTransfers() {
        val rail=journey("rail",transfers=1,walk=600.0,provider="mav")
        val bkk=journey("bkk",minutes=31,transfers=1,walk=300.0)
        val volan=journey("volan",minutes=33,walk=100.0,provider="volan")
        assertEquals(listOf(rail,bkk,volan),select(volan,rail,bkk))
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
        assertEquals(listOf(fast,lessWalking),select(fast,lessWalking))
    }
    @Test fun fewerThanThreeUsefulOptionsAreNotPadded() {
        val best=journey("a")
        assertEquals(listOf(best),select(best,journey("b",minutes=31),journey("c",minutes=32)))
        assertTrue(select().isEmpty())
    }
    @Test fun hugeDetoursAreDroppedEvenIfTheySaveOneTransferOrSomeWalking() {
        val fast=journey("fast",transfers=1,walk=600.0)
        val huge=journey("huge",minutes=90,walk=0.0)
        assertEquals(listOf(fast),select(huge,fast))
    }
    @Test fun tinyWalkingSavingsDoNotJustifyLaterArrival() {
        val fast=journey("fast",walk=500.0)
        val marginal=journey("marginal",minutes=31,walk=450.0)
        assertEquals(listOf(fast),select(fast,marginal))
    }
    @Test fun threeChoicesCoverFastestEasiestAndLeastWalkingRatherThanThreeMinorVariants() {
        val fastest=journey("fastest",transfers=2,walk=600.0)
        val almost=journey("almost",minutes=31,transfers=2,walk=350.0)
        val easy=journey("easy",minutes=33,transfers=0,walk=500.0)
        val walk=journey("walk",minutes=34,transfers=1,walk=100.0)
        val source=listOf(walk,almost,easy,fastest)
        assertEquals(listOf(fastest,easy,walk),TripPlanner.alternatives(source,ready))
        assertEquals(TripPlanner.alternatives(source,ready),TripPlanner.alternatives(source.reversed(),ready))
    }
    @Test fun aRestrictedSchoolServiceDoesNotEraseAnAvailablePublicJourney() {
        val restricted=journey("school",restricted=true)
        val public=journey("public",minutes=32,line="school")
        assertEquals(listOf(restricted,public),select(restricted,public))
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
    @Test fun busOnlySearchIncludesRegionalCoachAndCityBusAlongsideUnrestrictedTransitQuery() {
        assertTrue(TripPlanner.QUERY.contains("{mode:TRANSIT}"))
        assertTrue(TripPlanner.query(true).contains("{mode:COACH}"))
        assertTrue(TripPlanner.query(true).contains("{mode:BUS}"))
        assertFalse(TripPlanner.QUERY.contains("banned"))
    }
}
