package hu.app.bmenetrend.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    PlannerScreen(
        model = model,
        onRequestLocation = onRequestLocation,
        onStopDepartures = null,
        onClose = null,
        transitMapContent = null,
        mapContent = null
    )
}

@Composable
fun PlannerScreen(
    model: PlannerViewModel,
    onRequestLocation: () -> Unit,
    onStopDepartures: ((String) -> Unit)?,
) {
    PlannerScreen(
        model = model,
        onRequestLocation = onRequestLocation,
        onStopDepartures = onStopDepartures,
        onClose = null,
        transitMapContent = null,
        mapContent = null
    )
}

@Composable
fun PlannerScreen(
    model: PlannerViewModel,
    onRequestLocation: () -> Unit,
    onStopDepartures: ((String) -> Unit)?,
    onClose: (() -> Unit)?,
    mapContent: (@Composable (Modifier) -> Unit)? = null,
) {
    PlannerScreen(
        model = model,
        onRequestLocation = onRequestLocation,
        onStopDepartures = onStopDepartures,
        onClose = onClose,
        transitMapContent = null,
        mapContent = mapContent
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen(
    model: PlannerViewModel,
    onRequestLocation: () -> Unit,
    onStopDepartures: ((String) -> Unit)?,
    onClose: (() -> Unit)?,
    transitMapContent: (@Composable (
        modifier: Modifier,
        routeGeometry: List<GeoPoint>,
        destinationPlace: PlaceResult?,
        cameraRequest: Int,
        isDotted: Boolean,
        originPosition: GeoPoint?
    ) -> Unit)? = null,
    mapContent: (@Composable (Modifier) -> Unit)? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var departurePicker by rememberSaveable { mutableStateOf(false) }
    var inResultsView by rememberSaveable { mutableStateOf(false) }

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

    val result = state.result
    val clockActive = result?.itineraries?.isNotEmpty() == true
    val clockOwner = LocalLifecycleOwner.current
    val now by produceState(Instant.now(), clockActive, clockOwner) {
        if (clockActive) clockOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { value = Instant.now(); delay(15_000) }
        }
    }
    val selected = result?.itineraries?.firstOrNull { it.id == state.selectedId }
    if (result != null && selected != null) {
        JourneyDetail(result, selected, now) { model.selectItinerary(null) }
        return
    }

    if (departurePicker) {
        DeparturePicker(state.departure, onDismiss = { departurePicker = false }) {
            model.setDeparture(it)
            departurePicker = false
        }
    }

    val currentOrigin = state.origin
    val currentDest = state.destination

    val showResults = inResultsView || (result != null && result.itineraries.isNotEmpty()) || state.planning

    BackHandler(enabled = inResultsView || state.selectedId != null || onClose != null) {
        when {
            state.selectedId != null -> model.selectItinerary(null)
            inResultsView -> {
                inResultsView = false
                model.cancelPlan()
            }
            onClose != null -> onClose()
        }
    }

    val resultGutter = adaptivePageGutter()
    if (showResults) {
        // Keep the results readable while the planner map uses the entire window.
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.planner_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            inResultsView = false
                            model.cancelPlan()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.ui_back)
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { model.plan() },
                            enabled = !state.planning
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.planner_refresh)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                state = listState,
                contentPadding = PaddingValues(start = resultGutter, end = resultGutter, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (state.offline) item(key = "offline", contentType = "notice") {
                    Text(stringResource(R.string.offline_valid_until, state.offlineValidUntil.orEmpty()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.offline_endpoint_unavailable), style = MaterialTheme.typography.bodySmall)
                }

                item(key = "form", contentType = "form") {
                    val connectorColor = MaterialTheme.colorScheme.outlineVariant
                    Surface(
                        shape = TransitDesign.panelShape,
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            EndpointField(R.string.planner_from, state.origin, state.useLocation) {
                                model.edit(PlannerField.ORIGIN)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.Canvas(Modifier.padding(start = 16.dp).width(24.dp).height(36.dp)) {
                                    drawLine(
                                        connectorColor,
                                        androidx.compose.ui.geometry.Offset(size.width / 2, 0f),
                                        androidx.compose.ui.geometry.Offset(size.width / 2, size.height),
                                        strokeWidth = 2.dp.toPx(),
                                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                FilledTonalIconButton(
                                    onClick = model::swap,
                                    shape = TransitDesign.controlShape,
                                    enabled = state.origin != null || state.destination != null
                                ) {
                                    Icon(Icons.Rounded.SwapVert, stringResource(R.string.planner_swap))
                                }
                            }
                            EndpointField(R.string.planner_to, state.destination, false) {
                                model.edit(PlannerField.DESTINATION)
                            }
                        }
                    }
                }

                item(key = "departure", contentType = "departure") {
                    DepartureControl(state.departure) { departurePicker = true }
                }

                item(key = "action", contentType = "action") {
                    Button(
                        onClick = {
                            if (state.useLocation && state.origin == null) onRequestLocation()
                            model.plan()
                        },
                        enabled = (state.useLocation || state.origin != null) && state.destination != null && !state.planning,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        shape = TransitDesign.controlShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        if (state.planning) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.planner_loading))
                        } else {
                            Icon(Icons.Rounded.Route, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.planner_find))
                        }
                    }
                }

                state.error?.let { error ->
                    item(key = "error", contentType = "error") {
                        Surface(
                            shape = TransitDesign.controlShape,
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                stringResource(
                                    when (error) {
                                        PlannerError.DEPARTURE_PASSED -> R.string.planner_departure_passed
                                        PlannerError.OUTSIDE_AREA -> R.string.planner_outside_area
                                        PlannerError.LOCATION -> R.string.planner_location_error
                                        PlannerError.SAME_PLACE -> R.string.planner_same_place
                                        PlannerError.NO_ROUTE -> R.string.planner_no_route
                                        PlannerError.OFFLINE_DATE -> R.string.offline_date_unavailable
                                        PlannerError.OFFLINE_ENDPOINT -> R.string.offline_endpoint_unavailable
                                        PlannerError.UNAVAILABLE -> R.string.planner_unavailable
                                    }
                                ),
                                Modifier.padding(18.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                if (result != null && result.itineraries.isNotEmpty()) {
                    if (!state.planning && result.partial) item(key = "partial", contentType = "notice") {
                        Text(
                            stringResource(R.string.planner_partial),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    item(key = "results-title", contentType = "header") {
                        Text(
                            stringResource(R.string.planner_options),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.planner_checked, planClock.format(result.requestedAt)),
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(result.itineraries, key = { it.id }, contentType = { "itinerary" }) { itinerary ->
                        ItineraryCard(itinerary, itinerary == result.itineraries.first(), now) {
                            model.selectItinerary(itinerary.id)
                        }
                    }
                    item(key = "source", contentType = "notice") {
                        Text(
                            stringResource(R.string.planner_source),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    } else {
        // Initial Map-Based Planner Screen
        val originPos = remember(currentOrigin, state.userLocation) {
            currentOrigin?.position ?: state.userLocation
        }
        val destPos = remember(currentDest) {
            currentDest?.position
        }
        val previewGeometry = remember(originPos, destPos) {
            if (originPos != null && destPos != null) {
                listOf(originPos, destPos)
            } else {
                emptyList()
            }
        }
        val previewPlace = remember(currentDest, originPos, destPos) {
            if (currentDest != null && destPos != null) {
                val bounds = if (originPos != null) {
                    MapBounds(
                        west = minOf(originPos.lon, destPos.lon),
                        south = minOf(originPos.lat, destPos.lat),
                        east = maxOf(originPos.lon, destPos.lon),
                        north = maxOf(originPos.lat, destPos.lat)
                    )
                } else null
                PlaceResult(
                    id = currentDest.id,
                    name = currentDest.name,
                    position = destPos,
                    address = currentDest.detail,
                    bounds = bounds
                )
            } else null
        }
        var previewCameraRequest by remember { mutableIntStateOf(0) }
        LaunchedEffect(currentDest?.id) {
            if (currentDest != null) {
                previewCameraRequest++
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            PlannerMapOverlay(
                mapContent = { modifier ->
                    if (transitMapContent != null) transitMapContent(modifier.testTag("planner-map"),
                        previewGeometry, previewPlace, previewCameraRequest, currentDest != null, originPos)
                    else mapContent?.invoke(modifier)
                },
                onLocalProvider = model::setLocalProvider,
                state = state,
                onOrigin = { model.edit(PlannerField.ORIGIN) },
                onDestination = { model.edit(PlannerField.DESTINATION) },
                onSwap = model::swap,
                onDeparture = { departurePicker = true },
                onClose = onClose,
                onFind = {
                    if (currentDest == null) model.edit(PlannerField.DESTINATION)
                    else {
                        if (state.useLocation && currentOrigin == null) onRequestLocation()
                        inResultsView = true
                        model.plan()
                    }
                },
            )
        }
    }
}

/** The map has its own measured viewport; controls never cover route bounds. */
@Composable
internal fun PlannerMapOverlay(
    state: PlannerState,
    onOrigin: () -> Unit, onDestination: () -> Unit, onSwap: () -> Unit,
    onDeparture: () -> Unit, onFind: () -> Unit, onClose: (() -> Unit)? = null,
    onLocalProvider: (String, Boolean) -> Unit = { _, _ -> },
    mapContent: @Composable (Modifier) -> Unit = {},
) {
    val wide = transitWindowWidth() >= 600.dp
    var servicesOpen by rememberSaveable { mutableStateOf(false) }
    val maxCardHeight = (LocalConfiguration.current.screenHeightDp * .48f).dp
    if (servicesOpen) LocalPlanningServicesDialog(state.localProviders, onLocalProvider) { servicesOpen = false }
    val controls: @Composable () -> Unit = {
        Surface(modifier = Modifier.widthIn(max = if (wide) 360.dp else 560.dp).fillMaxWidth()
            .heightIn(max = if (wide) 600.dp else maxCardHeight).testTag("planner-controls-card"),
            shape = TransitDesign.panelShape, color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 3.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.planner_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (onClose != null) IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.ui_close))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        EndpointField(R.string.planner_from, state.origin, state.useLocation, onOrigin)
                        EndpointField(R.string.planner_to, state.destination, false, onDestination)
                    }
                    FilledTonalIconButton(onClick = onSwap, enabled = state.origin != null || state.destination != null,
                        shape = TransitDesign.controlShape, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Rounded.SwapVert, stringResource(R.string.planner_swap))
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDeparture, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Schedule, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.departure == null) stringResource(R.string.planner_leave_now) else departureLabel(state.departure),
                            style = MaterialTheme.typography.labelLarge)
                    }
                    TextButton(onClick = { servicesOpen = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("planner-local-services")) {
                        Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                            LocalPlanningService.entries.forEach { TransitVehicleIcon(it.providerId, size = 24.dp) }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.planner_local_services_count, state.localProviders.size), style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (wide) PlannerFindButton(state.planning, onFind)
            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Column(Modifier.width(384.dp).fillMaxHeight().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                controls()
                MapAttribution()
            }
            mapContent(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.TopCenter) { controls() }
            MapAttribution(Modifier.align(Alignment.CenterHorizontally).padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) { mapContent(Modifier.fillMaxSize()) }
            PlannerFindButton(state.planning, onFind, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

@Composable
internal fun LocalPlanningServicesDialog(selected: Set<String>, onProvider: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.planner_local_services)) },
        text = {
            Column {
                Text(stringResource(R.string.planner_local_services_detail), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                LocalPlanningService.entries.forEach { service ->
                    val checked = service.providerId in selected
                    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox,
                        onValueChange = { onProvider(service.providerId, it) }).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TransitVehicleIcon(service.providerId, size = 40.dp)
                        Text(service.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Checkbox(checked, onCheckedChange = null)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.planner_local_services_done)) } })
}

@Composable
private fun PlannerFindButton(planning: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    Button(onClick = onClick, enabled = !planning, modifier = modifier.heightIn(min = 56.dp), shape = TransitDesign.controlShape) {
        if (planning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Icon(Icons.Rounded.Route, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (planning) R.string.planner_loading else R.string.planner_find))
    }
}

@Composable
private fun EndpointField(label: Int, endpoint: PlannerEndpoint?, current: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = TransitDesign.controlShape) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (current) Icons.Rounded.MyLocation else if (label == R.string.planner_from) Icons.Rounded.TripOrigin else Icons.Rounded.LocationOn,
                null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val platformCode: String? = (if (endpoint?.detail?.matches(Regex("^[A-Z0-9]{1,4}$")) == true) endpoint.detail else null)
                    ?: hu.app.bmenetrend.data.BkkPlatformIndex.getPlatformCode(endpoint?.id)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (current || endpoint?.id == "current") stringResource(R.string.planner_current)
                        else endpoint?.name ?: stringResource(if (label == R.string.planner_to) R.string.planner_subtitle else R.string.planner_choose),
                        style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    platformCode?.let { PlatformBadge(it) }
                }
                if (endpoint?.detail?.isNotBlank() == true && platformCode == null) Text(endpoint.detail, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlannerSearch(
    state: PlannerState,
    onQuery: (String) -> Unit,
    onChoose: (PlannerEndpoint) -> Unit,
    onBack: () -> Unit,
    onCurrent: () -> Unit,
    onStopDepartures: ((String) -> Unit)? = null,
) {
    BackHandler(onBack = onBack)
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    LaunchedEffect(state.query) { listState.scrollToItem(0) }
    val (stations, places) = remember(state.suggestions) {
        state.suggestions.partition { it.isStation || it.routeIds.isNotEmpty() || it.id.startsWith("emma:") }
    }
    val choose: (PlannerEndpoint) -> Unit = { keyboard?.hide(); onChoose(it) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = maxOf(0.dp, adaptivePageGutter() - 20.dp))) {
        TransitSearchInput(state.query, onQuery, onBack,
            stringResource(if (state.editing == PlannerField.ORIGIN) R.string.planner_from else R.string.planner_to))
        if (state.editing == PlannerField.ORIGIN) {
            FilledTonalButton(onClick = onCurrent, shape = TransitDesign.controlShape,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.MyLocation, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.planner_current))
            }
        }
        Box(Modifier.fillMaxWidth().height(8.dp)) {
            if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())) {
            if (state.query.trim().length < 2) {
                item(key = "hint", contentType = "intro") {
                    EmptyState(stringResource(R.string.search_discover_title), stringResource(R.string.search_discover_body), Icons.Rounded.Search)
                    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Keleti pályaudvar", "Kelenföld", "Nyugati pályaudvar").forEach { example ->
                            SuggestionChip(onClick = { onQuery(example) }, label = { Text(example) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
            }
            state.recommendation?.takeIf { state.query.trim().length >= 3 }?.let { endpoint ->
                item(key = "recommendation", contentType = "recommendation") {
                    SearchRecommendation(endpoint.name, providerNames(endpoint.providerIds), { choose(endpoint) })
                }
            }
            if (stations.isNotEmpty()) {
                item(key = "stations_header", contentType = "header") { SearchGroupTitle(stringResource(R.string.search_stations)) }
                items(stations, key = { "station-${it.id}" }, contentType = { "station" }) { endpoint ->
                    PlannerSearchResult(endpoint, state.userLocation, { choose(endpoint) }, onStopDepartures)
                }
            }
            if (places.isNotEmpty()) {
                item(key = "places_header", contentType = "header") { SearchGroupTitle(stringResource(R.string.search_places)) }
                items(places, key = { "place-${it.id}" }, contentType = { "place" }) { endpoint ->
                    PlannerSearchResult(endpoint, state.userLocation, { choose(endpoint) }, null)
                }
                item(key = "attribution", contentType = "notice") {
                    Text(stringResource(R.string.search_places_attribution), Modifier.padding(20.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!state.searching && state.query.trim().length >= 2 && state.suggestions.isEmpty() && state.recommendation == null) {
                item(key = "empty", contentType = "notice") {
                    Text(stringResource(if (state.searchUnavailable) R.string.planner_search_error else R.string.planner_no_places),
                        Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (state.searchUnavailable && (state.suggestions.isNotEmpty() || state.recommendation != null)) {
                item(key = "partial_error", contentType = "notice") {
                    Text(stringResource(R.string.search_places_unavailable), Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlannerSearchResult(endpoint: PlannerEndpoint, location: GeoPoint?, onChoose: () -> Unit, onStopDepartures: ((String) -> Unit)?) {
    val distance = remember(location, endpoint.position) {
        location?.let { loc ->
            val meters = StopClusters.distanceMeters(loc, endpoint.position)
            if (meters < 1000) "${meters.toInt()} m" else "${(meters / 1000.0).roundToInt()} km"
        }
    }
    val platform = if (endpoint.isStation) BkkPlatformIndex.resolve(endpoint.id, endpoint.detail.takeIf { it.isNotBlank() }) else null
    val routes = remember(endpoint.routeIds) {
        endpoint.routeIds.mapNotNull { it.substringAfterLast(":").takeIf(StopClusters::isCleanRouteNumber) }.distinct().take(4)
    }
    val detail = listOfNotNull(if (endpoint.isStation) providerNames(endpoint.providerIds) else endpoint.detail,
        if (endpoint.isStation) transportLabel(endpoint.transportMode) else null, distance).filter(String::isNotBlank).joinToString(" · ")
    SearchResultCard(endpoint.name, detail, onChoose,
        leading = {
            if (endpoint.isStation && (endpoint.transportMode != null || endpoint.providerIds.any { it in LocalPlanningService.providerIds }))
                TransitVehicleIcon(endpoint.providerIds.firstOrNull().orEmpty(), endpoint.transportMode, 40.dp)
            else TransitIconTile(Icons.Rounded.LocationOn)
        },
        badge = { platform?.let { PlatformBadge(it) } },
        supporting = {
            if (routes.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                routes.forEach { number ->
                    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text(number, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        trailing = {
            if (onStopDepartures != null && endpoint.isStation) IconButton(onClick = { onStopDepartures(endpoint.id) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Schedule, stringResource(R.string.planner_view_departures))
            } else Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        })
}
