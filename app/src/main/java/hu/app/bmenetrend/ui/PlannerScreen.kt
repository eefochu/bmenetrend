package hu.app.bmenetrend.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

internal val planClock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Budapest"))
internal fun minutes(seconds: Long) = ceil(seconds / 60.0).toInt().coerceAtLeast(0)

@Composable
fun PlannerScreen(model: PlannerViewModel, onRequestLocation: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var departurePicker by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (state.editing != null) {
        PlannerSearch(state, model::search, model::choose, model::closeSearch) {
            model.useCurrentLocation(); onRequestLocation()
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
            Text(stringResource(R.string.planner_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.planner_subtitle), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item(key = "form", contentType = "form") {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(8.dp)) {
                    EndpointField(R.string.planner_from, state.origin, state.useLocation) { model.edit(PlannerField.ORIGIN) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HorizontalDivider(Modifier.padding(start = 50.dp).weight(1f))
                        IconButton(onClick = model::swap, enabled = state.origin != null || state.destination != null) {
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
            Button(onClick = model::plan, enabled = state.origin != null && state.destination != null && !state.planning,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(20.dp)) {
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
                    PlannerError.UNAVAILABLE -> R.string.planner_unavailable
                }), Modifier.padding(18.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        } }
        if (result != null && result.itineraries.isNotEmpty()) {
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
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(if (current) Icons.Rounded.MyLocation else if (label == R.string.planner_from) Icons.Rounded.TripOrigin else Icons.Rounded.LocationOn,
                null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (endpoint?.id == "current") stringResource(R.string.planner_current) else endpoint?.name ?: stringResource(R.string.planner_choose),
                    style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (endpoint?.detail?.isNotBlank() == true) Text(endpoint.detail, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlannerSearch(state: PlannerState, onQuery: (String)->Unit, onChoose:(PlannerEndpoint)->Unit, onBack:()->Unit, onCurrent:()->Unit) {
    BackHandler(onBack = onBack)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back)) }
            OutlinedTextField(state.query, onQuery, modifier = Modifier.weight(1f).focusRequester(focus), singleLine = true,
                label = { Text(stringResource(if (state.editing == PlannerField.ORIGIN) R.string.planner_from else R.string.planner_to)) },
                placeholder = { Text(stringResource(R.string.planner_search_hint)) }, shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.ui_clear_search)) } })
        }
        if (state.editing == PlannerField.ORIGIN) TextButton(onClick = onCurrent, modifier = Modifier.padding(horizontal = 16.dp)) {
            Icon(Icons.Rounded.MyLocation, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.planner_current))
        }
        Box(Modifier.fillMaxWidth().height(8.dp)) { if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth()) }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp)) {
            if (state.query.trim().length < 2) item(key = "hint", contentType = "notice") {
                Text(stringResource(R.string.planner_search_hint), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.suggestions, key = { it.id }, contentType = { "suggestion" }) { endpoint ->
                ListItem(headlineContent = { Text(endpoint.name) }, supportingContent = { if (endpoint.detail.isNotBlank()) Text(endpoint.detail) },
                    leadingContent = { Icon(if (endpoint.id.startsWith("emma:") || endpoint.id.startsWith("homm:")) Icons.Rounded.DirectionsBus else Icons.Rounded.LocationOn, null) },
                    modifier = Modifier.clickable { onChoose(endpoint) })
            }
            if (!state.searching && state.query.trim().length >= 2 && state.suggestions.isEmpty()) item(key = "empty", contentType = "notice") {
                Text(stringResource(if (state.searchUnavailable) R.string.planner_search_error else R.string.planner_no_places), Modifier.padding(16.dp))
            }
            if (state.suggestions.isNotEmpty()) item(key = "attribution", contentType = "notice") {
                Text(stringResource(R.string.search_places_attribution), Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

