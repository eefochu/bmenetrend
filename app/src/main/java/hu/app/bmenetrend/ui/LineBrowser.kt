package hu.app.bmenetrend.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.TransitRoute

@Composable
fun EmmaLineBrowser(
    state: UiState,
    route: TransitRoute,
    patternId: String?,
    onDirection: (String) -> Unit,
    onBoard: (String, String, String) -> Unit,
    onRetry: () -> Unit,
) {
    val details = state.lineDetails[route.id]
    val primaryVariants = remember(details?.patterns) {
        details?.patterns?.groupBy { it.familyKey }?.values?.map { it.first() }.orEmpty()
    }
    val pattern = if (patternId != null) {
        details?.patterns?.find { it.id == patternId } ?: details?.patterns?.firstOrNull()
    } else {
        null
    }
    val color = lineColor(route)
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
        item {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LineBadge(route, showIcon = false)
                Text(route.name, style = MaterialTheme.typography.titleLarge)
                Text(pattern?.headsign ?: stringResource(R.string.line_direction_hint), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (pattern != null) Text(pluralStringResource(R.plurals.emma_journey_stops, pattern.stops.size, pattern.stops.size), style = MaterialTheme.typography.labelMedium)
                val variants = details?.patterns?.filter { it.familyKey == pattern?.familyKey }.orEmpty()
                if (pattern != null && variants.size > 1) {
                    Text(stringResource(R.string.line_starting_point), style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(variants, key = { it.id }, contentType = { "variant_chip" }) { variant ->
                            FilterChip(selected = variant.id == pattern.id, onClick = { onDirection(variant.id) }, label = {
                                Text(variant.stops.firstOrNull()?.name?.removePrefix("Budapest, ").orEmpty(), Modifier.widthIn(max = 220.dp), maxLines = 2)
                            })
                        }
                    }
                }
            }
        }
        if (state.loadingLineId == route.id) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (state.lineErrorId == route.id) item {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.line_load_error))
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.emma_vehicle_refresh)) }
            }
        }
        if (details != null && details.patterns.isEmpty()) item { Text(stringResource(R.string.line_no_directions), Modifier.padding(22.dp)) }
        if (pattern == null && details != null) {
            items(primaryVariants, key = { it.id }, contentType = { "line_variant" }) { variant ->
                Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.clickable { onDirection(variant.id) }.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(details.route, showIcon = false)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(variant.headsign, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            variant.stops.firstOrNull()?.name?.let {
                                Text(stringResource(R.string.line_from, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(pluralStringResource(R.plurals.ui_stops_count, variant.stops.size, variant.stops.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else if (pattern != null) {
            itemsIndexed(pattern.stops, key = { index, stop -> "$index-${stop.id}" }, contentType = { _, _ -> "pattern_stop" }) { index, stop ->
                LineStopRow(stop, index == 0, index == pattern.stops.lastIndex, color, onClick = { onBoard(route.id, pattern.id, stop.id) })
            }
        }
    }
}
