package hu.app.bmenetrend.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.app.bmenetrend.R

/** Lift the credit with the sheet, retaining room above the collapsed departures button. */
internal fun mapAttributionLiftPx(visibleSheetPx: Float, peekHeightPx: Float, collapsedControlsPx: Float): Float {
    val visible = visibleSheetPx.coerceAtLeast(0f)
    val collapsedFraction = if (peekHeightPx > 0f) (1f - visible / peekHeightPx).coerceIn(0f, 1f) else 1f
    return visible + collapsedControlsPx * collapsedFraction
}

/** On-map credit; the complete source and licence links remain one tap away. */
@Composable
internal fun MapAttribution(modifier: Modifier = Modifier) {
    if (LocalMapAppearance.current.google) return
    val satellite = LocalMapAppearance.current.satellite
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(onClick = { expanded = true }, modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Text(stringResource(if (satellite) R.string.map_satellite_attribution else R.string.map_info),
            Modifier.widthIn(max = 260.dp).padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 12.sp))
    }
    if (expanded) MapCreditsDialog { expanded = false }
}

@Composable
internal fun MapCreditsDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.map_credits_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.map_credits_data))
                Text(stringResource(R.string.map_style_credit), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.map_satellite_credit), style = MaterialTheme.typography.bodySmall)
                listOf(
                    "OpenStreetMap · ODbL 1.0" to "https://www.openstreetmap.org/copyright",
                    "OpenMapTiles · CC BY 4.0" to "https://openmaptiles.org/",
                    "OpenFreeMap" to "https://openfreemap.org/",
                    "EOxCloudless · EOX · Copernicus Sentinel 2016" to "https://cloudless.eox.at/",
                    "Sentinel-2 2016 · CC BY 4.0" to "https://creativecommons.org/licenses/by/4.0/",
                    "BKK · M1–M4 / H5–H9" to "https://bkk.hu/fejlesztoknek/",
                    "Geofabrik" to "https://www.geofabrik.de/data/download.html",
                    "MapLibre Native · BSD 2-Clause" to "https://github.com/maplibre/maplibre-native/blob/main/LICENSE.md"
                ).forEach { (label, url) ->
                    TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text(label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) } })
}
