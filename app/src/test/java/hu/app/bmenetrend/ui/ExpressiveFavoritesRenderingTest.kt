package hu.app.bmenetrend.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import hu.app.bmenetrend.data.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "hu-rHU-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpressiveFavoritesRenderingTest {
    @get:Rule val compose = createComposeRule()

    private fun render(dark: Boolean, scale: Float) {
        val routes = listOf("S10", "G10", "Z30", "IC").map { TransitRoute("mav:$it", it, "Győr", emptyList(), "mav", transportMode = "RAIL") }
        val stop = TransitStop("test", "Budapest-Kelenföld", GeoPoint(47.46,19.02), routes.map { it.id }, "mav")
        val state = UiState(network = TransitNetwork(listOf(stop), routes, emptyList(), Instant.now(), "test"),
            favorites = setOf(stop.id), favoriteStations = listOf(NearbyStation(stop, listOf(stop), 0.0,
                sources = setOf(DataSource.BKK))))
        var root: View? = null
        compose.setContent {
            val currentView = LocalView.current
            SideEffect { root = currentView.rootView }
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                ViaBuszTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        FavoritesScreen(state, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("favorite:test").assertIsDisplayed()
        compose.onNodeWithText("S10").assertIsDisplayed()
        compose.onNodeWithText("G10").assertIsDisplayed()
        lateinit var image: Bitmap
        compose.runOnIdle {
            val view = requireNotNull(root)
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
        }
        val file = File("build/reports/ui/expressive-favorites-${if(dark) "dark" else "light"}.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun lightThemeUsesGoogleSansAndPlainWhiteRailLabels() = render(false, 1f)
    @Test fun darkThemeSupportsLargeText() = render(true, 1.6f)
}
