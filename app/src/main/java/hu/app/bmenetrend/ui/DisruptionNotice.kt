package hu.app.bmenetrend.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Immutable
data class DisruptionPresentation(val snapshot: DisruptionSnapshot = DisruptionSnapshot(), val now: Instant = Instant.now(), val offline: Boolean = false)
val LocalDisruptions = staticCompositionLocalOf { DisruptionPresentation() }

@Composable
fun StationDisruptionNotice(stops: List<TransitStop>, state: UiState, departures: List<Departure>, modifier: Modifier = Modifier) {
    val contexts = remember(stops, state.routesById, departures, state.now.epochSecond / 60) {
        DisruptionScope.station(stops, state.routesById, state.now, departures)
    }
    DisruptionNotice(contexts, modifier)
}

/** One compact, relevant notice; details stay behind a deliberate tap. No background notifications. */
@Composable
fun DisruptionNotice(contexts: List<DisruptionContext>, modifier: Modifier = Modifier) {
    val presentation = LocalDisruptions.current
    val snapshot = presentation.snapshot
    val warnings = remember(snapshot, contexts, presentation.now.epochSecond / 60, presentation.offline) {
        snapshot.warnings(contexts, presentation.now, presentation.offline)
    }
    val unavailable = snapshot.attempted && contexts.any { it.bkk } &&
        (presentation.offline || snapshot.alertsStale(presentation.now) || snapshot.changesStale(presentation.now))
    if (warnings.isEmpty()) return
    val language = LocalConfiguration.current.locales[0].language
    var expanded by remember { mutableStateOf(false) }
    val important = warnings.any { it.important }
    Surface(onClick = { expanded = true }, modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = if (important) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (important) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Row(Modifier.padding(12.dp).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (important) Icons.Rounded.WarningAmber else Icons.Rounded.Info, null, Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(warningTitle(warnings.first(), language),
                    style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (warnings.size > 1) Text(pluralStringResource(R.plurals.disruptions_more, warnings.size - 1, warnings.size - 1), style = MaterialTheme.typography.bodySmall)
                if (warnings.isNotEmpty() && unavailable) Text(stringResource(R.string.disruptions_stale_short), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Rounded.ChevronRight, stringResource(R.string.disruptions_details), Modifier.size(20.dp))
        }
    }
    if (expanded) {
        val uriHandler = LocalUriHandler.current
        AlertDialog(onDismissRequest = { expanded = false },
            icon = { Icon(if (important) Icons.Rounded.WarningAmber else Icons.Rounded.Info, null) },
            title = { Text(stringResource(R.string.disruptions_title)) },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (unavailable) Text(stringResource(R.string.disruptions_unavailable_detail), style = MaterialTheme.typography.bodyMedium)
                    warnings.forEach { warning ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(warningTitle(warning, language), style = MaterialTheme.typography.titleMedium)
                            warning.alert?.let { alert ->
                                val body = ServiceDisruptionsParser.localized(alert.description, language)
                                if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyMedium)
                                val link = ServiceDisruptionsParser.safeUrl(ServiceDisruptionsParser.localized(alert.urls, language))
                                if (link != null) TextButton(onClick = { runCatching { uriHandler.openUri(link) } }) {
                                    Text(stringResource(R.string.disruptions_official_details))
                                }
                            }
                            warning.change?.let { change -> Text(stringResource(if (change.cancelled)
                                R.string.disruptions_cancelled_detail else R.string.disruptions_skipped_detail), style = MaterialTheme.typography.bodyMedium) }
                            if (warning.stale) Text(stringResource(R.string.disruptions_stale_short), style = MaterialTheme.typography.labelMedium)
                        }
                        HorizontalDivider()
                    }
                    snapshot.alertsAt?.let { at -> Text(stringResource(R.string.disruptions_updated,
                        DateTimeFormatter.ofPattern("MM.dd. HH:mm").withZone(ZoneId.of("Europe/Budapest")).format(at)),
                        style = MaterialTheme.typography.labelSmall) }
                }
            }, confirmButton = { TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.disruptions_close)) } })
    }
}

@Composable
private fun warningTitle(warning: DisruptionWarning, language: String): String = warning.alert?.let {
    ServiceDisruptionsParser.localized(it.title, language).ifBlank { stringResource(R.string.disruptions_title) }
} ?: if (warning.serviceLabel != null) stringResource(
    if (warning.change?.cancelled == true) R.string.disruptions_cancelled_route else R.string.disruptions_skipped_route, warning.serviceLabel)
else stringResource(if (warning.change?.cancelled == true) R.string.disruptions_cancelled else R.string.disruptions_skipped)
