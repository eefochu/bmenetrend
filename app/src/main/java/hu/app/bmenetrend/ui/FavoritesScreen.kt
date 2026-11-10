@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package hu.app.bmenetrend.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.*
import kotlinx.coroutines.flow.distinctUntilChanged

data class StationBadgeItem(
    val id: String,
    val label: String,
    val isTrain: Boolean,
    val provider: String,
    val mode: String?,
    val sourceColor: String? = null,
)

data class StationLines(
    val trainBadges: List<StationBadgeItem>,
    val otherBadges: List<StationBadgeItem>,
)

private val tokenRegex = Regex("([a-zA-Z]+|\\d+)")

internal fun naturalCompare(a: String, b: String): Int {
    val tokensA = tokenRegex.findAll(a).map { it.value }.toList()
    val tokensB = tokenRegex.findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(tokensA.size, tokensB.size)) {
        val tA = tokensA[i]
        val tB = tokensB[i]
        val nA = tA.toIntOrNull()
        val nB = tB.toIntOrNull()
        if (nA != null && nB != null) {
            val cmp = nA.compareTo(nB)
            if (cmp != 0) return cmp
        } else if (nA != null) {
            return -1
        } else if (nB != null) {
            return 1
        } else {
            // Case-sensitive comparison: uppercase before lowercase in ASCII so 'Z30' comes before 'rjx'
            val cmp = tA.compareTo(tB)
            if (cmp != 0) return cmp
        }
    }
    return tokensA.size.compareTo(tokensB.size)
}

internal fun isTrainRoute(route: TransitRoute): Boolean {
    return RailSupport.isTrain(route.transportMode) ||
           route.providerId in setOf("mav", "gysev") ||
           route.transportMode in setOf("RAIL", "SUBURBAN_RAILWAY", "TRAMTRAIN") ||
           route.number.matches(Regex("^[GSZgsz]\\d+.*")) ||
           route.number in setOf("S", "SZ", "G", "GY", "Z", "IC", "IR", "RJ", "RJX", "EC", "EN", "EX")
}

internal fun extractStationLines(station: NearbyStation, state: UiState): StationLines {
    val allPlatformRouteIds = (station.platforms.flatMap { it.routeIds } + station.stop.routeIds).distinct()
    // The complete station catalogue is independent of today's departures and time window.
    val allRouteIds = allPlatformRouteIds

    val rawTrainBadges = mutableListOf<StationBadgeItem>()
    val rawOtherBadges = mutableListOf<StationBadgeItem>()

    fun addBadge(id: String, label: String, isTrain: Boolean, provider: String, mode: String?, color: String? = null) {
        if (label.isBlank() || label == "?") return
        val item = StationBadgeItem(id, label, isTrain, provider, mode, color)
        if (isTrain) rawTrainBadges.add(item) else rawOtherBadges.add(item)
    }

    // 1. From routes catalogue
    for (routeId in allRouteIds) {
        val route = state.findRoute(routeId)
        if (route != null) {
            val isTrain = isTrainRoute(route)
            val label = if (isTrain) RailSupport.canonicalBadge(route.number, route.name) else route.number
            addBadge(route.id, label, isTrain, route.providerId, route.transportMode, route.color)
        }
    }

    // Deduplicate by label (so there is only one Gy, one S, one Sz, one IC, one IR, one S10, etc.)
    // and sort naturally (EC, EN, Ex, G10, G40, G43, G44, IC, IR, S, S10, S12, S30, S36, S40, S42, Sz, Z30, rjx)
    val trainBadges = rawTrainBadges
        .distinctBy { it.label }
        .sortedWith { b1, b2 -> naturalCompare(b1.label, b2.label) }

    val otherBadges = rawOtherBadges
        .distinctBy { it.label }
        .sortedWith { b1, b2 -> naturalCompare(b1.label, b2.label) }

    return StationLines(
        trainBadges = trainBadges,
        otherBadges = otherBadges,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StationBadgeGrid(badges: List<StationBadgeItem>, modifier: Modifier = Modifier) {
    // Every badge has the same size, so trains, buses, metro and HÉV form one even grid; the cell width
    // is stretched from UniformBadge so the columns end flush with the card on both sides.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = 6.dp
        val columns = ((maxWidth + gap) / (UniformBadge.width + gap)).toInt().coerceAtLeast(1)
        // Whole pixels, rounded down: a fractional overflow would push each row's last badge onto the next line.
        val cell = with(androidx.compose.ui.platform.LocalDensity.current) {
            val cellPx = (constraints.maxWidth - gap.roundToPx() * (columns - 1)) / columns
            androidx.compose.ui.unit.DpSize(cellPx.toDp(), UniformBadge.height)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap),
            maxItemsInEachRow = columns) {
            badges.forEach { badge -> LineBadge(badge.label, badge.provider, badge.mode, badge.sourceColor, showIcon = false, uniform = cell) }
        }
    }
}

