package hu.app.bmenetrend.data

import hu.app.bmenetrend.map.MetroLineStyle
import hu.app.bmenetrend.map.StopMarkerColors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OnlineMetroMapTest {
    private fun stop(id: String, name: String) = JSONObject().put("gtfsId", "BKK:$id")
        .put("name", name).put("lat", 47.497).put("lon", 19.055).put("vehicleMode", "SUBWAY")
        .put("locationType", "STOP")

    private fun metro(number: Int, vararg stops: JSONObject) = JSONObject()
        .put("gtfsId", "BKK:5${number}00").put("shortName", "M$number").put("longName", "Metro $number")
        .put("mode", "SUBWAY").put("stops", JSONArray(stops.toList()))

    @Test fun nationalIndexRetainsAllMetroLinesAndInterchangeColorsWithoutLiveVehicles() {
        val deak = stop("F00955", "Deák Ferenc tér")
        val kelenfold = stop("F02000", "Kelenföld vasútállomás")
        val parent = stop("parent", "Deák Ferenc tér").put("locationType", "STATION").put("vehicleMode", JSONObject.NULL)
        deak.put("parentStation", JSONObject().put("gtfsId", "BKK:parent"))
        val response = JSONObject().put("data", JSONObject()
            .put("stops", JSONArray().put(deak).put(kelenfold).put(parent))
            .put("metroRoutes", JSONArray().put(metro(1, deak)).put(metro(2, deak))
                .put(metro(3, deak)).put(metro(4, kelenfold)))).toString()
        val index = EmmaStopsParser.index(response)
        val routes = index.routes.associateBy { it.id }
        assertEquals(setOf("M1", "M2", "M3", "M4"), index.routes.map { it.number }.toSet())
        val interchange = index.stops.single { it.id == "emma:BKK:F00955" }
        val expected = (1..3).map { MetroLineStyle.forLine("M$it", "SUBWAY")!!.color }.sorted()
        assertEquals(expected, StopMarkerColors.palette(listOf(interchange), routes))
        val station = index.stops.single { it.id == "emma:BKK:parent" }
        assertEquals("SUBWAY", station.transportMode)
        assertEquals(interchange.routeIds.toSet(), station.routeIds.toSet())
        assertEquals(expected, StopMarkerColors.palette(listOf(station), routes))
        assertEquals(listOf(MetroLineStyle.forLine("M4", "SUBWAY")!!.color),
            StopMarkerColors.palette(listOf(index.stops.single { it.id == "emma:BKK:F02000" }), routes))
        assertEquals(index.stops.size, index.stops.distinctBy { it.id }.size)
    }

    @Test fun stationQueriesRequestTheLineMembershipUsedByTheSharedMapRenderer() {
        assertTrue("Early viewport dots also need serving line colors", EmmaStopsParser.MAP_RENDER_QUERY.contains("routes {"))
        assertTrue("National loading must fetch metro membership without live vehicles", EmmaStopsParser.INDEX_QUERY.contains("metroRoutes:routes(transportModes:[SUBWAY])"))
    }

    @Test fun viewportAndNationalIndexUseExactPlatformLinesAtAnInterchange() {
        val m1 = stop("m1", "Deák Ferenc tér")
        val m2 = stop("m2", "Deák Ferenc tér")
        val line1 = metro(1, m1)
        val line2 = metro(2, m2)
        val metroRoutes = JSONArray().put(line1).put(line2)
        val generic = JSONObject(m1.toString()).put("routes", JSONArray().put(line1).put(line2))
        val viewport = EmmaStopsParser.area(JSONObject().put("data", JSONObject()
            .put("stopsByBbox", JSONArray().put(generic)).put("metroRoutes", metroRoutes)).toString())
        val national = EmmaStopsParser.index(JSONObject().put("data", JSONObject()
            .put("stops", JSONArray().put(m1).put(m2)).put("metroRoutes", metroRoutes)).toString())
        assertEquals(listOf("bkk:BKK:5100"), viewport.stops.single().routeIds)
        assertEquals("The viewport must not add out-of-area metro stations", 1, viewport.stops.size)
        assertEquals(StopMarkerColors.palette(listOf(national.stops.first()), national.routes.associateBy { it.id }),
            StopMarkerColors.palette(viewport.stops, viewport.routes.associateBy { it.id }))
    }

    @Test fun liveNationalResponseIncludesAllMetroPlatformColors() {
        val path = System.getenv("ONLINE_METRO_FIXTURE")
        org.junit.Assume.assumeTrue("Opt-in live response replay", path != null)
        val catalogue = EmmaStopsParser.index(java.io.File(path!!).readText())
        assertEquals(setOf("M1", "M2", "M3", "M4"), catalogue.routes.map { it.number }.toSet())
        val routes = catalogue.routes.associateBy { it.id }
        val metroStops = catalogue.stops.filter { stop -> stop.routeIds.any { it in routes } }
        assertTrue("Every live metro platform must retain membership", metroStops.size >= 103)
        for (stop in metroStops) {
            val expected = stop.routeIds.mapNotNull { routes[it] }.map { MetroLineStyle.forLine(it.number, "SUBWAY")!!.color }.distinct().sorted()
            assertEquals("Metro palette for ${stop.id}", expected, StopMarkerColors.palette(listOf(stop), routes))
        }
        System.getenv("ONLINE_METRO_AREA_FIXTURE")?.let { areaPath ->
            val area = EmmaStopsParser.area(java.io.File(areaPath).readText())
            val platforms = area.stops.filter { it.transportMode == "SUBWAY" }
            assertTrue("Live viewport must contain metro platforms", platforms.isNotEmpty())
            for (platform in platforms) {
                val indexed = metroStops.single { it.id == platform.id }
                assertEquals("Exact line membership for ${platform.id}", indexed.routeIds.toSet(), platform.routeIds.toSet())
                assertEquals(StopMarkerColors.palette(listOf(indexed), routes),
                    StopMarkerColors.palette(listOf(platform), area.routes.associateBy { it.id }))
            }
            println("LIVE_METRO_VIEWPORT_PARITY platforms=${platforms.size}")
        }
        println("LIVE_METRO_PARITY stations=${metroStops.size} totalStops=${catalogue.stops.size} lines=${routes.size}")
    }
}
