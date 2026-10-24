package hu.app.bmenetrend.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

private val LightColors = lightColorScheme(
    primary = Color(0xFF236942),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9EFD2),
    onPrimaryContainer = Color(0xFF082D19),
    secondary = Color(0xFF526352),
    secondaryContainer = Color(0xFFD5E8D1),
    onSecondaryContainer = Color(0xFF111F10),
    tertiary = Color(0xFF35666D),
    tertiaryContainer = Color(0xFFB9EAF0),
    background = Color(0xFFF7F9F2),
    surface = Color(0xFFF7F9F2),
    surfaceContainer = Color(0xFFEBEFE7),
    surfaceContainerLow = Color(0xFFF1F5EC),
    surfaceContainerHigh = Color(0xFFE5EAE0),
    onSurface = Color(0xFF192018),
    onSurfaceVariant = Color(0xFF455044),
    outline = Color(0xFF758171),
    outlineVariant = Color(0xFFD2DACC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA0D5AF),
    onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF175130),
    onPrimaryContainer = Color(0xFFBBF1C9),
    secondary = Color(0xFFBACCB6),
    secondaryContainer = Color(0xFF3B4C39),
    tertiary = Color(0xFFA1CFD5),
    background = Color(0xFF10160F),
    surface = Color(0xFF10160F),
    surfaceContainer = Color(0xFF1C231B),
    surfaceContainerLow = Color(0xFF181F17),
    surfaceContainerHigh = Color(0xFF272E25),
    onSurface = Color(0xFFE0E5D9),
    onSurfaceVariant = Color(0xFFC1CABA),
    outlineVariant = Color(0xFF404A3C),
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

@Composable
fun ViaBuszTheme(accent: AccentColor = AccentColor.SYSTEM, customArgb: Int = 0xFF6750A4.toInt(), content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = if (accent != AccentColor.SYSTEM) accentColorScheme(accent, darkTheme, customArgb)
    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            displayLarge = Typography().displayLarge.copy(fontFamily = OpenSans),
            displayMedium = Typography().displayMedium.copy(fontFamily = OpenSans),
            displaySmall = Typography().displaySmall.copy(fontFamily = OpenSans),
            headlineLarge = Typography().headlineLarge.copy(fontFamily = OpenSans),
            headlineSmall = Typography().headlineSmall.copy(fontFamily = OpenSans),
            titleSmall = Typography().titleSmall.copy(fontFamily = OpenSans),
            bodySmall = Typography().bodySmall.copy(fontFamily = OpenSans),
            labelSmall = Typography().labelSmall.copy(fontFamily = OpenSans),
            headlineMedium = TextStyle(fontFamily = OpenSans, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 33.sp),
            titleLarge = TextStyle(fontFamily = OpenSans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontFamily = OpenSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
            bodyLarge = TextStyle(fontFamily = OpenSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = OpenSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
            labelLarge = TextStyle(fontFamily = OpenSans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontFamily = OpenSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
        ),
        content = content,
    )
}
