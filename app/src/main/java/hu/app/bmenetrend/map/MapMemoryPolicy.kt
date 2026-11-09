package hu.app.bmenetrend.map

import android.content.ComponentCallbacks2

/** UI_HIDDEN is a visibility notification, not a request to discard warm map tiles. */
internal object MapMemoryPolicy {
    fun shouldTrim(level: Int): Boolean =
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
}
