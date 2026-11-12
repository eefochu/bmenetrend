package hu.app.bmenetrend.map

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Whether the Google map can run here. Emulators with a software GPU (SwiftShader) draw it blank,
 * and devices with missing or updating Play services (BlueStacks) cannot create it at all; both fall
 * back to the built-in MapLibre map for the rest of the session instead of showing an empty screen.
 */
internal object GoogleMapHealth {
    /** A Google map that has not drawn its first frame of tiles by then is treated as broken. */
    const val LOAD_TIMEOUT_MS = 12_000L

    var failed by mutableStateOf(false)
        private set

    private var playServices: Boolean? = null

    fun usable(context: Context): Boolean {
        if (failed) return false
        val available = playServices ?: (runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context.applicationContext) == ConnectionResult.SUCCESS
        }.getOrDefault(false)).also { playServices = it }
        return available
    }

    fun markFailed() { failed = true }
}
