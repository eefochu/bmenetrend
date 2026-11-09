@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package hu.app.bmenetrend.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Train
import androidx.compose.material.icons.rounded.Subway
import androidx.compose.material.icons.rounded.Tram
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsRailway
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R

/** Same keyboard, clear, back and focus behavior in map search and the trip planner. */
@Composable
internal fun TransitSearchInput(query: String, onQuery: (String) -> Unit, onBack: () -> Unit, label: String = stringResource(R.string.ui_search_short)) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    // One field with the back arrow inside it (Material search view), instead of a detached button beside it.
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(query, onQuery, Modifier.weight(1f).focusRequester(focus), singleLine = true,
            leadingIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back)) } },
            label = { Text(label) }, placeholder = { Text(stringResource(R.string.search_input_hint)) },
            shape = TransitDesign.panelShape,
            colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                Icon(Icons.Rounded.Close, stringResource(R.string.ui_clear_search))
            } else Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }))
    }
}

@Composable
internal fun SearchRecommendation(name: String, detail: String, onClick: () -> Unit,
    leading: @Composable () -> Unit = { Icon(Icons.Rounded.LocationOn, null, Modifier.size(28.dp)) },
    supporting: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.search_recommended), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary)
        Surface(onClick = onClick, shape = TransitDesign.panelShape, color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                leading()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    supporting()
                }
            }
        }
    }
}

@Composable
internal fun SearchGroupTitle(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 22.dp, end = 10.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
        action?.invoke()
    }
}

@Composable
internal fun SearchResultCard(
    name: String, detail: String, onClick: () -> Unit,
    leading: @Composable () -> Unit,
    badge: @Composable () -> Unit = {},
    supporting: @Composable () -> Unit = {},
    // The whole card is the tap target, so there is no trailing arrow by default.
    trailing: @Composable () -> Unit = {},
    /** Position within its result group; a lone card (0 of 1) is fully rounded. */
    index: Int = 0,
    count: Int = 1,
) {
    SegmentedItem(index, count, Modifier.padding(horizontal = 16.dp), onClick = onClick) {
        // Top-aligned: the leading badge and trailing meta sit level with the name they belong to.
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            leading()
            Column(Modifier.weight(1f).padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    badge()
                }
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                supporting()
            }
            trailing()
        }
    }
}

/** Shared, locale-aware distance formatting for map and planner search. */
internal fun formatStopDistance(meters: Double): String =
    if (meters < 1000) "${meters.toInt()} m"
    else String.format(java.util.Locale.getDefault(), "%.1f km", meters / 1000)

/**
 * Line badges on a single line: as many as fit, then "+N". Cards keep one predictable height instead
 * of wrapping into a wall of badges.
 */
@Composable
internal fun OneLineBadges(routes: List<hu.app.bmenetrend.data.TransitRoute>, modifier: Modifier = Modifier) {
    if (routes.isEmpty()) return
    val moreLabel: @Composable (Int) -> Unit = { hidden ->
        Text(stringResource(R.string.search_additional_lines, hidden), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
    androidx.compose.ui.layout.SubcomposeLayout(modifier) { constraints ->
        val gap = 6.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val badges = subcompose("badges") { routes.forEach { LineBadge(it, showIcon = false, uniform = UniformBadge) } }.map { it.measure(loose) }
        val fullWidth = badges.sumOf { it.width } + gap * (badges.size - 1).coerceAtLeast(0)
        var shown = badges.size
        var more: androidx.compose.ui.layout.Placeable? = null
        if (fullWidth > constraints.maxWidth) {
            // Reserve room for the widest "+N" label, then fit as many badges as possible before it.
            val reserve = subcompose("more-reserve") { moreLabel(badges.size) }.first().measure(loose).width + gap
            var used = 0
            shown = 0
            for (badge in badges) {
                val next = used + (if (shown > 0) gap else 0) + badge.width
                if (next + reserve > constraints.maxWidth) break
                used = next; shown++
            }
            shown = shown.coerceAtLeast(1)
            more = subcompose("more") { moreLabel(badges.size - shown) }.first().measure(loose)
        }
        val visible = badges.take(shown)
        val height = (visible.map { it.height } + listOfNotNull(more?.height)).maxOrNull() ?: 0
        layout(constraints.maxWidth.coerceAtMost(fullWidth.coerceAtLeast(0)).let { if (more != null) constraints.maxWidth else it }, height) {
            var x = 0
            visible.forEach { p -> p.place(x, (height - p.height) / 2); x += p.width + gap }
            more?.let { it.place(x, (height - it.height) / 2) }
        }
    }
}

/** A walking-distance pill, shared by search results and the nearby boxes. */
@Composable
internal fun DistancePill(text: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(Modifier.padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(Icons.AutoMirrored.Rounded.DirectionsWalk, null, Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMediumEmphasized, maxLines = 1)
        }
    }
}

/** The stop's main transport as a cookie badge, matching the app's other shaped badges. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StopModeBadge(stop: hu.app.bmenetrend.data.TransitStop, routes: List<hu.app.bmenetrend.data.TransitRoute>,
                           size: androidx.compose.ui.unit.Dp = 44.dp,
                           color: Color = MaterialTheme.colorScheme.primaryContainer,
                           contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    val modes = remember(stop.transportMode, routes) { (routes.mapNotNull { it.transportMode } + listOfNotNull(stop.transportMode)).toSet() }
    val rail = "mav" in stop.providerIds || "gysev" in stop.providerIds
    ShapeIconTile(stopModeIcon(modes, rail), size = size, shape = MaterialShapes.Cookie9Sided, color = color, contentColor = contentColor)
}

/** The most significant mode at a stop: rail, then metro, HÉV, tram, otherwise bus. */
internal fun stopModeIcon(modes: Set<String>, rail: Boolean): androidx.compose.ui.graphics.vector.ImageVector = when {
    rail || "RAIL" in modes || "TRAMTRAIN" in modes -> Icons.Rounded.Train
    "SUBWAY" in modes -> Icons.Rounded.Subway
    "SUBURBAN_RAILWAY" in modes -> Icons.Rounded.DirectionsRailway
    "TRAM" in modes -> Icons.Rounded.Tram
    else -> Icons.Rounded.DirectionsBus
}
