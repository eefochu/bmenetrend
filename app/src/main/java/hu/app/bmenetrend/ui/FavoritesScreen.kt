package hu.app.bmenetrend.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.*
import kotlinx.coroutines.flow.distinctUntilChanged

data class StationLines(
    val trainTypes: List<String>,
    val trainRoutes: List<TransitRoute>,
    val otherRoutes: List<TransitRoute>,
)

private val TRAIN_TYPE_PRIORITY = listOf(
    "Személyvonat",
    "Gyorsított",
    "Zónázó",
    "InterRégió",
    "InterCity",
    "Railjet",
    "EuroCity",
    "EuroNight",
    "Expressz",
    "Gyorsvonat",
    "Tram-Train",
)

internal fun trainTypeLabel(number: String, serviceName: String?): String {
    val cleanService = serviceName?.trim()?.uppercase(java.util.Locale.ROOT).orEmpty()
    val numUpper = number.trim().uppercase(java.util.Locale.ROOT)
    return when {
        cleanService.contains("INTERCITY") || numUpper.startsWith("IC") -> "InterCity"
        cleanService.contains("INTERRÉGIÓ") || cleanService.contains("INTERREGIO") || numUpper.startsWith("IR") -> "InterRégió"
        cleanService.contains("RAILJET") || numUpper.startsWith("RJ") -> "Railjet"
        cleanService.contains("EUROCITY") || numUpper.startsWith("EC") -> "EuroCity"
        cleanService.contains("EURONIGHT") || numUpper.startsWith("EN") -> "EuroNight"
        cleanService.contains("EXPRESSZ") || numUpper.startsWith("EX") -> "Expressz"
        cleanService.contains("TRAM-TRAIN") || cleanService.contains("TRAMTRAIN") || numUpper.startsWith("TT") -> "Tram-Train"
        cleanService.contains("GYORSÍTOTT") || numUpper.startsWith("G") -> "Gyorsított"
        cleanService.contains("ZÓNÁZÓ") || cleanService.contains("ZONAZO") || numUpper.startsWith("Z") -> "Zónázó"
        cleanService.contains("GYORS") -> "Gyorsvonat"
        cleanService.contains("SZEMÉLY") || numUpper.startsWith("S") -> "Személyvonat"
        cleanService.isNotBlank() && cleanService.length in 3..25 && !cleanService.any { it.isDigit() } -> serviceName!!.trim()
        else -> "Személyvonat"
    }
}

internal fun isTrainRoute(route: TransitRoute): Boolean {
    return RailSupport.isTrain(route.transportMode) ||
           route.providerId in setOf("mav", "gysev") ||
           route.transportMode in setOf("RAIL", "SUBURBAN_RAILWAY", "TRAMTRAIN")
}