@Composable
fun FavoritesScreen(
    state: UiState,
    onStop: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRefresh: () -> Unit,
    onVisible: (Set<String>) -> Unit,
    onBrowse: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val visibleAction by rememberUpdatedState(onVisible)
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? String)?.takeIf { key -> key.startsWith("favorite:") }?.removePrefix("favorite:") }.toSet() }
            .distinctUntilChanged().collect { visibleAction(it) }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
            TransitPageIntro(stringResource(R.string.favorites_title), null) {
                if (state.favorites.isNotEmpty()) CookieIconButton(onClick = onRefresh, enabled = !state.favoritesLoading, size = 52.dp) {
                    if (state.favoritesLoading) TransitLoading(size = 32.dp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    else Icon(Icons.Rounded.Refresh, stringResource(R.string.favorites_refresh_lines))
                }
            }
        }
        TransitRefreshBar(state.favoritesLoading, Modifier.padding(horizontal = 20.dp))
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
            if (state.offlineMode && state.favorites.any { it !in state.stopsById }) item(key = "offline_missing", contentType = "notice") {
                Text(stringResource(R.string.offline_missing_favorites), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.favoriteStations.isEmpty()) item(key = "empty", contentType = "notice") {
                Surface(Modifier.animateItem(), shape = TransitDesign.panelShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ShapeIconTile(Icons.Rounded.Favorite, shape = MaterialShapes.Cookie12Sided)
                        Text(stringResource(R.string.design_favorites_intro), style = MaterialTheme.typography.titleLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text(stringResource(if (state.favorites.isEmpty()) R.string.favorites_empty else if (state.favoritesLoading) R.string.favorites_loading else R.string.favorites_index_unavailable),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)

                    }
                }
            }
            val stations = state.favoriteStations
            itemsIndexed(stations, key = { _, it -> "favorite:${it.stop.id}" }, contentType = { _, _ -> "station" }) { index, station ->
                FavoriteStationCard(station, state, index, stations.size, onStop, onRemove,
                    // Springy placement: removing or reordering a favourite slides the others into place.
                    Modifier.animateItem(fadeInSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
                        placementSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                        fadeOutSpec = MaterialTheme.motionScheme.fastEffectsSpec()))
            }
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            // The page's one primary action: its corners spring rounder under the finger (Expressive shape morph).
            // Filled primary, like the planner's "Mutasd az útvonalakat": light accent with dark text in dark mode.
            Button(onClick = onBrowse,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 56.dp),
                shapes = ButtonDefaults.shapes(shape = TransitDesign.panelShape, pressedShape = RoundedCornerShape(28.dp)),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary)) {
                Icon(Icons.Rounded.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.design_add_stop), style = MaterialTheme.typography.labelLarge)
            }
        }

    }
}

/**
 * One favourite inside the grouped list: cookie transport badge, name and operator,
 * an even grid of same-size line badges, and the next departure in its own inset strip.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FavoriteStationCard(station: NearbyStation, state: UiState, index: Int, count: Int,
                                onStop: (String) -> Unit, onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    val lines = remember(station.stop, station.platforms, state.routes) { extractStationLines(station, state) }
    val badges = lines.trainBadges + lines.otherBadges
    val modes = remember(badges) { badges.mapNotNull { it.mode }.toSet() }
    val rail = lines.trainBadges.isNotEmpty()
    val stationPlatform = station.stop.platformCode?.ifBlank { null }
        ?: hu.app.bmenetrend.data.BkkPlatformIndex.getPlatformCode(station.stop.id)
    var menuExpanded by remember { mutableStateOf(false) }
    SegmentedItem(index, count, modifier.testTag("favorite:${station.stop.id}"), onClick = { onStop(station.stop.id) }) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ShapeIconTile(stopModeIcon(modes, rail), size = 44.dp, shape = MaterialShapes.Cookie9Sided)
                Column(Modifier.weight(1f).padding(top = 1.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(station.stop.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        stationPlatform?.let { PlatformBadge(it) }
                    }
                    Text(providerNames(station.stop.providerIds), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.search_show_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // Expressive menu: the everyday action and the destructive one sit in separate groups.
                    DropdownMenuPopup(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuGroup(shapes = MenuDefaults.groupShape(0, 2)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.favorites_all_departures)) },
                                shape = MenuDefaults.itemShape(0, 1).shape,
                                leadingIcon = { Icon(Icons.Rounded.Schedule, null) },
                                onClick = { menuExpanded = false; onStop(station.stop.id) },
                            )
                        }
                        Spacer(Modifier.height(MenuDefaults.GroupSpacing))
                        DropdownMenuGroup(shapes = MenuDefaults.groupShape(1, 2)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.ui_remove_favorite)) },
                                shape = MenuDefaults.itemShape(0, 1).shape,
                                leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error,
                                    leadingIconColor = MaterialTheme.colorScheme.error),
                                onClick = { menuExpanded = false; onRemove(station.stop.id) },
                            )
                        }
                    }
                }
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    badges.isNotEmpty() -> StationBadgeGrid(badges)
                    station.loading -> TransitRefreshBar(true)
                    else -> Text(stringResource(if (station.unavailable) R.string.favorites_lines_unavailable else R.string.favorites_no_lines),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (station.unavailable && badges.isNotEmpty()) Text(stringResource(R.string.favorites_lines_stale),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (badges.isNotEmpty()) SourceTag(station.sources)
                station.departures.filter { !it.isCancelled && it.expectedAt >= state.now }.minByOrNull { it.expectedAt }?.let { next ->
                    val wait = kotlin.math.ceil(java.time.Duration.between(state.now, next.expectedAt).seconds / 60.0).toInt().coerceAtLeast(0)
                    val nextRoute = state.findRoute(next.routeId)
                    // The card's one live element sits in its own inset strip.
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LineBadge(next.routeNumber, nextRoute?.providerId ?: station.stop.providerId,
                            nextRoute?.transportMode ?: station.stop.transportMode, nextRoute?.color, showIcon = false, uniform = UniformBadge)
                        Text(DestinationFormatter.clean(next.destination, next.routeNumber).ifBlank { next.destination }, Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMediumEmphasized, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        DepartureCount(wait, rememberFavoriteClock.format(next.expectedAt), next.kind == ArrivalKind.LIVE)
                    }
                }
            }
        }
    }
}

private val rememberFavoriteClock = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.of("Europe/Budapest"))
