package hu.app.bmenetrend.map

import android.content.ComponentCallbacks2
import org.junit.Assert.*
import org.junit.Test

class MapMemoryPolicyTest {
    @Test fun visibilityChangesDoNotEvictWarmTilesButRealMemoryPressureDoes() {
        assertFalse(MapMemoryPolicy.shouldTrim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN))
        assertFalse(MapMemoryPolicy.shouldTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE))
        assertTrue(MapMemoryPolicy.shouldTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))
        assertTrue(MapMemoryPolicy.shouldTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL))
        assertTrue(MapMemoryPolicy.shouldTrim(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND))
        assertTrue(MapMemoryPolicy.shouldTrim(ComponentCallbacks2.TRIM_MEMORY_MODERATE))
        assertTrue(MapMemoryPolicy.shouldTrim(ComponentCallbacks2.TRIM_MEMORY_COMPLETE))
    }
}
