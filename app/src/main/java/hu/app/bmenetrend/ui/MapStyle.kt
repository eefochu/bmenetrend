package hu.app.bmenetrend.ui

import androidx.compose.runtime.staticCompositionLocalOf
import hu.app.bmenetrend.R

enum class MapStyle(val key: String, val labelRes: Int) {
    STREET("street", R.string.map_style_street),
    SATELLITE("satellite", R.string.map_style_satellite),
    GOOGLE("google", R.string.map_style_google);

    companion object {
        fun fromKey(key: String?) = entries.find { it.key == key } ?: STREET
    }
}

internal data class MapAppearance(val style: MapStyle = MapStyle.STREET, val offline: Boolean = false) {
    val google: Boolean get() = style == MapStyle.GOOGLE && !offline && hu.app.bmenetrend.BuildConfig.GOOGLE_MAPS_CONFIGURED
    val satellite: Boolean get() = style == MapStyle.SATELLITE && !offline
}

internal val LocalMapAppearance = staticCompositionLocalOf { MapAppearance() }
