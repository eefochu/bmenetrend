package hu.viabusz.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.viabusz.app.R
import hu.viabusz.app.data.TransitSearch
import hu.viabusz.app.data.ArrivalKind
import hu.viabusz.app.data.Departure
import hu.viabusz.app.data.RouteDirection
import hu.viabusz.app.data.TransitRoute
import hu.viabusz.app.data.TransitStop
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

private val clockFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("hu-HU"))
    .withZone(ZoneId.of("Europe/Budapest"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViaBuszScreen(
    state: UiState,
    onStopSelected: (String) -> Unit,
    onRefresh: () -> Unit,
    onLocate: () -> Unit,
    onWindowChanged: (Int) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRecenter: () -> Unit = {},
    onNationwideNetworkChanged: (Boolean) -> Unit = {},
    onVehicleClosed: () -> Unit = {},
    onAccentColorChanged: (AccentColor) -> Unit = {},
    onCustomAccentChanged: (Int) -> Unit = {},
    onLineOpened: (String, Boolean) -> Unit = { _, _ -> },
    onBoardingStop: (String, String, String) -> Unit = { _, _, _ -> },
    onClearBoarding: () -> Unit = {},
    onShowWholeRoute: () -> Unit = {},
    onJourneyBoardingStop: (String) -> Unit = {},
    onVehicleOpened: (String) -> Unit = {},
    mapContent: @Composable (Modifier, Int) -> Unit,
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var routeId by rememberSaveable { mutableStateOf<String?>(null) }
    var directionId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = false,
        ),
    )
    val selectedStop = state.stops.firstOrNull { it.id == state.selectedStopId }
    val selectedRoute = state.routes.firstOrNull { it.id == routeId }
    val selectedDirection = selectedRoute?.directions?.firstOrNull { it.id == directionId }
    val openSearch: () -> Unit = {
        query = ""
        routeId = null
        directionId = null
        searchOpen = true
    }
    val selectStop: (String) -> Unit = {
        onStopSelected(it)
        searchOpen = false
        routeId = null
        directionId = null
        scope.launch { sheetState.bottomSheetState.partialExpand() }
    }
    val openDeparture: (Departure) -> Unit = { departure ->
        val observed = state.vehicles.find { it.tripId != null && it.tripId == departure.directionId }
        if (observed != null) {
            onVehicleOpened(observed.id)
            searchOpen = false
        } else state.routes.firstOrNull { it.id == departure.routeId }?.let { route ->
            routeId = route.id
            directionId = state.lineDetails[route.id]?.patterns?.find { departure.patternCode in it.codes }?.id
                ?: route.directions.firstOrNull { it.id == departure.directionId }?.id
                ?: route.directions.singleOrNull()?.id
            onLineOpened(route.id, false)
            searchOpen = true
        }
    }
    val navigateBack: () -> Unit = {
        when {
            directionId != null -> directionId = null
            routeId != null -> routeId = null
            else -> searchOpen = false
        }
    }
    BackHandler(enabled = state.selectedVehicle != null && !searchOpen, onBack = onVehicleClosed)
    BackHandler(enabled = searchOpen, onBack = navigateBack)
    LaunchedEffect(state.selectedVehicle?.id) {
        if (state.selectedVehicle != null) {
            searchOpen = false
            sheetState.bottomSheetState.partialExpand()
        }
    }
    LaunchedEffect(state.selectedStopId, state.cameraRequest, state.boardingSelection) {
        if (state.selectedVehicle == null && state.selectedStopId != null) {
            if (state.boardingSelection != null) sheetState.bottomSheetState.expand() else sheetState.bottomSheetState.partialExpand()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val peekHeight = (maxHeight * 0.34f).coerceIn(110.dp, 330.dp)
            val density = LocalDensity.current
            val peekHeightPx = with(density) { peekHeight.roundToPx() }
            val sheetHidden = sheetState.bottomSheetState.targetValue == SheetValue.Hidden
            val bottomPaddingPx = if (sheetHidden) 0 else peekHeightPx

            BottomSheetScaffold(
                scaffoldState = sheetState,
                sheetPeekHeight = peekHeight,
                sheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
                sheetContainerColor = MaterialTheme.colorScheme.surface,
                sheetTonalElevation = 1.dp,
                sheetShadowElevation = 8.dp,
                containerColor = Color.Transparent,
                sheetContent = {
                    if (state.selectedVehicle != null) VehiclePanel(
                        state = state,
                        onClose = onVehicleClosed,
                        onShowRoute = onShowWholeRoute,
                        onRefresh = onRefresh,
                        onStop = onJourneyBoardingStop,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f),
                    ) else StopPanel(
                        state = state,
                        stop = selectedStop,
                        onRefresh = onRefresh,
                        onWindowChanged = onWindowChanged,
                        onClearBoarding = onClearBoarding,
                        onChangeDirection = {
                            state.boardingSelection?.let { routeId = it.routeId; directionId = null; onLineOpened(it.routeId, false); searchOpen = true }
                        },
                        onToggleFavorite = onToggleFavorite,
                        onSearch = openSearch,
                        onDeparture = openDeparture,
                        onHide = { scope.launch { sheetState.bottomSheetState.hide() } },
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f),
                    )
                },
            ) { _ ->
                Box(Modifier.fillMaxSize()) {
                    // Full-screen map going under both the top search blob and the bottom sheet.
                    mapContent(Modifier.fillMaxSize(), bottomPaddingPx)

                    // A quiet search bar leaves map labels and route geometry unobstructed.
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FloatingSearchBlob(
                            onSearch = openSearch,
                            onSettings = { showSettings = true },
                        )
                    }

                    // Floating MyLocation button above the bottom sheet (or at bottom when sheet is hidden)
                    val locateBottomPadding by animateDpAsState(
                        targetValue = if (sheetHidden) 24.dp else (peekHeight + 16.dp),
                        label = "locateBottomPadding",
                    )
                    FilledTonalIconButton(
                        onClick = onLocate,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = locateBottomPadding)
                            .size(52.dp),
                        shape = CircleShape,
                    ) {
                        Icon(
                            Icons.Rounded.MyLocation,
                            contentDescription = stringResource(R.string.ui_nearest_stop_action),
                        )
                    }

                    if (sheetHidden) {
                        Surface(
                            onClick = { scope.launch { sheetState.bottomSheetState.partialExpand() } },
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 16.dp, bottom = 24.dp),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shadowElevation = 3.dp,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(Icons.Rounded.DirectionsBus, null, Modifier.size(20.dp))
                                Text(
                                    stringResource(R.string.ui_show_departures),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }

                    if (state.loading && state.network == null) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                }
            }
            if (searchOpen) {
                SearchAndRoutes(
                    state = state,
                    query = query,
                    onQueryChanged = { query = it },
                    route = selectedRoute,
                    direction = selectedDirection,
                    patternId = directionId,
                    onBack = navigateBack,
                    onRoute = { routeId = it; directionId = null; onLineOpened(it, false) },
                    onDirection = { directionId = it },
                    onStop = selectStop,
                    onBoard = { line, direction, stop ->
                        onBoardingStop(line, direction, stop)
                        searchOpen = false
                    },
                    onRetryLine = { onLineOpened(it, true) },
                )
            }
        }
    }
    if (showSettings) SettingsPage(state, onAccentColorChanged, onCustomAccentChanged,
        onNationwideNetworkChanged, onAbout = { showInfo = true }, onBack = { showSettings = false })
    if (showInfo) SourceDialog(onDismiss = { showInfo = false })
}

