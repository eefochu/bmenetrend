package hu.viabusz.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hu.viabusz.app.R
import hu.viabusz.app.data.DepartureTime
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun FavoritesScreen(state: UiState, onStop: (String) -> Unit, onRemove: (String) -> Unit,
                    onRefresh: () -> Unit, onWindow: (Int) -> Unit, onVisible: (Set<String>) -> Unit) {
    val listState = rememberLazyListState()
    val visibleAction by rememberUpdatedState(onVisible)
    var windowMenu by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? String)?.takeIf { key -> key.startsWith("favorite:") }?.removePrefix("favorite:") }.toSet() }
            .distinctUntilChanged().collect { visibleAction(it) }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.favorites_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, stringResource(R.string.ui_refresh_departures)) }
        }
        Box(Modifier.padding(horizontal = 20.dp)) {
            OutlinedButton(onClick = { windowMenu = true }) {
                Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.favorites_window, compactDuration(state.windowMinutes)))
                Icon(Icons.Rounded.ExpandMore, null)
            }
            DropdownMenu(windowMenu, onDismissRequest = { windowMenu = false }) {
                DepartureTime.windows.forEach { minutes -> DropdownMenuItem(text = { Text(compactDuration(minutes)) },
                    onClick = { onWindow(minutes); windowMenu = false },
                    trailingIcon = { if (state.windowMinutes == minutes) Icon(Icons.Rounded.Check, null) }) }
            }
        }
        Box(Modifier.fillMaxWidth().height(8.dp).padding(horizontal = 20.dp)) {
            if (state.favoritesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.favoriteStations.isEmpty()) item(key = "empty", contentType = "notice") {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Rounded.FavoriteBorder, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(if (state.favorites.isEmpty()) R.string.favorites_empty else if (state.favoritesLoading) R.string.favorites_loading else R.string.favorites_index_unavailable),
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            items(state.favoriteStations, key = { "favorite:${it.stop.id}" }, contentType = { "station" }) { station ->
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(bottom = 12.dp)) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onStop(station.stop.id) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                                Text(station.stop.name, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(onClick = { onRemove(station.stop.id) }) {
                                Icon(Icons.Rounded.Favorite, stringResource(R.string.ui_remove_favorite), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (station.loading && station.departures.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
                        station.departures.take(4).forEach { departure ->
                            NearbyDepartureCard(departure, state.findRoute(departure.routeId), station.platforms.firstOrNull { it.id == departure.stopId },
                                DepartureTime.minutes(state.now, departure.expectedAt)) { onStop(station.stop.id) }
                        }
                        if (station.departures.isEmpty() && !station.loading) Text(
                            stringResource(if (station.unavailable) R.string.nearby_unavailable else R.string.nearby_no_departures),
                            Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (station.unavailable && station.departures.isNotEmpty()) Text(stringResource(R.string.nearby_partial), Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onStop(station.stop.id) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(stringResource(R.string.favorites_all_departures)); Icon(Icons.Rounded.ChevronRight, null)
                        }
                    }
                }
            }
        }
    }
}
