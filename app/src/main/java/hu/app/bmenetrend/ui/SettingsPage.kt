package hu.app.bmenetrend.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Check
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.StationTimetableRepository
import java.util.Locale

@Composable
fun SettingsPage(
    state: UiState,
    onAccent: (AccentColor) -> Unit,
    onCustom: (Int) -> Unit,
    onNationwide: (Boolean) -> Unit,
    onAbout: () -> Unit,
    onBack: () -> Unit,
    onClearAllTimetables: () -> Unit = {},
    onOfflineMode: (Boolean) -> Unit = {},
    onOfflineDownload: (Boolean) -> Unit = {},
    onOfflineCancel: () -> Unit = {},
    onAutomaticOfflineUpdates: (Boolean) -> Unit = {},
    onOfflineDelete: () -> Unit = {},
    onOfflineImport: (android.net.Uri) -> Unit = {},
) {
    BackHandler(onBack=onBack)
    Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TransitPageHeader(stringResource(R.string.volan_settings),onBack)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=adaptivePageGutter()),
                verticalArrangement=Arrangement.spacedBy(16.dp)) {
                OfflinePackSettings(state, onOfflineMode, onOfflineDownload, onOfflineCancel, onOfflineDelete, onOfflineImport, onAutomaticOfflineUpdates)
                TransitSectionTitle(stringResource(R.string.accent_title))
                Text(stringResource(R.string.accent_hint),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.selectableGroup(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    val columns=if(androidx.compose.ui.platform.LocalDensity.current.fontScale>1.4f) 2 else 3
                    AccentColor.entries.chunked(columns).forEach { row ->
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            row.forEach { choice ->
                                val selected=state.accentColor==choice
                                val color=if(choice==AccentColor.GREEN) accentColorScheme(choice,false).primary else if(choice==AccentColor.CUSTOM) Color(state.customAccentArgb) else choice.hue?.let { Color.hsl(it,.65f,.35f) } ?: MaterialTheme.colorScheme.primary
                                Surface(Modifier.weight(1f),shape=TransitDesign.controlShape,
                                    color=if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                                    border=androidx.compose.foundation.BorderStroke(if(selected) 2.dp else 1.dp,
                                        if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                                    Column(Modifier.fillMaxWidth().heightIn(min=80.dp).selectable(selected,role=Role.RadioButton,onClick={onAccent(choice)})
                                        .padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                        Surface(Modifier.size(26.dp),shape=CircleShape,color=color) {
                                            if(selected) Box(contentAlignment=Alignment.Center) {
                                                Icon(androidx.compose.material.icons.Icons.Rounded.Check,null,Modifier.size(18.dp),
                                                    tint=if(color.luminance()>.35f) Color.Black else Color.White)
                                            }
                                        }
                                        Text(stringResource(choice.labelRes),style=MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                            repeat(columns-row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                androidx.compose.animation.AnimatedVisibility(state.accentColor==AccentColor.CUSTOM) { CustomColorPicker(state.customAccentArgb,onCustom) }
                TransitSectionTitle(stringResource(R.string.design_transit_data))
                Surface(shape=TransitDesign.groupShape,color=MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min=56.dp).toggleable(state.nationwideNetwork,role=Role.Switch,onValueChange=onNationwide),
                            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.volan_nationwide),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                            Switch(state.nationwideNetwork,onCheckedChange=null)
                        }
                        Text(stringResource(R.string.volan_local_scope),style=MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.volan_nationwide_hint),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(shape=TransitDesign.groupShape,color=MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        val count = state.offlineStationTimetables.size
                        val totalBytes = state.offlineStationTimetables.values.sumOf { it.fileSizeBytes }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.ui_downloaded_timetables), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (count == 0) stringResource(R.string.ui_no_downloaded_timetables)
                                    else "$count · ${StationTimetableRepository.formatFileSize(totalBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (count > 0) {
                                TextButton(onClick = onClearAllTimetables) {
                                    Text(stringResource(R.string.ui_clear_all_timetables), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
                OutlinedButton(onClick=onAbout,shape=TransitDesign.controlShape,modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) {
                    Icon(Icons.Rounded.Info,null,Modifier.size(20.dp));Spacer(Modifier.width(12.dp));Text(stringResource(R.string.ota_about_title))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun CustomColorPicker(argb: Int, onChanged: (Int) -> Unit) {
    var hex by remember(argb) { mutableStateOf(String.format(Locale.ROOT, "%06X", argb and 0xFFFFFF)) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.custom_color_title), style = MaterialTheme.typography.titleMedium)
            Box(Modifier.fillMaxWidth().height(40.dp).clip(MaterialTheme.shapes.small).background(Color(argb)))
            OutlinedTextField(hex, onValueChange = { raw ->
                hex = raw.filter { it in "0123456789abcdefABCDEF" }.take(6)
                if (hex.length == 6) onChanged(hex.toLong(16).toInt() or 0xFF000000.toInt())
            }, label = { Text(stringResource(R.string.custom_color_hex)) }, prefix = { Text("#") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            listOf(R.string.custom_color_red to 16, R.string.custom_color_green to 8, R.string.custom_color_blue to 0).forEach { (labelRes, shift) ->
                val label = stringResource(labelRes)
                val channel = (argb shr shift) and 255
                Text("$label · $channel", style = MaterialTheme.typography.labelMedium)
                Slider(channel.toFloat(), onValueChange = { value ->
                    val mask = 255 shl shift
                    onChanged((argb and mask.inv()) or (value.toInt().coerceIn(0, 255) shl shift))
                }, valueRange = 0f..255f, modifier = Modifier.semantics { contentDescription = label })
            }
            Text(stringResource(R.string.custom_color_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
