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
import java.util.Locale

@Composable
fun SettingsPage(state: UiState, onAccent: (AccentColor) -> Unit, onCustom: (Int) -> Unit,
                 onNationwide: (Boolean) -> Unit, onAbout: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ui_back)) }
                Text(stringResource(R.string.volan_settings), style = MaterialTheme.typography.headlineSmall)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.accent_title), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.accent_hint))
                Column(Modifier.selectableGroup()) {
                    AccentColor.entries.forEach { choice ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(state.accentColor == choice, role = Role.RadioButton, onClick = { onAccent(choice) }),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RadioButton(state.accentColor == choice, onClick = null)
                            val color = if (choice == AccentColor.CUSTOM) Color(state.customAccentArgb) else choice.hue?.let { Color.hsl(it, .65f, .4f) } ?: MaterialTheme.colorScheme.primary
                            Box(Modifier.size(20.dp).clip(CircleShape).background(color))
                            Text(stringResource(choice.labelRes))
                        }
                    }
                }
                CustomColorPicker(state.customAccentArgb, onCustom)
                HorizontalDivider()
                Text(stringResource(R.string.volan_nationwide), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.volan_local_scope))
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .toggleable(state.nationwideNetwork, role = Role.Switch, onValueChange = onNationwide),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.volan_nationwide), Modifier.weight(1f))
                    Switch(state.nationwideNetwork, onCheckedChange = null)
                }
                Text(stringResource(R.string.volan_nationwide_hint))
                HorizontalDivider()
                OutlinedButton(onClick = onAbout, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Info, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.ota_about_title))
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
