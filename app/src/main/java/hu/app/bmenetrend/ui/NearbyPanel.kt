@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package hu.app.bmenetrend.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.material3.MaterialShapes
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.*
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
internal fun NearbyPanel(
    stations: List<NearbyStation>,
    routesById: Map<String, TransitRoute>,
    loading: Boolean,
    stopStatus: String?,
    windowMinutes: Int,
    now: Instant,
    atUser: Boolean,
    onWindow: (Int) -> Unit,
    onRefresh: () -> Unit,
    onLocate: () -> Unit,
    onStop: (String) -> Unit,
    onDeparture: (Departure) -> Unit,
    onCollapse: () -> Unit,
    onToggleExpand: () -> Unit = onCollapse,
    onSearch: () -> Unit = {},
    modifier: Modifier = Modifier,
    isExpanded: Boolean = true,
) {
    val latestDepartureAction by rememberUpdatedState(onDeparture)
    val openDeparture = remember { { departure: Departure -> latestDepartureAction(departure) } }
    LazyColumn(modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "nearby-header", contentType = "nearby_header") {
            Column(Modifier.fillMaxWidth().padding(horizontal=TransitDesign.gutter),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.nearby_title),Modifier.weight(1f).clickable(enabled=!isExpanded,onClick=onToggleExpand),
                        style=MaterialTheme.typography.headlineSmall)
                    CookieIconButton(onClick=onRefresh,enabled=!loading,size=52.dp) {
                        if(loading) TransitLoading(size=32.dp,color=MaterialTheme.colorScheme.onSecondaryContainer)
                        else Icon(Icons.Rounded.Refresh,stringResource(R.string.ui_refresh_departures))
                    }
                    IconButton(onClick=onCollapse) { Icon(Icons.Rounded.KeyboardArrowDown,
                        stringResource(if(isExpanded) R.string.ui_collapse_departures else R.string.ui_hide_departures)) }
                }
                Text(stringResource(if(atUser) R.string.nearby_you else R.string.nearby_map),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                DepartureWindowSelector(windowMinutes, onWindow)
            }
            TransitRefreshBar(loading && stations.isNotEmpty(), Modifier.padding(horizontal = 22.dp))
        }
        if (stations.isEmpty() && loading) item(key = "nearby-loading", contentType = "nearby_loading") {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) { TransitLoading() }
        }
        if (stations.isEmpty() && !loading) item(key = "nearby-empty", contentType = "nearby_empty") {
            Column(Modifier.fillMaxWidth().padding(horizontal = TransitDesign.gutter, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ShapeIconTile(Icons.Rounded.NearMe, shape = MaterialShapes.Cookie7Sided)
                Text(stopStatus ?: stringResource(R.string.design_nearby_empty), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onSearch, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = TransitDesign.controlShape) {
                    Icon(Icons.Rounded.Search, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.design_find_stop))
                }
            }
        }
        // Each station is one boxed group: its header, then its next departures, separated by hairline gaps.
        stations.forEach { station ->
            val shown = station.departures.take(3)
            val count = 1 + shown.size
            item(key = "station-${station.stop.id}", contentType = "station_header") {
                val stationPlatform = station.stop.platformCode?.ifBlank { null }
                    ?: hu.app.bmenetrend.data.BkkPlatformIndex.getPlatformCode(station.stop.id)
                SegmentedItem(0, count, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
                    onClick = { onStop(station.stop.id) }, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(station.stop.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            stationPlatform?.let { PlatformBadge(it) }
                            Spacer(Modifier.weight(1f))
                            DistancePill(stringResource(R.string.nearby_distance, station.distanceMeters.roundToInt()))
                            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (station.departures.isEmpty() && !station.loading) Text(
                            stringResource(if (station.unavailable) R.string.nearby_unavailable else R.string.nearby_no_departures),
                            Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            shown.forEachIndexed { index, departure ->
                item(key = "${station.stop.id}-${departure.routeId}-${departure.directionId}-${departure.expectedAt}", contentType = "station_departure") {
                    val route = routesById[departure.routeId]
                    val platform = station.platforms.firstOrNull { it.id == departure.stopId }
                    val minutes = ceil(Duration.between(now, departure.expectedAt).seconds / 60.0).toInt().coerceAtLeast(0)
                    SegmentedItem(index + 1, count, Modifier.padding(horizontal = 16.dp), onClick = { openDeparture(departure) },
                        enabled = !departure.isCancelled) {
                        NearbyDepartureCard(departure = departure, route = route, platform = platform, minutes = minutes,
                            onClick = { openDeparture(departure) }, clickable = false, divider = false)
                    }
                }
            }
            if (station.unavailable && station.departures.isNotEmpty()) item(key = "partial-${station.stop.id}", contentType = "station_partial") {
                Text(stringResource(R.string.nearby_partial), Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun NearbyDepartureCard(
    departure: Departure,
    route: TransitRoute?,
    platform: TransitStop?,
    minutes: Int,
    onClick: () -> Unit,
    clickable: Boolean = true,
    divider: Boolean = true,
) {
    val provider = route?.providerId ?: platform?.providerId ?: "bkk"
    val resolvedPlatform = departure.platformCode?.ifBlank { null }
        ?: platform?.platformCode?.ifBlank { null }
        ?: hu.app.bmenetrend.data.BkkPlatformIndex.getPlatformCode(departure.stopId.ifBlank { null } ?: platform?.id)
    val boarding = if (resolvedPlatform == null) {
        departure.platformCode?.takeIf { it.isNotBlank() }?.let { stringResource(if(provider=="mav") R.string.rail_platform else R.string.emma_platform, it) }
    } else null
    DepartureBoardRow(departure, provider, route?.transportMode ?: platform?.transportMode, route?.color, minutes, boarding, onClick,
        platformCode = resolvedPlatform, clickable = clickable)
    if (divider) HorizontalDivider(Modifier.padding(start=20.dp,end=20.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f))
}
