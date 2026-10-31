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

@Composable
private fun StationBadgeGrid(
    badges: List<StationBadgeItem>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val columns = if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f) 3 else 4
        badges.chunked(columns).forEach { rowBadges ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowBadges.forEach { badge ->
                    val (bgColor, textColor) = if (badge.isTrain) {
                        railLineColor(badge.label) to railLineTextColor(badge.label)
                    } else {
                        val base = lineColor(badge.provider, badge.mode, badge.sourceColor, badge.label)
                        val text = badgeTextColor(base)
                        base to text
                    }

                    Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = bgColor,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 28.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(vertical = 3.dp)
                            ) {
                                Text(
                                    text = badge.label,
                                    color = textColor,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.3).sp,
                                        lineHeight = 18.sp,
                                    ),
                                    fontFamily = OpenSans,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
                repeat(columns - rowBadges.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun FavoritesScreen(
    state: UiState,
    onStop: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRefresh: () -> Unit,
    onVisible: (Set<String>) -> Unit
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
                FilledTonalIconButton(onClick = onRefresh, shape = TransitDesign.controlShape) {
                    Icon(Icons.Rounded.Refresh, stringResource(R.string.favorites_refresh_lines))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(8.dp).padding(horizontal = 20.dp)) {
            if (state.favoritesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.offlineMode && state.favorites.any { it !in state.stopsById }) item(key = "offline_missing", contentType = "notice") {
                Text(stringResource(R.string.offline_missing_favorites), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.favoriteStations.isEmpty()) item(key = "empty", contentType = "notice") {
                Surface(shape = TransitDesign.panelShape, color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Rounded.FavoriteBorder, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(if (state.favorites.isEmpty()) R.string.favorites_empty else if (state.favoritesLoading) R.string.favorites_loading else R.string.favorites_index_unavailable),
                            style = MaterialTheme.typography.bodyLarge)
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
                    Column(Modifier.padding(bottom = 14.dp)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
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
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-0.2).sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                stationPlatform?.let { PlatformBadge(it) }
                            }
                            IconButton(
                                onClick = { onRemove(station.stop.id) },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = stringResource(R.string.ui_remove_favorite),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                )
                            }
                            Box {
                                IconButton(
                                    onClick = { menuExpanded = true },
                                    modifier = Modifier.size(40.dp)
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

                        if (lines.trainBadges.isNotEmpty()) {
                            StationBadgeGrid(
                                badges = lines.trainBadges,
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                            )
                        }

                        if (lines.otherBadges.isNotEmpty()) {
                            if (lines.trainBadges.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.favorites_other_lines),
                                    Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StationBadgeGrid(
                                badges = lines.otherBadges,
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                            )
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

                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onStop(station.stop.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f),
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                stringResource(R.string.favorites_all_departures),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
