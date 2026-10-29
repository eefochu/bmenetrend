package hu.app.bmenetrend.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    val controlShape = RoundedCornerShape(16.dp)
    val panelShape = RoundedCornerShape(24.dp)
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val groupShape = RoundedCornerShape(20.dp)
}

@Composable
internal fun TransitIconTile(icon: ImageVector, modifier: Modifier = Modifier, prominent: Boolean = false) {
    Surface(modifier.size(44.dp), shape = RoundedCornerShape(topStart=16.dp,topEnd=16.dp,bottomStart=16.dp,bottomEnd=8.dp),
        color = if(prominent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer,
        contentColor = if(prominent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer) {
        Box(contentAlignment=Alignment.Center) { Icon(icon,null,Modifier.size(23.dp)) }
    }
}

@Composable
internal fun TransitPageHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,top=8.dp,bottom=16.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        FilledTonalIconButton(onClick=onBack,shape=TransitDesign.controlShape,modifier=Modifier.size(48.dp)) {
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
        shape = if (isPill) RoundedCornerShape(11.dp) else CircleShape,
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
    Surface(modifier.widthIn(min=58.dp),shape=RoundedCornerShape(14.dp),
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

/** Timetable row, shared only by departure boards. Large text gets a second line of layout, not narrower text. */
@Composable
internal fun DepartureBoardRow(
    departure: hu.app.bmenetrend.data.Departure,provider: String,mode: String?,sourceColor: String?,minutes: Int,
    boardingInfo: String?,onClick: ()->Unit,modifier: Modifier=Modifier,
    platformCode: String? = departure.platformCode,
) {
    val large=androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f || transitWindowWidth()<350.dp
    val live=departure.kind==hu.app.bmenetrend.data.ArrivalKind.LIVE
    val clock=rememberBoardClock
    val resolvedPlatform = platformCode?.ifBlank { null }
        ?: hu.app.bmenetrend.data.BkkPlatformIndex.getPlatformCode(departure.stopId)
    val badge: @Composable ()->Unit = {
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            ServiceBadge(departure.routeNumber,provider,mode,sourceColor,departure.trainNumber)
            Text(providerName(provider),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val time: @Composable ()->Unit = {
        // Compare instants, not truncated delay minutes: even a short delay can change HH:mm.
        val original = departure.scheduledAt?.takeIf { live && !departure.isCancelled && it != departure.expectedAt }
        val shown = if (departure.isCancelled) departure.scheduledAt ?: departure.expectedAt else departure.expectedAt
        DepartureCount(minutes,clock.format(shown),live,cancelled=departure.isCancelled,
            delay=if (live) departure.delayMinutes else 0,scheduledTime=original?.let(clock::format))
    }
    val detail: @Composable ()->Unit = {
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(departure.destination.ifBlank { stringResource(R.string.emma_vehicle_no_direction) },
                    style=MaterialTheme.typography.titleMedium,maxLines=if(large) 4 else 3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                resolvedPlatform?.let { PlatformBadge(it) }
            }
            Text(serviceTypeLabel(provider,mode),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            listOfNotNull(departure.serviceName, boardingInfo?.takeIf { resolvedPlatform == null }).forEach { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            if(!departure.isCancelled) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                Icon(if(live) Icons.Rounded.Sensors else Icons.Rounded.Schedule,null,Modifier.size(13.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(if(live) R.string.ui_live_arrival else R.string.ui_scheduled),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val features=listOfNotNull(
                if(departure.wheelchairAccessible=="POSSIBLE") stringResource(R.string.line_accessible) else null,
                if(departure.bikesAllowed=="ALLOWED") stringResource(R.string.line_bikes) else null)
            if(features.isNotEmpty()) Text(features.joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(departure.restrictedService) Text(stringResource(R.string.ui_registration_required),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Column(modifier.fillMaxWidth().then(Modifier.clickable(enabled=!departure.isCancelled,role=androidx.compose.ui.semantics.Role.Button,onClick=onClick))
        .padding(horizontal=20.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(large) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) { badge();Spacer(Modifier.weight(1f));time() }
            detail()
        } else Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            badge();Box(Modifier.weight(1f)) { detail() };time()
        }
    }
}

private val rememberBoardClock=java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(java.time.ZoneId.of("Europe/Budapest"))
