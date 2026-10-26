package hu.app.bmenetrend.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.ArrivalKind
import hu.app.bmenetrend.data.Departure
import hu.app.bmenetrend.data.DepartureTime
import hu.app.bmenetrend.data.NearbyStation
import java.time.Instant
import hu.app.bmenetrend.data.TransitRoute
import hu.app.bmenetrend.data.TransitStop
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.roundToInt

private val nearbyClock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Budapest"))

@Composable
fun NearbyPanel(
    stations: List<NearbyStation>,
    routesById: Map<String, TransitRoute>,
    atUser: Boolean,
    loading: Boolean,
    stopStatus: String?,
    windowMinutes: Int,
    now: Instant,
    onStop: (String) -> Unit,
    onDeparture: (Departure) -> Unit,
    onRefresh: () -> Unit,
    onLocate: () -> Unit,
    onToggleExpand: () -> Unit,
    onCollapse: () -> Unit,
    onWindow: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isExpanded: Boolean = true,
) {
    var windowMenu by remember { mutableStateOf(false) }
    val latestDepartureAction by rememberUpdatedState(onDeparture)
    val openDeparture = remember { { departure: Departure -> latestDepartureAction(departure) } }
    LazyColumn(modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "nearby-header", contentType = "nearby_header") {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier
                        .weight(1f)
                        .clickable(enabled = !isExpanded, onClick = onToggleExpand)
                ) {
                    Text(stringResource(R.string.nearby_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Box {
                        Row(Modifier.clickable { windowMenu = true }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(if (atUser) R.string.nearby_you else R.string.nearby_map) + " · " + compactDuration(windowMinutes),
                                Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(16.dp))
                        }
                        DropdownMenu(expanded = windowMenu, onDismissRequest = { windowMenu = false }) {
                            DepartureTime.windows.forEach { minutes -> DropdownMenuItem(
                                text = { Text(countdown(minutes)) },
                                onClick = { onWindow(minutes); windowMenu = false },
                                trailingIcon = { if (minutes == windowMinutes) Icon(Icons.Rounded.Check, null) }) }
                        }
                    }
                }
                IconButton(onClick = onLocate) { Icon(Icons.Rounded.MyLocation, stringResource(R.string.nearby_locate)) }
                IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, stringResource(R.string.ui_refresh_departures)) }
                IconButton(onClick = onCollapse) {
                    Icon(Icons.Rounded.KeyboardArrowDown,
                        stringResource(if (isExpanded) R.string.ui_collapse_departures else R.string.ui_hide_departures))
                }
            }
            Box(Modifier.fillMaxWidth().height(10.dp).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (stations.isEmpty() && !loading) item(key = "nearby-empty", contentType = "nearby_empty") {
            Text(stopStatus ?: stringResource(R.string.nearby_no_departures_2h), Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        stations.forEach { station ->
            item(key = "station-${station.stop.id}", contentType = "station_header") {
                Row(Modifier.fillMaxWidth().clickable { onStop(station.stop.id) }.padding(horizontal = 22.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.LocationOn, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(station.stop.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.nearby_distance, station.distanceMeters.roundToInt()),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp))
                }
                if (station.departures.isEmpty() && !station.loading) Text(
                    stringResource(if (station.unavailable) R.string.nearby_unavailable else R.string.nearby_no_departures),
                    Modifier.padding(start = 48.dp, end = 22.dp, bottom = 16.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            station.departures.take(3).forEach { departure ->
                item(key = "${station.stop.id}-${departure.routeId}-${departure.directionId}-${departure.expectedAt}", contentType = "station_departure") {
                    val route = routesById[departure.routeId]
                    val platform = station.platforms.firstOrNull { it.id == departure.stopId }
                    val minutes = ceil(Duration.between(now, departure.expectedAt).seconds / 60.0).toInt().coerceAtLeast(0)
                    NearbyDepartureCard(
                        departure = departure,
                        route = route,
                        platform = platform,
                        minutes = minutes,
                        onClick = { openDeparture(departure) },
                    )
                }
            }
            if (station.unavailable && station.departures.isNotEmpty()) item(key = "partial-${station.stop.id}", contentType = "station_partial") {
                Text(stringResource(R.string.nearby_partial), Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
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
) {
    val live = departure.kind == ArrivalKind.LIVE
    Surface(
        Modifier.padding(horizontal = 16.dp, vertical = 3.dp).fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ServiceBadge(departure.routeNumber, route?.providerId ?: platform?.providerId ?: "bkk", route?.transportMode ?: platform?.transportMode, route?.color, departure.trainNumber)
            Icon(Icons.AutoMirrored.Rounded.ArrowForward,null,Modifier.size(16.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(departure.destination.ifBlank { stringResource(R.string.emma_vehicle_no_direction) }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(serviceTypeLabel(route?.providerId ?: platform?.providerId ?: "bkk",route?.transportMode ?: platform?.transportMode),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                departure.serviceName?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(nearbyClock.format(departure.expectedAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    (departure.platformCode ?: platform?.platformCode)?.let { Text(stringResource(if (route?.providerId == "mav") R.string.rail_platform else R.string.emma_platform, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (live) Icon(Icons.Rounded.Sensors, stringResource(R.string.ui_live_arrival), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(countdown(minutes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}
