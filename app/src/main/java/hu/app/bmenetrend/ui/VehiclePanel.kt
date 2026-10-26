package hu.app.bmenetrend.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import hu.app.bmenetrend.map.HevLineStyle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.data.VehicleStopProgress
import hu.app.bmenetrend.R
import hu.app.bmenetrend.map.VehicleMarkerStyle
import hu.app.bmenetrend.map.MetroLineStyle
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val observationFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Europe/Budapest"))

@Composable
fun VehiclePanel(state: UiState, onClose: () -> Unit, onRefresh: () -> Unit, onStop: (String) -> Unit, onShowRoute: () -> Unit, modifier: Modifier = Modifier) {
    val vehicle = state.selectedVehicle ?: return
    val journey = state.vehicleJourney?.takeIf { it.tripId == vehicle.tripId }
    val listState = rememberLazyListState()
    val color = lineColor(vehicle.providerId, vehicle.transportMode, vehicle.markerColor)
    val progress = VehicleStopProgress.resolve(vehicle, journey, state.now)
    val provider = when (vehicle.providerId) { "mav" -> stringResource(R.string.rail_provider); "hev" -> stringResource(R.string.emma_provider_hev); "volan" -> "Volán"; "bkk" -> "BKK"; "homm" -> "HOMM"; "kanyargo" -> "KanyarGO"; else -> "ViaBusz" }
    val style = VehicleMarkerStyle.forVehicle(vehicle)
    val number = journey?.routeNumber ?: vehicle.displayRouteNumber
    val hev = HevLineStyle.forLine(number, vehicle.transportMode)
    val context = LocalContext.current
    val height = with(LocalDensity.current) { 30.dp.roundToPx() }
    val metro = MetroLineStyle.forLine(number, vehicle.transportMode)
    val hevBitmap = remember(hev, metro, height) {
        hev?.let { HevLineStyle.render(context, it, height).asImageBitmap() }
            ?: metro?.let { MetroLineStyle.render(context, it, height).asImageBitmap() }
    }
    val vehicleIcon = MetroLineStyle.forLine(number, vehicle.transportMode)?.drawable ?: style.drawableRes
    val destination = journey?.destination ?: vehicle.destination ?: journey?.stops?.lastOrNull()?.name
    LaunchedEffect(journey?.tripId, progress?.nextIndex) {
        if (journey != null && progress?.nextIndex != null) listState.scrollToItem(progress.nextIndex.coerceAtLeast(0))
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start=8.dp,end=8.dp,top=4.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack,stringResource(R.string.ui_back)) }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    VehicleServiceBadge(vehicle,number,estimated=journey?.routeNumber==null && vehicle.isRouteEstimated)
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(destination ?: stringResource(R.string.emma_vehicle_no_direction),Modifier.weight(1f),
                        style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
                Text("${serviceTypeLabel(vehicle.providerId,vehicle.transportMode)} · $provider",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                journey?.stops?.firstOrNull()?.name?.let { origin ->
                    if(origin!=destination) Text(stringResource(R.string.line_from,origin),style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick=onRefresh,enabled=!state.vehicleJourneyLoading) { Icon(Icons.Rounded.Refresh,stringResource(R.string.emma_vehicle_refresh)) }
        }
        progress?.let { position ->
            Text(when {
                position.at != null -> stringResource(R.string.vehicle_at_stop, position.at.name)
                position.previous != null && position.next != null -> stringResource(R.string.vehicle_between_stops, position.previous.name, position.next.name)
                else -> stringResource(R.string.emma_vehicle_next_stop, position.next!!.name)
            }, Modifier.padding(start = 22.dp, end = 22.dp, bottom = 12.dp), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        Text(listOfNotNull(vehicle.serviceName, vehicle.registration, vehicle.speedKmh?.let { stringResource(R.string.emma_speed, it.toInt()) }).joinToString(" · "),
            Modifier.padding(horizontal = 22.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.volan_observation_time, observationFormat.format(vehicle.observedAt)),
            Modifier.padding(start = 22.dp, end = 22.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.vehicleJourneyLoading) LinearProgressIndicator(Modifier.fillMaxWidth()) else HorizontalDivider()
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
            if (Duration.between(vehicle.observedAt, state.now).seconds > 180) item(contentType = "old_position") {
                Text(stringResource(R.string.emma_vehicle_old_position), Modifier.padding(22.dp), color = MaterialTheme.colorScheme.error)
            }
            if (journey != null) item(contentType = "route_summary") {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (journey.geometry.size >= 2) OutlinedButton(onClick = onShowRoute) { Text(stringResource(R.string.line_show_route)) }
                }
            }
            if (state.vehicleJourneyLoading) item(contentType = "loading") {
                Text(stringResource(R.string.emma_vehicle_loading), Modifier.padding(22.dp))
            }
            if (state.vehicleJourneyError) item(contentType = "error") {
                Text(stringResource(R.string.emma_vehicle_unavailable), Modifier.padding(22.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!state.vehicleJourneyLoading && !state.vehicleJourneyError && journey?.stops.isNullOrEmpty()) item(contentType = "no_stops") {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vehicle.nextStopName?.let { Text(stringResource(R.string.emma_vehicle_next_stop, it), style = MaterialTheme.typography.titleSmall) }
                    Text(stringResource(R.string.emma_vehicle_no_stops), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!journey?.stops.isNullOrEmpty()) {
                item(contentType = "stops_header") { Text(pluralStringResource(R.plurals.emma_journey_stops, journey!!.stops.size, journey.stops.size),
                    Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleSmall) }
                itemsIndexed(journey!!.stops, key = { index, stop -> "$index-${stop.id}" }, contentType = { _, _ -> "journey_stop" }) { index, stop ->
                    val atIndex = if (progress?.at == null) null else if (progress.at.id == progress.next?.id) progress.nextIndex else progress.nextIndex?.minus(1)
                    if (index == progress?.nextIndex && progress.at == null && progress.previous != null) LineVehicleBetween(color, vehicleIcon, hevBitmap)
                    val stopTime = journey.stopTimes.getOrNull(index)
                    LineStopRow(stop, index == 0, index == journey.stops.lastIndex, color,
                        onClick = { onStop(stop.id) }, past = progress?.nextIndex?.let { index < it && index != atIndex } == true,
                        highlighted = index == progress?.nextIndex || index == atIndex,
                        vehicleIcon = if (index == atIndex) vehicleIcon else null, vehicleBitmap = if (index == atIndex) hevBitmap else null,
                        arrivalTime = stopTime?.arrivalTime,
                        scheduledTime = stopTime?.scheduledTime,
                        isLive = stopTime?.isLive == true,
                        delayMinutes = stopTime?.delayMinutes ?: 0)
                }
            }
        }
    }
}
