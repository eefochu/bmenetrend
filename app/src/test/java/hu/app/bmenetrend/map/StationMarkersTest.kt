package hu.app.bmenetrend.map

import hu.app.bmenetrend.data.GeoPoint
import hu.app.bmenetrend.data.MapBounds
import hu.app.bmenetrend.data.TransitRoute
import hu.app.bmenetrend.data.TransitStop
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class StationMarkersTest {
    private fun stop(id: String, lat: Double, lon: Double, mode: String? = null, routes: List<String> = emptyList(),
                     name: String = "Stop $id\"quoted\"") =
        TransitStop(id, name, GeoPoint(lat, lon), routes, providerId = "bkk", transportMode = mode)

    private fun scene(vararg stops: TransitStop, offsets: Map<String, StopMarkerLayout.Offset> = emptyMap()): StationScene {
        val groups = stops.map { StationGroup(stationAnchor(listOf(it)), listOf(it)) }
        return StationScene(groups, StationChunkIndex(groups) { it.anchor.position }, offsets)
    }

    private fun build(scene: StationScene, region: MapBounds?, focused: Boolean = false, highlighted: Set<String> = emptySet(),
                      bearings: Map<String, List<Double>> = emptyMap(), routes: Map<String, TransitRoute> = emptyMap()) =
        StationMarkers.build(scene, region, focused, highlighted, bearings, routes, dark = false, routeColor = 0,
            imageName = { it.key })

    private fun features(data: StationMarkers.Data) = JSONObject(requireNotNull(data.json)).getJSONArray("features").let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }
    }

    private val budapest = MapBounds(18.9, 47.4, 19.2, 47.6)

    @Test fun railIsNationwideButRoadStopsOnlyInsideRegion() {
        val data = build(scene(
            stop("road-in", 47.5, 19.05),
            stop("road-out", 46.25, 20.15),
            stop("rail-out", 46.25, 20.14, mode = "RAIL"),
        ), budapest)
        assertEquals(setOf("road-in", "rail-out"), data.markers.keys)
        assertEquals(setOf("road-in", "rail-out"), features(data).map { it.getJSONObject("properties").getString("id") }.toSet())
    }

    @Test fun withoutRegionOnlyRailStationsAreUploaded() {
        val data = build(scene(stop("road", 47.5, 19.05), stop("rail", 47.5, 19.06, mode = "RAIL")), region = null)
        assertEquals(setOf("rail"), data.markers.keys)
    }

    @Test fun focusedLineShowsItsStopsEverywhereRegardlessOfRegion() {
        val data = build(scene(stop("far", 46.25, 20.15), stop("near", 47.5, 19.05)), budapest,
            focused = true, highlighted = setOf("far"))
        assertEquals(setOf("far"), data.markers.keys)
        assertTrue(data.focused)
    }

    @Test fun jsonIsValidEscapedAndCarriesMarkerProperties() {
        val offset = StopMarkerLayout.Offset(3.0, -4.0)
        val data = build(scene(stop("a\"b\\c", 47.5, 19.05), offsets = mapOf("a\"b\\c" to offset)), budapest)
        val feature = features(data).single()
        val properties = feature.getJSONObject("properties")
        assertEquals("a\"b\\c", properties.getString("id"))
        assertFalse(properties.getBoolean("rail"))
        assertEquals(3.0, properties.getJSONArray("offset").getDouble(0), 1e-9)
        assertEquals(-4.0, properties.getJSONArray("offset").getDouble(1), 1e-9)
        assertTrue(properties.getString("icon") in data.sprites.keys)
        val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
        assertEquals(19.05, coordinates.getDouble(0), 1e-9)
        assertEquals(47.5, coordinates.getDouble(1), 1e-9)
    }

    @Test fun arrowOffsetIsPreRotatedSoTheRotatedIconLandsOnTheDisplacedDot() {
        val offset = StopMarkerLayout.Offset(6.0, 2.0)
        val data = build(scene(stop("s", 47.5, 19.05), offsets = mapOf("s" to offset)), budapest,
            bearings = mapOf("s" to listOf(90.0)))
        val properties = features(data).single().getJSONObject("properties")
        assertEquals(90, properties.getInt("a0"))
        assertFalse(properties.has("a1"))
        // MapLibre rotates icon-offset by icon-rotate (clockwise): rotating it back must give the dot's offset.
        val rotated = properties.getJSONArray("o0")
        val angle = Math.toRadians(90.0)
        val x = rotated.getDouble(0) * cos(angle) - rotated.getDouble(1) * sin(angle)
        val y = rotated.getDouble(0) * sin(angle) + rotated.getDouble(1) * cos(angle)
        assertEquals(offset.x, x, 1e-9)
        assertEquals(offset.y, y, 1e-9)
    }

    @Test fun directionsKeepTheOldMarkerRule() {
        val members = listOf(stop("a", 47.5, 19.05), stop("b", 47.5, 19.05))
        assertEquals(listOf(0, 90), StationMarkers.directions(members, mapOf("a" to listOf(1.0), "b" to listOf(91.0))))
        // More than two distinct headings: no arrows, as before.
        assertEquals(emptyList<Int>(), StationMarkers.directions(members, mapOf("a" to listOf(0.0, 90.0), "b" to listOf(180.0))))
    }

    @Test fun anchorLookupCoversEveryMember() {
        val first = stop("p1", 47.5, 19.05); val second = stop("p2", 47.5001, 19.0501)
        val group = StationGroup(stationAnchor(listOf(first, second)), listOf(first, second))
        val data = build(StationScene(listOf(group), StationChunkIndex(listOf(group)) { it.anchor.position }, emptyMap()), budapest)
        assertEquals("p1", data.anchorByStop["p1"])
        assertEquals("p1", data.anchorByStop["p2"])
        assertEquals("p1", data.markers.getValue("p1").stop.id)
    }

    @Test fun unrelatedRouteChangesReuseUploadedMarkers() {
        val used = TransitRoute("r1", "1", "Line 1", emptyList(), providerId = "bkk", transportMode = "BUS")
        val unrelated = TransitRoute("r9", "9", "Line 9", emptyList(), providerId = "bkk", transportMode = "BUS")
        val scene = scene(stop("s", 47.5, 19.05, routes = listOf("r1")))
        val routes = mapOf("r1" to used)
        val data = build(scene, budapest, routes = routes)
        fun matches(routes: Map<String, TransitRoute>, region: MapBounds? = budapest) =
            data.matches(scene, region, false, emptySet(), emptyMap(), routes, dark = false, routeColor = 0)
        assertTrue(matches(routes + ("r9" to unrelated)))
        assertTrue(matches(mapOf("r1" to used.copy(directions = emptyList(), sourceUrl = "x"))))
        assertFalse(matches(mapOf("r1" to used.copy(transportMode = "TRAM"))))
        assertFalse(matches(emptyMap()))
        assertFalse(matches(routes, region = MapBounds(0.0, 0.0, 1.0, 1.0)))
    }

    @Test fun regionIsStableWhilePanningInsideAndMovesNearItsEdge() {
        val view = MapBounds(19.0, 47.45, 19.1, 47.55)
        assertNull(StationMarkers.nextRegion(null, view, zoom = 10.0))
        val region = requireNotNull(StationMarkers.nextRegion(null, view, zoom = 12.5))
        assertTrue(region.east - region.west >= StationMarkers.REGION_MIN_LON_SPAN - 1e-9)
        assertTrue(region.north - region.south >= StationMarkers.REGION_MIN_LAT_SPAN - 1e-9)
        assertTrue(region.west < view.west && region.east > view.east && region.south < view.south && region.north > view.north)
        // A modest pan keeps the same instance: no rebuild.
        assertSame(region, StationMarkers.nextRegion(region, MapBounds(19.05, 47.48, 19.15, 47.58), 12.5))
        // Zooming out below the road-stop zoom keeps it too.
        assertSame(region, StationMarkers.nextRegion(region, MapBounds(17.0, 46.0, 21.0, 49.0), 9.0))
        // Nearing the edge re-centres the region on the view.
        val far = MapBounds(19.2, 47.45, 19.3, 47.55)
        val moved = requireNotNull(StationMarkers.nextRegion(region, far, 12.5))
        assertNotSame(region, moved)
        assertEquals(19.25, (moved.west + moved.east) / 2, 1e-9)
    }
}
