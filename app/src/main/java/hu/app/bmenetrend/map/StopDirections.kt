package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.*
import kotlin.math.*

/** Travel away from a platform, from ordered stops/shape geometry; never guess from its name. */
object StopDirections {
    fun difference(a: Double,b: Double)=abs((a-b+540)%360-180)
    fun bearing(a: GeoPoint,b: GeoPoint): Double {
        val lat1=Math.toRadians(a.lat); val lat2=Math.toRadians(b.lat); val lon=Math.toRadians(b.lon-a.lon)
        return (Math.toDegrees(atan2(sin(lon)*cos(lat2),cos(lat1)*sin(lat2)-sin(lat1)*cos(lat2)*cos(lon)))+360)%360
    }
    fun ahead(point: GeoPoint,bearing: Double,meters: Double=25.0): GeoPoint {
        val angle=Math.toRadians(bearing)
        return GeoPoint(point.lat+cos(angle)*meters/111195,point.lon+sin(angle)*meters/(111195*cos(Math.toRadians(point.lat))))
    }
    fun collect(routes: List<TransitRoute>,stops: List<TransitStop>,details: Collection<LineDetails>,journey: VehicleJourney?=null): Map<String,List<Double>> {
        val localIds=routes.flatMap { it.directions }.flatMap { it.stopIds }.toSet()
        val byId=stops.filter { it.id in localIds }.associateBy { it.id }
        val samples=linkedMapOf<String,MutableList<Double>>()
        fun add(sequence: List<TransitStop>,geometry: List<GeoPoint>) {
            var cursor=0
            for(i in 0 until sequence.lastIndex) {
                val stop=sequence[i]
                val next=sequence.drop(i+1).firstOrNull { StopClusters.distanceMeters(stop.position,it.position)>8 } ?: continue
                var target=next.position
                if(geometry.size>=2) {
                    val index=(cursor until geometry.size).minByOrNull { StopClusters.distanceMeters(stop.position,geometry[it]) } ?: 0
                    cursor=index
                    target=geometry.drop(index+1).firstOrNull { StopClusters.distanceMeters(stop.position,it)>12 } ?: target
                }
                val heading=bearing(stop.position,target)
                val headings=samples.getOrPut(stop.id) { mutableListOf() }
                if(headings.none { difference(it,heading)<35 }) headings.add(heading)
            }
        }
        routes.forEach { route -> route.directions.forEach { direction -> add(direction.stopIds.mapNotNull(byId::get),emptyList()) } }
        details.forEach { line -> line.patterns.forEach { add(it.stops,it.geometry) } }
        journey?.let { add(it.stops,it.geometry) }
        // A shared terminal with many diverging routes has no single platform direction.
        return samples.mapValues { (_,values) -> values.sorted().takeIf { it.size<=2 }.orEmpty() }
    }

    data class Point(val x: Double,val y: Double)
    fun triangle(x: Double,y: Double,radius: Double,dx: Double,dy: Double,density: Double): List<Point> {
        val distance=hypot(dx,dy)
        if(distance<.001) return emptyList()
        val ux=dx/distance; val uy=dy/distance
        val base=radius-2*density; val tip=radius+7*density; val half=4*density
        return listOf(Point(x+ux*tip,y+uy*tip),Point(x+ux*base-uy*half,y+uy*base+ux*half),
            Point(x+ux*base+uy*half,y+uy*base-ux*half))
    }
}
