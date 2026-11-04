package hu.app.bmenetrend.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
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
    FlowRow(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        badges.forEach { badge -> LineBadge(badge.label, badge.provider, badge.mode, badge.sourceColor, showIcon = false) }
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
                if (state.favorites.isNotEmpty()) FilledTonalIconButton(onClick = onRefresh, shape = TransitDesign.controlShape) {
                    Icon(Icons.Rounded.Refresh, stringResource(R.string.favorites_refresh_lines))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(8.dp).padding(horizontal = 20.dp)) {
            if (state.favoritesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.offlineMode && state.favorites.any { it !in state.stopsById }) item(key = "offline_missing", contentType = "notice") {
                Text(stringResource(R.string.offline_missing_favorites), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.favoriteStations.isEmpty()) item(key = "empty", contentType = "notice") {
                Surface(shape = TransitDesign.panelShape, color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        TransitIconTile(Icons.Rounded.FavoriteBorder)
                        Text(stringResource(R.string.design_favorites_intro), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(if (state.favorites.isEmpty()) R.string.favorites_empty else if (state.favoritesLoading) R.string.favorites_loading else R.string.favorites_index_unavailable),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    }
                }
            }
            items(state.favoriteStations, key = { "favorite:${it.stop.id}" }, contentType = { "station" }) { station ->
                val lines = remember(station.stop, station.platforms, state.routes) {
                    extractStationLines(station, state)
                }
                var menuExpanded by remember { mutableStateOf(false) }
                Surface(
                    onClick = { onStop(station.stop.id) },
                    modifier = Modifier.testTag("favorite:${station.stop.id}"),
                    shape = TransitDesign.panelShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                ) {
                    Column(Modifier.padding(bottom = 6.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 0.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val stationPlatform = station.stop.platformCode?.ifBlank { null }
                                ?: hu.app.bmenetrend.data.BkkPlatformIndex.getPlatformCode(station.stop.id)
                            Row(
                                modifier = Modifier
                                    .weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = station.stop.name,
                                    modifier = Modifier.weight(1f, fill = false),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                stationPlatform?.let { PlatformBadge(it) }
                            }
                            Box {
                                IconButton(
                                    onClick = { menuExpanded = true },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.MoreVert,
                                        contentDescription = stringResource(R.string.search_show_more),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false },
                                    shape = RoundedCornerShape(12.dp),
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.favorites_all_departures)) },
                                        leadingIcon = { Icon(Icons.Rounded.Schedule, null) },
                                        onClick = {
                                            menuExpanded = false
                                            onStop(station.stop.id)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.ui_remove_favorite)) },
                                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                                        onClick = {
                                            menuExpanded = false
                                            onRemove(station.stop.id)
                                        }
                                    )
                                }
                            }
                        }
                        if (station.loading && lines.trainBadges.isEmpty() && lines.otherBadges.isEmpty()) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
                        }

                        if (lines.trainBadges.isNotEmpty() || lines.otherBadges.isNotEmpty()) {
                            StationBadgeGrid(lines.trainBadges + lines.otherBadges)
                        }

                        if (lines.trainBadges.isEmpty() && lines.otherBadges.isEmpty() && !station.loading) {
                            Text(
                                stringResource(if (station.unavailable) R.string.favorites_lines_unavailable else R.string.favorites_no_lines),
                                Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (station.unavailable && (lines.trainBadges.isNotEmpty() || lines.otherBadges.isNotEmpty())) {
                            Text(
                                stringResource(R.string.favorites_lines_stale),
                                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        station.departures.filter { !it.isCancelled && it.expectedAt >= state.now }.minByOrNull { it.expectedAt }?.let { next ->
                            val wait = kotlin.math.ceil(java.time.Duration.between(state.now, next.expectedAt).seconds / 60.0).toInt().coerceAtLeast(0)
                            val nextRoute = state.findRoute(next.routeId)
                            HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LineBadge(next.routeNumber, nextRoute?.providerId ?: station.stop.providerId,
                                    nextRoute?.transportMode ?: station.stop.transportMode, nextRoute?.color, showIcon = false)
                                Text(next.destination, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                                DepartureCount(wait, rememberFavoriteClock.format(next.expectedAt), next.kind == ArrivalKind.LIVE)
                            }
                        }
                        Text(providerNames(station.stop.providerIds), Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            FilledTonalButton(onClick = onBrowse,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 56.dp),
                shape = TransitDesign.controlShape) {
                Icon(Icons.Rounded.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.design_add_stop), style = MaterialTheme.typography.labelLarge)
            }
        }

    }
}

private val rememberFavoriteClock = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.of("Europe/Budapest"))
