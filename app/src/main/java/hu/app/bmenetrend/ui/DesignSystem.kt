package hu.app.bmenetrend.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.*
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.center
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hu.app.bmenetrend.R

/** Timetable spacing: compact information inside generous, consistent interaction bounds. */
@Composable
internal fun transitWindowWidth()=with(androidx.compose.ui.platform.LocalDensity.current) {
    androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.width.toDp()
}

@Composable
internal fun adaptivePageGutter()=maxOf(20.dp,(transitWindowWidth()-TransitDesign.contentWidth)/2)

internal object TransitDesign {
    val gutter = 20.dp
    val compact = 8.dp
    val section = 24.dp
    val contentWidth = 760.dp
    val controlShape = RoundedCornerShape(12.dp)
    val panelShape = RoundedCornerShape(20.dp)
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val groupShape = RoundedCornerShape(16.dp)
}

/** Grouped ("boxed") lists: the app's 20dp corners at a group's ends, tight corners and a hairline gap inside it. */
internal object Segments {
    val outer = 20.dp
    val inner = 6.dp
    val gap = 3.dp
}

/**
 * One item of a grouped list. While pressed, its tight inner corners spring open so the touched item
 * briefly lifts out of its group (the expressive press response, without shadows).
 */
@Composable
internal fun SegmentedItem(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable () -> Unit,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.Dp>()
    val top by androidx.compose.animation.core.animateDpAsState(if (index <= 0 || pressed) Segments.outer else Segments.inner, spec, label = "segment top")
    val bottom by androidx.compose.animation.core.animateDpAsState(if (index >= count - 1 || pressed) Segments.outer else Segments.inner, spec, label = "segment bottom")
    // A little give under the finger, on the expressive spatial spring (slight overshoot on release).
    val scale by androidx.compose.animation.core.animateFloatAsState(if (pressed) 0.985f else 1f,
        MaterialTheme.motionScheme.fastSpatialSpec(), label = "segment press")
    val shape = RoundedCornerShape(top, top, bottom, bottom)
    val spaced = modifier.fillMaxWidth().padding(bottom = if (index < count - 1) Segments.gap else 0.dp)
        .graphicsLayer { scaleX = scale; scaleY = scale }
    if (onClick == null) Surface(spaced, shape = shape, color = color, contentColor = contentColor, content = content)
    else Surface(onClick = onClick, modifier = spaced, enabled = enabled, shape = shape, color = color,
        contentColor = contentColor, interactionSource = interaction, content = content)
}

/**
 * A single-choice connected button group (Material 3 Expressive): every option visible and one tap
 * away; the chosen one morphs to a pill. Used for the departure window and the search categories.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> ConnectedSelector(options: List<T>, selected: T, onSelect: (T) -> Unit, label: String,
                                   modifier: Modifier = Modifier, text: @Composable (T) -> String) {
    Row(modifier.fillMaxWidth().semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, option ->
            val chosen = selected == option
            ToggleButton(
                checked = chosen,
                onCheckedChange = { onSelect(option) },
                // Single choice: announce it as a selected radio option, not a checkbox.
                modifier = Modifier.weight(1f).semantics {
                    role = androidx.compose.ui.semantics.Role.RadioButton
                    this.selected = chosen
                },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 2.dp),
            ) { Text(text(option), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLargeEmphasized) }
        }
    }
}

/** A cookie that springs into a circle while pressed; between the two it is a [Morph] of both outlines. */
private class MorphShape(private val morph: androidx.graphics.shapes.Morph, private val progress: Float) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                               density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.Outline {
        val path = morph.toPath(progress)
        path.transform(androidx.compose.ui.graphics.Matrix().apply { scale(size.width, size.height) })
        path.translate(size.center - path.getBounds().center)
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}

