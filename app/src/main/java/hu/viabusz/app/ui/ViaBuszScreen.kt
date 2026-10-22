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
        state.routes.firstOrNull { it.id == departure.routeId }?.let { route ->
            routeId = route.id
            directionId = route.directions.firstOrNull { it.id == departure.directionId }?.id
                ?: route.directions.singleOrNull()?.id
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
    LaunchedEffect(state.selectedStopId, state.cameraRequest) {
        if (state.selectedVehicle == null && state.selectedStopId != null) sheetState.bottomSheetState.partialExpand()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val peekHeight = (maxHeight * 0.34f).coerceIn(110.dp, 330.dp)
            val density = LocalDensity.current
            val peekHeightPx = with(density) { peekHeight.roundToPx() }
            val sheetHidden = sheetState.bottomSheetState.targetValue == SheetValue.Hidden
            val bottomPaddingPx = if (sheetHidden) 0 else peekHeightPx

            LaunchedEffect(sheetHidden) {
                if (state.selectedStopId != null) {
                    onRecenter()
                }
            }

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
                        onRefresh = onRefresh,
                        onStop = selectStop,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f),
                    ) else StopPanel(
                        state = state,
                        stop = selectedStop,
                        onRefresh = onRefresh,
                        onWindowChanged = onWindowChanged,
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

                    // Top floating search blob and area/bus count badge
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FloatingSearchBlob(
                            onSearch = openSearch,
                            onInfo = { showInfo = true },
                            onSettings = { showSettings = true },
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.Start,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                                shadowElevation = 3.dp,
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.DirectionsBus,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        if (state.vehicles.isEmpty()) stringResource(R.string.ui_area)
                                        else pluralStringResource(R.plurals.ui_buses_on_map, state.vehicles.size, state.vehicles.size),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
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
                    onBack = navigateBack,
                    onRoute = { routeId = it; directionId = null },
                    onDirection = { directionId = it },
                    onStop = selectStop,
                )
            }
        }
    }
    if (showInfo) SourceDialog(onDismiss = { showInfo = false })
    if (showSettings) NetworkSettingsDialog(state.nationwideNetwork, onNationwideNetworkChanged, state.accentColor, onAccentColorChanged) { showSettings = false }
}

