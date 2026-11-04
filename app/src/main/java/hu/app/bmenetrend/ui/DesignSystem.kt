package hu.app.bmenetrend.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
            } else Text(countdown(minutes),style=MaterialTheme.typography.titleLarge,maxLines=1)
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
        .clickable(enabled=!departure.isCancelled,role=androidx.compose.ui.semantics.Role.Button,onClick=onClick)
        .heightIn(min=64.dp).padding(horizontal=16.dp,vertical=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
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

private val rememberBoardClock=java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.of("Europe/Budapest"))