/**
 * The app's icon-only button: a cookie (Material 3 Expressive shape) that morphs into a circle under
 * the finger and springs back on release. The touch target stays a full [size] square.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CookieIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    shadowElevation: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val progress by androidx.compose.animation.core.animateFloatAsState(if (pressed) 1f else 0f,
        MaterialTheme.motionScheme.fastSpatialSpec(), label = "cookie morph")
    val morph = remember { androidx.graphics.shapes.Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Circle) }
    val shape = remember(progress) { MorphShape(morph, progress) }
    Surface(onClick = onClick, modifier = modifier.size(size), enabled = enabled, shape = shape,
        color = if (enabled) containerColor else containerColor.copy(alpha = .38f),
        contentColor = if (enabled) contentColor else contentColor.copy(alpha = .38f),
        shadowElevation = shadowElevation, interactionSource = interaction) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** Short waits (under ~5 s): the Material 3 Expressive loading indicator, labelled for screen readers. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TransitLoading(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 48.dp, contained: Boolean = false,
                            color: Color = if (contained) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary) {
    val label = stringResource(R.string.ui_loading_data)
    val labelled = modifier.size(size).semantics { contentDescription = label }
    if (contained) ContainedLoadingIndicator(labelled, indicatorColor = color) else LoadingIndicator(labelled, color = color)
}

/** Activity along the top edge of a list that already shows content, e.g. refreshing in place. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TransitRefreshBar(visible: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(10.dp), contentAlignment = Alignment.Center) {
        if (visible) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** Downloads and other long work with known progress. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun TransitProgressBar(progress: () -> Float, modifier: Modifier = Modifier) {
    LinearWavyProgressIndicator(progress = progress, modifier = modifier.fillMaxWidth())
}

/**
 * Empty and welcome states use a Material shape (a soft cookie / star) as their illustration, with a
 * big centred icon: one expressive moment instead of artwork.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ShapeIconTile(icon: ImageVector, modifier: Modifier = Modifier, shape: androidx.graphics.shapes.RoundedPolygon = MaterialShapes.Cookie9Sided,
                           size: androidx.compose.ui.unit.Dp = 96.dp, color: Color = MaterialTheme.colorScheme.primaryContainer,
                           contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer) {
    val outline = shape.toShape()
    Box(modifier.size(size).clip(outline).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * .42f), tint = contentColor)
    }
}

@Composable
internal fun TransitIconTile(icon: ImageVector, modifier: Modifier = Modifier, prominent: Boolean = false) {
    Surface(modifier.size(44.dp), shape = RoundedCornerShape(12.dp),
        color = if(prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if(prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer) {
        Box(contentAlignment=Alignment.Center) { Icon(icon,null,Modifier.size(23.dp)) }
    }
}

@Composable
internal fun TransitPageHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,top=8.dp,bottom=16.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        IconButton(onClick=onBack,modifier=Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack,stringResource(R.string.ui_back))
        }
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
        actions()
    }
}

@Composable
internal fun TransitPageIntro(title: String, subtitle: String?, actions: @Composable RowScope.() -> Unit = {}) {
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Text(title,Modifier.weight(1f),style=MaterialTheme.typography.headlineMedium)
            actions()
        }
        if(subtitle!=null) Text(subtitle,style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun TransitSectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title,modifier.padding(top=8.dp,bottom=4.dp),style=MaterialTheme.typography.titleMedium,
        color=MaterialTheme.colorScheme.onSurface,fontWeight=FontWeight.Bold)
}

/**
 * Vibrant orange platform / quay / track badge (e.g. "A1", "A4", "E4", "B1", "10", "1")
 * styled after the standard BudapestGO / MÁV / Volán platform indicators.
 */
@Composable
internal fun PlatformBadge(
    platformCode: String?,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color(0xFFE67008),
    contentColor: Color = Color.White,
) {
    val clean = platformCode?.trim().orEmpty()
    if (clean.isBlank()) return
    val isPill = clean.length > 2
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = backgroundColor,
        contentColor = contentColor,
        modifier = modifier
            .heightIn(min = 22.dp)
            .widthIn(min = 22.dp),
        shadowElevation = 0.5.dp,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = if (isPill) 6.dp else 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = clean,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = OpenSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.5.sp,
                    lineHeight = 12.sp,
                    letterSpacing = 0.sp
                ),
                color = contentColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** The time is the scanning anchor. Live status has an icon as well as colour. */
