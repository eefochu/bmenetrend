package hu.app.bmenetrend.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.offline.NetworkAccessPolicy
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

/**
 * [PlacesRepository.cached] is the search-as-you-type fast path: it may only read the
 * in-memory hit and must never touch disk or the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PlacesRepositoryCacheTest {

    private fun seedDiskCache(context: Application, raw: String): List<PlaceResult> {
        val query = PlaceSearch.query(raw)
        val key = MessageDigest.getInstance("SHA-256")
            .digest(TransitSearch.normalize(query).toByteArray()).joinToString("") { "%02x".format(it) }
        val directory = File(context.cacheDir, "places-v1").apply { mkdirs() }
        val feature = JSONObject()
            .put("properties", JSONObject().put("countrycode", "HU").put("name", "Keleti pályaudvar")
                .put("osm_type", "node").put("osm_id", "42").put("city", "Budapest"))
            .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(19.083).put(47.500)))
        val payload = JSONObject().put("features", JSONArray().put(feature)).toString()
        File(directory, "$key.json").writeText(payload)
        return PlaceSearch.parse(payload, query)
    }

    @Test
    fun cachedIsImmediateOnlyAfterSearchHasFilledMemory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        NetworkAccessPolicy.setOffline(context, false)
        File(context.cacheDir, "places-v1").deleteRecursively()
        val expected = seedDiskCache(context, "Keleti")
        val repository = PlacesRepository(context)
        try {
            // Seeded disk data is not an immediate hit: cached() must not perform disk IO.
            assertNull(repository.cached("Keleti"))
            val searched = repository.search("Keleti")
            assertEquals(expected, searched)
            assertEquals("cached() must expose the memory entry without another search",
                expected, repository.cached("Keleti"))
            assertEquals(expected, repository.cached("  Keleti  "))
            assertNull(repository.cached("Keleti pályaudvar"))
        } finally {
            File(context.cacheDir, "places-v1").deleteRecursively()
        }
    }

    @Test
    fun cachedReturnsNullForBlankOrShortQueriesWithoutTouchingMemory() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repository = PlacesRepository(context)
        assertNull(repository.cached(""))
        assertNull(repository.cached("K"))
        assertNotNull(repository)
    }
}
