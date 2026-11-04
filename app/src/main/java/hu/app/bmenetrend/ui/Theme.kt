package hu.app.bmenetrend.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import hu.app.bmenetrend.R
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val OpenSans = FontFamily(
    Font(R.font.open_sans_regular, FontWeight.Normal),
    Font(R.font.open_sans_semibold, FontWeight.SemiBold),
    Font(R.font.open_sans_bold, FontWeight.Bold),
)

// Fallback palette for devices without Android dynamic colour support.
private val LightColors = lightColorScheme(
    primary=Color(0xFF145FAD), onPrimary=Color.White,
    primaryContainer=Color(0xFFE1EDFA), onPrimaryContainer=Color(0xFF163D66),
    secondary=Color(0xFF536171), onSecondary=Color.White,
    secondaryContainer=Color(0xFFE8ECF1), onSecondaryContainer=Color(0xFF293542),
    tertiary=Color(0xFF356B71), onTertiary=Color.White,
    tertiaryContainer=Color(0xFFD8EEF0), onTertiaryContainer=Color(0xFF173B40),
    background=Color(0xFFF6F7F9), onBackground=Color(0xFF1C2025),
    surface=Color(0xFFF6F7F9), onSurface=Color(0xFF1C2025),
    surfaceContainerLowest=Color.White, surfaceContainerLow=Color(0xFFEDF0F3),
    surfaceContainer=Color(0xFFE7EBEF), surfaceContainerHigh=Color(0xFFE0E5EB),
    surfaceContainerHighest=Color(0xFFD8DFE6), surfaceVariant=Color(0xFFE7EBEF),
    onSurfaceVariant=Color(0xFF53606C), outline=Color(0xFF75808A), outlineVariant=Color(0xFFCED5DC),
    inverseSurface=Color(0xFF252A30), inverseOnSurface=Color(0xFFF1F3F5), inversePrimary=Color(0xFF94C9FF),
)
private val DarkColors = darkColorScheme(
    primary=Color(0xFF94C9FF), onPrimary=Color(0xFF102F4B),
    primaryContainer=Color(0xFF213950), onPrimaryContainer=Color(0xFFD7EAFE),
    secondary=Color(0xFFB7C1CC), onSecondary=Color(0xFF26323D),
    secondaryContainer=Color(0xFF303841), onSecondaryContainer=Color(0xFFDDE4EC),
    tertiary=Color(0xFF9CD1D7), onTertiary=Color(0xFF12363A),
    tertiaryContainer=Color(0xFF27474C), onTertiaryContainer=Color(0xFFCFEDF0),
    background=Color(0xFF131519), onBackground=Color(0xFFF1F3F5),
    surface=Color(0xFF131519), onSurface=Color(0xFFF1F3F5),
    surfaceContainerLowest=Color(0xFF1B1E23), surfaceContainerLow=Color(0xFF22262C),
    surfaceContainer=Color(0xFF2A2F36), surfaceContainerHigh=Color(0xFF323841),
    surfaceContainerHighest=Color(0xFF3C444E), surfaceVariant=Color(0xFF2A2F36),
    onSurfaceVariant=Color(0xFFB8C1CC), outline=Color(0xFF85919E), outlineVariant=Color(0xFF3B434D),
    inverseSurface=Color(0xFFF1F3F5), inverseOnSurface=Color(0xFF252A30), inversePrimary=Color(0xFF145FAD),
)

internal val TransitShapes=Shapes(
    extraSmall=RoundedCornerShape(8.dp),small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(16.dp),
    large=RoundedCornerShape(24.dp),extraLarge=RoundedCornerShape(28.dp),
)
private fun type(size: Int,line: Int,weight: FontWeight=FontWeight.Normal,tracking: Float=0f)=TextStyle(
    fontFamily=OpenSans,fontWeight=weight,fontSize=size.sp,lineHeight=line.sp,letterSpacing=tracking.sp,
    fontFeatureSettings="tnum",
)
internal val TransitTypography=Typography(
    displayLarge=type(48,54,FontWeight.Bold,-1f),displayMedium=type(40,46,FontWeight.Bold,-.8f),
    displaySmall=type(34,40,FontWeight.Bold,-.6f),headlineLarge=type(30,36,FontWeight.Bold,-.6f),
    headlineMedium=type(27,34,FontWeight.Bold,-.5f),headlineSmall=type(24,30,FontWeight.Bold,-.4f),
    titleLarge=type(21,27,FontWeight.Bold,-.3f),titleMedium=type(16,22,FontWeight.SemiBold,-.1f),
    titleSmall=type(14,20,FontWeight.Bold),bodyLarge=type(16,24),bodyMedium=type(14,21),bodySmall=type(12,18),
    labelLarge=type(14,20,FontWeight.SemiBold),labelMedium=type(12,17,FontWeight.SemiBold),labelSmall=type(11,16,FontWeight.SemiBold),
)

