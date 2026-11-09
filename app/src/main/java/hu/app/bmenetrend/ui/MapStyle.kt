package hu.app.bmenetrend.ui

import androidx.compose.runtime.staticCompositionLocalOf
import hu.app.bmenetrend.R

enum class MapStyle(val key: String, val labelRes: Int) {
    STREET("street", R.string.map_style_street),
    SATELLITE("satellite", R.string.map_style_satellite);

    companion object {
        fun fromKey(key: String?) = entries.find { it.key == key } ?: STREET
    }
}

internal data class MapAppearance(val style: MapStyle = MapStyle.STREET, val offline: Boolean = false) {
    val satellite: Boolean get() = style == MapStyle.SATELLITE && !offline
}

internal val LocalMapAppearance = staticCompositionLocalOf { MapAppearance() }
