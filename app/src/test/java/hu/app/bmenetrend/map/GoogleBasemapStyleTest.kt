package hu.app.bmenetrend.map

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class GoogleBasemapStyleTest {
    @Test fun newlyRevealedLandCategoriesKeepTheCharcoalBase() {
        val styles = JSONArray(GoogleBasemapStyle.json(true))
        val rules = (0 until styles.length()).map(styles::getJSONObject)
        for (feature in listOf("landscape", "landscape.man_made", "landscape.natural",
            "landscape.natural.landcover", "landscape.natural.terrain")) {
            val geometry = rules.last { it.optString("featureType") == feature && it.optString("elementType") == "geometry" }
            assertEquals("$feature must not turn the map green as Google reveals it at a new zoom",
                "#1f1f1f", geometry.getJSONArray("stylers").getJSONObject(0).getString("color"))
        }
        val parks = rules.last { it.optString("featureType") == "poi.park" }
        assertEquals("#284d36", parks.getJSONArray("stylers").getJSONObject(0).getString("color"))
    }
}
