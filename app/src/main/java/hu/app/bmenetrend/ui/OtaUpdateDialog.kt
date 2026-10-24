package hu.app.bmenetrend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hu.app.bmenetrend.R
import hu.app.bmenetrend.data.OtaUpdateInfo

@Composable
fun OtaUpdateDialog(
    update: OtaUpdateInfo,
    downloadProgress: Float?,
    error: String? = null,
    isDownloaded: Boolean = false,
    onDownloadAndInstall: (OtaUpdateInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary) },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.ota_dialog_update_title))
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        stringResource(R.string.ota_new_version_label, update.version, update.tag),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!update.changelog.isNullOrBlank()) {
                    Text(
                        stringResource(R.string.ota_changelog_label),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(12.dp)
                    ) {
                        Text(
                            update.changelog,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                if (!update.lastCommit.isNullOrBlank()) {
                    TextButton(
                        onClick = { runCatching { uriHandler.openUri(update.lastCommit) } },
                        modifier = Modifier.align(Alignment.Start),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.ota_last_commit_link), style = MaterialTheme.typography.bodySmall)
                            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(14.dp))
                        }
                    }
                }

                if (downloadProgress != null) {
                    Column(
                        Modifier.padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                        )
                        Text(
                            stringResource(R.string.ota_downloading, (downloadProgress * 100).toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (update.apkUrl != null) {
                Button(
                    onClick = { onDownloadAndInstall(update) },
                    enabled = downloadProgress == null
                ) {
                    Text(
                        if (isDownloaded) stringResource(R.string.ota_install_now)
                        else stringResource(R.string.ota_dialog_update_now)
                    )
                }
            } else {
                OutlinedButton(
                    onClick = {},
                    enabled = false
                ) {
                    Text(stringResource(R.string.ota_apk_not_available))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ota_dismiss_later))
            }
        }
    )
}
