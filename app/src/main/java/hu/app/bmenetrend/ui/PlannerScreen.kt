package hu.app.bmenetrend.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import hu.app.bmenetrend.map.VehicleMarkerStyle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
        isDotted: Boolean
    ) -> Unit)? = null,
    mapContent: (@Composable (Modifier) -> Unit)? = null,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var departurePicker by rememberSaveable { mutableStateOf(false) }
    var inResultsView by rememberSaveable { mutableStateOf(false) }

    var routePreference by rememberSaveable { mutableIntStateOf(0) }
    var showPreferenceMenu by remember { mutableStateOf(false) }
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var wheelchairAccessible by rememberSaveable { mutableStateOf(false) }
    var bicycleTransport by rememberSaveable { mutableStateOf(false) }
    var busEnabled by rememberSaveable { mutableStateOf(true) }
    var tramEnabled by rememberSaveable { mutableStateOf(true) }
    var trainEnabled by rememberSaveable { mutableStateOf(true) }

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

    val now by produceState(Instant.now()) {
        while (true) { value = Instant.now(); delay(15_000) }
    }
    val result = state.result
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
    val currentDeparture = state.departure

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

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = {
                Text(
                    stringResource(R.string.planner_route_settings),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { wheelchairAccessible = !wheelchairAccessible }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Accessible, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(R.string.planner_wheelchair_accessible),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = wheelchairAccessible,
                            onCheckedChange = { wheelchairAccessible = it }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { bicycleTransport = !bicycleTransport }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.DirectionsBike, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(R.string.planner_bicycle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = bicycleTransport,
                            onCheckedChange = { bicycleTransport = it }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Text(
                        stringResource(R.string.planner_transit_modes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = busEnabled,
                            onClick = { busEnabled = !busEnabled },
                            label = { Text("Busz", maxLines = 1, softWrap = false) },
                            leadingIcon = {
                                Image(
                                    rememberTransitIcon(R.drawable.vehicle_bkk_bus, 18.dp),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                        FilterChip(
                            selected = tramEnabled,
                            onClick = { tramEnabled = !tramEnabled },
                            label = { Text("Villamos", maxLines = 1, softWrap = false) },
                            leadingIcon = {
                                Image(
                                    rememberTransitIcon(R.drawable.vehicle_tram, 18.dp),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                        FilterChip(
                            selected = trainEnabled,
                            onClick = { trainEnabled = !trainEnabled },
                            label = { Text("Vonat", maxLines = 1, softWrap = false) },
                            leadingIcon = {
                                Image(
                                    rememberTransitIcon(R.drawable.vehicle_train, 18.dp),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) {
                    Text(stringResource(R.string.planner_done))
                }
            }
        )
    }

    if (showResults) {
        // Older-style Plan and Results Screen
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
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
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
                    if (!state.planning && result.partial) item(key = "partial") {
                        Text(
                            stringResource(R.string.planner_partial),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    item(key = "results-title") {
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
                    items(result.itineraries, key = { it.id }) { itinerary ->
                        ItineraryCard(itinerary, itinerary == result.itineraries.first(), now) {
                            model.selectItinerary(itinerary.id)
                        }
                    }
                    item(key = "source") {
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
            // Live map background with route preview line & destination highlight
            if (transitMapContent != null) {
                transitMapContent(
                    Modifier.fillMaxSize(),
                    previewGeometry,
                    previewPlace,
                    previewCameraRequest,
                    currentDest != null
                )
            } else {
                mapContent?.invoke(Modifier.fillMaxSize())
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Card coming from the top edge
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Top Header Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { onClose?.invoke() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.ui_back),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = stringResource(R.string.planner_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            )
                            IconButton(
                                onClick = { onClose?.invoke() },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.ui_close),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Endpoints Layout
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left route indicator column (slim & dotted, not fat)
                            Column(
                                modifier = Modifier.width(22.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                )
                                val outlineCol = MaterialTheme.colorScheme.outlineVariant
                                Canvas(modifier = Modifier.width(2.dp).height(10.dp)) {
                                    val r = 1.dp.toPx()
                                    val step = 3.5.dp.toPx()
                                    var y = r
                                    while (y < size.height) {
                                        drawCircle(outlineCol, r, androidx.compose.ui.geometry.Offset(size.width / 2f, y))
                                        y += step
                                    }
                                }
                                Surface(
                                    onClick = model::swap,
                                    enabled = currentOrigin != null || currentDest != null,
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    modifier = Modifier.size(22.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Rounded.SwapVert,
                                            contentDescription = stringResource(R.string.planner_swap),
                                            modifier = Modifier.size(15.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Canvas(modifier = Modifier.width(2.dp).height(10.dp)) {
                                    val r = 1.dp.toPx()
                                    val step = 3.5.dp.toPx()
                                    var y = r
                                    while (y < size.height) {
                                        drawCircle(outlineCol, r, androidx.compose.ui.geometry.Offset(size.width / 2f, y))
                                        y += step
                                    }
                                }
                                Icon(
                                    Icons.Rounded.Place,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(15.dp)
                                )
                            }

                            // Right input fields
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Origin Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(46.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                        onClick = { model.edit(PlannerField.ORIGIN) }
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(horizontal = 12.dp),
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = stringResource(R.string.planner_from),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            val originText = if (state.useLocation && (currentOrigin == null || currentOrigin.id == "current")) {
                                                stringResource(R.string.planner_current)
                                            } else {
                                                currentOrigin?.name ?: stringResource(R.string.planner_current)
                                            }
                                            Text(
                                                text = originText,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    // Járműről Button
                                    Surface(
                                        modifier = Modifier.size(46.dp),
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                        onClick = { model.edit(PlannerField.ORIGIN) }
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Image(
                                                rememberTransitIcon(R.drawable.vehicle_bkk_bus, 18.dp),
                                                contentDescription = stringResource(R.string.planner_from_vehicle),
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = stringResource(R.string.planner_from_vehicle),
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }

                                // Destination Input Box
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                    onClick = { model.edit(PlannerField.DESTINATION) }
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 12.dp),
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = stringResource(R.string.planner_to),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (currentDest != null) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Rounded.StarBorder,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Text(
                                                        text = currentDest.name,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            } else {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Rounded.Search,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Text(
                                                        text = stringResource(R.string.planner_subtitle),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                        // Options Row (Indulás most, Legjobb útvonal, Beállítások)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Indulás most ∨
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (currentDeparture != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                onClick = { departurePicker = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Schedule,
                                        contentDescription = null,
                                        tint = if (currentDeparture != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    val depText = if (currentDeparture == null) {
                                        stringResource(R.string.planner_leave_now)
                                    } else {
                                        departureLabel(currentDeparture)
                                    }
                                    Text(
                                        text = depText,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false,
                                        color = if (currentDeparture != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    Icon(
                                        Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = if (currentDeparture != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }

                            // Legjobb útvonal ∨
                            Box {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                    onClick = { showPreferenceMenu = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = when (routePreference) {
                                                1 -> stringResource(R.string.planner_fewest_transfers)
                                                2 -> stringResource(R.string.planner_least_walking)
                                                else -> stringResource(R.string.planner_best_route)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            softWrap = false,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Icon(
                                            Icons.Rounded.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                                DropdownMenu(
                                    expanded = showPreferenceMenu,
                                    onDismissRequest = { showPreferenceMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.planner_best_route)) },
                                        onClick = { routePreference = 0; showPreferenceMenu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.planner_fewest_transfers)) },
                                        onClick = { routePreference = 1; showPreferenceMenu = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.planner_least_walking)) },
                                        onClick = { routePreference = 2; showPreferenceMenu = false }
                                    )
                                }
                            }

                            // Beállítások button
                            val hasCustomSettings = wheelchairAccessible || bicycleTransport || !busEnabled || !tramEnabled || !trainEnabled
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (hasCustomSettings) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                onClick = { showSettingsDialog = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Tune,
                                        contentDescription = stringResource(R.string.planner_settings),
                                        tint = if (hasCustomSettings) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.planner_settings),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false,
                                        color = if (hasCustomSettings) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }

                // Middle area over map
                Spacer(Modifier.weight(1f))

                // Error Notice if any
                state.error?.let { error ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = stringResource(
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
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                // Bottom Action Button: "Mutasd az útvonalakat"
                Button(
                    onClick = {
                        if (currentDest == null) {
                            model.edit(PlannerField.DESTINATION)
                        } else {
                            if (state.useLocation && currentOrigin == null) onRequestLocation()
                            inResultsView = true
                            model.plan()
                        }
                    },
                    enabled = !state.planning,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp, pressedElevation = 10.dp)
                ) {
                    if (state.planning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.planner_loading),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Route,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.planner_find),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
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

internal fun resolveEndpointVehicleStyle(endpoint: PlannerEndpoint): VehicleMarkerStyle {
    val isTrain = endpoint.transportMode in setOf("RAIL", "TRAIN", "SUBURBAN_RAILWAY", "TRAMTRAIN") ||
                  endpoint.providerIds.any { it in setOf("mav", "gysev") } ||
                  endpoint.id.contains("mav", ignoreCase = true) ||
                  endpoint.id.contains("gysev", ignoreCase = true) ||
                  endpoint.name.contains("vasútállomás", ignoreCase = true) ||
                  endpoint.name.contains("pályaudvar", ignoreCase = true) ||
                  endpoint.name.matches(Regex(".*\\b(pu|vá)\\b.*", RegexOption.IGNORE_CASE)) ||
                  endpoint.name.startsWith("Budapest-")
    if (isTrain) return VehicleMarkerStyle.TRAIN

    val isTram = endpoint.transportMode == "TRAM" ||
                 endpoint.name.contains("villamos", ignoreCase = true)
    if (isTram) return VehicleMarkerStyle.TRAM

    val isTrolley = endpoint.transportMode == "TROLLEYBUS" ||
                    endpoint.name.contains("troli", ignoreCase = true)
    if (isTrolley) return VehicleMarkerStyle.TROLLEY

    val isSubway = endpoint.transportMode == "SUBWAY" ||
                   endpoint.name.endsWith(" M") ||
                   endpoint.name.contains("metró", ignoreCase = true)
    if (isSubway) return VehicleMarkerStyle.TRAIN

    if (endpoint.providerIds.contains("volan") || endpoint.id.startsWith("volan:") || endpoint.name.contains("VB pályaudvar", ignoreCase = true)) {
        return VehicleMarkerStyle.VOLAN_BUS
    }
    if (endpoint.providerIds.contains("viabusz") || endpoint.id.startsWith("viabusz:")) {
        return VehicleMarkerStyle.VIABUSZ_BUS
    }
    return VehicleMarkerStyle.BKK_BUS
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
                    val vehicleStyle = remember(endpoint) { resolveEndpointVehicleStyle(endpoint) }
                    val isTrain = vehicleStyle == VehicleMarkerStyle.TRAIN
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
                                Image(
                                    bitmap = rememberTransitIcon(vehicleStyle.drawableRes, 36.dp),
                                    contentDescription = null,
                                    modifier = Modifier.size(36.dp)
                                )

                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = endpoint.name,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 17.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurface,
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
                                                val badgeBg = if (isRail) railLineColor(badge) else Color(vehicleStyle.color)
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
                                    color = MaterialTheme.colorScheme.onSurface,
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
