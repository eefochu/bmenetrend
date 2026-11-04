package hu.app.bmenetrend.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MaterialYouDynamicThemeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectedAccentSurfaceRolesReachMaterialThemeWithoutNeutralOverrides() {
        var observedColors: ColorScheme? = null
        compose.setContent {
            ViaBuszTheme(themeMode = ThemeMode.DARK, accent = AccentColor.ROSE) {
                observedColors = MaterialTheme.colorScheme
            }
        }
        val expected = accentColorScheme(AccentColor.ROSE, dark = true)
        assertNotNull(observedColors)
        assertEquals(expected.surface, observedColors!!.surface)
        assertEquals(expected.surfaceContainerLow, observedColors!!.surfaceContainerLow)
        assertEquals(expected.surfaceContainerHigh, observedColors!!.surfaceContainerHigh)
        assertEquals(expected.background, observedColors!!.background)
    }

    @Test fun materialYouSystemFollowsDynamicColorSchemeOnAndroid12Plus() {
        var observedColors: ColorScheme? = null
        compose.setContent {
            ViaBuszTheme(themeMode = ThemeMode.LIGHT, accent = AccentColor.SYSTEM) {
                observedColors = MaterialTheme.colorScheme
            }
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expected = dynamicLightColorScheme(context)
        assertNotNull(observedColors)
        assertEquals(expected.surface, observedColors!!.surface)
        assertEquals(expected.primary, observedColors!!.primary)
        assertEquals(expected.background, observedColors!!.background)
    }

    @Test fun materialYouSystemDarkFollowsDynamicDarkColorSchemeOnAndroid12Plus() {
        var observedColors: ColorScheme? = null
        compose.setContent {
            ViaBuszTheme(themeMode = ThemeMode.DARK, accent = AccentColor.SYSTEM) {
                observedColors = MaterialTheme.colorScheme
            }
        }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val expected = dynamicDarkColorScheme(context)
        assertNotNull(observedColors)
        assertEquals(expected.surface, observedColors!!.surface)
        assertEquals(expected.primary, observedColors!!.primary)
        assertEquals(expected.background, observedColors!!.background)
    }
}
