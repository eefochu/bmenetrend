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
            Column(Modifier.fillMaxWidth().padding(horizontal=TransitDesign.gutter),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.nearby_title),Modifier.fillMaxWidth().clickable(enabled=!isExpanded,onClick=onToggleExpand),
                    style=MaterialTheme.typography.headlineSmall)
                Text(stringResource(if(atUser) R.string.nearby_you else R.string.nearby_map),
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Box {
                        FilledTonalButton(onClick={windowMenu=true},shape=TransitDesign.controlShape,contentPadding=PaddingValues(horizontal=12.dp)) {
                            Icon(Icons.Rounded.Schedule,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp))
                            Text(compactDuration(windowMinutes));Icon(Icons.Rounded.ExpandMore,null,Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded=windowMenu,onDismissRequest={windowMenu=false}) {
                            DepartureTime.windows.forEach { minutes -> DropdownMenuItem(
                                text={Text(countdown(minutes))},onClick={onWindow(minutes);windowMenu=false},
                                trailingIcon={if(minutes==windowMinutes) Icon(Icons.Rounded.Check,null)}) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick=onLocate) { Icon(Icons.Rounded.MyLocation,stringResource(R.string.nearby_locate)) }
                    IconButton(onClick=onRefresh) { Icon(Icons.Rounded.Refresh,stringResource(R.string.ui_refresh_departures)) }
                    IconButton(onClick=onCollapse) { Icon(Icons.Rounded.KeyboardArrowDown,
                        stringResource(if(isExpanded) R.string.ui_collapse_departures else R.string.ui_hide_departures)) }
                }
            }
            Box(Modifier.fillMaxWidth().height(10.dp).padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        if (stations.isEmpty() && !loading) item(key = "nearby-empty", contentType = "nearby_empty") {
            EmptyState(stringResource(R.string.nearby_title),stopStatus ?: stringResource(R.string.nearby_no_departures_2h),Icons.Rounded.NearMe)
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
    val provider=route?.providerId ?: platform?.providerId ?: "bkk"
    val boarding=(departure.platformCode ?: platform?.platformCode)?.let {
        stringResource(if(provider=="mav") R.string.rail_platform else R.string.emma_platform,it)
    }
    DepartureBoardRow(departure,provider,route?.transportMode ?: platform?.transportMode,route?.color,minutes,boarding,onClick)
    HorizontalDivider(Modifier.padding(start=20.dp,end=20.dp),color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.45f))
}
