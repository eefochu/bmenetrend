package hu.app.bmenetrend.ui

import androidx.compose.foundation.layout.*
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

/** Small on-map credit; the complete source and licence links remain one tap away. */
@Composable
internal fun MapAttribution(modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(onClick = { expanded = true }, modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = .94f)) {
        Text(stringResource(R.string.map_info),
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
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
                listOf(
                    "OpenStreetMap · ODbL 1.0" to "https://www.openstreetmap.org/copyright",
                    "OpenMapTiles · CC BY 4.0" to "https://openmaptiles.org/",
                    "OpenFreeMap" to "https://openfreemap.org/",
                    "Geofabrik" to "https://www.geofabrik.de/data/download.html",
                    "MapLibre Native · BSD 2-Clause" to "https://github.com/maplibre/maplibre-native/blob/main/LICENSE.md"
                ).forEach { (label, url) ->
                    TextButton(onClick = { runCatching { uri.openUri(url) } }) { Text(label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) } })
}
