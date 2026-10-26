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

    @Test fun cityWideLatitudeStripsCannotMakeDistantOperatorsAPlatformCandidate() {
        val local=(0..500).map { i -> stop("local-$i",lon=18.5+i*.002).copy(providerId="viabusz",providerIds=listOf("viabusz")) }
        val volan=local.map { it.copy(id="v-${it.id}",providerId="volan",providerIds=listOf("volan"),position=it.position.copy(lon=it.position.lon+.00002)) }
        val groups=StopMapClustering.groups(local+volan,bounds,12.0)
        assertEquals(501,groups.size)
        assertTrue(groups.all { it.size==2 })
        assertEquals(groups,StopMapClustering.groups((local+volan).reversed(),bounds,12.0))
    }
    @Test fun cellsKeepThirtyMetreNeighboursAcrossBothGridBoundaries() {
        val a=stop("a",lon=19.000401).copy(position=GeoPoint(47.500191,19.000401),providerId="viabusz",providerIds=listOf("viabusz"))
        val b=stop("b",lon=19.00065).copy(position=GeoPoint(47.50031,19.00065),providerId="volan",providerIds=listOf("volan"))
        assertTrue(StopClusters.distanceMeters(a.position,b.position)<30)
        assertEquals(1,StopMapClustering.groups(listOf(a,b),bounds,16.0).size)
    }


    @Test fun vasutUtcaViabuszAndVolanPlatformsMergeByCorridorDirection() {
        fun via(id: String,name: String,lat: Double,lon: Double)=TransitStop(id,name,GeoPoint(lat,lon),emptyList(),"viabusz",providerIds=listOf("viabusz"))
        fun volan(id: String,name: String,lat: Double,lon: Double)=TransitStop(id,name,GeoPoint(lat,lon),emptyList(),"volan",providerIds=listOf("volan"),transportMode="COACH")

        val via5 = via("5","Vasút utca",47.471394,18.824603)
        val via81 = via("81","Vasút utca (Ellenirány)",47.47140816,18.82467514)
        val volan1 = volan("emma:hkir:hkir_777139_1","Biatorbágy, Vasút utca",47.471243,18.825238)
        val volan2 = volan("emma:hkir:hkir_777139_2","Biatorbágy, Vasút utca",47.471223,18.825772)

        val bearings = mapOf(
            "5" to listOf(232.0),
            "81" to listOf(358.0),
            "emma:hkir:hkir_777139_1" to listOf(230.0),
            "emma:hkir:hkir_777139_2" to listOf(355.0),
        )

        assertTrue(StopMapClustering.samePlatform(via5, volan1, bearings))
        assertTrue(StopMapClustering.samePlatform(via81, volan2, bearings))
        assertFalse(StopMapClustering.samePlatform(via5, volan2, bearings))
        assertFalse(StopMapClustering.samePlatform(via81, volan1, bearings))

        val groups = StopMapClustering.groups(listOf(via5, via81, volan1, volan2), bounds, 16.0, bearings)
        assertEquals(2, groups.size)
        val g1 = groups.first { via5 in it }
        val g2 = groups.first { via81 in it }
        assertEquals(setOf(via5, volan1), g1.toSet())
        assertEquals(setOf(via81, volan2), g2.toSet())
    }
}
