package hu.viabusz.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class AccentColorTest {
    @Test fun storedAccentKeysRoundTripAndUnknownSettingsUseSystem() {
        AccentColor.entries.forEach { assertEquals(it, AccentColor.fromKey(it.key)) }
        assertEquals(AccentColor.SYSTEM, AccentColor.fromKey(null))
        assertEquals(AccentColor.SYSTEM, AccentColor.fromKey("old-removed-colour"))
    }
    @Test fun rgbPickerSeedsKeepReadablePrimaryTextIncludingGreysAndBrightCyan() {
        listOf(0xFF00FF00, 0xFF00FFFF, 0xFFFF0000, 0xFF0000FF, 0xFFFFFFFF, 0xFF000000, 0xFF808080).forEach { seed ->
            listOf(false, true).forEach { dark ->
                val colors = accentColorScheme(AccentColor.CUSTOM, dark, seed.toInt())
                val a = colors.primary.luminance()
                val b = colors.onPrimary.luminance()
                assertTrue("seed=$seed dark=$dark", (maxOf(a, b) + .05f) / (minOf(a, b) + .05f) >= 4.5f)
            }
        }
    }
    @Test fun customAccentTextRemainsReadableInLightAndDarkModes() {
        fun contrast(a: Color, b: Color): Float = (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)
        AccentColor.entries.filter { it != AccentColor.SYSTEM }.forEach { accent ->
            listOf(false, true).forEach { dark ->
                val scheme = accentColorScheme(accent, dark)
                val pairs = listOf(scheme.primary to scheme.onPrimary, scheme.primaryContainer to scheme.onPrimaryContainer,
                    scheme.secondary to scheme.onSecondary, scheme.secondaryContainer to scheme.onSecondaryContainer,
                    scheme.tertiary to scheme.onTertiary, scheme.tertiaryContainer to scheme.onTertiaryContainer,
                    scheme.surface to scheme.onSurface, scheme.surfaceContainerHigh to scheme.onSurfaceVariant)
                pairs.forEach { (background, foreground) ->
                    assertTrue("$accent dark=$dark contrast=${contrast(background, foreground)}", contrast(background, foreground) >= 4.5f)
                }
            }
        }
    }
}
