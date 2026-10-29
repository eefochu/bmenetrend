package hu.app.bmenetrend.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class SearchCategory(val title: Int) {
    ALL(R.string.search_all), STATIONS(R.string.search_stations), PLACES(R.string.search_places), VEHICLES(R.string.search_vehicles)
}

@Immutable
private data class SearchMatches<T>(val query: String = "", val items: List<T> = emptyList())

@Immutable
private data class StationMatches(val query: String = "", val items: List<TransitStop> = emptyList(), val recommended: TransitStop? = null)

private suspend fun buildSearchStopIndex(local: List<TransitStop>, national: List<TransitStop>): List<TransitSearch.StopEntry> =
    withContext(Dispatchers.Default) { TransitSearch.indexStops(local, national) }

@Composable
@OptIn(ExperimentalLayoutApi::class)
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
    onQueryChanged: (String) -> Unit = {},
    buildStopIndex: suspend (List<TransitStop>, List<TransitStop>) -> List<TransitSearch.StopEntry> = ::buildSearchStopIndex,
) {
    val parsed = remember(query) { TransitSearch.query(query) }
    val searchPlaces = !state.offlineMode && (category == SearchCategory.ALL || category == SearchCategory.PLACES)
    val localStops = state.network?.stops.orEmpty()
    val nationalStops = state.emmaStops
    val routes = remember(state.network?.routes, state.emmaRoutes) { state.routes }
    val stopIndex by produceState(TransitSearch.getCachedStopIndex(localStops, nationalStops), localStops, nationalStops) {
        value = buildStopIndex(localStops, nationalStops)
    }
    // Query results belong to their data source, not to the selected tab. Stop
    // indexing and live vehicle updates must not hold up or restart line matching.
    val routeMatches by produceState(SearchMatches<TransitRoute>(), parsed, routes) {
        value = if (parsed.text.isBlank()) SearchMatches(parsed.text) else withContext(Dispatchers.Default) {
            SearchMatches(parsed.text, TransitSearch.routes(routes, parsed))
        }
    }
    val vehicleMatches by produceState(SearchMatches<Vehicle>(), parsed, state.vehicles) {
        value = if (parsed.text.isBlank()) SearchMatches(parsed.text) else withContext(Dispatchers.Default) {
            SearchMatches(parsed.text, TransitSearch.vehicles(state.vehicles, parsed))
        }
    }
    val stopMatches by produceState(StationMatches(), parsed, stopIndex, state.userLocation, state.favorites) {
        value = if (stopIndex == null) StationMatches(parsed.text) else withContext(Dispatchers.Default) {
            if (parsed.text.isBlank()) StationMatches(parsed.text, stopIndex.orEmpty().filter { it.stop.id in state.favorites }.map { it.stop }.take(5))
            else StationMatches(parsed.text, TransitSearch.stops(stopIndex.orEmpty(), parsed, state.userLocation, state.favorites),
                StationRecommendations.stops(stopIndex.orEmpty(), parsed, state.userLocation, state.favorites).firstOrNull())
        }
    }
    LaunchedEffect(query, searchPlaces) { onSearchPlaces(query, searchPlaces) }
    DisposableEffect(Unit) { onDispose { onSearchPlaces("", false) } }
    BackHandler(category != SearchCategory.ALL) { onCategoryChanged(SearchCategory.ALL) }
    val listState = rememberLazyListState()
    LaunchedEffect(query, category) { listState.scrollToItem(0) }
    val places = if (state.placeQuery == query) state.placeResults else emptyList()
    val matchingRoutes = if (routeMatches.query == parsed.text) routeMatches.items else emptyList()
    val matchingVehicles = if (vehicleMatches.query == parsed.text) vehicleMatches.items else emptyList()
    val matchingStops = if (stopMatches.query == parsed.text) stopMatches.items else emptyList()
    val recommendation = stopMatches.recommended.takeIf { stopMatches.query == parsed.text }
    val stopsLoading = stopMatches.query != parsed.text || stopIndex == null
    val routesLoading = routeMatches.query != parsed.text
    val vehiclesLoading = vehicleMatches.query != parsed.text
    val placeLoading = searchPlaces && query.trim().length >= 2 && (state.placeQuery != query || state.placesLoading)
    val limit = if (category == SearchCategory.ALL) 3 else 100
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        if (state.offlineMode) Text(stringResource(R.string.offline_live_unavailable), Modifier.padding(horizontal = 22.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SearchCategory.entries, key = { it.name }, contentType = { "category_chip" }) { section ->
                FilterChip(selected = category == section, onClick = { onCategoryChanged(section) },shape=TransitDesign.controlShape,
                    modifier=Modifier.heightIn(min=48.dp),label = { Text(stringResource(section.title)) })
            }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
            if (query.isBlank()) {
                item(key = "discover", contentType = "intro") {
                    EmptyState(stringResource(R.string.search_discover_title), stringResource(R.string.search_discover_body), Icons.Rounded.Search)
                    SearchGroupTitle(stringResource(R.string.search_examples))
                    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Keleti pályaudvar", "Kelenföld", "1 villamos").forEach { example ->
                            SuggestionChip(onClick = { onQueryChanged(example) }, label = { Text(example) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
                if (matchingStops.isNotEmpty()) {
                    item(key = "favorites_header", contentType = "section_header") { SearchGroupTitle(stringResource(R.string.search_favorites)) }
                    items(matchingStops, key = { "favorite-${it.id}" }, contentType = { "station" }) { stop -> StopSearchRow(stop, state, true, onStop) }
                }
                return@LazyColumn
            }
            if (recommendation != null && (category == SearchCategory.ALL || category == SearchCategory.STATIONS)) {
                item(key = "recommendation", contentType = "recommendation") {
                    SearchRecommendation(StopClusters.stopDisplayName(recommendation.name), providerNames(recommendation.providerIds), { onStop(recommendation.id) })
                }
            }
            // Stations primarily
            if (category == SearchCategory.STATIONS || category == SearchCategory.ALL && (matchingStops.isNotEmpty() || stopsLoading || state.stopStatus != null)) {
                item(contentType = "section_header") { SearchSectionHeader(SearchCategory.STATIONS, matchingStops.size > 3 && category == SearchCategory.ALL) { onCategoryChanged(SearchCategory.STATIONS) } }
                if (stopsLoading || matchingStops.isEmpty() && !state.stopIndexLoaded && state.stopStatus == null) item(contentType = "progress") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) }
                state.stopStatus?.let { status -> item(contentType = "status") { Text(status, Modifier.padding(horizontal = 22.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) } }
                items(matchingStops.take(limit), key = { "station-${it.id}" }, contentType = { "station" }) { stop -> StopSearchRow(stop, state, stop.id in state.favorites, onStop) }
                if (category == SearchCategory.STATIONS && !stopsLoading && state.stopIndexLoaded && matchingStops.isEmpty() && recommendation == null) item(contentType = "empty") { SearchEmpty() }
            }
            // Normal places
            if (searchPlaces && (category == SearchCategory.PLACES || places.isNotEmpty() || placeLoading || state.placesUnavailable)) {
                item(contentType = "section_header") { SearchSectionHeader(SearchCategory.PLACES, places.size > 3 && category == SearchCategory.ALL) { onCategoryChanged(SearchCategory.PLACES) } }
                if (placeLoading) item(contentType = "progress") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) }
                if (state.placeQuery == query && state.placesUnavailable) item(contentType = "unavailable") {
                    Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.search_places_unavailable), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onSearchPlaces(query, true) }) { Text(stringResource(R.string.search_retry)) }
                    }
                }
                items(places.take(limit), key = { "place-${it.id}" }, contentType = { "place" }) { place ->
                    SearchResultCard(place.name, place.address, { onPlace(place) }, leading = { TransitIconTile(Icons.Rounded.LocationOn) })
                }

                if (category == SearchCategory.PLACES && places.isEmpty() && !placeLoading && !state.placesUnavailable) item(contentType = "empty") { SearchEmpty() }
                if (places.isNotEmpty()) item(contentType = "attribution") {
                    TextButton(onClick = { uri.openUri("https://www.openstreetmap.org/copyright") }, Modifier.padding(horizontal = 14.dp)) {
                        Text(stringResource(R.string.search_places_attribution), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            // Vehicles / Lines
            if (category == SearchCategory.VEHICLES || category == SearchCategory.ALL && (matchingRoutes.isNotEmpty() || matchingVehicles.isNotEmpty() || routesLoading || vehiclesLoading || state.lineCatalogueLoading || state.lineCatalogueStatus != null)) {
                item(contentType = "section_header") { SearchSectionHeader(SearchCategory.VEHICLES, matchingRoutes.size + matchingVehicles.size > 3 && category == SearchCategory.ALL) { onCategoryChanged(SearchCategory.VEHICLES) } }
                if (routesLoading || vehiclesLoading || matchingRoutes.isEmpty() && matchingVehicles.isEmpty() && state.lineCatalogueLoading) item(contentType = "progress") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) }
                state.lineCatalogueStatus?.let { status -> item(contentType = "status") {
                    Text(status, Modifier.padding(horizontal = 22.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                } }
                items(matchingRoutes.take(limit), key = { "line-${it.id}" }, contentType = { "route" }) { route ->
                    SearchResultCard(if (route.name == route.number) stringResource(R.string.ui_choose_direction) else route.name,
                        listOf(providerName(route.providerId), serviceTypeLabel(route.providerId, route.transportMode)).joinToString(" · "),
                        { onRoute(route.id) }, leading = { LineBadge(route) })
                }
                val vehicleLimit = if (category == SearchCategory.ALL) (3 - matchingRoutes.size).coerceAtLeast(0) else 100
                if (category == SearchCategory.VEHICLES && matchingVehicles.isNotEmpty()) item(contentType = "header") {
                    Text(stringResource(R.string.search_live_vehicles), Modifier.padding(horizontal = 22.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall)
                }
                items(matchingVehicles.take(vehicleLimit), key = { "vehicle-${it.id}" }, contentType = { "vehicle" }) { vehicle ->
                    SearchResultCard(vehicle.serviceName ?: vehicle.registration ?: vehicle.id.substringAfterLast(":"),
                        listOfNotNull(vehicle.destination, stringResource(R.string.search_live_vehicle)).joinToString(" · "),
                        { onVehicle(vehicle.id) }, leading = { VehicleServiceBadge(vehicle) })
                }
                if (category == SearchCategory.VEHICLES && !routesLoading && !vehiclesLoading && !state.lineCatalogueLoading && matchingRoutes.isEmpty() && matchingVehicles.isEmpty()) item(contentType = "empty") { SearchEmpty() }
            }
            if (category == SearchCategory.ALL && recommendation == null && matchingStops.isEmpty() && places.isEmpty() && matchingRoutes.isEmpty() && matchingVehicles.isEmpty() &&
                !stopsLoading && !routesLoading && !vehiclesLoading && !placeLoading && !state.lineCatalogueLoading) {
                item(key = "no_results", contentType = "empty") { SearchEmpty() }
            }
            if (category != SearchCategory.ALL && (matchingRoutes.size >= 100 || matchingStops.size >= 100 || matchingVehicles.size >= 100)) item(contentType = "refine") {
                Text(stringResource(R.string.search_refine), Modifier.padding(22.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(category: SearchCategory, showMore: Boolean, onMore: () -> Unit) {
    SearchGroupTitle(stringResource(category.title), action = {
        if (showMore) TextButton(onClick = onMore) { Text(stringResource(R.string.search_show_more)) }
    })
}

@Composable
private fun SearchEmpty() {
    Text(stringResource(R.string.search_no_matches), Modifier.padding(horizontal = 22.dp, vertical = 14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
