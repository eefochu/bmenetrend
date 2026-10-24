package hu.app.bmenetrend.ui

import hu.app.bmenetrend.R

enum class AccentColor(val key: String, val labelRes: Int, val hue: Float?) {
    SYSTEM("system", R.string.accent_system, null),
    GREEN("green", R.string.accent_green, 145f),
    BLUE("blue", R.string.accent_blue, 215f),
    PURPLE("purple", R.string.accent_purple, 275f),
    ROSE("rose", R.string.accent_rose, 345f),
    ORANGE("orange", R.string.accent_orange, 25f),
    CUSTOM("custom", R.string.accent_custom, null);

    companion object {
        fun fromKey(key: String?) = entries.find { it.key == key } ?: SYSTEM
    }
}

/** RGB seed conversion is platform independent so colour persistence and tonal contrast can be tested. */
internal fun accentHueSaturation(argb: Int): Pair<Float, Float> {
    val r = ((argb shr 16) and 255) / 255f
    val g = ((argb shr 8) and 255) / 255f
    val b = (argb and 255) / 255f
    val maximum = maxOf(r, g, b)
    val minimum = minOf(r, g, b)
    val delta = maximum - minimum
    if (delta == 0f) return 0f to 0f
    val lightness = (maximum + minimum) / 2
    val saturation = delta / (1 - kotlin.math.abs(2 * lightness - 1))
    val hue = when (maximum) {
        r -> ((g - b) / delta) % 6
        g -> (b - r) / delta + 2
        else -> (r - g) / delta + 4
    } * 60
    return (hue + 360) % 360 to saturation
}
