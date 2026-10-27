package hu.app.bmenetrend.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.StationTimetableRepository
import hu.app.bmenetrend.offline.*

@Composable
fun OfflinePackSettings(state: UiState, onMode: (Boolean) -> Unit, onDownload: (Boolean) -> Unit,
    onCancel: () -> Unit, onDelete: () -> Unit, onImport: (android.net.Uri) -> Unit, onAutomaticUpdates: (Boolean) -> Unit = {}) {
    val pack = state.offlinePack
    var wifiOnly by rememberSaveable { mutableStateOf(true) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onImport) }
    val notification = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDownload(wifiOnly) }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text(stringResource(R.string.offline_delete)) },
        text = { Text(stringResource(R.string.offline_delete_hint)) }, confirmButton = { TextButton(onClick = { deleting = false; onDelete() }) { Text(stringResource(R.string.offline_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.offline_cancel)) } })
    TransitSectionTitle(stringResource(R.string.offline_title))
    Surface(shape = TransitDesign.groupShape, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.offline_pack_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.offline_coverage), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.offline_limits), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.offline_map_coverage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            pack.metadata?.let { meta ->
                Text(stringResource(R.string.offline_installed, StationTimetableRepository.formatFileSize(meta.installedFootprintBytes), meta.validFrom.toString(), meta.validUntil.toString()), style = MaterialTheme.typography.bodyMedium)
                Text(meta.sources.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                meta.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.offline_updated, meta.builtAt.atZone(java.time.ZoneId.of("Europe/Budapest")).toLocalDate().toString()), style = MaterialTheme.typography.bodySmall)
                if (java.time.LocalDate.now(java.time.ZoneId.of("Europe/Budapest")) > meta.validUntil) Text(stringResource(R.string.offline_expired), color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(state.offlineMode, enabled = pack.metadata != null && !pack.busy, role = Role.Switch, onValueChange = onMode),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.offline_mode), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(state.offlineMode, null, enabled = pack.metadata != null && !pack.busy)
            }
            Text(stringResource(if (pack.metadata == null) R.string.offline_download_first else R.string.offline_mode_hint), style = MaterialTheme.typography.bodySmall)
            if (!state.offlineMode) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(wifiOnly, enabled = !pack.busy, role = Role.Checkbox, onValueChange = { wifiOnly = it }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(wifiOnly, null, enabled = !pack.busy)
                    Text(stringResource(R.string.offline_wifi), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                if (pack.busy) {
                    Text(stringResource(if (pack.phase == OfflinePackPhase.IMPORTING) R.string.offline_installing else R.string.offline_downloading), style = MaterialTheme.typography.bodyMedium)
                    if (pack.source.isNotBlank()) Text(pack.source, style = MaterialTheme.typography.bodySmall)
                    if (pack.phase == OfflinePackPhase.IMPORTING) {
                        val detail = pack.importProgress
                        Text(if (detail == null) stringResource(R.string.offline_download_complete) else stringResource(detail.step.labelResource), style = MaterialTheme.typography.bodyMedium)
                        val fraction = detail?.fraction
                        if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        if (detail != null && detail.records > 0) Text(stringResource(R.string.offline_processed_records, detail.records), style = MaterialTheme.typography.bodySmall)
                        if (detail?.step == OfflineImportStep.INDEXING) Text(stringResource(R.string.offline_index_progress, detail.completed + 1, detail.total), style = MaterialTheme.typography.bodySmall)
                    } else {
                        if (pack.total > 0) LinearProgressIndicator(progress = { (pack.bytes.toFloat() / pack.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(StationTimetableRepository.formatFileSize(pack.bytes), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.offline_cancel)) }
                } else {
                    if (pack.phase == OfflinePackPhase.FAILED) Text(stringResource(when (pack.error) { "storage" -> R.string.offline_error_storage; "size" -> R.string.offline_error_size; "import" -> R.string.offline_error_import; else -> R.string.offline_error_download }), color = MaterialTheme.colorScheme.error)
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 33) notification.launch(Manifest.permission.POST_NOTIFICATIONS) else onDownload(wifiOnly)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (pack.metadata == null) R.string.offline_download else R.string.offline_update))
                    }
                    OutlinedButton(onClick = { import.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.offline_import)) }
                }
            }
            if (pack.metadata != null && !state.offlineMode) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(pack.automaticUpdates, enabled = !pack.busy, role = Role.Checkbox, onValueChange = onAutomaticUpdates), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(pack.automaticUpdates, null, enabled = !pack.busy)
                Text(stringResource(R.string.offline_auto_updates), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            if (pack.metadata != null && !pack.busy) TextButton(onClick = { deleting = true }) { Text(stringResource(R.string.offline_delete), color = MaterialTheme.colorScheme.error) }
            Text(stringResource(R.string.offline_attribution), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
