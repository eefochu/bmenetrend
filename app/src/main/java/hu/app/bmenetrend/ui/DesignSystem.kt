package hu.app.bmenetrend.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
internal fun TransitPageIntro(title: String, subtitle: String?, icon: ImageVector, actions: @Composable RowScope.() -> Unit = {}) {
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            TransitIconTile(icon,prominent=true)
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

/** The time is the scanning anchor. Live status has an icon as well as colour. */
@Composable
internal fun DepartureCount(minutes: Int, time: String, live: Boolean, modifier: Modifier = Modifier, cancelled: Boolean=false, delay: Long=0) {
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
                Text(time,style=MaterialTheme.typography.labelSmall,textDecoration=if(cancelled) TextDecoration.LineThrough else null)
            }
        }
    }
}

/** Timetable row, shared only by departure boards. Large text gets a second line of layout, not narrower text. */
@Composable
internal fun DepartureBoardRow(
    departure: hu.app.bmenetrend.data.Departure,provider: String,mode: String?,sourceColor: String?,minutes: Int,
    boardingInfo: String?,onClick: ()->Unit,modifier: Modifier=Modifier,
) {
    val large=androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f || transitWindowWidth()<350.dp
    val live=departure.kind==hu.app.bmenetrend.data.ArrivalKind.LIVE
    val clock=rememberBoardClock
    val badge: @Composable ()->Unit = {
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            ServiceBadge(departure.routeNumber,provider,mode,sourceColor,departure.trainNumber)
            Text(providerName(provider),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val time: @Composable ()->Unit = {
        DepartureCount(minutes,clock.format(departure.expectedAt),live,cancelled=departure.isCancelled,delay=departure.delayMinutes)
    }
    val detail: @Composable ()->Unit = {
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(departure.destination.ifBlank { stringResource(R.string.emma_vehicle_no_direction) },
                style=MaterialTheme.typography.titleMedium,maxLines=if(large) 4 else 3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(serviceTypeLabel(provider,mode),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            listOfNotNull(departure.serviceName,boardingInfo).forEach { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            if(!departure.isCancelled) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                Icon(if(live) Icons.Rounded.Sensors else Icons.Rounded.Schedule,null,Modifier.size(13.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(if(live) R.string.ui_live_arrival else R.string.ui_scheduled),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if(!departure.isCancelled && departure.delayMinutes!=0L && departure.scheduledAt!=null) {
                Text(stringResource(R.string.line_scheduled_delay,clock.format(departure.scheduledAt),
                    if(departure.delayMinutes>0) "+${departure.delayMinutes}" else "${departure.delayMinutes}"),
                    style=MaterialTheme.typography.labelSmall,color=if(departure.delayMinutes>0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
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
