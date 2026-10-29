package hu.app.bmenetrend.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.app.bmenetrend.R
import hu.app.bmenetrend.PlannerViewModel
import hu.app.bmenetrend.PlannerState
import hu.app.bmenetrend.PlannerField
import hu.app.bmenetrend.PlannerError
import hu.app.bmenetrend.data.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.roundToInt

internal val planClock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Budapest"))
internal fun minutes(seconds: Long) = ceil(seconds / 60.0).toInt().coerceAtLeast(0)

@Composable
fun PlannerScreen(
    model: PlannerViewModel,
    onRequestLocation: () -> Unit = {},
) {
    PlannerScreen(model = model, onRequestLocation = onRequestLocation, onStopDepartures = null)
}

@Composable
fun PlannerScreen(
    model: PlannerViewModel,
    onRequestLocation: () -> Unit,
    onStopDepartures: ((String) -> Unit)?,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var departurePicker by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (state.editing != null) {
        PlannerSearch(
            state = state,
            onQuery = model::search,
            onChoose = model::choose,
            onBack = model::closeSearch,
            onCurrent = {
                model.useCurrentLocation()
                onRequestLocation()
            },
            onStopDepartures = onStopDepartures,
        )
        return
    }
    if (state.destination == null) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EndpointField(R.string.planner_to, null, false) { model.edit(PlannerField.DESTINATION) }
        }
        return
    }
    val now by produceState(Instant.now()) {
        while (true) { value = Instant.now(); delay(15_000) }
    }
    val result = state.result
    val selected = result?.itineraries?.firstOrNull { it.id == state.selectedId }
    if (result != null && selected != null) {
        JourneyDetail(result, selected, now) { model.selectItinerary(null) }
        return
    }
    if (departurePicker) DeparturePicker(state.departure, onDismiss = { departurePicker = false }) {
        model.setDeparture(it); departurePicker = false
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "title", contentType = "title") {
            TransitPageIntro(stringResource(R.string.planner_title), null)
        }
        if (state.offline) item(key = "offline", contentType = "notice") {
            Text(stringResource(R.string.offline_valid_until, state.offlineValidUntil.orEmpty()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.offline_endpoint_unavailable), style = MaterialTheme.typography.bodySmall)
        }
        item(key = "form", contentType = "form") {
            val connectorColor=MaterialTheme.colorScheme.outlineVariant
            Surface(shape = TransitDesign.panelShape, color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f))) {
                Column(Modifier.padding(8.dp)) {
                    EndpointField(R.string.planner_from, state.origin, state.useLocation) { model.edit(PlannerField.ORIGIN) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.Canvas(Modifier.padding(start=16.dp).width(24.dp).height(36.dp)) {
                            drawLine(connectorColor,
                                androidx.compose.ui.geometry.Offset(size.width/2,0f),androidx.compose.ui.geometry.Offset(size.width/2,size.height),
                                strokeWidth=2.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round)
                        }
                        Spacer(Modifier.weight(1f))
                        FilledTonalIconButton(onClick = model::swap, shape=TransitDesign.controlShape, enabled = state.origin != null || state.destination != null) {
                            Icon(Icons.Rounded.SwapVert, stringResource(R.string.planner_swap))
                        }
                    }
                    EndpointField(R.string.planner_to, state.destination, false) { model.edit(PlannerField.DESTINATION) }
                }
            }
        }
        item(key = "departure", contentType = "departure") {
            DepartureControl(state.departure) { departurePicker = true }
        }
        item(key = "action", contentType = "action") {
            Button(onClick = {
                if (state.useLocation && state.origin == null) onRequestLocation()
                model.plan()
            }, enabled = (state.useLocation || state.origin != null) && state.destination != null && !state.planning,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = TransitDesign.controlShape) {
                if (state.planning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Icon(Icons.Rounded.Route, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(if (state.planning) R.string.planner_loading else if (state.result != null) R.string.planner_refresh else R.string.planner_find))
            }
            if (state.planning) TextButton(onClick = model::cancelPlan) { Text(stringResource(R.string.planner_cancel)) }
            Text(stringResource(if (state.departure == null) R.string.planner_timing else R.string.planner_timing_later), Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error?.let { error -> item(key = "error", contentType = "notice") {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.errorContainer) {
                Text(stringResource(when(error) {
                    PlannerError.DEPARTURE_PASSED -> R.string.planner_departure_passed
                    PlannerError.OUTSIDE_AREA -> R.string.planner_outside_area
                    PlannerError.LOCATION -> R.string.planner_location_error
                    PlannerError.SAME_PLACE -> R.string.planner_same_place
                    PlannerError.NO_ROUTE -> R.string.planner_no_route
                    PlannerError.OFFLINE_DATE -> R.string.offline_date_unavailable
                    PlannerError.OFFLINE_ENDPOINT -> R.string.offline_endpoint_unavailable
                    PlannerError.UNAVAILABLE -> R.string.planner_unavailable
                }), Modifier.padding(18.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        } }
        if (result != null && result.itineraries.isNotEmpty()) {
            if (!state.planning && result.partial) item(key = "partial", contentType = "notice") {
                Text(stringResource(R.string.planner_partial), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item(key = "results-title", contentType = "title") {
                Text(stringResource(R.string.planner_options), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.planner_checked, planClock.format(result.requestedAt)), Modifier.padding(top = 5.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(result.itineraries, key = { it.id }, contentType = { "itinerary" }) { itinerary ->
                ItineraryCard(itinerary, itinerary == result.itineraries.first(), now) {
                    model.selectItinerary(itinerary.id)
                }
            }
            item(key = "source", contentType = "source") { Text(stringResource(R.string.planner_source), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun EndpointField(label: Int, endpoint: PlannerEndpoint?, current: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = TransitDesign.controlShape) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(if (current) Icons.Rounded.MyLocation else if (label == R.string.planner_from) Icons.Rounded.TripOrigin else Icons.Rounded.LocationOn,
                null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (current || endpoint?.id == "current") stringResource(R.string.planner_current)
                    else endpoint?.name ?: stringResource(if (label == R.string.planner_to) R.string.planner_subtitle else R.string.planner_choose),
                    style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (endpoint?.detail?.isNotBlank() == true) Text(endpoint.detail, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlannerSearch(
    state: PlannerState,
    onQuery: (String) -> Unit,
    onChoose: (PlannerEndpoint) -> Unit,
    onBack: () -> Unit,
    onCurrent: () -> Unit,
    onStopDepartures: ((String) -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    val (stations, places) = remember(state.suggestions) {
        state.suggestions.partition { endpoint ->
            endpoint.isStation ||
            endpoint.routeIds.isNotEmpty() ||
            endpoint.id.startsWith("emma:") ||
            endpoint.id.startsWith("homm:") ||
            endpoint.id.startsWith("viabusz:") ||
            endpoint.id.startsWith("kanyargo:")
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back)) }
            OutlinedTextField(
                value = state.query,
                onValueChange = onQuery,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
                singleLine = true,
                label = { Text(stringResource(if (state.editing == PlannerField.ORIGIN) R.string.planner_from else R.string.planner_to)) },
                placeholder = { Text(stringResource(R.string.planner_search_hint)) },
                shape = TransitDesign.controlShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.ui_clear_search))
                        }
                    }
                }
            )
        }

        if (state.editing == PlannerField.ORIGIN) {
            TextButton(onClick = onCurrent, modifier = Modifier.padding(horizontal = 16.dp)) {
                Icon(Icons.Rounded.MyLocation, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.planner_current))
            }
        }

        Box(Modifier.fillMaxWidth().height(8.dp)) {
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.query.trim().length < 2) {
                item(key = "hint", contentType = "notice") {
                    Text(
                        stringResource(R.string.planner_search_hint),
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (stations.isNotEmpty()) {
                item(key = "stations_header", contentType = "header") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.planner_stations_header),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                items(stations, key = { "station-${it.id}" }, contentType = { "station" }) { endpoint ->
                    val isTrain = endpoint.transportMode in setOf("RAIL", "TRAIN", "SUBURBAN_RAILWAY", "TRAMTRAIN") ||
                                  endpoint.providerIds.any { it in setOf("mav", "gysev") } ||
                                  endpoint.id.contains("mav", ignoreCase = true)
                    val isTram = endpoint.transportMode == "TRAM"
                    val isMetro = endpoint.transportMode == "SUBWAY"
                    val isTrolley = endpoint.transportMode == "TROLLEYBUS"
                    val modeColor = when {
                        isTrain -> Color(0xFF0066AA)
                        isTram -> Color(0xFFFFD800)
                        isMetro -> Color(0xFF007A3D)
                        isTrolley -> Color(0xFFE31B23)
                        endpoint.providerIds.contains("volan") -> Color(0xFFF9B900)
                        else -> Color(0xFF0080FF)
                    }
                    val modeIcon = when {
                        isTrain -> Icons.Rounded.Train
                        isTram -> Icons.Rounded.Tram
                        isMetro -> Icons.Rounded.Subway
                        else -> Icons.Rounded.DirectionsBus
                    }
                    val routeBadges = remember(endpoint.routeIds) {
                        endpoint.routeIds.map { routeId ->
                            routeId.substringAfterLast(":")
                        }.filter { it.isNotBlank() && it != "?" }.distinct().take(10)
                    }
                    val distance = remember(state.userLocation, endpoint.position) {
                        state.userLocation?.let { loc ->
                            val meters = StopClusters.distanceMeters(loc, endpoint.position)
                            if (meters < 1000) "${meters.toInt()} m" else "${(meters / 1000.0).roundToInt()} km"
                        }
                    }

                    Surface(
                        onClick = { onChoose(endpoint) },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = modeColor,
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            modeIcon,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = badgeTextColor(modeColor)
                                        )
                                    }
                                }

                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = endpoint.name,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 17.sp
                                        ),
                                        color = Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (endpoint.detail.isNotBlank()) {
                                        Text(
                                            text = endpoint.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                if (onStopDepartures != null) {
                                    IconButton(
                                        onClick = { onStopDepartures(endpoint.id) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Schedule,
                                            contentDescription = stringResource(R.string.planner_view_departures),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }

                            if (routeBadges.isNotEmpty() || distance != null) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    if (routeBadges.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier.weight(1f, fill = false),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            routeBadges.take(6).forEach { badge ->
                                                val isRail = isTrain || badge.matches(Regex("^[GSZgsz]\\d.*")) || badge in setOf("EC","EN","IC","IR","RJ","RJX","EX","S","SZ","G","GY","Z")
                                                val badgeBg = if (isRail) railLineColor(badge) else modeColor
                                                val badgeText = if (isRail) railLineTextColor(badge) else badgeTextColor(badgeBg)
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = badgeBg,
                                                ) {
                                                    Text(
                                                        text = badge,
                                                        color = badgeText,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        ),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            if (routeBadges.size > 6) {
                                                Text(
                                                    "+${routeBadges.size - 6}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    } else {
                                        Spacer(Modifier.width(1.dp))
                                    }

                                    if (distance != null) {
                                        Text(
                                            text = distance,
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (places.isNotEmpty()) {
                item(key = "places_header", contentType = "header") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.planner_places_header),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(places, key = { "place-${it.id}" }, contentType = { "place" }) { endpoint ->
                    val distance = remember(state.userLocation, endpoint.position) {
                        state.userLocation?.let { loc ->
                            val meters = StopClusters.distanceMeters(loc, endpoint.position)
                            if (meters < 1000) "${meters.toInt()} m" else "${(meters / 1000.0).roundToInt()} km"
                        }
                    }

                    Surface(
                        onClick = { onChoose(endpoint) },
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Rounded.LocationOn,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = endpoint.name,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (endpoint.detail.isNotBlank()) {
                                    Text(
                                        text = endpoint.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            if (distance != null) {
                                Text(
                                    text = distance,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (!state.searching && state.query.trim().length >= 2 && state.suggestions.isEmpty()) {
                item(key = "empty", contentType = "notice") {
                    Text(
                        stringResource(if (state.searchUnavailable) R.string.planner_search_error else R.string.planner_no_places),
                        Modifier.padding(16.dp)
                    )
                }
            }
            if (places.isNotEmpty()) {
                item(key = "attribution", contentType = "notice") {
                    Text(
                        stringResource(R.string.search_places_attribution),
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
