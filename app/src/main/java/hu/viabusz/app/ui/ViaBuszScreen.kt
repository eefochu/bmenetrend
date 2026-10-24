package hu.viabusz.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.KeyboardArrowUp
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
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
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
import hu.viabusz.app.data.StopClusters
import hu.viabusz.app.data.TransitRoute
import hu.viabusz.app.data.TransitStop
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlinx.coroutines.launch

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
    onReturnToNearby: () -> Unit = onVehicleClosed,
    onAccentColorChanged: (AccentColor) -> Unit = {},
    onCustomAccentChanged: (Int) -> Unit = {},
    onLineOpened: (String, Boolean) -> Unit = { _, _ -> },
    onBoardingStop: (String, String, String) -> Unit = { _, _, _ -> },
    onClearBoarding: () -> Unit = {},
    onShowWholeRoute: () -> Unit = {},
    onJourneyBoardingStop: (String) -> Unit = {},
    onVehicleOpened: (String) -> Unit = {},
    onSearchPlaces: (String, Boolean) -> Unit = { _, _ -> },
    onPlaceSelected: (hu.viabusz.app.data.PlaceResult) -> Unit = {},
    onPlaceClosed: () -> Unit = {},
    onOtaChannelChanged: (hu.viabusz.app.data.UpdateChannel) -> Unit = {},
    onCheckOtaUpdates: () -> Unit = {},
    onDownloadAndInstallOta: (hu.viabusz.app.data.OtaUpdateInfo) -> Unit = {},
    mapContent: @Composable (Modifier, Int) -> Unit = { modifier, _ -> Box(modifier) },
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var routeId by rememberSaveable { mutableStateOf<String?>(null) }
    var directionId by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showUpdates by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(
            initialValue = SheetValue.PartiallyExpanded,
            skipHiddenState = false,
        ),
    )
    var shownStopToken by remember { mutableStateOf<Pair<String?, Int>?>(null) }
    val selectedStop = remember(state.selectedStopId, state.network?.stops, state.emmaStops) {
        state.findStop(state.selectedStopId)
    }
    val routesById = remember(state.network?.routes, state.emmaRoutes) { state.routesById }
    val selectedRoute = routesById[routeId]
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
    }
    val openDeparture: (Departure) -> Unit = { departure ->
        val observed = state.vehicles.find { it.tripId != null && it.tripId == departure.directionId }
        if (observed != null) {
            onVehicleOpened(observed.id)
            searchOpen = false
        } else (state.findRoute(departure.routeId))?.let { route ->
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
    val minimize: () -> Unit = {
        scope.launch { sheetState.bottomSheetState.hide() }
    }
    val exitRouteView: () -> Unit = {
        onClearBoarding()
        onReturnToNearby()
        minimize()
    }
    val sheet = sheetState.bottomSheetState
    // Discrete anchor changes drive controls. Drag offsets stay inside Material's layout phase.
    val isExpanded by remember { derivedStateOf { sheet.currentValue == SheetValue.Expanded } }
    val isMinimized by remember { derivedStateOf { sheet.currentValue == SheetValue.Hidden } }
    val collapse: () -> Unit = {
        scope.launch {
            if (sheet.targetValue == SheetValue.Expanded) sheet.partialExpand() else sheet.hide()
        }
    }
    val toggleExpand: () -> Unit = {
        scope.launch {
            if (sheet.targetValue == SheetValue.Expanded) sheet.partialExpand() else sheet.expand()
        }
    }
    BackHandler(enabled = !searchOpen && !showSettings && !showAbout && !showUpdates && !isMinimized, onBack = {
        if (state.selectedVehicle != null || state.boardingSelection != null || !state.showNearby) exitRouteView()
        else collapse()
    })
    BackHandler(enabled = searchOpen, onBack = navigateBack)
    LaunchedEffect(sheet.currentValue) {
        if (sheet.currentValue == SheetValue.Hidden && (state.selectedVehicle != null || state.boardingSelection != null || !state.showNearby)) {
            onClearBoarding()
            onReturnToNearby()
        }
    }
    LaunchedEffect(state.selectedVehicle?.id) {
        if (state.selectedVehicle != null) {
            searchOpen = false
            sheet.partialExpand()
        }
    }
    LaunchedEffect(state.selectedStopId, state.cameraRequest, state.boardingSelection) {
        if (!state.showNearby && state.selectedVehicle == null && state.selectedStopId != null) {
            val token = state.selectedStopId to state.cameraRequest
            if (state.boardingSelection != null) sheet.expand()
            else if (token != shownStopToken) sheet.partialExpand()
            shownStopToken = token
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            val screenHeight = LocalConfiguration.current.screenHeightDp.dp
            val peekHeight = (screenHeight * 0.36f).coerceIn(220.dp, 340.dp)
            val density = LocalDensity.current
            val peekHeightPx = with(density) { peekHeight.roundToPx() }
            var layoutHeightPx by remember { mutableIntStateOf(0) }
            // Full expansion overlays the map; refitting it here competes with the sheet animation.
            val bottomPaddingPx = if (isMinimized) 0 else peekHeightPx

            BottomSheetScaffold(
                scaffoldState = sheetState,
                modifier = Modifier.onSizeChanged { layoutHeightPx = it.height },
                sheetPeekHeight = peekHeight,
                sheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
                sheetContainerColor = MaterialTheme.colorScheme.surface,
                sheetTonalElevation = 1.dp,
                sheetShadowElevation = 8.dp,
                containerColor = Color.Transparent,
                sheetDragHandle = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = toggleExpand)
                            .padding(top = 10.dp, bottom = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier.size(width = 36.dp, height = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(2.dp)
                        ) {}
                    }
                },
                sheetContent = {
                    if (state.selectedVehicle != null) VehiclePanel(
                        state = state,
                        onClose = exitRouteView,
                        onShowRoute = { onShowWholeRoute(); scope.launch { sheetState.bottomSheetState.partialExpand() } },
                        onRefresh = onRefresh,
                        onStop = onJourneyBoardingStop,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f),
                    ) else if (state.showNearby || selectedStop == null) NearbyPanel(
                        stations = state.nearbyStations,
                        routesById = routesById,
                        atUser = state.nearbyAtUser,
                        loading = state.nearbyLoading,
                        stopStatus = state.stopStatus,
                        windowMinutes = state.windowMinutes,
                        now = state.now,
                        onStop = selectStop,
                        onDeparture = openDeparture,
                        onRefresh = onRefresh,
                        onLocate = onLocate,
                        onToggleExpand = toggleExpand,
                        onCollapse = collapse,
                        onWindow = onWindowChanged,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f),
                        isExpanded = isExpanded,
                    ) else StopPanel(
                        state = state,
                        stop = selectedStop,
                        onRefresh = onRefresh,
                        onWindowChanged = onWindowChanged,
                        onClearBoarding = exitRouteView,
                        onChangeDirection = {
                            state.boardingSelection?.let { routeId = it.routeId; directionId = null; onLineOpened(it.routeId, false); searchOpen = true }
                        },
                        onToggleFavorite = onToggleFavorite,
                        onSearch = openSearch,
                        onDeparture = openDeparture,
                        onToggleExpand = toggleExpand,
                        onCollapse = collapse,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f),
                        isExpanded = isExpanded,
                    )
                },
            ) { _ ->
                Box(Modifier.fillMaxSize()) {
                    // Full-screen map going under both the top search blob and the bottom sheet.
                    mapContent(Modifier.fillMaxSize(), bottomPaddingPx)

                    if (state.selectedVehicle != null || state.boardingSelection != null) {
                        FilledTonalButton(
                            onClick = exitRouteView,
                            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 16.dp, top = 86.dp),
                        ) {
                            Icon(Icons.Rounded.Close, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.map_exit_route))
                        }
                    }
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

                    // Floating MyLocation button above the bottom sheet
                    FilledTonalIconButton(
                        onClick = onLocate,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 24.dp)
                            .size(52.dp)
                            .graphicsLayer {
                                val visibleSheet = runCatching { layoutHeightPx - sheet.requireOffset() }.getOrDefault(0f)
                                translationY = -visibleSheet.coerceIn(0f, peekHeightPx.toFloat())
                            },
                        shape = CircleShape,
                    ) {
                        Icon(
                            Icons.Rounded.MyLocation,
                            contentDescription = stringResource(R.string.ui_nearest_stop_action),
                        )
                    }

                    if (isMinimized) FilledTonalButton(
                        onClick = { scope.launch { sheet.partialExpand() } },
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 24.dp),
                        shape = RoundedCornerShape(24.dp),
                    ) {
                        Icon(Icons.Rounded.DirectionsBus, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ui_show_departures))
                    }

                    state.selectedPlace?.let { place ->
                        Surface(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 84.dp, bottom = if (isMinimized) 96.dp else peekHeight + 12.dp),
                            shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, shadowElevation = 3.dp) {
                            Row(Modifier.padding(start = 18.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (place.address.isNotBlank()) Text(place.address, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = onPlaceClosed) { Icon(Icons.Rounded.Close, stringResource(R.string.search_close_place)) }
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
                    onSearchPlaces = onSearchPlaces,
                    onPlace = { place ->
                        onPlaceSelected(place)
                        searchOpen = false
                        scope.launch { sheetState.bottomSheetState.partialExpand() }
                    },
                    onVehicle = { onVehicleOpened(it); searchOpen = false },
                )
            }
        }
    }
    if (showUpdates) {
        UpdatesPage(
            state = state,
            onChannelChanged = onOtaChannelChanged,
            onCheckUpdates = onCheckOtaUpdates,
            onDownloadAndInstall = onDownloadAndInstallOta,
            onBack = { showUpdates = false }
        )
    } else if (showAbout) {
        AboutPage(
            onOpenUpdates = { showUpdates = true },
            onBack = { showAbout = false }
        )
    } else if (showSettings) {
        SettingsPage(
            state = state,
            onAccent = onAccentColorChanged,
            onCustom = onCustomAccentChanged,
            onNationwide = onNationwideNetworkChanged,
            onAbout = { showAbout = true },
            onBack = { showSettings = false }
        )
    }
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
    onToggleExpand: () -> Unit,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    isExpanded: Boolean = true,
) {
    val latestDepartureAction by rememberUpdatedState(onDeparture)
    val openDeparture = remember { { departure: Departure -> latestDepartureAction(departure) } }
    val routeById = remember(state.network?.routes, state.emmaRoutes) { state.routesById }
    val stopTitle = stop?.name ?: stringResource(if (state.loading) R.string.ui_loading_stops else R.string.ui_select_stop)
    val clusterProviders = stop?.providerIds.orEmpty()
    val boardingInfo = stop?.platformCode?.let { stringResource(R.string.emma_platform, it) }
        ?: stop?.takeIf { it.id.startsWith("emma:") }?.let { stringResource(R.string.emma_stop_code, it.id.substringAfterLast(":")) }
    val timetableOnly = clusterProviders.isNotEmpty() && clusterProviders.all { it == "homm" }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .clickable(enabled = !isExpanded, onClick = onToggleExpand)
            ) {
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
                Text(stopTitle, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
                if (stop != null) {
                    Text(listOfNotNull(providerNames(clusterProviders), transportLabel(stop?.transportMode), boardingInfo).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
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
            IconButton(onClick = onCollapse) {
                Icon(Icons.Rounded.KeyboardArrowDown,
                    stringResource(if (isExpanded) R.string.ui_collapse_departures else R.string.ui_hide_departures))
            }
        }
        state.boardingSelection?.let { selection ->
            Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        routeById[selection.routeId]?.let { LineBadge(it) }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onClearBoarding) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back)) }
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
            item(contentType = "label") { Text(stringResource(R.string.ui_departures), Modifier.padding(end = 5.dp), style = MaterialTheme.typography.labelLarge) }
            items(hu.viabusz.app.data.DepartureTime.windows, key = { it }, contentType = { "chip" }) { minutes ->
                FilterChip(
                    selected = state.windowMinutes == minutes,
                    onClick = { onWindowChanged(minutes) },
                    label = { Text(compactDuration(minutes)) },
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
                item(contentType = "notice") { Notice(state.error, Icons.Rounded.WifiOff, isError = true) }
            }
            if (!state.emmaStatus.isNullOrBlank()) {
                item(contentType = "notice") { Notice(state.emmaStatus, Icons.Rounded.DirectionsBus) }
            }
            if (!state.stopStatus.isNullOrBlank()) item(contentType = "notice") { Notice(state.stopStatus, Icons.Rounded.LocationOn) }
            if (!state.departureStatus.isNullOrBlank()) item(contentType = "notice") { Notice(state.departureStatus, Icons.Rounded.AccessTime) }
            if (stop == null && !state.loading) {
                item(contentType = "empty") {
                    EmptyState(stringResource(R.string.ui_pick_stop_title), stringResource(R.string.ui_pick_stop_body), Icons.Rounded.LocationOn)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Button(onClick = onSearch) { Text(stringResource(R.string.ui_search_stop)) }
                    }
                }
            }
            if (state.liveStopMinutes.isNotEmpty()) {
                item(contentType = "live_signal") { LiveStopSignal(state.liveStopMinutes) }
            }
            if (stop != null && state.departures.isEmpty() && !state.loading && !state.departureBoardLoading && !state.departureBoardUnavailable) {
                item(contentType = "empty") { EmptyState(stringResource(R.string.ui_no_departures_title), stringResource(R.string.ui_no_departures_body, state.windowMinutes), Icons.Rounded.AccessTime) }
            }
            itemsIndexed(
                state.departures,
                key = { _, departure -> "${departure.routeId}-${departure.directionId}-${departure.expectedAt.epochSecond}" },
                contentType = { _, _ -> "departure" }
            ) { index, departure ->
                val minutes = ceil(Duration.between(state.now, departure.expectedAt).seconds / 60.0).toInt().coerceAtLeast(0)
                DepartureRow(
                    departure = departure,
                    minutes = minutes,
                    providerId = routeById[departure.routeId]?.providerId ?: "viabusz",
                    route = routeById[departure.routeId],
                    boardingInfo = departure.platformCode?.let { stringResource(if (routeById[departure.routeId]?.providerId == "mav") R.string.rail_platform else R.string.emma_platform, it) } ?: boardingInfo,
                    onClick = { openDeparture(departure) },
                )
                if (index < state.departures.lastIndex) HorizontalDivider(Modifier.padding(horizontal = 22.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            if (!state.status.isNullOrBlank()) {
                item(contentType = "notice") { Notice(state.status, Icons.Rounded.Info) }
            }
            if (!state.locationMessage.isNullOrBlank()) {
                item(contentType = "notice") { Notice(state.locationMessage, Icons.Rounded.MyLocation) }
            }
            item(contentType = "footer") {
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
                Text(minutes.map { countdown(it) }.joinToString(" / "), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Text(stringResource(R.string.ui_live_stop_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun DepartureRow(departure: Departure, minutes: Int, providerId: String, route: TransitRoute?, boardingInfo: String?, onClick: () -> Unit) {
    val live = departure.kind == ArrivalKind.LIVE
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 22.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ServiceBadge(departure.routeNumber, providerId, route?.transportMode, route?.color, departure.trainNumber)
            Text(providerName(providerId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(departure.destination, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            departure.serviceName?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            boardingInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(if (live) Icons.Rounded.Sensors else Icons.Rounded.AccessTime, null, Modifier.size(13.dp), tint = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(if (live) R.string.ui_live_arrival else R.string.ui_scheduled), style = MaterialTheme.typography.labelMedium, color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(countdown(minutes), style = MaterialTheme.typography.titleLarge, color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(clockFormat.format(departure.expectedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onSearchPlaces: (String, Boolean) -> Unit,
    onPlace: (hu.viabusz.app.data.PlaceResult) -> Unit,
    onVehicle: (String) -> Unit,
) {
    var category by rememberSaveable { mutableStateOf(SearchCategory.ALL) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (route == null && category != SearchCategory.ALL) category = SearchCategory.ALL else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back)) }
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
                route == null -> CategorizedSearch(state, query, category, { category = it }, onRoute, onStop, onVehicle, onPlace, onSearchPlaces)
                route.providerId in setOf("volan", "bkk", "hev", "mav") -> EmmaLineBrowser(state, route, patternId, onDirection, onBoard) { onRetryLine(route.id) }
                direction == null -> DirectionList(route, onDirection)
                else -> RouteStops(state, route, direction) { onBoard(route.id, direction.id, it) }
            }
        }
    }
}

@Composable
internal fun transportLabel(mode: String?): String? = when (mode) {
    "TRAM" -> stringResource(R.string.emma_mode_tram)
    "TROLLEYBUS" -> stringResource(R.string.emma_mode_trolley)
    "SUBWAY" -> stringResource(R.string.emma_mode_metro)
    "BUS", "COACH" -> stringResource(R.string.emma_mode_bus)
    "SUBURBAN_RAILWAY" -> stringResource(R.string.emma_mode_hev)
    "RAIL", "TRAMTRAIN" -> stringResource(R.string.emma_mode_train)
    else -> null
}

@Composable
private fun stopLabel(stop: TransitStop): String? = stop.platformCode?.let { stringResource(R.string.emma_platform, it) }
    ?: stop.id.takeIf { it.startsWith("emma:") }?.let { stringResource(R.string.emma_stop_code, it.substringAfterLast(":")) }

@Composable
internal fun StopSearchRow(stop: TransitStop, state: UiState, favorite: Boolean, onStop: (String) -> Unit) {
    val displayName = stop.name
    Row(
        Modifier.fillMaxWidth().clickable { onStop(stop.id) }.padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(if (favorite) Icons.Rounded.Favorite else Icons.Rounded.LocationOn, null, Modifier.padding(11.dp).size(21.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(displayName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val numbers = stop.routeIds.map { id -> state.findRoute(id)?.number ?: id }.distinct()
            val boarding = stopLabel(stop)
            Text(listOfNotNull(providerNames(stop.providerIds), transportLabel(stop.transportMode), boarding, numbers.joinToString(" · ").takeIf { it.isNotBlank() }).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DirectionList(route: TransitRoute, onDirection: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(contentType = "header") {
            Text(route.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(4.dp))
            Text(stringResource(R.string.ui_direction_question), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 7.dp, bottom = 12.dp))
        }
        items(route.directions, key = { it.id }, contentType = { "direction" }) { direction ->
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
        if (route.directions.isEmpty()) item(contentType = "empty") { EmptyState(stringResource(R.string.ui_no_directions_title), stringResource(R.string.ui_no_directions_body), Icons.Rounded.Info) }
    }
}

@Composable
private fun RouteStops(state: UiState, route: TransitRoute, direction: RouteDirection, onStop: (String) -> Unit) {
    val stopById = remember(state.network) { state.stopsById }
    LazyColumn(contentPadding = PaddingValues(bottom = 36.dp)) {
        item(contentType = "header") {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(stringResource(R.string.ui_route_direction, route.number), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(direction.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(stringResource(R.string.ui_stop_departures_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (direction.restrictedService) Text(stringResource(R.string.ui_registration_required), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
        itemsIndexed(direction.stopIds, key = { index, stopId -> "$index-$stopId" }, contentType = { _, _ -> "route_stop" }) { index, stopId ->
            stopById[stopId]?.let { stop -> LineStopRow(stop, index == 0, index == direction.stopIds.lastIndex,
                lineColor(route), onClick = { onStop(stopId) }) }
        }
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
                Text(stringResource(R.string.map_provider_privacy))
                Text(stringResource(R.string.map_style_credit), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { runCatching { uriHandler.openUri("https://openfreemap.org/") } }) {
                    Text(stringResource(R.string.map_provider_source))
                }
                Text(stringResource(R.string.search_places_privacy))
                TextButton(onClick = { runCatching { uriHandler.openUri("https://github.com/komoot/photon") } }) {
                    Text(stringResource(R.string.search_places_source))
                }
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
internal fun providerName(providerId: String): String = stringResource(
    when (providerId) {
        "mav" -> R.string.rail_provider
        "hev" -> R.string.emma_provider_hev
        "kanyargo" -> R.string.ui_provider_kanyargo
        "homm" -> R.string.ui_provider_homm
        "volan" -> R.string.volan_provider
        "bkk" -> R.string.emma_bkk_provider
        else -> R.string.ui_provider_viabusz
    },
)

@Composable
private fun providerNames(providerIds: List<String>): String = providerIds.distinct().map { providerName(it) }.joinToString(" · ")
