package hu.viabusz.app.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

@Immutable
data class PlaceResult(val id: String, val name: String, val address: String, val position: GeoPoint, val bounds: MapBounds? = null)

object PlaceSearch {
    private val roman = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX", "XXI", "XXII", "XXIII")
    private val DISTRICT_REGEX_1 = Regex("""^(\d{1,2}|[ivx]+)\.?\s*(?:kerulet|ker\.?|district)$""")
    private val DISTRICT_REGEX_2 = Regex("""^district\s+(\d{1,2}|[ivx]+)\.?$""")

    fun district(text: String): Int? {
        val normalized = TransitSearch.normalize(text).removePrefix("budapest").trim()
        val match = DISTRICT_REGEX_1.matchEntire(normalized)
            ?: DISTRICT_REGEX_2.matchEntire(normalized) ?: return null
        val number = match.groupValues[1].toIntOrNull() ?: roman.indexOf(match.groupValues[1].uppercase()).let { if (it < 0) 0 else it + 1 }
        return number.takeIf { it in 1..23 }
    }
    fun query(text: String): String = district(text)?.let { "Budapest ${roman[it - 1]}. kerület" } ?: text.trim()

    fun parse(text: String, query: String): List<PlaceResult> {
        val rows = JSONObject(text).getJSONArray("features")
        val requestedDistrict = district(query)
        return (0 until rows.length()).mapNotNull { i ->
            val feature = rows.optJSONObject(i) ?: return@mapNotNull null
            val p = feature.optJSONObject("properties") ?: return@mapNotNull null
            fun value(key: String) = p.optString(key).takeUnless { it.isBlank() || it == "null" }
            if (value("countrycode")?.uppercase() != "HU") return@mapNotNull null
            val geometry = feature.optJSONObject("geometry") ?: return@mapNotNull null
            if (geometry.optString("type") != "Point") return@mapNotNull null
            val coordinates = geometry.optJSONArray("coordinates") ?: return@mapNotNull null
            val lon = coordinates.optDouble(0); val lat = coordinates.optDouble(1)
            if (!lat.isFinite() || !lon.isFinite() || lat !in 45.7..48.6 || lon !in 16.1..22.9) return@mapNotNull null
            val name = value("name") ?: listOfNotNull(value("street"), value("housenumber")).joinToString(" ").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (requestedDistrict != null && value("type") == "district" && district(name) != requestedDistrict) return@mapNotNull null
            val extent = p.optJSONArray("extent")
            val bounds = extent?.takeIf { it.length() == 4 }?.let {
                val west = it.optDouble(0); val north = it.optDouble(1); val east = it.optDouble(2); val south = it.optDouble(3)
                MapBounds(west, south, east, north).takeIf { west.isFinite() && south.isFinite() && east.isFinite() && north.isFinite() && west < east && south < north && west >= 16.1 && east <= 22.9 && south >= 45.7 && north <= 48.6 }
            }
            PlaceResult("${value("osm_type") ?: "point"}:${value("osm_id") ?: "$lat,$lon"}", name,
                listOfNotNull(listOfNotNull(value("street"), value("housenumber")).joinToString(" ").takeIf { it.isNotBlank() && it != name },
                    value("district")?.takeIf { it != name }, value("city")?.takeIf { it != name },
                    value("county")?.takeIf { value("city") == null && it != name }, value("postcode"))
                    .distinct().joinToString(" · "), GeoPoint(lat, lon), bounds)
        }.distinctBy { it.id }.distinctBy { TransitSearch.normalize(it.name) to TransitSearch.normalize(it.address) }
            .sortedByDescending { requestedDistrict != null && district(it.name) == requestedDistrict }
    }
}
