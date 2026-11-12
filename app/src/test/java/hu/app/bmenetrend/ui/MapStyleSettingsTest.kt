package hu.app.bmenetrend.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import hu.app.bmenetrend.R
import hu.app.bmenetrend.TransitViewModel
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "hu-rHU-w360dp-h800dp")
class MapStyleSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun satelliteChoiceUpdatesSelectionAndExplainsOfflineFallback() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var state by mutableStateOf(UiState())
        val selected = mutableListOf<MapStyle>()
        compose.setContent {
            ViaBuszTheme {
                SettingsPage(state, {}, {}, {}, {}, {}, onMapStyle = {
                    selected += it
                    state = state.copy(mapStyle = it)
                })
            }
        }
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        val satellite = compose.onNode(hasText(app.getString(R.string.map_style_satellite)) and radio)
        satellite.performScrollTo().assertHeightIsAtLeast(48.dp).assertIsNotSelected().performClick()
        satellite.assertIsSelected()
        assertEquals(listOf(MapStyle.SATELLITE), selected)
        compose.onNode(hasText(app.getString(R.string.map_style_street)) and radio).assertIsNotSelected()
        compose.runOnIdle { state = state.copy(offlineMode = true) }
        compose.onNodeWithText(app.getString(R.string.map_style_offline_hint)).assertExists()
        assertEquals(MapStyle.SATELLITE, state.mapStyle)
        assertFalse(MapAppearance(state.mapStyle, state.offlineMode).satellite)
    }

    @Test fun savedMapTypeSurvivesViewModelRecreationAndUnknownValuesUseStreetMap() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        try { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) } catch (_: Exception) { }
        val prefs = app.getSharedPreferences("viabusz", 0)
        val previous = prefs.getString("map_style", null)
        val models = mutableListOf<TransitViewModel>()
        try {
            prefs.edit().remove("map_style").commit()
            val first = TransitViewModel(app).also { models += it }
            assertEquals(MapStyle.STREET, first.state.value.mapStyle)
            first.setMapStyle(MapStyle.SATELLITE)
            assertEquals("satellite", prefs.getString("map_style", null))
            assertEquals(MapStyle.SATELLITE, TransitViewModel(app).also { models += it }.state.value.mapStyle)
            first.setMapStyle(MapStyle.GOOGLE)
            assertEquals("google", prefs.getString("map_style", null))
            assertEquals(MapStyle.GOOGLE, TransitViewModel(app).also { models += it }.state.value.mapStyle)
            first.setMapStyle(MapStyle.GOOGLE_SATELLITE)
            assertEquals("google_satellite", prefs.getString("map_style", null))
            assertEquals(MapStyle.GOOGLE_SATELLITE, TransitViewModel(app).also { models += it }.state.value.mapStyle)
            assertEquals(MapStyle.GOOGLE, MapStyle.fromKey("google"))
            assertEquals(MapStyle.GOOGLE_SATELLITE, MapStyle.fromKey("google_satellite"))
            assertEquals(MapStyle.STREET, MapStyle.fromKey("unsupported"))
        } finally {
            models.forEach { it.viewModelScope.cancel() }
            prefs.edit().apply { if (previous == null) remove("map_style") else putString("map_style", previous) }.commit()
        }
    }

    @Test fun googleChoiceUpdatesSelectionAndExplainsOfflineFallback() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var state by mutableStateOf(UiState())
        val selected = mutableListOf<MapStyle>()
        compose.setContent {
            ViaBuszTheme {
                SettingsPage(state, {}, {}, {}, {}, {}, onMapStyle = {
                    selected += it
                    state = state.copy(mapStyle = it)
                })
            }
        }
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        val google = compose.onNode(hasText(app.getString(R.string.map_style_google)) and radio)
        google.performScrollTo().assertHeightIsAtLeast(48.dp).assertIsNotSelected().performClick()
        google.assertIsSelected()
        assertEquals(listOf(MapStyle.GOOGLE), selected)
        compose.onNode(hasText(app.getString(R.string.map_style_street)) and radio).assertIsNotSelected()
        compose.onNode(hasText(app.getString(R.string.map_style_satellite)) and radio).assertIsNotSelected()
        compose.runOnIdle { state = state.copy(offlineMode = true) }
        compose.onNodeWithText(app.getString(R.string.map_style_offline_hint)).assertExists()
        assertEquals(MapStyle.GOOGLE, state.mapStyle)
        assertFalse(MapAppearance(state.mapStyle, state.offlineMode).google)
    }

    @Test fun googleMapHasNoFalseOpenStreetMapAttributionAndFallsBackWhenOffline() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var appearance by mutableStateOf(MapAppearance(MapStyle.GOOGLE, offline = false))
        compose.setContent {
            ViaBuszTheme {
                CompositionLocalProvider(LocalMapAppearance provides appearance) {
                    MapAttribution()
                }
            }
        }
        compose.onNodeWithText(app.getString(R.string.map_info)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.map_satellite_attribution)).assertDoesNotExist()

        compose.runOnIdle { appearance = MapAppearance(MapStyle.GOOGLE, offline = true) }
        compose.onNodeWithText(app.getString(R.string.map_info)).assertIsDisplayed()
    }

    @Test fun satelliteMapDisplaysImageryAttributionAndOpensSourceCredits() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        compose.setContent {
            ViaBuszTheme {
                CompositionLocalProvider(LocalMapAppearance provides MapAppearance(MapStyle.SATELLITE)) {
                    MapAttribution()
                }
            }
        }
        compose.onNodeWithText(app.getString(R.string.map_satellite_attribution)).assertIsDisplayed().performClick()
        compose.onNodeWithText(app.getString(R.string.map_credits_title)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.map_satellite_credit)).assertExists()
    }

    @Test fun googleSatelliteChoiceUpdatesSelectionAndExplainsOfflineFallback() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var state by mutableStateOf(UiState())
        val selected = mutableListOf<MapStyle>()
        compose.setContent {
            ViaBuszTheme {
                SettingsPage(state, {}, {}, {}, {}, {}, onMapStyle = {
                    selected += it
                    state = state.copy(mapStyle = it)
                })
            }
        }
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        val satellite = compose.onNode(hasText(app.getString(R.string.map_style_google_satellite)) and radio)
        satellite.performScrollTo().assertHeightIsAtLeast(48.dp).assertIsNotSelected().performClick()
        satellite.assertIsSelected()
        assertEquals(listOf(MapStyle.GOOGLE_SATELLITE), selected)
        compose.onNode(hasText(app.getString(R.string.map_style_street)) and radio).assertIsNotSelected()
        compose.onNode(hasText(app.getString(R.string.map_style_satellite)) and radio).assertIsNotSelected()
        compose.onNode(hasText(app.getString(R.string.map_style_google)) and radio).assertIsNotSelected()
        assertEquals(MapStyle.GOOGLE_SATELLITE, state.mapStyle)

        val onlineAppearance = MapAppearance(state.mapStyle, offline = false)
        assertTrue("Google maps must be configured in online appearance", onlineAppearance.google)
        assertTrue("Satellite must be active in online appearance", onlineAppearance.satellite)

        compose.runOnIdle { state = state.copy(offlineMode = true) }
        compose.onNodeWithText(app.getString(R.string.map_style_offline_hint)).assertExists()
        val offlineAppearance = MapAppearance(state.mapStyle, offline = true)
        assertFalse("Google maps must be disabled in offline fallback", offlineAppearance.google)
        assertFalse("Satellite must be disabled in offline fallback", offlineAppearance.satellite)
    }

    @Test fun googleSatelliteMapHasNoFalseOpenStreetMapAttributionAndFallsBackWhenOffline() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var appearance by mutableStateOf(MapAppearance(MapStyle.GOOGLE_SATELLITE, offline = false))
        compose.setContent {
            ViaBuszTheme {
                CompositionLocalProvider(LocalMapAppearance provides appearance) {
                    MapAttribution()
                }
            }
        }
        compose.onNodeWithText(app.getString(R.string.map_info)).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.map_satellite_attribution)).assertDoesNotExist()

        compose.runOnIdle { appearance = MapAppearance(MapStyle.GOOGLE_SATELLITE, offline = true) }
        compose.onNodeWithText(app.getString(R.string.map_info)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.map_satellite_attribution)).assertDoesNotExist()
    }
}
