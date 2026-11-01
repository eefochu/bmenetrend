package hu.app.bmenetrend.ui

import hu.app.bmenetrend.R

enum class ThemeMode(val key: String, val labelRes: Int) {
    SYSTEM("system", R.string.theme_system),
    LIGHT("light", R.string.theme_light),
    DARK("dark", R.string.theme_dark);

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromKey(key: String?) = entries.find { it.key == key } ?: SYSTEM
    }
}
