package hu.app.bmenetrend.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.data.TransitRoute
import hu.app.bmenetrend.data.TransitStop
import hu.app.bmenetrend.map.StationSprite
import hu.app.bmenetrend.map.StopMarkerColors

/** Search and map share the same stop palette and marker renderer. */
@Composable
internal fun StopMapDot(stop: TransitStop, routes: List<TransitRoute> = emptyList()) {
    val colors = remember(stop.providerId, stop.providerIds, stop.transportMode, stop.routeIds, routes) {
        StopMarkerColors.palette(listOf(stop), routes.associateBy { it.id }).ifEmpty {
            listOf(hu.app.bmenetrend.map.StopMarkerLayout.color(stop.providerId, stop.transportMode))
        }
    }
    val dark = LocalDarkTheme.current
    val marker = remember(colors, dark) {
        StationSprite(colors, 22, focused = false, selected = false, dark = dark, routeColor = 0).render().asImageBitmap()
    }
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Image(marker, contentDescription = null, modifier = Modifier.size(32.dp))
    }
}
