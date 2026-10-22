package hu.viabusz.app.ui

import hu.viabusz.app.R

enum class AccentColor(val key: String, val labelRes: Int, val hue: Float?) {
    SYSTEM("system", R.string.accent_system, null),
    GREEN("green", R.string.accent_green, 145f),
    BLUE("blue", R.string.accent_blue, 215f),
    PURPLE("purple", R.string.accent_purple, 275f),
    ROSE("rose", R.string.accent_rose, 345f),
    ORANGE("orange", R.string.accent_orange, 25f);

    companion object {
        fun fromKey(key: String?) = entries.find { it.key == key } ?: SYSTEM
    }
}
