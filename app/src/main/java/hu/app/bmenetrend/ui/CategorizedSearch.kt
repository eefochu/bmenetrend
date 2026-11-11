@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package hu.app.bmenetrend.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.MaterialShapes
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Place
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
    val networkStops = state.network?.stops.orEmpty()
    val nationalStops = state.emmaStops
    val routes = remember(state.network?.routes, state.emmaRoutes) { state.routes }
    val stopIndex by produceState(TransitSearch.getCachedStopIndex(networkStops, nationalStops), networkStops, nationalStops) {
        value = buildStopIndex(networkStops, nationalStops)
    }
    // Query results belong to their data source, not to the selected tab. Stop
    // indexing and live vehicle updates must not hold up or restart line matching.
    val routeMatches by produceState(SearchMatches<TransitRoute>(), parsed, routes) {
        value = if (parsed.text.isBlank()) SearchMatches(parsed.text) else withContext(Dispatchers.Default) {
            SearchMatches(parsed.text, TransitSearch.routes(routes, parsed))
        }
    }
    // Loaded vehicles first; BKK search adds matching vehicles outside the loaded map area.
    val remoteVehicles = state.remoteVehicles.takeIf { it.query == query }?.vehicles.orEmpty()
    val vehicleMatches by produceState(SearchMatches<Vehicle>(), parsed, state.vehicles, remoteVehicles) {
        value = if (parsed.text.isBlank()) SearchMatches(parsed.text) else withContext(Dispatchers.Default) {
            val loaded = TransitSearch.vehicles(state.vehicles, parsed)
            val ids = loaded.mapTo(HashSet()) { it.id }
            SearchMatches(parsed.text, loaded + remoteVehicles.filter { it.id !in ids })
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
    val remoteStations = state.remoteStations.takeIf { it.query == query && parsed.modes.isEmpty() }
    val localStops = if (stopMatches.query == parsed.text) stopMatches.items else emptyList()
    // BKK's station records lead; the local index only adds stations the remote results do not cover.
    val matchingStops = remember(localStops, remoteStations) {
        if (remoteStations == null || remoteStations.stops.isEmpty()) localStops else {
            val covered = remoteStations.coveredSourceIds
            val remoteIds = remoteStations.stops.mapTo(HashSet()) { it.id }
            remoteStations.stops + localStops.filter { stop -> stop.id !in remoteIds &&
                stop.id.removePrefix("emma:") !in covered && stop.sourceStopIds.none { it in covered } }
        }
    }
    val recommendation = stopMatches.recommended.takeIf { stopMatches.query == parsed.text }
    val recommendedId = recommendation?.id.takeIf { category == SearchCategory.ALL || category == SearchCategory.STATIONS }
    val otherStops = remember(matchingStops, recommendedId) {
        val recommendedSources = recommendation?.takeIf { recommendedId != null }?.sourceStopIds.orEmpty().toSet()
        // A remote record for the recommended station's own platforms would repeat the recommendation.
        matchingStops.filter { it.id != recommendedId && (recommendedSources.isEmpty() || it.sourceStopIds.none(recommendedSources::contains)) }
    }
    val stopsLoading = stopMatches.query != parsed.text || stopIndex == null
    val routesLoading = routeMatches.query != parsed.text
    val vehiclesLoading = vehicleMatches.query != parsed.text
    val placeLoading = searchPlaces && query.trim().length >= 2 && (state.placeQuery != query || state.placesLoading)
    val limit = if (category == SearchCategory.ALL) 3 else 100
    val uri = LocalUriHandler.current
    Column(Modifier.fillMaxSize()) {
        if (state.offlineMode) Text(stringResource(R.string.offline_live_unavailable), Modifier.padding(horizontal = 22.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        ConnectedSelector(SearchCategory.entries, category, onCategoryChanged, stringResource(R.string.ui_search_short),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp)) { stringResource(it.title) }
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
            if (query.isBlank()) {
                item(key = "discover", contentType = "intro") {
                    EmptyState(stringResource(R.string.search_discover_title), stringResource(R.string.search_discover_body), Icons.Rounded.Search)
                    SearchGroupTitle(stringResource(R.string.search_examples))
                    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Keleti pályaudvar", "Kelenföld", "1 villamos").forEach { example ->
                            SuggestionChip(onClick = { onQueryChanged(example) }, label = { Text(example) }, modifier = Modifier.heightIn(min = 48.dp),
                                icon = { Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) })
                        }
                    }
                }
                if (matchingStops.isNotEmpty()) {
                    item(key = "favorites_header", contentType = "section_header") { SearchGroupTitle(stringResource(R.string.search_favorites)) }
                    itemsIndexed(matchingStops, key = { _, it -> "favorite-${it.id}" }, contentType = { _, _ -> "station" }) { index, stop ->
                        StopSearchRow(stop, state, true, onStop, index, matchingStops.size)
                    }
                }
                return@LazyColumn
            }
            if (recommendation != null && (category == SearchCategory.ALL || category == SearchCategory.STATIONS)) {
                item(key = "recommendation", contentType = "recommendation") {
                    // The same station in the result list carries the full, consolidated line set (rail included).
                    val lineSource = matchingStops.firstOrNull { it.id == recommendation.id } ?: recommendation
                    val lines = remember(lineSource.routeIds, routes) {
                        lineSource.routeIds.mapNotNull(state::findRoute).distinctBy { it.number to it.transportMode }
                    }
                    SearchRecommendation(StopClusters.stopDisplayName(recommendation.name), providerNames(recommendation.providerIds),
                        { onStop(recommendation.id) },
                        leading = { StopModeBadge(recommendation, lines, size = 56.dp, color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary) },
                        // The recommendation carries its lines itself, so it no longer repeats as the first stop result.
                        supporting = { OneLineBadges(lines, Modifier.padding(top = 4.dp)) })
                }
            }
            // Stations primarily
            if (category == SearchCategory.STATIONS || category == SearchCategory.ALL && (otherStops.isNotEmpty() || stopsLoading || state.stopStatus != null)) {
                item(contentType = "section_header") { SearchSectionHeader(SearchCategory.STATIONS, otherStops.size > 3 && category == SearchCategory.ALL) { onCategoryChanged(SearchCategory.STATIONS) } }
                if (stopsLoading || matchingStops.isEmpty() && !state.stopIndexLoaded && state.stopStatus == null) item(contentType = "progress") { TransitRefreshBar(true, Modifier.padding(horizontal = 22.dp)) }
                state.stopStatus?.let { status -> item(contentType = "status") { Text(status, Modifier.padding(horizontal = 22.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium) } }
                val shownStops = otherStops.take(limit)
                itemsIndexed(shownStops, key = { _, it -> "station-${it.id}" }, contentType = { _, _ -> "station" }) { index, stop ->
                    StopSearchRow(stop, state, stop.id in state.favorites, onStop, index, shownStops.size,
                        sources = remoteStations?.sources?.get(stop.id).orEmpty())
                }
                if (category == SearchCategory.STATIONS && !stopsLoading && state.stopIndexLoaded && matchingStops.isEmpty() && recommendation == null) item(contentType = "empty") { SearchEmpty() }
            }
            // Normal places
            if (searchPlaces && (category == SearchCategory.PLACES || places.isNotEmpty() || placeLoading || state.placesUnavailable)) {
                item(contentType = "section_header") { SearchSectionHeader(SearchCategory.PLACES, places.size > 3 && category == SearchCategory.ALL) { onCategoryChanged(SearchCategory.PLACES) } }
                if (placeLoading) item(contentType = "progress") { TransitRefreshBar(true, Modifier.padding(horizontal = 22.dp)) }
                if (state.placeQuery == query && state.placesUnavailable) item(contentType = "unavailable") {
                    Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.search_places_unavailable), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onSearchPlaces(query, true) }) { Text(stringResource(R.string.search_retry)) }
                    }
                }
                val shownPlaces = places.take(limit)
                itemsIndexed(shownPlaces, key = { _, it -> "place-${it.id}" }, contentType = { _, _ -> "place" }) { index, place ->
                    SearchResultCard(place.name, place.address, { onPlace(place) }, leading = { ShapeIconTile(Icons.Rounded.Place, size = 44.dp, shape = MaterialShapes.Cookie4Sided) },
                        index = index, count = shownPlaces.size)
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
                if (routesLoading || vehiclesLoading || matchingRoutes.isEmpty() && matchingVehicles.isEmpty() && state.lineCatalogueLoading) item(contentType = "progress") { TransitRefreshBar(true, Modifier.padding(horizontal = 22.dp)) }
                state.lineCatalogueStatus?.let { status -> item(contentType = "status") {
                    Text(status, Modifier.padding(horizontal = 22.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                } }
                val shownRoutes = matchingRoutes.take(limit)
                val vehicleLimit = if (category == SearchCategory.ALL) (3 - matchingRoutes.size).coerceAtLeast(0) else 100
                val shownVehicles = matchingVehicles.take(vehicleLimit)
                // Lines and live vehicles form one group in "All", two groups (with a heading between) in their own tab.
                val joined = category != SearchCategory.VEHICLES
                val lineGroup = if (joined) shownRoutes.size + shownVehicles.size else shownRoutes.size
                itemsIndexed(shownRoutes, key = { _, it -> "line-${it.id}" }, contentType = { _, _ -> "route" }) { index, route ->
                    SearchResultCard(if (route.name == route.number) stringResource(R.string.ui_choose_direction) else route.name,
                        listOf(providerName(route.providerId), serviceTypeLabel(route.providerId, route.transportMode)).joinToString(" · "),
                        { onRoute(route.id) }, leading = { LineBadge(route) }, index = index, count = lineGroup)
                }
                if (category == SearchCategory.VEHICLES && matchingVehicles.isNotEmpty()) item(contentType = "header") {
                    Text(stringResource(R.string.search_live_vehicles), Modifier.padding(horizontal = 22.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                itemsIndexed(shownVehicles, key = { _, it -> "vehicle-${it.id}" }, contentType = { _, _ -> "vehicle" }) { index, vehicle ->
                    SearchResultCard(vehicle.serviceName ?: vehicle.registration ?: vehicle.id.substringAfterLast(":"),
                        listOfNotNull(vehicle.destination, stringResource(R.string.search_live_vehicle)).joinToString(" · "),
                        { onVehicle(vehicle.id) }, leading = { VehicleServiceBadge(vehicle) },
                        badge = { VehicleSourceTag(vehicle) },
                        index = if (joined) shownRoutes.size + index else index, count = if (joined) lineGroup else shownVehicles.size)
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ShapeIconTile(Icons.Rounded.SearchOff, size = 72.dp, shape = MaterialShapes.Cookie7Sided)
        Text(stringResource(R.string.search_no_matches), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
