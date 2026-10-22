package hu.viabusz.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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

@Composable
fun ViaBuszTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 27.sp, lineHeight = 33.sp),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
        ),
        content = content,
    )
}
