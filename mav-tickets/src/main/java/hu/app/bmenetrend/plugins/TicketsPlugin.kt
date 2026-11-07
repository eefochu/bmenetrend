package hu.app.bmenetrend.plugins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.app.bmenetrend.mav.MavViewModel
import hu.app.bmenetrend.ui.mav.MavTicketsScreen

/**
 * The app's only entry point into the optional MÁV tickets module.
 *
 * The app contains a stub with exactly this API (app/src/ticketsStub) that is compiled in when the
 * mav-tickets folder is absent. Keep the public signatures of both files identical.
 */
object TicketsPlugin {
    /** Call once at activity level, so a signed-in session syncs tickets on start as before. */
    @Composable
    fun host(): TicketsHost? {
        val model: MavViewModel = viewModel()
        return remember(model) { TicketsHost(model) }
    }
}

class TicketsHost internal constructor(private val model: MavViewModel) {
    /** True while a ticket barcode is open; the app hides its navigation bar then. */
    @Composable
    fun isViewingTicket(): Boolean {
        val barcode by model.barcodeData.collectAsStateWithLifecycle()
        return barcode != null
    }

    @Composable
    fun Screen() = MavTicketsScreen(viewModel = model)
}
