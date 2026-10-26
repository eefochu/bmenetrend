package hu.app.bmenetrend.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.TransitStop
import hu.app.bmenetrend.data.Vehicle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MapStopPicker(stops: List<TransitStop>,vehicle: Vehicle?,onStop: (String)->Unit,onVehicle: (String)->Unit,onDismiss: ()->Unit) {
    ModalBottomSheet(onDismissRequest=onDismiss) {
        Text(stringResource(R.string.map_choose_stop),Modifier.padding(horizontal=22.dp,vertical=8.dp),style=MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=360.dp),contentPadding=PaddingValues(bottom=24.dp)) {
            if(vehicle!=null) item(key="vehicle") {
                Row(Modifier.fillMaxWidth().clickable { onVehicle(vehicle.id) }.padding(20.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                    VehicleServiceBadge(vehicle)
                    Column(Modifier.weight(1f)) {
                        Text(vehicle.destination ?: vehicle.registration ?: vehicle.id,style=MaterialTheme.typography.titleMedium)
                        Text(serviceTypeLabel(vehicle.providerId,vehicle.transportMode),style=MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
            }
            items(stops,key={it.id}) { stop ->
                ListItem(headlineContent={Text(stop.name)},supportingContent={
                    Text(listOfNotNull(providerName(stop.providerId),stop.platformCode?.let { stringResource(R.string.emma_platform,it) }
                        ?: stringResource(R.string.emma_stop_code,stop.id.substringAfterLast(':'))).joinToString(" · "))
                },modifier=Modifier.clickable { onStop(stop.id) }.heightIn(min=56.dp))
            }
        }
    }
}
