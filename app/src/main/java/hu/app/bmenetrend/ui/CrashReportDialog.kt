package hu.app.bmenetrend.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R

@Composable
internal fun CrashReportDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(shape=TransitDesign.panelShape,containerColor=MaterialTheme.colorScheme.surfaceContainerLow,onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crash_report_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(stringResource(R.string.crash_report_description), style = MaterialTheme.typography.bodyMedium)
                SelectionContainer {
                    Text(report, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("BMenetrend", report))
            onDismiss()
        }) { Text(stringResource(R.string.crash_report_copy)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_close)) } })
}
