package hu.app.bmenetrend.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PlaceSearchTest {
    private fun feature(id: Int = 221998, name: String = "XI. kerület", lon: Double = 19.0274649, lat: Double = 47.4670427) = JSONObject()
        .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(lon, lat))))
        .put("properties", JSONObject().put("osm_type", "R").put("osm_id", id).put("name", name).put("city", "Budapest")
            .put("countrycode", "HU").put("type", "district").put("extent", JSONArray(listOf(18.9699958, 47.4888863, 19.0675055, 47.4277552))))
    private fun response(vararg features: JSONObject) = JSONObject().put("features", JSONArray(features.toList())).toString()

    @Test fun arabicRomanAndHungarianDistrictQueriesResolveToSamePlaceQuery() {
        listOf("11.kerület", "11. kerulet", "XI. kerület", "Budapest 11. kerület", "district 11", "11 district").forEach {
            assertEquals("Budapest XI. kerület", PlaceSearch.query(it))
        }
        assertEquals("Budapest XXIII. kerület", PlaceSearch.query("23. kerület"))
        assertEquals("24. kerület", PlaceSearch.query("24. kerület"))
        assertEquals("Biatorbágy", PlaceSearch.query(" Biatorbágy "))
    }

    @Test fun geocoderCoordinatesAndExtentUseCorrectAxisAndNorthSouthOrder() {
        val place = PlaceSearch.parse(response(feature()), "11.kerület").single()
        assertEquals("R:221998", place.id)
        assertEquals(47.4670427, place.position.lat, .0000001)
        assertEquals(19.0274649, place.position.lon, .0000001)
        assertEquals(MapBounds(18.9699958, 47.4277552, 19.0675055, 47.4888863), place.bounds)
        assertEquals("Budapest", place.address)
    }

    @Test fun duplicateWrongCountryAndInvalidCoordinatesAreExcluded() {
        val wrongCountry = feature(2).apply { getJSONObject("properties").put("countrycode", "AT") }
        val invalid = feature(3, lon = 0.0, lat = 0.0)
        assertEquals(1, PlaceSearch.parse(response(feature(), feature(), feature(99), wrongCountry, invalid), "Budapest").size)
    }

    @Test fun districtSearchNeverSubstitutesAnotherDistrictAndBadExtentDoesNotHideValidPlace() {
        val invalidExtent = feature().apply { getJSONObject("properties").put("extent", JSONArray(listOf(0, 1, 0, 1))) }
        val result = PlaceSearch.parse(response(feature(3, "XIV. kerület"), invalidExtent), "11. kerület")
        assertEquals(1, result.size)
        assertNull(result.single().bounds)
    }

    @Test fun liveVehiclesMatchRegistrationAndRespectExactLineMode() {
        val now = Instant.now()
        val bus = Vehicle("bkk:bus", GeoPoint(47.5, 19.0), now, routeNumber = "1", providerId = "bkk", registration = "ABC-123", transportMode = "BUS")
        val tram = bus.copy(id = "bkk:tram", registration = "TRAM-77", transportMode = "TRAM")
        val otherTram = tram.copy(id = "bkk:tram14", routeNumber = "14")
        assertEquals(listOf(bus), TransitSearch.vehicles(listOf(bus, tram), TransitSearch.query("ABC123")))
        assertEquals(listOf(tram), TransitSearch.vehicles(listOf(bus, tram, otherTram), TransitSearch.query("1 villamos")))
    }
}
