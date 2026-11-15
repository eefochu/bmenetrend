package hu.app.bmenetrend.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.app.bmenetrend.R
import hu.app.bmenetrend.debug.HttpPacket
import hu.app.bmenetrend.debug.PacketTracer
import hu.app.bmenetrend.ui.TransitDesign
import hu.app.bmenetrend.ui.TransitPageHeader
import hu.app.bmenetrend.ui.adaptivePageGutter
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT)
    .withZone(ZoneId.systemDefault())

private enum class PacketFilter {
    ALL, SUCCESS, FAILED
}

@Composable
fun DebugOptionsPage(
    onOpenPacketTracer: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            TransitPageHeader(stringResource(R.string.debug_options_title), onBack)

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = adaptivePageGutter(), vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    onClick = onOpenPacketTracer,
                    modifier = Modifier.fillMaxWidth(),
                    shape = TransitDesign.groupShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.NetworkCheck,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.packet_tracer_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Live HTTP traffic monitor (GET & POST payload, headers, status)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PacketTracerPage(
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)
    val packets by PacketTracer.packets.collectAsStateWithLifecycle()
    var selectedPacket by remember { mutableStateOf<HttpPacket?>(null) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(PacketFilter.ALL) }

    val filteredPackets = remember(packets, searchQuery, filter) {
        packets.filter { packet ->
            val matchesFilter = when (filter) {
                PacketFilter.ALL -> true
                PacketFilter.SUCCESS -> packet.isSuccessful
                PacketFilter.FAILED -> !packet.isSuccessful
            }
            if (!matchesFilter) return@filter false
            if (searchQuery.isBlank()) return@filter true
            val q = searchQuery.trim()
            packet.url.contains(q, ignoreCase = true) ||
                packet.method.contains(q, ignoreCase = true) ||
                (packet.requestBody?.contains(q, ignoreCase = true) == true) ||
                (packet.responseBody?.contains(q, ignoreCase = true) == true) ||
                (packet.error?.contains(q, ignoreCase = true) == true) ||
                packet.statusCode.toString().contains(q)
        }
    }

    val successCount = remember(packets) { packets.count { it.isSuccessful } }
    val failedCount = remember(packets) { packets.count { !it.isSuccessful } }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Header with Clear button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.ui_back)
                    )
                }
                Text(
                    stringResource(R.string.packet_tracer_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                if (packets.isNotEmpty()) {
                    IconButton(onClick = { PacketTracer.clear() }) {
                        Icon(
                            Icons.Rounded.DeleteSweep,
                            contentDescription = stringResource(R.string.packet_tracer_clear),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = adaptivePageGutter(), vertical = 4.dp),
                placeholder = { Text(stringResource(R.string.packet_tracer_search_hint), fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )

            // Filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = adaptivePageGutter(), vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter == PacketFilter.ALL,
                    onClick = { filter = PacketFilter.ALL },
                    label = { Text(stringResource(R.string.packet_tracer_filter_all, packets.size)) }
                )
                FilterChip(
                    selected = filter == PacketFilter.SUCCESS,
                    onClick = { filter = PacketFilter.SUCCESS },
                    label = { Text(stringResource(R.string.packet_tracer_filter_success, successCount)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF1B5E20).copy(alpha = 0.2f),
                        selectedLabelColor = Color(0xFF2E7D32)
                    )
                )
                FilterChip(
                    selected = filter == PacketFilter.FAILED,
                    onClick = { filter = PacketFilter.FAILED },
                    label = { Text(stringResource(R.string.packet_tracer_filter_failed, failedCount)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                )
            }

            // Packet list
            if (filteredPackets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.packet_tracer_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = adaptivePageGutter(), vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredPackets, key = { it.id }) { packet ->
                        PacketListItem(
                            packet = packet,
                            onClick = { selectedPacket = packet }
                        )
                    }
                }
            }
        }
    }

    selectedPacket?.let { packet ->
        PacketDetailsDialog(
            packet = packet,
            onDismiss = { selectedPacket = null }
        )
    }
}

@Composable
private fun PacketListItem(
    packet: HttpPacket,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Method badge (GET/POST)
                    Surface(
                        color = if (packet.method == "POST") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = packet.method,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (packet.method == "POST") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    // Success / Failed tag
                    StatusBadge(packet = packet)
                }

                Text(
                    text = "${packet.durationMs}ms · ${TIME_FORMATTER.format(packet.timestamp)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = packet.url,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (!packet.error.isNullOrBlank()) {
                Text(
                    text = packet.error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(packet: HttpPacket) {
    val isSuccess = packet.isSuccessful
    val bgColor = if (isSuccess) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
    val text = if (packet.statusCode > 0) {
        if (isSuccess) "successful (${packet.statusCode})" else "failed (${packet.statusCode})"
    } else {
        "failed"
    }

    Surface(
        color = bgColor.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = bgColor
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PacketDetailsDialog(
    packet: HttpPacket,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(0.95f),
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        packet.method,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    StatusBadge(packet = packet)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.ui_close))
                }
            }
        },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 500.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // URL
                    SectionHeader("URL")
                    Text(
                        packet.url,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )

                    // Error if any
                    if (!packet.error.isNullOrBlank()) {
                        SectionHeader(stringResource(R.string.packet_tracer_error))
                        Text(
                            packet.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Request Headers
                    SectionHeader(stringResource(R.string.packet_tracer_request_headers))
                    if (packet.requestHeaders.isEmpty()) {
                        Text("(none)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        packet.requestHeaders.forEach { header ->
                            HeaderRow(header.name, header.value)
                        }
                    }

                    // Request Body
                    if (!packet.requestBody.isNullOrBlank()) {
                        SectionHeader(stringResource(R.string.packet_tracer_request_body))
                        BodyCard(packet.requestBody)
                    }

                    // Response Headers
                    SectionHeader(stringResource(R.string.packet_tracer_response_headers))
                    if (packet.responseHeaders.isEmpty()) {
                        Text("(none)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        packet.responseHeaders.forEach { header ->
                            HeaderRow(header.name, header.value)
                        }
                    }

                    // Response Body
                    if (!packet.responseBody.isNullOrBlank()) {
                        SectionHeader(stringResource(R.string.packet_tracer_response_body))
                        BodyCard(packet.responseBody)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val formatted = buildString {
                        appendLine("${packet.method} ${packet.url}")
                        appendLine("Status: ${packet.statusCode} ${packet.statusMessage}")
                        appendLine("Duration: ${packet.durationMs}ms")
                        if (packet.error != null) appendLine("Error: ${packet.error}")
                        appendLine("\n--- Request Headers ---")
                        packet.requestHeaders.forEach { appendLine("${it.name}: ${it.value}") }
                        if (packet.requestBody != null) {
                            appendLine("\n--- Request Body ---")
                            appendLine(packet.requestBody)
                        }
                        appendLine("\n--- Response Headers ---")
                        packet.responseHeaders.forEach { appendLine("${it.name}: ${it.value}") }
                        if (packet.responseBody != null) {
                            appendLine("\n--- Response Body ---")
                            appendLine(packet.responseBody)
                        }
                    }
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Packet", formatted))
                    Toast.makeText(context, context.getString(R.string.packet_tracer_copied), Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.packet_tracer_copy_details))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ui_close))
            }
        }
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun HeaderRow(name: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "$name:",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BodyCard(content: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = content,
            modifier = Modifier
                .padding(8.dp)
                .horizontalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            fontFamily = FontFamily.Monospace
        )
    }
}
