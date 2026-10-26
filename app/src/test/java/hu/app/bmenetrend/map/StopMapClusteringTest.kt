package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.*
import org.junit.Assert.*
import org.junit.Test

class StopMapClusteringTest {
    private val bounds = MapBounds(18.0,47.0,20.0,48.0)
    private fun stop(id: String, name: String = "Budapest, Example tér", lon: Double = 19.0, parent: String? = null) =
        TransitStop(id,name,GeoPoint(47.5,lon),emptyList(),"bkk",parentStationId = parent)
    @Test fun overviewKeepsOppositePlatformsWithoutChangingTheirLocations() {
        val stops = listOf(stop("a"),stop("b",lon = 19.0005),stop("c",lon = 19.001))
        val groups = StopMapClustering.groups(stops,bounds,11.0)
        assertEquals(stops.map { listOf(it) },groups)
        assertEquals(19.0005,groups[1][0].position.lon,0.0)
    }
    @Test fun closeZoomRevealsEveryPlatform() {
        assertEquals(2,StopMapClustering.groups(listOf(stop("a"),stop("b")),bounds,14.5).size)
    }
    @Test fun sharedParentKeepsDifferentPlatformLabelsAndModesSeparate() {
        val stops = listOf(stop("a","Example bus",parent = "parent"),stop("b","Example tram",19.0005,"parent").copy(transportMode = "TRAM"))
        assertEquals(2,StopMapClustering.groups(stops,bounds,13.0).size)
    }
    @Test fun distantSameNameStopsAndNearbyUnrelatedStopsStaySeparate() {
        val stops = listOf(stop("a"),stop("b",lon = 19.01),stop("c","Other stop",19.0001))
        assertEquals(3,StopMapClustering.groups(stops,bounds,11.0).size)
    }
    @Test fun groupingIsStableAndCannotChainAcrossALongStreet() {
        val stops = (0..8).map { stop("p$it",lon = 19.0 + it * .002) }
        val groups = StopMapClustering.groups(stops,bounds,11.0)
        assertTrue(groups.size > 2)
        assertEquals(groups,StopMapClustering.groups(stops.reversed(),bounds,11.0))
        assertEquals(stops.size,groups.sumOf { it.size })
    }
    @Test fun missingBoundsAndOffscreenStopsDoNotProduceMarkers() {
        assertTrue(StopMapClustering.groups(listOf(stop("a")),null,11.0).isEmpty())
        assertTrue(StopMapClustering.groups(listOf(stop("a",lon = 30.0)),bounds,11.0).isEmpty())
    }
    @Test fun coLocatedLocalOperatorsShareAVolanMarkerButOppositePlatformsStaySeparate() {
        val local=stop("local").copy(providerId="viabusz",providerIds=listOf("viabusz","kanyargo"))
        val volan=stop("volan",lon=19.00002).copy(providerId="volan",providerIds=listOf("volan"))
        val homm=stop("homm",lon=19.00004).copy(providerId="homm",providerIds=listOf("homm"))
        val opposite=local.copy(id="opposite",position=GeoPoint(47.5,19.00025),name="Example tér (Ellenirány)")
        val groups=StopMapClustering.groups(listOf(local,volan,homm,opposite),bounds,16.0)
        assertEquals(2,groups.size)
        assertEquals(setOf(local,volan,homm),groups.first { local in it }.toSet())
        assertEquals(listOf(opposite),groups.first { opposite in it })
        assertEquals(setOf("viabusz","kanyargo","volan","homm"),groups.first { local in it }.flatMap { it.providerIds }.toSet())
    }
    @Test fun directionsPreventMergingOppositePlatformsEvenWithRoundedCoordinates() {
        val a=stop("a").copy(providerId="viabusz",providerIds=listOf("viabusz"))
        val b=stop("b").copy(providerId="volan",providerIds=listOf("volan"))
        assertFalse(StopMapClustering.samePlatform(a,b,mapOf("a" to listOf(0.0),"b" to listOf(180.0))))
        assertTrue(StopMapClustering.samePlatform(a,b,mapOf("a" to listOf(0.0),"b" to listOf(15.0))))
    }
    @Test fun ambiguousEquidistantPlatformsAreNotArbitrarilyMerged() {
        val a=stop("a").copy(providerId="viabusz",providerIds=listOf("viabusz"))
        val b=a.copy(id="b")
        val c=stop("c").copy(providerId="volan",providerIds=listOf("volan"))
        assertEquals(3,StopMapClustering.groups(listOf(a,b,c),bounds,16.0).size)
    }
    @Test fun busesAndTramsNeverShareAPlatformJustBecauseTheirCoordinatesMatch() {
        val bus=stop("bus").copy(providerId="volan",providerIds=listOf("volan"),transportMode="BUS")
        val tram=stop("tram").copy(transportMode="TRAM")
        assertFalse(StopMapClustering.samePlatform(bus,tram))
    }

    @Test fun biatorbagySourceOffsetsMergeRailwayStopButNotTheWrongVendelPlatform() {
        fun local(id: String,name: String,lat: Double,lon: Double)=TransitStop(id,name,GeoPoint(lat,lon),emptyList(),"viabusz")
        fun volan(id: String,name: String,lat: Double,lon: Double)=TransitStop(id,name,GeoPoint(lat,lon),emptyList(),"volan",transportMode="COACH")
        val railway=local("1","Vasútállomás",47.479821,18.833979)
        val railwayVolan=volan("emma:hkir:hkir_777140_3","Biatorbágy, vasútállomás",47.479964,18.83392)
        val vendel=local("26","Vendel tér",47.478029,18.829206)
        val opposite=local("69","Vendel tér (Ellenirány)",47.47816558,18.82939381)
        val vendelVolan=volan("emma:hkir:hkir_777142_5","Biatorbágy, Vendel tér",47.478171,18.829472)
        val groups=StopMapClustering.groups(listOf(railway,railwayVolan,vendel,opposite,vendelVolan),bounds,16.0)
        assertEquals(setOf(railway,railwayVolan),groups.first { railway in it }.toSet())
        assertEquals(setOf(opposite,vendelVolan),groups.first { opposite in it }.toSet())
        assertEquals(listOf(vendel),groups.first { vendel in it })
    }

    @Test fun highlightingALinePreservesAllMembersOfItsSharedPlatform() {
        val local=stop("local").copy(providerId="viabusz",providerIds=listOf("viabusz"))
        val volan=stop("volan",lon=19.00002).copy(providerId="volan",providerIds=listOf("volan"))
        val opposite=local.copy(id="opposite",position=GeoPoint(47.5,19.0005))
        val all=listOf(local,volan,opposite)
        val normal=StopMapClustering.groups(all,bounds,15.0).first { local in it }
        val highlighted=StopMapClustering.groups(all,bounds,15.0,highlightedStopIds=setOf(local.id)).single()
        assertEquals(normal,highlighted)
        assertEquals(setOf(local,volan),highlighted.toSet())
        assertFalse(opposite in highlighted)
    }

}
