package hu.app.bmenetrend.ui

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
    showHeader: Boolean = true,
) {
    val details = state.lineDetails[route.id]
    val primaryVariants = remember(details?.patterns) {
        details?.patterns?.groupBy { it.familyKey }?.values?.map { it.first() }.orEmpty()
    }
    val pattern = if (patternId != null) {
        details?.patterns?.find { it.id == patternId }
    } else {
        null
    }
    val color = lineColor(route)
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
        if(showHeader) item {
            Row(Modifier.padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                LineBadge(route)
                Text(serviceTypeLabel(route.providerId,route.transportMode),style=MaterialTheme.typography.titleSmall)
            }
        }
        if(pattern!=null) item {
            RouteDirectionRow(route,pattern.headsign,pattern.stops.firstOrNull()?.name)
            val variants=details?.patterns?.filter { it.familyKey==pattern.familyKey }.orEmpty()
            if(variants.size>1) LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                items(variants,key={it.id}) { variant ->
                    FilterChip(selected=variant.id==pattern.id,onClick={onDirection(variant.id)},label={
                        Text(variant.stops.firstOrNull()?.name.orEmpty(),Modifier.widthIn(max=220.dp),maxLines=2)
                    })
                }
            }
            Text(pluralStringResource(R.plurals.emma_journey_stops,pattern.stops.size,pattern.stops.size),
                Modifier.padding(horizontal=22.dp,vertical=8.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
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
                RouteDirectionRow(details.route,variant.headsign,variant.stops.firstOrNull()?.name,onClick={onDirection(variant.id)})

            }
        } else if (pattern != null) {
            itemsIndexed(pattern.stops, key = { index, stop -> "$index-${stop.id}" }, contentType = { _, _ -> "pattern_stop" }) { index, stop ->
                LineStopRow(stop, index == 0, index == pattern.stops.lastIndex, color, onClick = { onBoard(route.id, pattern.id, stop.id) })
            }
        }
    }
}
