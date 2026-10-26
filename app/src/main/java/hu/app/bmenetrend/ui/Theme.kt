package hu.app.bmenetrend.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
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
    primary=Color(0xFF205C48),onPrimary=Color.White,
    primaryContainer=Color(0xFFC7ECD9),onPrimaryContainer=Color(0xFF103C2C),
    secondary=Color(0xFF49655C),onSecondary=Color.White,
    secondaryContainer=Color(0xFFDCEBE3),onSecondaryContainer=Color(0xFF233F35),
    tertiary=Color(0xFF346779),onTertiary=Color.White,
    tertiaryContainer=Color(0xFFD2EBF4),onTertiaryContainer=Color(0xFF163E4E),
    background=Color(0xFFF3F7F5),onBackground=Color(0xFF172D26),
    surface=Color(0xFFFAFCFA),onSurface=Color(0xFF172D26),
    surfaceContainerLowest=Color.White,surfaceContainerLow=Color(0xFFEDF3EF),
    surfaceContainer=Color(0xFFE6EEE8),surfaceContainerHigh=Color(0xFFDCE8DF),
    surfaceContainerHighest=Color(0xFFD3E1D7),surfaceVariant=Color(0xFFE2EBE4),
    onSurfaceVariant=Color(0xFF4C6257),outline=Color(0xFF71867B),outlineVariant=Color(0xFFC4D2C9),
    inverseSurface=Color(0xFF243B31),inverseOnSurface=Color(0xFFF0F6F1),inversePrimary=Color(0xFF9DDFC3),
)

private val DarkColors = darkColorScheme(
    primary=Color(0xFF9DDFC3),onPrimary=Color(0xFF073B29),
    primaryContainer=Color(0xFF245642),onPrimaryContainer=Color(0xFFC7F2DC),
    secondary=Color(0xFFB4CFC1),onSecondary=Color(0xFF1F3B2F),
    secondaryContainer=Color(0xFF344E42),onSecondaryContainer=Color(0xFFD4EADC),
    tertiary=Color(0xFFA4D4E6),onTertiary=Color(0xFF103747),
    tertiaryContainer=Color(0xFF244F60),onTertiaryContainer=Color(0xFFC8EAF6),
    background=Color(0xFF101B17),onBackground=Color(0xFFE2EDE5),
    surface=Color(0xFF111E18),onSurface=Color(0xFFE2EDE5),
    surfaceContainerLowest=Color(0xFF0C1612),surfaceContainerLow=Color(0xFF18261F),
    surfaceContainer=Color(0xFF1E2E25),surfaceContainerHigh=Color(0xFF283B30),
    surfaceContainerHighest=Color(0xFF33473A),surfaceVariant=Color(0xFF304238),
    onSurfaceVariant=Color(0xFFBACDC0),outline=Color(0xFF869E8E),outlineVariant=Color(0xFF3F5547),
    inverseSurface=Color(0xFFE2EDE5),inverseOnSurface=Color(0xFF243B31),inversePrimary=Color(0xFF205C48),
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
    if(accent==AccentColor.GREEN) return if(dark) DarkColors else LightColors
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
        shapes = TransitShapes,
        typography = TransitTypography,
        content = content,
    )
}