@Composable
private fun FloatingSearchBlob(
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onSearch,
        modifier = modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = stringResource(R.string.ui_search_hint),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(stringResource(R.string.ui_search_short), Modifier.weight(1f).padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onSettings) {
                Icon(Icons.Rounded.Settings, stringResource(R.string.volan_settings))
            }

        }
    }
}

@Composable
private fun StopPanel(
    state: UiState,
    stop: TransitStop?,
    onRefresh: () -> Unit,
    onWindowChanged: (Int) -> Unit,
    onClearBoarding: () -> Unit,
    onChangeDirection: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSearch: () -> Unit,
    onDeparture: (Departure) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val routeById = remember(state.network?.routes, state.emmaRoutes) { state.routes.associateBy { it.id } }
    val timetableOnly = stop?.providerIds?.let { providers -> providers.isNotEmpty() && providers.all { it == "homm" } } == true
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(
                        if (stop != null && stop.id == state.nearestStopId) Icons.Rounded.NearMe else Icons.Rounded.LocationOn,
                        null,
                        Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(if (stop != null && stop.id == state.nearestStopId) R.string.ui_nearest_stop else R.string.ui_selected_stop),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.8.sp,
                    )
                }
                Text(stop?.name ?: stringResource(if (state.loading) R.string.ui_loading_stops else R.string.ui_select_stop), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                if (stop != null) {
                    Text(listOfNotNull(providerNames(stop.providerIds), stop.platformCode?.let { stringResource(R.string.emma_platform, it) }).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                }
            }
            if (stop != null) {
                IconButton(onClick = { onToggleFavorite(stop.id) }) {
                    Icon(
                        if (stop.id in state.favorites) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        stringResource(if (stop.id in state.favorites) R.string.ui_remove_favorite else R.string.ui_add_favorite),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                if (state.refreshing) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.Refresh, stringResource(R.string.ui_refresh_departures))
            }
            IconButton(onClick = onHide) {
                Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.ui_hide_departures))
            }
        }
        state.boardingSelection?.let { selection ->
            Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        routeById[selection.routeId]?.let { LineBadge(it) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onClearBoarding) { Icon(Icons.Rounded.Close, stringResource(R.string.line_all_departures)) }
                    }
                    Text(stringResource(R.string.emma_vehicle_towards, selection.title), style = MaterialTheme.typography.bodyLarge)
                    TextButton(onClick = onChangeDirection, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.line_change_direction)) }
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 6.dp, bottom = 3.dp),
        ) {
            item { Text(stringResource(R.string.ui_departures), Modifier.padding(end = 5.dp), style = MaterialTheme.typography.labelLarge) }
            items(listOf(30, 60, 120)) { minutes ->
                FilterChip(
                    selected = state.windowMinutes == minutes,
                    onClick = { onWindowChanged(minutes) },
                    label = { Text(if (minutes == 120) stringResource(R.string.ui_two_hours) else stringResource(R.string.ui_minutes_value, minutes)) },
                )
            }
        }
        if (state.loading || state.departureBoardLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        else HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        ) {
            if (!state.error.isNullOrBlank()) {
                item { Notice(state.error, Icons.Rounded.WifiOff, isError = true) }
            }
            if (!state.emmaStatus.isNullOrBlank()) {
                item { Notice(state.emmaStatus, Icons.Rounded.DirectionsBus) }
            }
            if (!state.stopStatus.isNullOrBlank()) item { Notice(state.stopStatus, Icons.Rounded.LocationOn) }
            if (!state.departureStatus.isNullOrBlank()) item { Notice(state.departureStatus, Icons.Rounded.AccessTime) }
            if (stop == null && !state.loading) {
                item {
                    EmptyState(stringResource(R.string.ui_pick_stop_title), stringResource(R.string.ui_pick_stop_body), Icons.Rounded.LocationOn)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Button(onClick = onSearch) { Text(stringResource(R.string.ui_search_stop)) }
                    }
                }
            }
            if (state.liveStopMinutes.isNotEmpty()) {
                item { LiveStopSignal(state.liveStopMinutes) }
            }
            if (stop != null && state.departures.isEmpty() && !state.loading && !state.departureBoardLoading && !state.departureBoardUnavailable) {
                item { EmptyState(stringResource(R.string.ui_no_departures_title), stringResource(R.string.ui_no_departures_body, state.windowMinutes), Icons.Rounded.AccessTime) }
            }
            itemsIndexed(state.departures) { index, departure ->
                DepartureRow(
                    departure = departure,
                    now = state.now,
                    providerId = routeById[departure.routeId]?.providerId ?: "viabusz",
                    route = routeById[departure.routeId],
                    onClick = { onDeparture(departure) },
                )
                if (index < state.departures.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 22.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            if (!state.status.isNullOrBlank()) {
                item { Notice(state.status, Icons.Rounded.Info) }
            }
            if (!state.locationMessage.isNullOrBlank()) {
                item { Notice(state.locationMessage, Icons.Rounded.MyLocation) }
            }
            item {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        if (timetableOnly) stringResource(R.string.ui_homm_schedule_only)
                        else (if (stop?.id?.startsWith("emma:") == true) state.boardUpdatedAt else state.lastUpdated)?.let { stringResource(R.string.ui_last_updated, clockFormat.format(it)) }
                            ?: stringResource(if (state.loading) R.string.ui_loading_data else R.string.ui_no_live_update),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveStopSignal(minutes: List<Int>) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Rounded.Sensors, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(stringResource(R.string.ui_live_stop_signal), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Spacer(Modifier.weight(1f))
                Text(minutes.map { if (it == 0) stringResource(R.string.ui_now) else stringResource(R.string.ui_minutes_short, it) }.joinToString(" / "), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Text(stringResource(R.string.ui_live_stop_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun DepartureRow(departure: Departure, now: Instant, providerId: String, route: TransitRoute?, onClick: () -> Unit) {
    val live = departure.kind == ArrivalKind.LIVE
    val minutes = ceil(Duration.between(now, departure.expectedAt).seconds / 60.0).toInt().coerceAtLeast(0)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LineBadge(departure.routeNumber, providerId, route?.transportMode, route?.color)
            Text(providerName(providerId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(departure.destination, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(if (live) Icons.Rounded.Sensors else Icons.Rounded.AccessTime, null, Modifier.size(13.dp), tint = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(if (live) R.string.ui_live_arrival else R.string.ui_scheduled), style = MaterialTheme.typography.labelMedium, color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("· ${clockFormat.format(departure.expectedAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (live && departure.scheduledAt != null && kotlin.math.abs(Duration.between(departure.scheduledAt, departure.expectedAt).seconds) >= 60) {
                val delay = Duration.between(departure.scheduledAt, departure.expectedAt).toMinutes().toInt()
                Text(stringResource(R.string.line_scheduled_delay, clockFormat.format(departure.scheduledAt), if (delay > 0) "+$delay" else "$delay"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val features = listOfNotNull(
                if (departure.wheelchairAccessible == "POSSIBLE") stringResource(R.string.line_accessible) else null,
                if (departure.bikesAllowed == "ALLOWED") stringResource(R.string.line_bikes) else null)
            if (features.isNotEmpty()) Text(features.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (departure.restrictedService) Text(stringResource(R.string.ui_registration_required), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(clockFormat.format(departure.expectedAt), style = MaterialTheme.typography.titleLarge, color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(if (minutes == 0) stringResource(R.string.ui_now) else stringResource(R.string.ui_minutes_short, minutes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SearchAndRoutes(
    state: UiState,
    query: String,
    onQueryChanged: (String) -> Unit,
    route: TransitRoute?,
    direction: RouteDirection?,
    patternId: String?,
    onBack: () -> Unit,
    onRoute: (String) -> Unit,
    onDirection: (String) -> Unit,
    onStop: (String) -> Unit,
    onBoard: (String, String, String) -> Unit,
    onRetryLine: (String) -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back)) }
                if (route == null) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChanged,
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.ui_search_field_hint)) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { onQueryChanged("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.ui_clear_search)) }
                        },
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.weight(1f).padding(end = 9.dp),
                    )
                } else {
                    LineBadge(route)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(transportLabel(route.transportMode) ?: stringResource(R.string.emma_mode_bus), style = MaterialTheme.typography.titleLarge)
                        Text(listOfNotNull(providerName(route.providerId), transportLabel(route.transportMode)).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            when {
                route == null -> SearchResults(state, query, onRoute, onStop)
                route.providerId in setOf("volan", "bkk") -> EmmaLineBrowser(state, route, patternId, onDirection, onBoard) { onRetryLine(route.id) }
                direction == null -> DirectionList(route, onDirection)
                else -> RouteStops(state, route, direction) { onBoard(route.id, direction.id, it) }
            }
        }
    }
}

@Composable
private fun transportLabel(mode: String?): String? = when (mode) {
    "TRAM" -> stringResource(R.string.emma_mode_tram)
    "TROLLEYBUS" -> stringResource(R.string.emma_mode_trolley)
    "SUBWAY" -> stringResource(R.string.emma_mode_metro)
    "BUS", "COACH" -> stringResource(R.string.emma_mode_bus)
    "RAIL", "SUBURBAN_RAILWAY", "TRAMTRAIN" -> stringResource(R.string.emma_mode_train)
    else -> null
}

@Composable
private fun SearchResults(state: UiState, query: String, onRoute: (String) -> Unit, onStop: (String) -> Unit) {
    val parsedQuery = remember(query) { TransitSearch.query(query) }
    if (parsedQuery.text.isBlank()) return
    val stopIndex by produceState<List<TransitSearch.StopEntry>?>(null, state.network?.stops, state.emmaStops) {
        value = withContext(Dispatchers.Default) { TransitSearch.indexStops(state.stops) }
    }
    var stopSearchLoading by remember(parsedQuery, stopIndex, state.emmaRoutes) { mutableStateOf(true) }
    val result by produceState(Pair(emptyList<TransitRoute>(), emptyList<TransitStop>()), parsedQuery, stopIndex, state.emmaRoutes, state.network, state.favorites) {
        delay(120)
        value = withContext(Dispatchers.Default) {
            TransitSearch.routes(state.routes, parsedQuery) to TransitSearch.stops(stopIndex.orEmpty(), parsedQuery, state.userLocation, state.favorites)
        }
        stopSearchLoading = false
    }
    val routes = result.first
    val stops = result.second
    val favorites = stops.filter { it.id in state.favorites }
    val otherStops = stops.filterNot { it.id in state.favorites }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        if (stopSearchLoading || stopIndex == null || !state.stopIndexLoaded && state.stopStatus == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!state.stopStatus.isNullOrBlank()) item { Notice(state.stopStatus, Icons.Rounded.LocationOn) }
        if (routes.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.ui_local_routes), stringResource(if (routes.any { it.providerId in setOf("volan", "bkk") }) R.string.volan_route_results else R.string.ui_directions_and_stops)) }
            items(routes, key = { "route-${it.id}" }) { route ->
                Row(
                    Modifier.fillMaxWidth().clickable { onRoute(route.id) }.padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    LineBadge(route)
                    Column(Modifier.weight(1f)) {
                        Text(listOfNotNull(providerName(route.providerId), transportLabel(route.transportMode)).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                        Text(if (route.name == route.number) stringResource(R.string.ui_choose_direction) else route.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (favorites.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.ui_favorite_stops)) }
            items(favorites, key = { "favorite-${it.id}" }) { StopSearchRow(it, state, true, onStop) }
        }
        if (otherStops.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.ui_stops), pluralStringResource(R.plurals.ui_results_count, stops.size, stops.size)) }
            items(otherStops, key = { "stop-${it.id}" }) { StopSearchRow(it, state, false, onStop) }
        }
        if (routes.size >= 100 || stops.size >= 100) item { Notice(stringResource(R.string.search_refine), Icons.Rounded.Search) }
        if (routes.isEmpty() && stops.isEmpty() && !state.loading && !stopSearchLoading && stopIndex != null && state.stopIndexLoaded) {
            item { EmptyState(stringResource(R.string.ui_no_results_title), stringResource(R.string.ui_no_results_body), Icons.Rounded.Search) }
        }
    }
}

@Composable
private fun stopLabel(stop: TransitStop): String? = stop.platformCode?.let { stringResource(R.string.emma_platform, it) }
    ?: stop.id.takeIf { it.startsWith("emma:") }?.let { stringResource(R.string.emma_stop_code, it.substringAfterLast(":")) }

@Composable
private fun StopSearchRow(stop: TransitStop, state: UiState, favorite: Boolean, onStop: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onStop(stop.id) }.padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.LocationOn, null, Modifier.padding(11.dp).size(21.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stop.name, style = MaterialTheme.typography.titleMedium)
            val numbers = stop.routeIds.map { id -> state.routes.find { it.id == id }?.number ?: id }.distinct()
            Text("${providerNames(stop.providerIds)}${stopLabel(stop)?.let { " · $it" }.orEmpty()} · ${numbers.joinToString("  ·  ").ifBlank { stringResource(R.string.ui_stops) }}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DirectionList(route: TransitRoute, onDirection: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(route.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(4.dp))
            Text(stringResource(R.string.ui_direction_question), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 7.dp, bottom = 12.dp))
        }
        items(route.directions, key = { it.id }) { direction ->
            Surface(onClick = { onDirection(direction.id) }, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(direction.name, style = MaterialTheme.typography.titleMedium)
                        Text(pluralStringResource(R.plurals.ui_stops_count, direction.stopIds.size, direction.stopIds.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (direction.restrictedService) Text(stringResource(R.string.ui_registration_required), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }
        if (route.directions.isEmpty()) item { EmptyState(stringResource(R.string.ui_no_directions_title), stringResource(R.string.ui_no_directions_body), Icons.Rounded.Info) }
    }
}

@Composable
private fun RouteStops(state: UiState, route: TransitRoute, direction: RouteDirection, onStop: (String) -> Unit) {
    val stopById = remember(state.network) { state.network?.stops.orEmpty().associateBy { it.id } }
    LazyColumn(contentPadding = PaddingValues(bottom = 36.dp)) {
        item {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(stringResource(R.string.ui_route_direction, route.number), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(direction.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(stringResource(R.string.ui_stop_departures_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (direction.restrictedService) Text(stringResource(R.string.ui_registration_required), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        itemsIndexed(direction.stopIds) { index, stopId ->
            stopById[stopId]?.let { stop -> LineStopRow(stop, index == 0, index == direction.stopIds.lastIndex,
                lineColor(route), onClick = { onStop(stopId) }) }
        }
    }
}

@Composable
private fun SectionLabel(title: String, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 25.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Notice(message: String, icon: ImageVector, isError: Boolean = false) {
    val color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer
    val textColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = RoundedCornerShape(16.dp), color = color) {
        Row(Modifier.padding(13.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.padding(top = 1.dp).size(17.dp), tint = textColor)
            Text(message, style = MaterialTheme.typography.bodySmall, color = textColor)
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String, icon: ImageVector) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(27.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SourceDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DirectionsBus, null) },
        title = { Text(stringResource(R.string.ui_brand)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ui_about_description))
                Text(stringResource(R.string.ui_about_live_data))
                Text(stringResource(R.string.ui_about_homm_data))
                Text(stringResource(R.string.volan_about))
                TextButton(onClick = { runCatching { uriHandler.openUri("https://mavplusz.hu/") } }) {
                    Text(stringResource(R.string.volan_source_link))
                }
                Text(stringResource(R.string.ui_about_location))
                TextButton(onClick = { runCatching { uriHandler.openUri("https://viabusz.biatorbagy.hu/") } }) {
                    Text(stringResource(R.string.ui_viabusz_website))
                }
                TextButton(onClick = { runCatching { uriHandler.openUri("https://kanyargo.paty.hu/") } }) {
                    Text(stringResource(R.string.ui_kanyargo_website))
                }
                TextButton(onClick = { runCatching { uriHandler.openUri("https://homm.hu/helyi-kozlekedes/") } }) {
                    Text(stringResource(R.string.ui_homm_website))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) }
        },
    )
}

@Composable
private fun providerName(providerId: String): String = stringResource(
    when (providerId) {
        "kanyargo" -> R.string.ui_provider_kanyargo
        "homm" -> R.string.ui_provider_homm
        "volan" -> R.string.volan_provider
        "bkk" -> R.string.emma_bkk_provider
        else -> R.string.ui_provider_viabusz
    },
)

@Composable
private fun providerNames(providerIds: List<String>): String = providerIds.distinct().map { providerName(it) }.joinToString(" · ")

private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)
    .replace("\\p{M}+".toRegex(), "")
    .lowercase(Locale.forLanguageTag("hu-HU"))