@Composable
private fun FloatingSearchBlob(
    onSearch: () -> Unit,
    onInfo: () -> Unit,
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
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.ui_brand),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.ui_search_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Rounded.Settings, stringResource(R.string.volan_settings))
            }
            IconButton(onClick = onInfo) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = stringResource(R.string.ui_about_action),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    onToggleFavorite: (String) -> Unit,
    onSearch: () -> Unit,
    onDeparture: (Departure) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    Text(listOfNotNull(providerNames(stop.providerIds), stopLabel(stop)).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
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
                    providerId = state.routes.firstOrNull { it.id == departure.routeId }?.providerId ?: "viabusz",
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
                    Text(stringResource(R.string.ui_data_sources), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun DepartureRow(departure: Departure, now: Instant, providerId: String, onClick: () -> Unit) {
    val live = departure.kind == ArrivalKind.LIVE
    val minutes = ceil(Duration.between(now, departure.expectedAt).seconds / 60.0).toInt().coerceAtLeast(0)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RouteBadge(departure.routeNumber, providerId)
            Text(providerName(providerId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(departure.destination, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(if (live) Icons.Rounded.Sensors else Icons.Rounded.AccessTime, null, Modifier.size(13.dp), tint = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(if (live) R.string.ui_live_arrival else R.string.ui_scheduled), style = MaterialTheme.typography.labelMedium, color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("· ${clockFormat.format(departure.expectedAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (departure.restrictedService) Text(stringResource(R.string.ui_registration_required), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(if (minutes == 0) stringResource(R.string.ui_now) else "$minutes", style = MaterialTheme.typography.headlineMedium, color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (minutes != 0) Text(stringResource(R.string.ui_minutes_unit), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onBack: () -> Unit,
    onRoute: (String) -> Unit,
    onDirection: (String) -> Unit,
    onStop: (String) -> Unit,
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
                    RouteBadge(route.number, route.providerId)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(if (route.providerId in setOf("volan", "bkk")) stringResource(R.string.ui_route_number, route.number) else stringResource(if (direction == null) R.string.ui_choose_direction else R.string.ui_stops), style = MaterialTheme.typography.titleLarge)
                        Text("${route.number} · ${providerName(route.providerId)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            when {
                route == null -> SearchResults(state, query, onRoute, onStop)
                route.providerId in setOf("volan", "bkk") -> EmmaRouteDetails(state, route)
                direction == null -> DirectionList(route, onDirection)
                else -> RouteStops(state, route, direction, onStop)
            }
        }
    }
}

@Composable
private fun SearchResults(state: UiState, query: String, onRoute: (String) -> Unit, onStop: (String) -> Unit) {
    val normalized = remember(query) { normalize(query.trim()) }
    if (normalized.isBlank()) return
    val routes = state.routes.filter { normalize("${it.number} ${it.name} ${it.providerId}").contains(normalized) }
    var stopSearchLoading by remember(state.network, state.emmaStops, normalized) { mutableStateOf(true) }
    val stops by produceState(emptyList<TransitStop>(), state.network, state.emmaStops, normalized) {
        delay(150)
        value = withContext(Dispatchers.Default) {
            state.stops.filter { normalize("${it.name} ${it.aliases.joinToString(" ")} ${it.platformCode.orEmpty()} ${it.sourceStopIds.joinToString(" ")}").contains(normalized) }
        }
        stopSearchLoading = false
    }
    val favorites = stops.filter { it.id in state.favorites }
    val otherStops = stops.filterNot { it.id in state.favorites }
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        if (stopSearchLoading || !state.stopIndexLoaded && state.stopStatus == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!state.stopStatus.isNullOrBlank()) item { Notice(state.stopStatus, Icons.Rounded.LocationOn) }
        if (routes.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.ui_local_routes), stringResource(if (routes.any { it.providerId in setOf("volan", "bkk") }) R.string.volan_route_results else R.string.ui_directions_and_stops)) }
            items(routes, key = { "route-${it.id}" }) { route ->
                Row(
                    Modifier.fillMaxWidth().clickable { onRoute(route.id) }.padding(horizontal = 22.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    RouteBadge(route.number, route.providerId)
                    Column(Modifier.weight(1f)) {
                        Text("${stringResource(R.string.ui_route_number, route.number)} · ${providerName(route.providerId)}", style = MaterialTheme.typography.titleMedium)
                        Text(route.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
        if (routes.isEmpty() && stops.isEmpty() && !state.loading && !stopSearchLoading && state.stopIndexLoaded) {
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
            val stop = stopById[stopId]
            val first = index == 0
            val last = index == direction.stopIds.lastIndex
            Row(
                Modifier.fillMaxWidth().heightIn(min = 74.dp).clickable(enabled = stop != null) { onStop(stopId) }.padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Box(Modifier.width(22.dp).height(80.dp), contentAlignment = Alignment.Center) {
                    if (!first) Box(Modifier.align(Alignment.TopCenter).width(3.dp).height(40.dp).background(MaterialTheme.colorScheme.primary))
                    if (!last) Box(Modifier.align(Alignment.BottomCenter).width(3.dp).height(40.dp).background(MaterialTheme.colorScheme.primary))
                    Box(Modifier.size(if (first || last) 17.dp else 13.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(if (first || last) 7.dp else 6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stop?.name ?: stringResource(R.string.ui_unknown_stop), style = MaterialTheme.typography.titleMedium)
                    if (first || last) Text(stringResource(if (first) R.string.ui_origin else R.string.ui_terminus), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RouteBadge(number: String, providerId: String = "viabusz") {
    Surface(shape = RoundedCornerShape(12.dp), color = if (providerId == "volan") Color(0xFFFFCC00) else MaterialTheme.colorScheme.primaryContainer) {
        Box(Modifier.size(width = 51.dp, height = 43.dp), contentAlignment = Alignment.Center) {
            Text(number, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = if (providerId == "volan") Color(0xFF262100) else MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1)
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


@Composable
private fun NetworkSettingsDialog(enabled: Boolean, onChanged: (Boolean) -> Unit, accent: AccentColor, onAccentChanged: (AccentColor) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.volan_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.accent_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.accent_hint), style = MaterialTheme.typography.bodyMedium)
                Column(Modifier.selectableGroup()) {
                    AccentColor.entries.forEach { choice ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = accent == choice, role = Role.RadioButton, onClick = { onAccentChanged(choice) }),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(selected = accent == choice, onClick = null)
                            Box(Modifier.size(20.dp).clip(CircleShape).background(choice.hue?.let { Color.hsl(it, 0.65f, 0.40f) } ?: MaterialTheme.colorScheme.primary))
                            Text(stringResource(choice.labelRes), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.volan_local_scope))
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .toggleable(value = enabled, role = Role.Switch, onValueChange = onChanged)
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.volan_nationwide), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(checked = enabled, onCheckedChange = null)
                }
                Text(stringResource(R.string.volan_nationwide_hint), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) } },
    )
}

@Composable
private fun EmmaRouteDetails(state: UiState, route: TransitRoute) {
    val uri = LocalUriHandler.current
    val vehicles = state.vehicles.filter { it.routeId == route.id }
    LazyColumn(contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(route.name, style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.volan_route_hint), Modifier.padding(top = 12.dp))
        }
        item {
            route.sourceUrl?.let { url ->
                Button(onClick = { runCatching { uri.openUri(url) } }) { Text(stringResource(R.string.volan_timetable_link)) }
            }
        }
        if (vehicles.isEmpty()) item { Text(stringResource(R.string.volan_no_route_positions)) }
        items(vehicles, key = { it.id }) { vehicle ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(vehicle.registration.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Text(vehicle.destination.orEmpty())
                    Text(stringResource(R.string.volan_observation_time, clockFormat.format(vehicle.observedAt)), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
