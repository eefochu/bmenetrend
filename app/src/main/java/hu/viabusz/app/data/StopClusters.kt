package hu.viabusz.app.data

import kotlin.math.*

/** Geographic distances only. Names, proximity and shared parents never merge source stops. */
object StopClusters {
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat = Math.toRadians(b.lat - a.lat)
        val lon = Math.toRadians(b.lon - a.lon)
        val haversine = sin(lat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(lon / 2).pow(2)
        return 6_371_000 * 2 * atan2(sqrt(haversine.coerceIn(0.0, 1.0)), sqrt((1 - haversine).coerceIn(0.0, 1.0)))
    }

}