/** Tonal roles keep the chosen hue readable in both themes, including their container colours. */
internal fun accentColorScheme(accent: AccentColor, dark: Boolean, customArgb: Int = 0xFF6750A4.toInt()): ColorScheme {
    val custom = accentHueSaturation(customArgb)
    val hue = accent.hue ?: if (accent == AccentColor.CUSTOM) custom.first else return if (dark) DarkColors else LightColors
    fun tone(lightness: Float, saturation: Float = if (accent == AccentColor.CUSTOM) custom.second.coerceAtMost(0.85f) else 0.65f, shift: Float = 0f) =
        Color.hsl((hue + shift) % 360f, saturation, lightness)
    val lightPrimary = if (accent == AccentColor.CUSTOM) 0.27f else 0.30f
    return (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = tone(if (dark) 0.80f else lightPrimary),
        onPrimary = if (dark) tone(0.12f) else Color.White,
        primaryContainer = tone(if (dark) 0.24f else 0.90f),
        onPrimaryContainer = tone(if (dark) 0.93f else 0.12f),
        inversePrimary = tone(if (dark) lightPrimary else 0.80f),
        secondary = tone(if (dark) 0.78f else 0.30f, 0.22f),
        onSecondary = if (dark) tone(0.12f, 0.22f) else Color.White,
        secondaryContainer = tone(if (dark) 0.24f else 0.90f, 0.22f),
        onSecondaryContainer = tone(if (dark) 0.93f else 0.12f, 0.22f),
        tertiary = tone(if (dark) 0.80f else 0.30f, 0.45f, 45f),
        onTertiary = if (dark) tone(0.12f, 0.45f, 45f) else Color.White,
        tertiaryContainer = tone(if (dark) 0.24f else 0.90f, 0.45f, 45f),
        onTertiaryContainer = tone(if (dark) 0.93f else 0.12f, 0.45f, 45f),
        background = tone(if (dark) 0.065f else 0.985f, 0.08f),
        onBackground = tone(if (dark) 0.92f else 0.10f, 0.08f),
        surface = tone(if (dark) 0.065f else 0.985f, 0.08f),
        onSurface = tone(if (dark) 0.92f else 0.10f, 0.08f),
        surfaceContainerLowest = tone(if (dark) 0.04f else 1.0f, 0.08f),
        surfaceContainerLow = tone(if (dark) 0.10f else 0.97f, 0.08f),
        surfaceContainer = tone(if (dark) 0.12f else 0.945f, 0.08f),
        surfaceContainerHigh = tone(if (dark) 0.16f else 0.92f, 0.08f),
        surfaceContainerHighest = tone(if (dark) 0.20f else 0.90f, 0.08f),
        surfaceDim = tone(if (dark) 0.065f else 0.87f, 0.08f),
        surfaceBright = tone(if (dark) 0.24f else 0.985f, 0.08f),
        surfaceVariant = tone(if (dark) 0.24f else 0.90f, 0.12f),
        onSurfaceVariant = tone(if (dark) 0.80f else 0.30f, 0.12f),
        surfaceTint = tone(if (dark) 0.80f else 0.30f),
        outline = tone(if (dark) 0.60f else 0.46f, 0.10f),
        outlineVariant = tone(if (dark) 0.30f else 0.80f, 0.10f),
    )
}

internal val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun ViaBuszTheme(accent: AccentColor = AccentColor.SYSTEM, customArgb: Int = 0xFF6750A4.toInt(), themeMode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val darkTheme = themeMode.isDark(isSystemInDarkTheme())
    val context = LocalContext.current
    val colors = if (accent != AccentColor.SYSTEM) accentColorScheme(accent, darkTheme, customArgb)
    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colors,
            shapes = TransitShapes,
            typography = TransitTypography,
            content = content,
        )
    }
}