internal fun extractStationLines(station: NearbyStation, state: UiState): StationLines {
    val allPlatformRouteIds = (station.platforms.flatMap { it.routeIds } + station.stop.routeIds).distinct()
    val departureRouteIds = station.departures.map { it.routeId }.distinct()
    val cluster = StopClusters.clusterFor(station.stop, state.stops)
    val clusterRouteIds = cluster.flatMap { it.routeIds }
    val allRouteIds = (allPlatformRouteIds + departureRouteIds + clusterRouteIds).distinct()

    val resolvedRoutes = allRouteIds.mapNotNull { routeId ->
        state.findRoute(routeId) ?: if (routeId.startsWith("emma:")) {
            val dep = station.departures.firstOrNull { it.routeId == routeId }
            TransitRoute(
                id = routeId,
                number = dep?.serviceName?.let { RailSupport.serviceNumber(it) } ?: routeId.substringAfter(":"),
                name = dep?.serviceName ?: "",
                directions = emptyList(),
                providerId = "mav",
                transportMode = "RAIL"
            )
        } else null
    }.distinctBy { it.id }

    val (trainRoutes, otherRoutes) = resolvedRoutes.partition { isTrainRoute(it) }

    val rawTrainTypes = mutableSetOf<String>()
    for (route in trainRoutes) {
        val dep = station.departures.firstOrNull { it.routeId == route.id }
        rawTrainTypes.add(trainTypeLabel(route.number, dep?.serviceName ?: route.name))
    }
    for (dep in station.departures) {
        val route = state.findRoute(dep.routeId)
        if (route == null || isTrainRoute(route)) {
            rawTrainTypes.add(trainTypeLabel(route?.number ?: dep.routeId, dep.serviceName))
        }
    }

    val sortedTrainTypes = rawTrainTypes.toList().sortedWith(
        Comparator { a, b ->
            val idxA = TRAIN_TYPE_PRIORITY.indexOf(a)
            val idxB = TRAIN_TYPE_PRIORITY.indexOf(b)
            when {
                idxA != -1 && idxB != -1 -> idxA.compareTo(idxB)
                idxA != -1 -> -1
                idxB != -1 -> 1
                else -> a.compareTo(b)
            }
        }
    )

    val sortedTrainRoutes = trainRoutes.sortedWith(
        Comparator { r1, r2 ->
            val type1 = trainTypeLabel(r1.number, r1.name)
            val type2 = trainTypeLabel(r2.number, r2.name)
            val p1 = TRAIN_TYPE_PRIORITY.indexOf(type1).let { if (it == -1) 999 else it }
            val p2 = TRAIN_TYPE_PRIORITY.indexOf(type2).let { if (it == -1) 999 else it }
            if (p1 != p2) p1.compareTo(p2) else r1.number.compareTo(r2.number)
        }
    )

    return StationLines(
        trainTypes = sortedTrainTypes,
        trainRoutes = sortedTrainRoutes,
        otherRoutes = otherRoutes.sortedBy { it.number }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FavoritesScreen(
    state: UiState,
    onStop: (String) -> Unit,
    onStopRoute: (String, String) -> Unit = { _, _ -> },
    onRemove: (String) -> Unit,
    onRefresh: () -> Unit,
    onWindow: (Int) -> Unit,
    onVisible: (Set<String>) -> Unit
) {
    val listState = rememberLazyListState()
    val visibleAction by rememberUpdatedState(onVisible)
    var windowMenu by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { (it.key as? String)?.takeIf { key -> key.startsWith("favorite:") }?.removePrefix("favorite:") }.toSet() }
            .distinctUntilChanged().collect { visibleAction(it) }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.favorites_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, stringResource(R.string.ui_refresh_departures)) }
        }
        Box(Modifier.padding(horizontal = 20.dp)) {
            OutlinedButton(onClick = { windowMenu = true }) {
                Icon(Icons.Rounded.Schedule, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.favorites_window, compactDuration(state.windowMinutes)))
                Icon(Icons.Rounded.ExpandMore, null)
            }
            DropdownMenu(windowMenu, onDismissRequest = { windowMenu = false }) {
                DepartureTime.windows.forEach { minutes -> DropdownMenuItem(text = { Text(compactDuration(minutes)) },
                    onClick = { onWindow(minutes); windowMenu = false },
                    trailingIcon = { if (state.windowMinutes == minutes) Icon(Icons.Rounded.Check, null) }) }
            }
        }
        Box(Modifier.fillMaxWidth().height(8.dp).padding(horizontal = 20.dp)) {
            if (state.favoritesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.favoriteStations.isEmpty()) item(key = "empty", contentType = "notice") {
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(Icons.Rounded.FavoriteBorder, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(if (state.favorites.isEmpty()) R.string.favorites_empty else if (state.favoritesLoading) R.string.favorites_loading else R.string.favorites_index_unavailable),
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            items(state.favoriteStations, key = { "favorite:${it.stop.id}" }, contentType = { "station" }) { station ->
                val lines = remember(station.stop.id, station.platforms, station.departures, state.stops, state.routes) {
                    extractStationLines(station, state)
                }
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(bottom = 12.dp)) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { onStop(station.stop.id) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                                Text(station.stop.name, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                            IconButton(onClick = { onRemove(station.stop.id) }) {
                                Icon(Icons.Rounded.Favorite, stringResource(R.string.ui_remove_favorite), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (station.loading && lines.trainRoutes.isEmpty() && lines.otherRoutes.isEmpty()) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
                        }

                        if (lines.trainTypes.isNotEmpty()) {
                            Text(
                                stringResource(R.string.favorites_train_types),
                                Modifier.padding(start = 16.dp, top = 6.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                lines.trainTypes.forEach { type ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                val matchingRoute = lines.trainRoutes.firstOrNull {
                                                    val depService = station.departures.firstOrNull { d -> d.routeId == it.id }?.serviceName
                                                    trainTypeLabel(it.number, depService ?: it.name) == type
                                                }
                                                if (matchingRoute != null) {
                                                    onStopRoute(station.stop.id, matchingRoute.id)
                                                } else {
                                                    val matchingDep = station.departures.firstOrNull {
                                                        trainTypeLabel(state.findRoute(it.routeId)?.number ?: it.routeId, it.serviceName) == type
                                                    }
                                                    if (matchingDep != null) {
                                                        onStopRoute(station.stop.id, matchingDep.routeId)
                                                    } else {
                                                        onStop(station.stop.id)
                                                    }
                                                }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(Icons.Rounded.Train, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                            Text(text = type, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }

                        if (lines.trainRoutes.isNotEmpty()) {
                            Text(
                                stringResource(R.string.favorites_train_lines),
                                Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                lines.trainRoutes.forEach { route ->
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onStopRoute(station.stop.id, route.id) }
                                    ) {
                                        LineBadge(route, showIcon = false)
                                    }
                                }
                            }
                        }

                        if (lines.otherRoutes.isNotEmpty()) {
                            Text(
                                stringResource(R.string.favorites_other_lines),
                                Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                lines.otherRoutes.forEach { route ->
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { onStopRoute(station.stop.id, route.id) }
                                    ) {
                                        LineBadge(route, showIcon = false)
                                    }
                                }
                            }
                        }

                        if (lines.trainRoutes.isEmpty() && lines.otherRoutes.isEmpty() && !station.loading) {
                            Text(
                                stringResource(if (station.unavailable) R.string.nearby_unavailable else R.string.favorites_no_lines),
                                Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (station.unavailable && (lines.trainRoutes.isNotEmpty() || lines.otherRoutes.isNotEmpty())) {
                            Text(
                                stringResource(R.string.nearby_partial),
                                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        TextButton(onClick = { onStop(station.stop.id) }, modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
                            Text(stringResource(R.string.favorites_all_departures))
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Rounded.ChevronRight, null)
                        }
                    }
                }
            }
        }
    }
}
