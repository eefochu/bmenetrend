package hu.app.bmenetrend.plugins

import androidx.compose.runtime.Composable

/**
 * Stand-in used only when the optional mav-tickets folder is absent (see app/build.gradle.kts).
 * It mirrors mav-tickets/src/main/java/hu/app/bmenetrend/plugins/TicketsPlugin.kt; keep the
 * public signatures identical. With it, the Tickets tab is hidden.
 */
object TicketsPlugin {
    @Composable
    fun host(): TicketsHost? = null
}

class TicketsHost private constructor() {
    @Composable
    fun isViewingTicket(): Boolean = false

    @Composable
    fun Screen() = Unit
}
