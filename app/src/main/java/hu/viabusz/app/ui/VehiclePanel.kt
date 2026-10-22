package hu.viabusz.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.viabusz.app.data.VehicleStopProgress
import hu.viabusz.app.R
import hu.viabusz.app.map.VehicleMarkerStyle
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val observationFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Europe/Budapest"))

@Composable
fun VehiclePanel(state: UiState, onClose: () -> Unit, onRefresh: () -> Unit, onStop: (String) -> Unit, onShowRoute: () -> Unit, modifier: Modifier = Modifier) {
    val vehicle = state.selectedVehicle ?: return
    val journey = state.vehicleJourney
    val listState = rememberLazyListState()
    val color = lineColor(vehicle.providerId, vehicle.transportMode, vehicle.markerColor)
    val progress = VehicleStopProgress.resolve(vehicle, journey, state.now)
    val provider = when (vehicle.providerId) { "volan" -> "Volán"; "bkk" -> "BKK"; "homm" -> "HOMM"; "kanyargo" -> "KanyarGO"; else -> "ViaBusz" }
    val style = VehicleMarkerStyle.forVehicle(vehicle)
    val mode = stringResource(when (style) {
        VehicleMarkerStyle.TROLLEY -> R.string.emma_mode_trolley
        VehicleMarkerStyle.TRAM -> R.string.emma_mode_tram
        VehicleMarkerStyle.TRAIN -> if (vehicle.transportMode == "SUBWAY") R.string.emma_mode_metro else R.string.emma_mode_train
        else -> R.string.emma_mode_bus
    })
    val number = journey?.routeNumber ?: vehicle.routeNumber
    val destination = journey?.destination ?: vehicle.destination ?: journey?.stops?.lastOrNull()?.name
    LaunchedEffect(journey?.tripId, progress?.nextIndex) {
        if (journey != null && progress?.nextIndex != null) listState.scrollToItem(progress.nextIndex.coerceAtLeast(0))
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LineBadge(number ?: "?", vehicle.providerId, vehicle.transportMode, vehicle.markerColor)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(provider, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(mode, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRefresh, enabled = !state.vehicleJourneyLoading) {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.emma_vehicle_refresh))
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.emma_vehicle_close)) }
        }
        Text(destination?.let { stringResource(R.string.emma_vehicle_towards, it) } ?: stringResource(R.string.emma_vehicle_no_direction),
            Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        progress?.let { position ->
            Text(when {
                position.at != null -> stringResource(R.string.vehicle_at_stop, position.at.name)
                position.previous != null && position.next != null -> stringResource(R.string.vehicle_between_stops, position.previous.name, position.next.name)
                else -> stringResource(R.string.emma_vehicle_next_stop, position.next!!.name)
            }, Modifier.padding(start = 22.dp, end = 22.dp, bottom = 12.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        Text(listOfNotNull(vehicle.registration, vehicle.speedKmh?.let { stringResource(R.string.emma_speed, it.toInt()) }).joinToString(" · "),
            Modifier.padding(horizontal = 22.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.volan_observation_time, observationFormat.format(vehicle.observedAt)),
            Modifier.padding(start = 22.dp, end = 22.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.vehicleJourneyLoading) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
            if (Duration.between(vehicle.observedAt, state.now).seconds > 180) item {
                Text(stringResource(R.string.emma_vehicle_old_position), Modifier.padding(22.dp), color = MaterialTheme.colorScheme.error)
            }
            if (journey != null) item {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    journey.routeName?.takeIf { it != destination }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    if (journey.geometry.size >= 2) OutlinedButton(onClick = onShowRoute) { Text(stringResource(R.string.line_show_route)) }
                }
            }
            if (state.vehicleJourneyLoading) item {
                Text(stringResource(R.string.emma_vehicle_loading), Modifier.padding(22.dp))
            }
            if (state.vehicleJourneyError) item {
                Text(stringResource(R.string.emma_vehicle_unavailable), Modifier.padding(22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!state.vehicleJourneyLoading && !state.vehicleJourneyError && journey?.stops.isNullOrEmpty()) item {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vehicle.nextStopName?.let { Text(stringResource(R.string.emma_vehicle_next_stop, it), style = MaterialTheme.typography.titleSmall) }
                    Text(stringResource(R.string.emma_vehicle_no_stops), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!journey?.stops.isNullOrEmpty()) {
                item { Text(pluralStringResource(R.plurals.emma_journey_stops, journey!!.stops.size, journey.stops.size),
                    Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall) }
                itemsIndexed(journey!!.stops, key = { index, stop -> "$index-${stop.id}" }) { index, stop ->
                    val atIndex = if (progress?.at == null) null else if (progress.at.id == progress.next?.id) progress.nextIndex else progress.nextIndex?.minus(1)
                    if (index == progress?.nextIndex && progress.at == null && progress.previous != null) LineVehicleBetween(color, style.drawableRes)
                    LineStopRow(stop, index == 0, index == journey.stops.lastIndex, color,
                        onClick = { onStop(stop.id) }, past = progress?.nextIndex?.let { index < it && index != atIndex } == true,
                        highlighted = index == progress?.nextIndex || index == atIndex,
                        vehicleIcon = if (index == atIndex) style.drawableRes else null)
                }
            }
        }
    }
}
