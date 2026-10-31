package hu.app.bmenetrend.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.TransitRoute

/** Route browsing lives in the map's existing bottom card. */
@Composable
internal fun RouteBrowserCard(state: UiState,route: TransitRoute,directionId: String?,onDirection: (String)->Unit,
    onBoard: (String,String,String)->Unit,onBack: ()->Unit,onClose: ()->Unit,onRetry: ()->Unit,modifier: Modifier=Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start=8.dp,end=8.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack,stringResource(R.string.ui_back)) }
            LineBadge(route)
            Column(Modifier.weight(1f).padding(start=12.dp)) {
                Text(serviceTypeLabel(route.providerId,route.transportMode),style=MaterialTheme.typography.titleMedium)
                Text(providerName(route.providerId),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick=onClose) { Icon(Icons.Rounded.Close,stringResource(R.string.route_close)) }
        }
        HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f))
        if(route.providerId in setOf("volan","bkk","hev","mav")) {
            EmmaLineBrowser(state,route,directionId,onDirection,onBoard,onRetry,showHeader=false)
        } else {
            val direction=route.directions.firstOrNull { it.id==directionId }
            LazyColumn(contentPadding=PaddingValues(bottom=24.dp)) {
                if(direction==null) {
                    if(route.directions.isEmpty()) item(key="empty",contentType="notice") { EmptyState(stringResource(R.string.ui_no_directions_title),stringResource(R.string.ui_no_directions_body),Icons.Rounded.Info) }
                    items(route.directions,key={it.id},contentType={"direction"}) { variant ->
                        RouteDirectionRow(route,variant.name,variant.stopIds.firstOrNull()?.let { state.findStop(it)?.name },onClick={onDirection(variant.id)})
                        if(variant.restrictedService) Text(stringResource(R.string.ui_registration_required),Modifier.padding(horizontal=22.dp),style=MaterialTheme.typography.bodySmall)
                    }
                } else {
                    item(key="direction-header",contentType="direction-header") {
                        RouteDirectionRow(route,direction.name,direction.stopIds.firstOrNull()?.let { state.findStop(it)?.name })
                        if(direction.restrictedService) Text(stringResource(R.string.ui_registration_required),Modifier.padding(horizontal=22.dp,vertical=8.dp),style=MaterialTheme.typography.bodySmall)
                    }
                    itemsIndexed(direction.stopIds,key={i,id -> "$i-$id"},contentType={_,_->"stop"}) { i,id ->
                        state.findStop(id)?.let { stop -> LineStopRow(stop,i==0,i==direction.stopIds.lastIndex,lineColor(route),{onBoard(route.id,direction.id,id)}) }
                    }
                }
            }
        }
    }
}