@Composable
internal fun DepartureCount(minutes: Int, time: String, live: Boolean, modifier: Modifier = Modifier, cancelled: Boolean=false, delay: Long=0, scheduledTime: String?=null) {
    Surface(modifier.widthIn(min=58.dp),shape=RoundedCornerShape(8.dp),
        color=when { cancelled || delay>0 -> MaterialTheme.colorScheme.errorContainer;live -> MaterialTheme.colorScheme.primaryContainer;else -> MaterialTheme.colorScheme.surfaceContainerHigh },
        contentColor=when { cancelled || delay>0 -> MaterialTheme.colorScheme.onErrorContainer;live -> MaterialTheme.colorScheme.onPrimaryContainer;else -> MaterialTheme.colorScheme.onSurface }) {
        Column(Modifier.padding(horizontal=8.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(2.dp)) {
            if(cancelled) {
                Icon(Icons.Rounded.Cancel,null,Modifier.size(20.dp))
                Text(stringResource(R.string.board_cancelled),style=MaterialTheme.typography.labelLarge)
            } else {
                val slide = MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
                val fadeIn = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
                val fadeOut = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
                androidx.compose.animation.AnimatedContent(minutes, label = "countdown",
                transitionSpec = {
                    // The new minute rolls in from below on a spring; the old one fades up and out.
                    (androidx.compose.animation.slideInVertically(slide) { it / 2 } + androidx.compose.animation.fadeIn(fadeIn)) togetherWith
                        (androidx.compose.animation.slideOutVertically(slide) { -it / 2 } + androidx.compose.animation.fadeOut(fadeOut))
                }) { value -> Text(countdown(value),style=MaterialTheme.typography.titleLarge,maxLines=1) } }
            if(!cancelled && delay>0) Text(androidx.compose.ui.res.pluralStringResource(R.plurals.board_delay,delay.toInt(),delay),style=MaterialTheme.typography.labelMedium)
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                if(live && !cancelled) Icon(Icons.Rounded.Sensors,stringResource(R.string.ui_live_arrival),Modifier.size(12.dp))
                Text(if (live && scheduledTime != null && !cancelled) stringResource(R.string.board_estimated_time,time) else time,
                    style=MaterialTheme.typography.labelLarge,textDecoration=if(cancelled) TextDecoration.LineThrough else null)
            }
            if (!cancelled && scheduledTime != null) {
                Text(stringResource(R.string.board_original_time,scheduledTime),style=MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** A departure scans as badge, destination and time. Service metadata lives in its opened detail. */
@Composable
internal fun DepartureBoardRow(
    departure: hu.app.bmenetrend.data.Departure,provider: String,mode: String?,sourceColor: String?,minutes: Int,
    boardingInfo: String?,onClick: ()->Unit,modifier: Modifier=Modifier,
    platformCode: String? = departure.platformCode,
    /** False when a [SegmentedItem] around the row already handles the click. */
    clickable: Boolean = true,
) {
    val live=departure.kind==hu.app.bmenetrend.data.ArrivalKind.LIVE
    val large=androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f
    val shown=if(departure.isCancelled) departure.scheduledAt ?: departure.expectedAt else departure.expectedAt
    val original=departure.scheduledAt?.takeIf { live && it!=departure.expectedAt }
    val accessibility=listOfNotNull(
        stringResource(if(live) R.string.ui_live_arrival else R.string.ui_scheduled),
        if(original!=null) stringResource(R.string.board_estimated_time,rememberBoardClock.format(shown)) else null,
        original?.let { stringResource(R.string.board_original_time,rememberBoardClock.format(it)) },
        boardingInfo, platformCode).joinToString(" · ")
    val badge: @Composable ()->Unit = { LineBadge(departure.routeNumber,provider,mode,sourceColor,showIcon=true) }
    val time: @Composable ()->Unit = {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            if(live && !departure.isCancelled) Icon(Icons.Rounded.Sensors,null,Modifier.size(13.dp),tint=MaterialTheme.colorScheme.primary)
            Text(if(departure.isCancelled) stringResource(R.string.board_cancelled)
                else if(minutes<60) "$minutes′" else rememberBoardClock.format(shown),
                style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,fontFamily=OpenSans,maxLines=1,
                color=if(departure.isCancelled || departure.delayMinutes>0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
    val cleanDest = remember(departure.destination, departure.routeNumber) {
        DestinationFormatter.clean(departure.destination, departure.routeNumber).ifBlank { departure.destination }
    }
    val destination: @Composable (Modifier)->Unit = { textModifier ->
        Row(
            textModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_direction_triangle),
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
            Text(
                text = cleanDest.ifBlank { stringResource(R.string.emma_vehicle_no_direction) },
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = OpenSans,
                ),
                maxLines = if (large) 3 else 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
    Column(modifier.fillMaxWidth().semantics { stateDescription=accessibility }
        .then(if(clickable) Modifier.clickable(enabled=!departure.isCancelled,role=androidx.compose.ui.semantics.Role.Button,onClick=onClick) else Modifier)
        .heightIn(min=64.dp).padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp,Alignment.CenterVertically)) {
        if(large) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { badge();Spacer(Modifier.weight(1f));time() }
            destination(Modifier.fillMaxWidth())
        } else Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            badge();destination(Modifier.weight(1f));time()
        }
    }
}

/** Metadata for the exact departure, shown only after choosing its compact row. */
@Composable
internal fun DepartureDetails(departure: hu.app.bmenetrend.data.Departure) {
    val live=departure.kind==hu.app.bmenetrend.data.ArrivalKind.LIVE
    val cleanDest = remember(departure.destination, departure.routeNumber) {
        DestinationFormatter.clean(departure.destination, departure.routeNumber).ifBlank { departure.destination }
    }
    Column(Modifier.fillMaxWidth().heightIn(max=240.dp).verticalScroll(rememberScrollState())
        .padding(horizontal=20.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(cleanDest, style=MaterialTheme.typography.titleMedium)
        Text(if(live) stringResource(R.string.board_estimated_time,rememberBoardClock.format(departure.expectedAt))
            else rememberBoardClock.format(departure.expectedAt),style=MaterialTheme.typography.bodyLarge)
        departure.scheduledAt?.takeIf { live && it!=departure.expectedAt }?.let {
            Text(stringResource(R.string.board_original_time,rememberBoardClock.format(it)),style=MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(if(live) R.string.ui_live_arrival else R.string.ui_scheduled),
            style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        listOfNotNull(departure.serviceName,departure.trainNumber).distinct().forEach {
            Text(it,style=MaterialTheme.typography.bodyMedium)
        }
        departure.platformCode?.takeIf(String::isNotBlank)?.let { PlatformBadge(it) }
        if(departure.wheelchairAccessible=="POSSIBLE") Text(stringResource(R.string.line_accessible),style=MaterialTheme.typography.bodyMedium)
        if(departure.bikesAllowed=="ALLOWED") Text(stringResource(R.string.line_bikes),style=MaterialTheme.typography.bodyMedium)
        if(departure.restrictedService) Text(stringResource(R.string.ui_registration_required),style=MaterialTheme.typography.bodyMedium)
    }
}

internal val rememberBoardClock=java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.of("Europe/Budapest"))

/**
 * The departure-window picker as a connected button group (Material 3 Expressive): every option is
 * visible and one tap away, and the chosen one morphs to a pill.
 */
@Composable
internal fun DepartureWindowSelector(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) =
    ConnectedSelector(hu.app.bmenetrend.data.DepartureTime.windows, selected, onSelect, stringResource(R.string.ui_departures), modifier) {
        compactDuration(it)
    }

/**
 * The stop board's one hero: the very next departure, large. Its colour carries the state (live,
 * scheduled, late, cancelled) and the countdown is the biggest figure in the sheet.
 */
@Composable
internal fun NextDepartureHero(
    departure: hu.app.bmenetrend.data.Departure, provider: String, mode: String?, sourceColor: String?, minutes: Int,
    onClick: () -> Unit, modifier: Modifier = Modifier, platformCode: String? = departure.platformCode,
) {
    val live = departure.kind == hu.app.bmenetrend.data.ArrivalKind.LIVE
    val scheme = MaterialTheme.colorScheme
    // Red only for cancellations; a late vehicle still runs, so it gets the calmer tertiary role plus its delay.
    val container = when { departure.isCancelled -> scheme.errorContainer; departure.delayMinutes > 0 -> scheme.tertiaryContainer
        live -> scheme.primaryContainer; else -> scheme.secondaryContainer }
    val content = when { departure.isCancelled -> scheme.onErrorContainer; departure.delayMinutes > 0 -> scheme.onTertiaryContainer
        live -> scheme.onPrimaryContainer; else -> scheme.onSecondaryContainer }
    val shown = if (departure.isCancelled) departure.scheduledAt ?: departure.expectedAt else departure.expectedAt
    val destination = remember(departure.destination, departure.routeNumber) {
        DestinationFormatter.clean(departure.destination, departure.routeNumber).ifBlank { departure.destination }
    }
    val status = listOf(stringResource(if (live) R.string.ui_live_arrival else R.string.ui_scheduled), rememberBoardClock.format(shown))
        .joinToString(" · ")
    Surface(onClick = onClick, enabled = !departure.isCancelled, modifier = modifier.fillMaxWidth(),
        shape = TransitDesign.panelShape, color = container, contentColor = content) {
        Row(Modifier.padding(start = 18.dp, end = 16.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LineBadge(departure.routeNumber, provider, mode, sourceColor, showIcon = true)
                    platformCode?.takeIf(String::isNotBlank)?.let { PlatformBadge(it) }
                }
                Text(destination.ifBlank { stringResource(R.string.emma_vehicle_no_direction) },
                    style = MaterialTheme.typography.titleLarge, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (live && !departure.isCancelled) Icon(Icons.Rounded.Sensors, null, Modifier.size(15.dp))
                    Text(status, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    departure.isCancelled -> { Icon(Icons.Rounded.Cancel, null, Modifier.size(32.dp))
                        Text(stringResource(R.string.board_cancelled), style = MaterialTheme.typography.labelLargeEmphasized) }
                    minutes <= 0 -> Text(stringResource(R.string.ui_now), style = MaterialTheme.typography.headlineMedium)
                    minutes < 60 -> {
                        Text("$minutes", style = MaterialTheme.typography.displayMedium)
                        Text(stringResource(R.string.ui_minutes_unit), style = MaterialTheme.typography.labelLargeEmphasized)
                    }
                    else -> Text(rememberBoardClock.format(shown), style = MaterialTheme.typography.headlineMedium)
                }
                if (!departure.isCancelled && departure.delayMinutes > 0) Text(
                    androidx.compose.ui.res.pluralStringResource(R.plurals.board_delay, departure.delayMinutes.toInt(), departure.delayMinutes),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
