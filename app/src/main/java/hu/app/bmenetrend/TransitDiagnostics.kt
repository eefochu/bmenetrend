package hu.app.bmenetrend

import android.util.Log

/** Opt-in on-device capture, enabled with `adb shell setprop log.tag.BmMap DEBUG`. */
internal object TransitDiagnostics {
    val enabled: Boolean get() = runCatching { Log.isLoggable("BmMap", Log.DEBUG) }.getOrDefault(false)

    inline fun event(name: String, details: () -> String) {
        if (enabled) Log.d("BmMap", "[DEBUG-maptrace1] $name ${details()}")
    }
}
