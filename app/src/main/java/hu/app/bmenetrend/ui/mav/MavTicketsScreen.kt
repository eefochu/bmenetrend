package hu.app.bmenetrend.ui.mav

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import hu.app.bmenetrend.R
import hu.app.bmenetrend.mav.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun MavTicketsScreen(
    viewModel: MavViewModel,
    modifier: Modifier = Modifier
) {
    val session by viewModel.session.collectAsState()
    val loginState by viewModel.loginState.collectAsState()
    val ticketsState by viewModel.ticketsState.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val barcodeData by viewModel.barcodeData.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    var showLogoutConfirm by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (session == null) {
            MavLoginView(
                loginState = loginState,
                onLogin = viewModel::login,
                onClearError = viewModel::clearLoginError
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                val isOffline = (ticketsState as? TicketsUiState.Success)?.isOffline == true
                val lastSyncTime = (ticketsState as? TicketsUiState.Success)?.lastSyncTime ?: 0L

                MavHeader(
                    user = session?.user,
                    email = session?.email.orEmpty(),
                    isRefreshing = isRefreshing,
                    isOffline = isOffline,
                    onRefresh = viewModel::refreshTickets,
                    onLogoutClick = { showLogoutConfirm = true }
                )

                if (isOffline) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.CloudOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                stringResource(R.string.mav_offline_notice),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            if (lastSyncTime > 0L) {
                                val syncDateStr = remember(lastSyncTime) {
                                    DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
                                        .withZone(ZoneId.systemDefault())
                                        .format(Instant.ofEpochMilli(lastSyncTime))
                                }
                                Text(
                                    stringResource(R.string.mav_sync_time, syncDateStr),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }

                TabRow(
                    selectedTabIndex = if (selectedTab == MavTab.ACTIVE) 0 else 1,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == MavTab.ACTIVE,
                        onClick = { viewModel.setTab(MavTab.ACTIVE) },
                        text = {
                            Text(
                                stringResource(R.string.mav_active_tab),
                                fontWeight = if (selectedTab == MavTab.ACTIVE) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        icon = { Icon(Icons.Rounded.ConfirmationNumber, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    )
                    Tab(
                        selected = selectedTab == MavTab.PAST,
                        onClick = { viewModel.setTab(MavTab.PAST) },
                        text = {
                            Text(
                                stringResource(R.string.mav_past_tab),
                                fontWeight = if (selectedTab == MavTab.PAST) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        icon = { Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (val state = ticketsState) {
                        is TicketsUiState.Loading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    CircularProgressIndicator()
                                    Text(
                                        stringResource(R.string.ui_loading_data),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        is TicketsUiState.Error -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    ),
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.ErrorOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onErrorContainer,
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Text(
                                            state.message,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            textAlign = TextAlign.Center
                                        )
                                        Button(
                                            onClick = viewModel::refreshTickets,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error
                                            )
                                        ) {
                                            Text(stringResource(R.string.ota_retry))
                                        }
                                    }
                                }
                            }
                        }
                        is TicketsUiState.Success -> {
                            val orders = if (selectedTab == MavTab.ACTIVE) state.activeOrders else state.pastOrders
                            if (orders.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            if (selectedTab == MavTab.ACTIVE) Icons.Rounded.ConfirmationNumber else Icons.Rounded.History,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Text(
                                            if (selectedTab == MavTab.ACTIVE) {
                                                stringResource(R.string.mav_empty_active)
                                            } else {
                                                stringResource(R.string.mav_empty_past)
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(orders, key = { it.orderId }) { order ->
                                        OrderTicketCard(
                                            order = order,
                                            isActiveTab = selectedTab == MavTab.ACTIVE,
                                            onShowBarcode = { receiptId, ticketNumber, title, isPass, passengerName, birthDate ->
                                                viewModel.showBarcode(
                                                    receiptId = receiptId,
                                                    ticketNumber = ticketNumber,
                                                    title = title,
                                                    isPass = isPass,
                                                    passengerName = passengerName,
                                                    birthDate = birthDate
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text(stringResource(R.string.mav_logout_confirm_title)) },
            text = { Text(stringResource(R.string.mav_logout_confirm_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirm = false
                        viewModel.logout()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.mav_logout_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) {
                    Text(stringResource(R.string.mav_cancel))
                }
            }
        )
    }

    barcodeData?.let { data ->
        MavTicketDetailView(
            data = data,
            user = session?.user,
            onDismiss = viewModel::dismissBarcode
        )
    }
}

@Composable
private fun MavHeader(
    user: MavUser?,
    email: String,
    isRefreshing: Boolean,
    isOffline: Boolean,
    onRefresh: () -> Unit,
    onLogoutClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    val photoBitmap = remember(user?.photoBase64) { user?.toPhotoBitmap() }
                    if (photoBitmap != null) {
                        Image(
                            bitmap = photoBitmap.asImageBitmap(),
                            contentDescription = user?.fullName,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Image(
                            painter = painterResource(R.drawable.sample_passenger_photo),
                            contentDescription = user?.fullName ?: email,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            user?.fullName?.ifBlank { email } ?: email,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isOffline) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                modifier = Modifier.padding(start = 2.dp)
                            ) {
                                Text(
                                    stringResource(R.string.mav_offline_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        email,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                    if (isRefreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.mav_refresh))
                    }
                }
                IconButton(onClick = onLogoutClick) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Logout,
                        contentDescription = stringResource(R.string.mav_logout_btn),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun MavLoginView(
    loginState: LoginUiState,
    onLogin: (String, String) -> Unit,
    onClearError: () -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.ConfirmationNumber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Text(
                    stringResource(R.string.mav_account_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    stringResource(R.string.mav_login_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                AnimatedVisibility(visible = loginState is LoginUiState.Error) {
                    val errorMsg = (loginState as? LoginUiState.Error)?.message.orEmpty()
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMsg,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        onClearError()
                    },
                    label = { Text(stringResource(R.string.mav_email_label)) },
                    leadingIcon = { Icon(Icons.Rounded.Email, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        onClearError()
                    },
                    label = { Text(stringResource(R.string.mav_password_label)) },
                    leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus()
                        onLogin(email, password)
                    }),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Button(
                    onClick = {
                        focusManager.clearFocus()
                        onLogin(email, password)
                    },
                    enabled = loginState !is LoginUiState.Loading && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (loginState is LoginUiState.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            stringResource(R.string.mav_login_btn),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    stringResource(R.string.mav_privacy_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun OrderTicketCard(
    order: MavOrder,
    isActiveTab: Boolean,
    onShowBarcode: (receiptId: String, ticketNumber: String?, title: String, isPass: Boolean, passenger: String?, birthDate: Long?) -> Unit
) {
    val isPassOrder = order.deliveryMode == "E-Bérlet" || order.allItems.any { it.isPass } || order.passHolder != null

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPassOrder) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        if (isPassOrder) Icons.Rounded.Badge else Icons.AutoMirrored.Rounded.ReceiptLong,
                        contentDescription = null,
                        tint = if (isPassOrder) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        if (isPassOrder && order.orderId.startsWith("HPT")) {
                            "Bérlet azonosító: ${order.orderId}"
                        } else {
                            stringResource(R.string.mav_order_id, order.orderId)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (order.purchaseDate > 0) {
                    val dateFormatted = remember(order.purchaseDate) {
                        DateTimeFormatter.ofPattern("yyyy. MM. dd.", Locale.getDefault())
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.ofEpochMilli(order.purchaseDate))
                    }
                    Text(
                        dateFormatted,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            order.receipts.forEach { receipt ->
                val isPass = isPassOrder || receipt.items.any { it.isPass }
                ReceiptItemView(
                    receipt = receipt,
                    isActiveTab = isActiveTab,
                    isPass = isPass,
                    onShowBarcode = { title, passFlag ->
                        onShowBarcode(
                            receipt.receiptId,
                            receipt.ticketNumber,
                            title,
                            passFlag,
                            receipt.passengerName,
                            receipt.birthDate
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ReceiptItemView(
    receipt: MavTicketReceipt,
    isActiveTab: Boolean,
    isPass: Boolean,
    onShowBarcode: (title: String, isPass: Boolean) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        receipt.passengerName?.let { name ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        receipt.items.forEach { item ->
            TicketItemRow(item = item)
        }

        if (isActiveTab && receipt.receiptId.isNotBlank()) {
            val mainItemTitle = receipt.items.firstOrNull()?.name ?: stringResource(R.string.mav_ticket_dialog_title)
            FilledTonalButton(
                onClick = { onShowBarcode(mainItemTitle, isPass) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(
                    Icons.Rounded.QrCode2,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.mav_show_barcode),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun TicketItemRow(item: MavTicketItem) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (item.isPass) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "BÉRLET",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (item.price > 0) {
                    Text(
                        "${item.price} Ft",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            item.discount?.let { discount ->
                Text(
                    discount,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (item.validFrom > 0 || item.validTo > 0) {
                val validityText = remember(item.validFrom, item.validTo) {
                    val fmt = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.getDefault())
                        .withZone(ZoneId.systemDefault())
                    val fromStr = if (item.validFrom > 0) fmt.format(Instant.ofEpochMilli(item.validFrom)) else null
                    val toStr = if (item.validTo > 0) fmt.format(Instant.ofEpochMilli(item.validTo)) else null
                    when {
                        fromStr != null && toStr != null -> "$fromStr – $toStr"
                        toStr != null -> "Érvényes: $toStr-ig"
                        fromStr != null -> "Kezdete: $fromStr"
                        else -> ""
                    }
                }
                if (validityText.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            validityText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MavTicketDetailView(
    data: TicketBarcodeData,
    user: MavUser?,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    var showInfoDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101012))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 150.dp)
        ) {
            // 1. Top bar: Back arrow, Title, Info button (i)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.mav_cancel),
                            tint = Color.White
                        )
                    }

                    Text(
                        text = if (data.isPass) {
                            stringResource(R.string.mav_pass_info_title)
                        } else {
                            stringResource(R.string.mav_ticket_info_title)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                IconButton(onClick = { showInfoDialog = true }) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = "Információ",
                        tint = Color(0xFFC0C0C4),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 2. Passenger Card (Rounded dark card, selfie on left, name & birthdate on right)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF1E1F24)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val userPhotoBitmap = remember(user?.photoBase64) { user?.toPhotoBitmap() }
                    Surface(
                        modifier = Modifier.size(width = 56.dp, height = 66.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF2C2D32)
                    ) {
                        if (userPhotoBitmap != null) {
                            Image(
                                bitmap = userPhotoBitmap.asImageBitmap(),
                                contentDescription = user?.fullName,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Image(
                                painter = painterResource(R.drawable.sample_passenger_photo),
                                contentDescription = user?.fullName ?: "Passenger selfie",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }

                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        val passengerName = data.passengerName?.ifBlank { null }
                            ?: user?.fullName?.ifBlank { null }
                            ?: "Kókai Balázs"
                        Text(
                            text = passengerName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        val birthDateStr = remember(data.birthDate) {
                            val b = data.birthDate ?: 0L
                            if (b > 0L) {
                                DateTimeFormatter.ofPattern("yyyy.MM.dd.", Locale.getDefault())
                                    .withZone(ZoneId.of("Europe/Budapest"))
                                    .format(Instant.ofEpochMilli(b))
                            } else {
                                "2011.07.14."
                            }
                        }
                        Text(
                            text = birthDateStr,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF9E9EA6)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 3. Middle Ticket / Pass Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                color = Color.White,
                shadowElevation = 4.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        data.loading -> {
                            Column(
                                modifier = Modifier.padding(48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                Text(
                                    stringResource(R.string.ui_loading_data),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.DarkGray
                                )
                            }
                        }
                        data.error != null -> {
                            Column(
                                modifier = Modifier.padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    data.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        data.bitmap != null -> {
                            Image(
                                bitmap = data.bitmap.asImageBitmap(),
                                contentDescription = data.title,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                }
            }
        }

        // 4. Moving train GIF at the bottom
        AnimatedTrainGif(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(384f / 128f)
                .align(Alignment.BottomCenter)
        )

        // 5. Info Dialog (Screenshot 2)
        if (showInfoDialog) {
            MavValidTicketInfoDialog(onDismiss = { showInfoDialog = false })
        }
    }
}

@Composable
private fun AnimatedTrainGif(modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                adjustViewBounds = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        val source = ImageDecoder.createSource(context.resources, R.drawable.vonat_cut)
                        val drawable = ImageDecoder.decodeDrawable(source)
                        setImageDrawable(drawable)
                        if (drawable is AnimatedImageDrawable) {
                            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                            drawable.start()
                        }
                    } catch (e: Exception) {
                        setImageResource(R.drawable.vonat_cut)
                    }
                } else {
                    setImageResource(R.drawable.vonat_cut)
                }
            }
        },
        update = { imageView ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val d = imageView.drawable
                if (d is AnimatedImageDrawable && !d.isRunning) {
                    d.start()
                }
            }
        }
    )
}

@Composable
private fun MavValidTicketInfoDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = Color(0xFF1E1F24),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.mav_valid_ticket_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Text(
                    text = stringResource(R.string.mav_valid_ticket_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD6D6DC)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    TextButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF4FA8EE),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                stringResource(R.string.mav_dialog_ok),
                                color = Color(0xFF4FA8EE),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    }
}
