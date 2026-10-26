package hu.app.bmenetrend.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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

internal fun naturalCompare(a: String, b: String): Int {
    val tokenRegex = Regex("([a-zA-Z]+|\\d+)")
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
    val departureRouteIds = station.departures.map { it.routeId }.distinct()
    val cluster = StopClusters.clusterFor(station.stop, state.stops)
    val clusterRouteIds = cluster.flatMap { it.routeIds }
    val allRouteIds = (allPlatformRouteIds + departureRouteIds + clusterRouteIds).distinct()

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

    // 2. From departures (covers ad-hoc trips, EMMA train departures, etc.)
    for (dep in station.departures) {
        val route = state.findRoute(dep.routeId)
        val isTrain = if (route != null) isTrainRoute(route) else RailSupport.isTrainDeparture(dep.routeNumber, dep.serviceName)
        val label = if (isTrain) RailSupport.canonicalBadge(route?.number ?: dep.routeNumber, dep.serviceName)
                    else route?.number ?: dep.routeNumber.ifBlank { dep.routeId.substringAfter(":") }
        val provider = route?.providerId ?: if (isTrain) "mav" else "volan"
        val mode = route?.transportMode ?: if (isTrain) "RAIL" else "BUS"
        addBadge(dep.routeId, label, isTrain, provider, mode, route?.color)
    }

    // 3. Deduplicate by label (so there is only one Gy, one S, one Sz, one IC, one IR, one S10, etc.)
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
    onBadgeClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val columns=if(androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f) 3 else 4
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

                    Box(Modifier.weight(1f).heightIn(min=48.dp).clickable { onBadgeClick(badge.id) },contentAlignment=Alignment.Center) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = bgColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min=28.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical=3.dp)
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
    onStopRoute: (String, String) -> Unit = { _, _ -> },
    onRemove: (String) -> Unit,
    onRefresh: () -> Unit,
    onWindow: (Int) -> Unit,
    onVisible: (Set<String>) -> Unit
) {
    val listState = rememberLazyListState()
    val visibleAction by rememberUpdatedState(onVisible)
    var windowMenu by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? String)?.takeIf { key -> key.startsWith("favorite:") }?.removePrefix("favorite:") }.toSet() }
            .distinctUntilChanged().collect { visibleAction(it) }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.padding(horizontal=20.dp,vertical=20.dp)) {
            TransitPageIntro(stringResource(R.string.favorites_title),null,Icons.Rounded.Favorite) {
                FilledTonalIconButton(onClick=onRefresh,shape=TransitDesign.controlShape) {
                    Icon(Icons.Rounded.Refresh,stringResource(R.string.ui_refresh_departures))
                }
            }
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
                Surface(shape = TransitDesign.panelShape, color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.55f))) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Rounded.FavoriteBorder, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(if (state.favorites.isEmpty()) R.string.favorites_empty else if (state.favoritesLoading) R.string.favorites_loading else R.string.favorites_index_unavailable),
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            items(state.favoriteStations, key = { "favorite:${it.stop.id}" }, contentType = { "station" }) { station ->
                val lines = remember(station.stop.id, station.platforms, station.departures, state.stops, state.routes) {
                    extractStationLines(station, state)
                }
                Surface(shape = TransitDesign.panelShape, color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.55f))) {
                    Column(Modifier.padding(bottom = 12.dp)) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onStop(station.stop.id) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                                Text(station.stop.name, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(onClick = { onRemove(station.stop.id) }) {
                                Icon(Icons.Rounded.Favorite, stringResource(R.string.ui_remove_favorite), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (station.loading && lines.trainBadges.isEmpty() && lines.otherBadges.isEmpty()) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
                        }

                        if (lines.trainBadges.isNotEmpty()) {
                            StationBadgeGrid(
                                badges = lines.trainBadges,
                                onBadgeClick = { routeId -> onStopRoute(station.stop.id, routeId) },
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
                                onBadgeClick = { routeId -> onStopRoute(station.stop.id, routeId) },
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                            )
                        }

                        if (lines.trainBadges.isEmpty() && lines.otherBadges.isEmpty() && !station.loading) {
                            Text(
                                stringResource(if (station.unavailable) R.string.nearby_unavailable else R.string.favorites_no_lines),
                                Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (station.unavailable && (lines.trainBadges.isNotEmpty() || lines.otherBadges.isNotEmpty())) {
                            Text(
                                stringResource(R.string.nearby_partial),
                                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        TextButton(onClick = { onStop(station.stop.id) }, modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
                            Text(stringResource(R.string.favorites_all_departures))
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
