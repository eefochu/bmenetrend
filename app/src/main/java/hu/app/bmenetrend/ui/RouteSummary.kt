package hu.app.bmenetrend.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.*

@Composable
internal fun serviceTypeLabel(provider: String,mode: String?): String = stringResource(when(TransportServiceType.of(provider,mode)) {
    TransportServiceType.INTERURBAN_BUS -> R.string.service_interurban_bus
    TransportServiceType.LOCAL_BUS -> R.string.service_local_bus
    TransportServiceType.CITY_BUS -> R.string.service_city_bus
    TransportServiceType.BUS -> R.string.emma_mode_bus
    TransportServiceType.TRAM -> R.string.emma_mode_tram
    TransportServiceType.TROLLEYBUS -> R.string.emma_mode_trolley
    TransportServiceType.METRO -> R.string.emma_mode_metro
    TransportServiceType.SUBURBAN_RAIL -> R.string.emma_mode_hev
    TransportServiceType.RAIL -> R.string.emma_mode_train
    TransportServiceType.FERRY -> R.string.service_ferry
})

/** One compact direction row shared by line choices, local routes and vehicle details. */
@Composable
internal fun RouteDirectionRow(route: TransitRoute,destination: String,origin: String? = null,
    modifier: Modifier = Modifier,onClick: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().then(if(onClick!=null) Modifier.clickable(onClick=onClick) else Modifier)
        .heightIn(min=64.dp).padding(horizontal=20.dp,vertical=12.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        LineBadge(route)
        Icon(Icons.AutoMirrored.Rounded.ArrowForward,null,Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            Text(destination,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
            if(!origin.isNullOrBlank() && origin!=destination) Text(stringResource(R.string.line_from,origin),
                style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
        }
    }
}
