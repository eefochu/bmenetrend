package hu.app.bmenetrend.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.*
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ItineraryCard(trip: TripItinerary, fastest: Boolean, now: Instant, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            JourneyBar(trip)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                trip.legs.forEach { leg ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (leg.walking) {
                            Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, stringResource(R.string.planner_walk), Modifier.size(22.dp))
                            Text(stringResource(R.string.ui_minutes_short, minutes(Duration.between(leg.start, leg.end).seconds)),
                                style = MaterialTheme.typography.labelLarge)
                        } else leg.route?.let { ServiceBadge(it.number, it.providerId, it.transportMode, it.color, leg.trainNumber) }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (fastest) Text(stringResource(R.string.planner_fastest), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.planner_summary,
                        if (trip.transfers == 0) stringResource(R.string.planner_direct) else pluralStringResource(R.plurals.planner_transfers, trip.transfers, trip.transfers),
                        minutes(trip.walkSeconds)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.ui_minutes_short, minutes(Duration.between(trip.start, trip.end).seconds)),
                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.planner_view_steps), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.primary)
            }
            if (trip.start < now.minusSeconds(30)) Text(stringResource(R.string.planner_refresh_needed), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun segmentColor(segment: PlanSegment): Color = when {
    segment.leg == null -> MaterialTheme.colorScheme.outlineVariant
    segment.leg.walking -> MaterialTheme.colorScheme.tertiary
    else -> segment.leg.route?.let(::lineColor) ?: MaterialTheme.colorScheme.primary
}

/** Draw-only proportions: the widths include every second of walking, riding and waiting. */
@Composable
internal fun JourneyBar(trip: TripItinerary) {
    val segments = remember(trip) { trip.segments() }
    val colors = segments.map { segmentColor(it) }
    val duration = Duration.between(trip.start, trip.end).toMillis().coerceAtLeast(1).toDouble()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ClockWithDate(trip.start, trip.start)
            ClockWithDate(trip.end, trip.start)
        }
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            segments.forEachIndexed { index, segment ->
                val left = (Duration.between(trip.start, segment.start).toMillis() / duration * size.width).toFloat()
                val right = (Duration.between(trip.start, segment.end).toMillis() / duration * size.width).toFloat()
                val gap = minOf(2.dp.toPx(), (right - left) / 4)
                drawRoundRect(colors[index], Offset(left + gap / 2, 0f), Size((right - left - gap).coerceAtLeast(0f), size.height), CornerRadius(2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun ClockWithDate(time: Instant, start: Instant, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    Column(modifier) {
        Text(planClock.format(time), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (time.atZone(TripPlanner.zone).toLocalDate() != start.atZone(TripPlanner.zone).toLocalDate()) {
            Text(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).withZone(TripPlanner.zone).format(time),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun JourneyDetail(plan: TripPlan, trip: TripItinerary, now: Instant, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val walkColor = MaterialTheme.colorScheme.tertiary
    val waitColor = MaterialTheme.colorScheme.outlineVariant
    val colors = remember(trip, walkColor) { trip.legs.map { if (it.walking) walkColor else it.route?.let(::lineColor) ?: walkColor } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back)) }
            Text(stringResource(R.string.planner_steps), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 20.dp, bottom = 24.dp)) {
            item(key = "summary", contentType = "summary") {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.padding(bottom = 24.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(departureLabel(trip.start), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text("${endpointName(plan.origin)} → ${endpointName(plan.destination)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        JourneyBar(trip)
                        Text(stringResource(R.string.planner_duration, minutes(Duration.between(trip.start, trip.end).seconds)), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.planner_bar_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (trip.start < now.minusSeconds(30)) Text(stringResource(R.string.planner_stale), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            trip.legs.forEachIndexed { index, leg ->
                val previous = trip.legs.getOrNull(index - 1)
                val next = trip.legs.getOrNull(index + 1)
                val color = colors[index]
                val waitAfter = next?.let { Duration.between(leg.end, it.start).seconds } ?: 0L
                item(key = "from-$index", contentType = "point") {
                    TimelinePoint(leg.from, leg.start, trip.start,
                        if (index == 0) endpointName(plan.origin) else leg.from.name,
                        top = if (previous == null) null else if (previous.end < leg.start) waitColor else color, bottom = color)
                }
                item(key = "ride-$index", contentType = "segment") {
                    TimelineRow(color, color, dotted = leg.walking) {
                        LegDescription(leg)
                    }
                }
                item(key = "to-$index", contentType = "point") {
                    TimelinePoint(leg.to, leg.end, trip.start,
                        if (next == null) endpointName(plan.destination) else leg.to.name,
                        top = color, bottom = if (next == null) null else if (waitAfter > 0) waitColor else colors[index + 1])
                }
                if (waitAfter > 0) item(key = "wait-$index", contentType = "wait") {
                    TimelineRow(waitColor, waitColor) {
                        Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.planner_wait, minutes(waitAfter)), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item(key = "source", contentType = "notice") { Text(stringResource(R.string.planner_source), Modifier.padding(top = 24.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun endpointName(endpoint: PlannerEndpoint) = if (endpoint.id == "current") stringResource(R.string.planner_current) else endpoint.name

@Composable
private fun TimelinePoint(point: PlanPoint, time: Instant, start: Instant, name: String, top: Color?, bottom: Color?) {
    TimelineRow(top, bottom, point = true, clock = { ClockWithDate(time, start) }) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            point.platform?.let { Text(stringResource(R.string.emma_platform, it), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun TimelineRow(top: Color?, bottom: Color?, point: Boolean = false, dotted: Boolean = false,
                        clock: @Composable (() -> Unit)? = null, content: @Composable () -> Unit) {
    val surface = MaterialTheme.colorScheme.surface
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterStart) { clock?.invoke() }
        Canvas(Modifier.width(24.dp).fillMaxHeight()) {
            val x = size.width / 2
            val y = size.height / 2
            val effect = if (dotted) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())) else null
            top?.let { drawLine(it, Offset(x, 0f), Offset(x, y), 4.dp.toPx(), pathEffect = effect) }
            bottom?.let { drawLine(it, Offset(x, y), Offset(x, size.height), 4.dp.toPx(), pathEffect = effect) }
            if (point) {
                drawCircle(bottom ?: top ?: surface, 7.dp.toPx(), Offset(x, y))
                drawCircle(surface, 3.dp.toPx(), Offset(x, y))
            }
        }
        Box(Modifier.weight(1f).padding(start = 12.dp)) { content() }
    }
}

@Composable
private fun LegDescription(leg: PlanLeg) {
    var expanded by rememberSaveable(leg.start.toEpochMilli(), leg.route?.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (leg.walking) {
            Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, tint = MaterialTheme.colorScheme.tertiary)
            Text(stringResource(R.string.planner_walk_detail, minutes(Duration.between(leg.start, leg.end).seconds), leg.distance.roundToInt()),
                style = MaterialTheme.typography.titleSmall)
        } else {
            leg.route?.let { ServiceBadge(it.number, it.providerId, it.transportMode, it.color, leg.trainNumber) }
            Text(leg.headsign ?: leg.to.name, style = MaterialTheme.typography.titleMedium)
            Text("${stringResource(R.string.ui_minutes_short, minutes(Duration.between(leg.start, leg.end).seconds))} · ${stringResource(if (leg.realtime) R.string.ui_live_arrival else R.string.ui_scheduled)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (leg.stayOnBoard) Text(stringResource(R.string.planner_stay), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (leg.restrictedService) Text(stringResource(R.string.ui_registration_required),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (leg.stops.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                    Text(pluralStringResource(R.plurals.planner_intermediate, leg.stops.size, leg.stops.size), Modifier.weight(1f))
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, stringResource(if (expanded) R.string.planner_hide_stops else R.string.planner_show_stops))
                }
                if (expanded) leg.stops.forEach { stop ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val color = leg.route?.let(::lineColor) ?: MaterialTheme.colorScheme.primary
                        Canvas(Modifier.size(8.dp)) { drawCircle(color) }
                        Text(stop.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
