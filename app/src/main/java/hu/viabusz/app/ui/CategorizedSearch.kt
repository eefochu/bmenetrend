package hu.viabusz.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.viabusz.app.R
import hu.viabusz.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal enum class SearchCategory(val title: Int) {
    ALL(R.string.search_all), PLACES(R.string.search_places), STATIONS(R.string.search_stations), VEHICLES(R.string.search_vehicles)
}

@Immutable
private data class SearchMatches(
    val query: String = "",
    val category: SearchCategory = SearchCategory.ALL,
    val routes: List<TransitRoute> = emptyList(),
    val stops: List<TransitStop> = emptyList(),
    val vehicles: List<Vehicle> = emptyList(),
)

@Composable
internal fun CategorizedSearch(
    state: UiState,
    query: String,
    category: SearchCategory,
    onCategoryChanged: (SearchCategory) -> Unit,
    onRoute: (String) -> Unit,
    onStop: (String) -> Unit,
    onVehicle: (String) -> Unit,
    onPlace: (PlaceResult) -> Unit,
    onSearchPlaces: (String, Boolean) -> Unit,
) {
    val parsed = remember(query) { TransitSearch.query(query) }
    val searchPlaces = category == SearchCategory.ALL || category == SearchCategory.PLACES
    val stopIndex by produceState<List<TransitSearch.StopEntry>?>(null, state.network?.stops, state.emmaStops) {
        value = withContext(Dispatchers.Default) { TransitSearch.indexStops(state.stops) }
    }
    val matches by produceState(SearchMatches(), query, category, stopIndex, state.network?.routes, state.emmaRoutes, state.vehicles, state.favorites) {
        delay(120)
        value = withContext(Dispatchers.Default) {
            SearchMatches(
                query,
                category,
                if (category == SearchCategory.ALL || category == SearchCategory.VEHICLES) TransitSearch.routes(state.routes, parsed) else emptyList(),
                if (category == SearchCategory.ALL || category == SearchCategory.STATIONS) TransitSearch.stops(stopIndex.orEmpty(), parsed, state.userLocation, state.favorites) else emptyList(),
                if (category == SearchCategory.ALL || category == SearchCategory.VEHICLES) TransitSearch.vehicles(state.vehicles, parsed) else emptyList(),
            )
        }
    }
    LaunchedEffect(query, searchPlaces) { onSearchPlaces(query, searchPlaces) }
    DisposableEffect(Unit) { onDispose { onSearchPlaces("", false) } }
    BackHandler(category != SearchCategory.ALL) { onCategoryChanged(SearchCategory.ALL) }
    val listState = rememberLazyListState()
    LaunchedEffect(query, category) { listState.scrollToItem(0) }
    val places = if (state.placeQuery == query) state.placeResults else emptyList()
    val local = if (matches.query == query && matches.category == category) matches else SearchMatches()
    val localLoading = matches.query != query || matches.category != category || stopIndex == null
    val placeLoading = searchPlaces && query.trim().length >= 2 && (state.placeQuery != query || state.placesLoading)
    val limit = if (category == SearchCategory.ALL) 3 else 100
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SearchCategory.entries, key = { it.name }, contentType = { "category_chip" }) { section ->
                FilterChip(selected = category == section, onClick = { onCategoryChanged(section) }, label = { Text(stringResource(section.title)) })
            }
        }
        if (query.isBlank()) {
            Text(stringResource(R.string.search_start), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
            if (searchPlaces) {
                item(contentType = "section_header") { SearchSectionHeader(SearchCategory.PLACES, places.size > 3 && category == SearchCategory.ALL) { onCategoryChanged(SearchCategory.PLACES) } }
                if (placeLoading) item(contentType = "progress") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) }
                if (state.placeQuery == query && state.placesUnavailable) item(contentType = "unavailable") {
                    Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.search_places_unavailable), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onSearchPlaces(query, true) }) { Text(stringResource(R.string.search_retry)) }
                    }
                }
                items(places.take(limit), key = { "place-${it.id}" }, contentType = { "place" }) { place ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPlace(place) }.padding(horizontal = 22.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(Icons.Rounded.LocationOn, null, Modifier.padding(12.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (place.address.isNotBlank()) Text(place.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        SearchArrow()
                    }
                }
                if (places.isEmpty() && !placeLoading && !state.placesUnavailable) item(contentType = "empty") { SearchEmpty() }
                if (places.isNotEmpty()) item(contentType = "attribution") {
                    TextButton(onClick = { uri.openUri("https://www.openstreetmap.org/copyright") }, Modifier.padding(horizontal = 14.dp)) {
                        Text(stringResource(R.string.search_places_attribution), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (category == SearchCategory.ALL || category == SearchCategory.STATIONS) {
                item(contentType = "section_header") { SearchSectionHeader(SearchCategory.STATIONS, local.stops.size > 3 && category == SearchCategory.ALL) { onCategoryChanged(SearchCategory.STATIONS) } }
                if (localLoading || !state.stopIndexLoaded && state.stopStatus == null) item(contentType = "progress") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) }
                state.stopStatus?.let { status -> item(contentType = "status") { Text(status, Modifier.padding(horizontal = 22.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) } }
                items(local.stops.take(limit), key = { "station-${it.id}" }, contentType = { "station" }) { stop -> StopSearchRow(stop, state, stop.id in state.favorites, onStop) }
                if (!localLoading && state.stopIndexLoaded && local.stops.isEmpty()) item(contentType = "empty") { SearchEmpty() }
            }
            if (category == SearchCategory.ALL || category == SearchCategory.VEHICLES) {
                item(contentType = "section_header") { SearchSectionHeader(SearchCategory.VEHICLES, local.routes.size + local.vehicles.size > 3 && category == SearchCategory.ALL) { onCategoryChanged(SearchCategory.VEHICLES) } }
                if (localLoading) item(contentType = "progress") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) }
                items(local.routes.take(limit), key = { "line-${it.id}" }, contentType = { "route" }) { route ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onRoute(route.id) }.padding(horizontal = 22.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        LineBadge(route)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(listOfNotNull(providerName(route.providerId), transportLabel(route.transportMode)).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (route.name == route.number) stringResource(R.string.ui_choose_direction) else route.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        SearchArrow()
                    }
                }
                val vehicleLimit = if (category == SearchCategory.ALL) (3 - local.routes.size).coerceAtLeast(0) else 100
                if (category == SearchCategory.VEHICLES && local.vehicles.isNotEmpty()) item(contentType = "header") {
                    Text(stringResource(R.string.search_live_vehicles), Modifier.padding(horizontal = 22.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
                }
                items(local.vehicles.take(vehicleLimit), key = { "vehicle-${it.id}" }, contentType = { "vehicle" }) { vehicle ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onVehicle(vehicle.id) }.padding(horizontal = 22.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        LineBadge(vehicle.routeNumber ?: "?", vehicle.providerId, vehicle.transportMode, vehicle.markerColor)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(vehicle.registration ?: vehicle.id.substringAfterLast(":"), style = MaterialTheme.typography.titleMedium)
                            vehicle.destination?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            Text(stringResource(R.string.search_live_vehicle), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        SearchArrow()
                    }
                }
                if (!localLoading && local.routes.isEmpty() && local.vehicles.isEmpty()) item(contentType = "empty") { SearchEmpty() }
            }
            if (category != SearchCategory.ALL && (local.routes.size >= 100 || local.stops.size >= 100 || local.vehicles.size >= 100)) item(contentType = "refine") {
                Text(stringResource(R.string.search_refine), Modifier.padding(22.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(category: SearchCategory, showMore: Boolean, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(category.title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        if (showMore) TextButton(onClick = onMore) { Text(stringResource(R.string.search_show_more)) }
    }
}

@Composable
private fun SearchEmpty() {
    Text(stringResource(R.string.search_no_matches), Modifier.padding(horizontal = 22.dp, vertical = 14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SearchArrow() {
    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}
