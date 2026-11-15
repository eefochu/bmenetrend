@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package hu.app.bmenetrend.ui.mav

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.os.Message
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.webkit.WebViewClient
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.window.DialogProperties
import hu.app.bmenetrend.mav.R
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
                onGoogleLogin = viewModel::loginWithGoogle,
                onClearError = viewModel::clearLoginError,
                onError = viewModel::setLoginError
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                val isOffline = (ticketsState as? TicketsUiState.Success)?.isOffline == true
                val lastSyncTime = (ticketsState as? TicketsUiState.Success)?.lastSyncTime ?: 0L
                val sessionExpired = (ticketsState as? TicketsUiState.Success)?.sessionExpired == true

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
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                } else if (sessionExpired) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
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
                                Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                stringResource(R.string.mav_session_expired_notice),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                if (lastSyncTime > 0L) {
                    val syncFormatted = remember(lastSyncTime) {
                        DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.getDefault())
                            .withZone(ZoneId.systemDefault())
                            .format(Instant.ofEpochMilli(lastSyncTime))
                    }
                    Text(
                        stringResource(R.string.mav_sync_time, syncFormatted),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
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
                        text = { Text(stringResource(R.string.mav_active_tab)) }
                    )
                    Tab(
                        selected = selectedTab == MavTab.PAST,
                        onClick = { viewModel.setTab(MavTab.PAST) },
                        text = { Text(stringResource(R.string.mav_past_tab)) }
                    )
                }

                when (val state = ticketsState) {
                    is TicketsUiState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularWavyProgressIndicator(modifier = Modifier.size(56.dp))
                        }
                    }
                    is TicketsUiState.Error -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(48.dp)
                                )
                                Text(
                                    state.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    textAlign = TextAlign.Center
                                )
                                Button(onClick = viewModel::refreshTickets) {
                                    Text(stringResource(R.string.mav_refresh))
                                }
                            }
                        }
                    }
                    is TicketsUiState.Success -> {
                        val currentOrders = when (selectedTab) {
                            MavTab.ACTIVE -> state.activeOrders
                            MavTab.PAST -> state.pastOrders
                        }

                        if (currentOrders.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.ConfirmationNumber,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(48.dp)
                                    )
                                    Text(
                                        when (selectedTab) {
                                            MavTab.ACTIVE -> stringResource(R.string.mav_empty_active)
                                            MavTab.PAST -> stringResource(R.string.mav_empty_past)
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
                                items(currentOrders, key = { it.orderId.ifBlank { it.hashCode().toString() } }) { order ->
                                    OrderTicketCard(
                                        order = order,
                                        isActiveTab = selectedTab == MavTab.ACTIVE,
                                        onShowBarcode = viewModel::showBarcode
                                    )
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
                    }
                ) {
                    Text(stringResource(R.string.mav_logout_btn), color = MaterialTheme.colorScheme.error)
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
                            contentDescription = user?.fullName ?: email,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AccountCircle,
                                contentDescription = user?.fullName ?: email,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(28.dp)
                            )
                        }
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
                        CircularWavyProgressIndicator(modifier = Modifier.size(20.dp))
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
    onGoogleLogin: (String) -> Unit,
    onClearError: () -> Unit,
    onError: (String) -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var showGoogleLoginDialog by rememberSaveable { mutableStateOf(false) }
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
                        // Repeated taps while signing in are ignored.
                        if (loginState !is LoginUiState.Loading) {
                            focusManager.clearFocus()
                            onLogin(email, password)
                        }
                    },
                    // Stay visually enabled while loading so the indicator sits on the primary color.
                    enabled = loginState is LoginUiState.Loading || (email.isNotBlank() && password.isNotBlank()),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (loginState is LoginUiState.Loading) {
                        CircularWavyProgressIndicator(
                            modifier = Modifier.size(30.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f)
                        )
                    } else {
                        Text(
                            stringResource(R.string.mav_login_btn),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Text(
                        text = stringResource(R.string.mav_login_or),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }

                OutlinedButton(
                    onClick = {
                        focusManager.clearFocus()
                        onClearError()
                        showGoogleLoginDialog = true
                    },
                    enabled = loginState !is LoginUiState.Loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_google_logo),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            stringResource(R.string.mav_login_google_btn),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
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

    if (showGoogleLoginDialog) {
        MavGoogleLoginDialog(
            onDismissRequest = { showGoogleLoginDialog = false },
            onCodeReceived = { code ->
                showGoogleLoginDialog = false
                onGoogleLogin(code)
            },
            onError = { errMsg ->
                showGoogleLoginDialog = false
                onError(errMsg)
            }
        )
    }
}

class GoogleAuthBridge(
    private val onCode: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    @JavascriptInterface
    fun onCodeReceived(code: String) {
        if (code.isNotBlank()) {
            onCode(code)
        }
    }

    @JavascriptInterface
    fun onError(error: String) {
        onError(error)
    }
}

@Composable
fun MavGoogleLoginDialog(
    onDismissRequest: () -> Unit,
    onCodeReceived: (String) -> Unit,
    onError: (String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var popupWebViewRef by remember { mutableStateOf<WebView?>(null) }
    var containerRef by remember { mutableStateOf<FrameLayout?>(null) }
    var handled by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BackHandler {
            if (popupWebViewRef?.canGoBack() == true) {
                popupWebViewRef?.goBack()
            } else if (popupWebViewRef != null) {
                containerRef?.removeView(popupWebViewRef)
                popupWebViewRef?.destroy()
                popupWebViewRef = null
                isLoading = false
            } else {
                onDismissRequest()
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onDismissRequest) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.mav_cancel)
                            )
                        }
                        Text(
                            text = stringResource(R.string.mav_google_login_dialog_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        if (isLoading) {
                            CircularWavyProgressIndicator(
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(20.dp)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { context ->
                            val container = FrameLayout(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                            containerRef = container

                            val bridge = GoogleAuthBridge(
                                onCode = { code ->
                                    coroutineScope.launch(Dispatchers.Main) {
                                        if (!handled) {
                                            handled = true
                                            onCodeReceived(code)
                                        }
                                    }
                                },
                                onError = { err ->
                                    coroutineScope.launch(Dispatchers.Main) {
                                        if (!handled) {
                                            handled = true
                                            onError(err)
                                        }
                                    }
                                }
                            )

                            val parentWebView = WebView(context).apply {
                                layoutParams = FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.setSupportMultipleWindows(true)
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                settings.userAgentString = MavApiClient.CHROME_MOBILE_USER_AGENT

                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                addJavascriptInterface(bridge, "AndroidBridge")

                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                    }
                                }

                                webChromeClient = object : WebChromeClient() {
                                    override fun onCreateWindow(
                                        view: WebView?,
                                        isDialog: Boolean,
                                        isUserGesture: Boolean,
                                        resultMsg: Message?
                                    ): Boolean {
                                        val popup = WebView(context).apply {
                                            layoutParams = FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                FrameLayout.LayoutParams.MATCH_PARENT
                                            )
                                            settings.javaScriptEnabled = true
                                            settings.domStorageEnabled = true
                                            settings.userAgentString = MavApiClient.CHROME_MOBILE_USER_AGENT
                                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                                            webViewClient = object : WebViewClient() {
                                                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                                                    super.onPageStarted(v, url, favicon)
                                                    isLoading = true
                                                    if (url != null && (url.contains("code=") || url.contains("error=")) &&
                                                        (url.contains("jegy.mav.hu") || url.contains("mvapi.mav.hu"))
                                                    ) {
                                                        val uri = Uri.parse(url)
                                                        val code = uri.getQueryParameter("code")
                                                        val error = uri.getQueryParameter("error")
                                                        if (!code.isNullOrBlank() && !handled) {
                                                            handled = true
                                                            v?.stopLoading()
                                                            coroutineScope.launch(Dispatchers.Main) {
                                                                onCodeReceived(code)
                                                            }
                                                        } else if (!error.isNullOrBlank() && !handled) {
                                                            handled = true
                                                            v?.stopLoading()
                                                            coroutineScope.launch(Dispatchers.Main) {
                                                                onError(uri.getQueryParameter("error_description") ?: error)
                                                            }
                                                        }
                                                    }
                                                }

                                                override fun onPageFinished(v: WebView?, url: String?) {
                                                    super.onPageFinished(v, url)
                                                    isLoading = false
                                                }
                                            }

                                            webChromeClient = object : WebChromeClient() {
                                                override fun onCloseWindow(window: WebView?) {
                                                    container.removeView(window)
                                                    window?.destroy()
                                                    popupWebViewRef = null
                                                    isLoading = false
                                                }
                                            }
                                        }

                                        popupWebViewRef = popup
                                        container.addView(popup)

                                        val transport = resultMsg?.obj as? WebView.WebViewTransport
                                        transport?.webView = popup
                                        resultMsg?.sendToTarget()
                                        return true
                                    }
                                }

                                val gisHtml = """
                                    <!DOCTYPE html>
                                    <html>
                                    <head>
                                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                        <script src="https://accounts.google.com/gsi/client"></script>
                                    </head>
                                    <body style="margin:0; background:#f9f9f9; display:flex; flex-direction:column; align-items:center; justify-content:center; height:100vh; font-family:sans-serif;">
                                        <div style="text-align:center; padding:24px;">
                                            <p style="font-size:16px; color:#555; font-weight:500;">Kapcsolódás a Google-fiókhoz...</p>
                                        </div>
                                        <script>
                                            function triggerGoogleAuth() {
                                                try {
                                                    const client = google.accounts.oauth2.initCodeClient({
                                                        client_id: '989905229285-hqta0tjm61slvfn3mkic8qvgjob0mvqj.apps.googleusercontent.com',
                                                        scope: 'email profile',
                                                        ux_mode: 'popup',
                                                        callback: function(response) {
                                                            if (response && response.code) {
                                                                AndroidBridge.onCodeReceived(response.code);
                                                            } else if (response && response.error) {
                                                                AndroidBridge.onError(response.error_description || response.error);
                                                            }
                                                        },
                                                        error_callback: function(err) {
                                                            AndroidBridge.onError((err && err.message) ? err.message : 'Google azonosítási hiba');
                                                        }
                                                    });
                                                    client.requestCode();
                                                } catch(e) {
                                                    AndroidBridge.onError(e.message || 'Hiba a Google azonosítás indításakor');
                                                }
                                            }
                                            window.onload = function() {
                                                setTimeout(triggerGoogleAuth, 300);
                                            };
                                        </script>
                                    </body>
                                    </html>
                                """.trimIndent()

                                loadDataWithBaseURL("https://jegy.mav.hu/", gisHtml, "text/html", "UTF-8", null)
                            }

                            container.addView(parentWebView)
                            container
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderTicketCard(
    order: MavOrder,
    isActiveTab: Boolean,
    onShowBarcode: (receiptId: String, ticketNumber: String?, title: String, isPass: Boolean, passenger: String?, birthDate: Long?, passHolderId: String?) -> Unit
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
                        if (isPassOrder && order.displayOrderId.startsWith("HPT")) {
                            "Bérlet azonosító: ${order.displayOrderId}"
                        } else {
                            stringResource(R.string.mav_order_id, order.displayOrderId)
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
                val passHolderId = (receipt.passHolderId ?: order.passHolder?.id)?.takeIf { it.isNotBlank() }
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
                            receipt.birthDate,
                            passHolderId
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
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        item.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Surface(
                        color = if (item.isPass) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            if (item.isPass) "BÉRLET" else "JEGY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (item.isPass) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
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
    TicketDisplayWindowEffect()

    val screenBackground = Color(0xFF0B0B0D)
    val cardColor = Color(0xFF22242A)
    val cardShape = RoundedCornerShape(22.dp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenBackground)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            // 1. Top bar: thin back arrow, title, outlined info button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.mav_cancel),
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.width(16.dp))
                Text(
                    text = if (data.isPass) {
                        stringResource(R.string.mav_pass_info_title)
                    } else {
                        stringResource(R.string.mav_ticket_info_title)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showInfoDialog = true }) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = "Információ",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 2. Passenger / Electronic passholder info card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp),
                shape = cardShape,
                color = cardColor
            ) {
                // Only set by the ViewModel when the id matches a real BERLETTOK passholder record; the
                // number itself often equals the order/ticket number, so it can't be compared against them.
                val isElectronicPassHolder = !data.passHolderNumber.isNullOrBlank()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        val passengerPhotoBitmap = remember(data.passengerPhotoBase64) { data.toPassengerPhotoBitmap() }
                        val photoShape = RoundedCornerShape(10.dp)
                        val photoModifier = if (isElectronicPassHolder) {
                            Modifier.size(width = 58.dp, height = 72.dp)
                        } else {
                            Modifier.size(54.dp)
                        }
                        Box(
                            modifier = photoModifier
                                .clip(photoShape)
                                .background(Color(0xFF2C2D32)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (passengerPhotoBitmap != null) {
                                Image(
                                    bitmap = passengerPhotoBitmap.asImageBitmap(),
                                    contentDescription = data.passengerName ?: "Utas fénykép",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Person,
                                    contentDescription = data.passengerName ?: "Utas fénykép",
                                    tint = Color(0xFF8E8E93),
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            val baseName = data.passengerName?.ifBlank { null }
                                ?: data.title
                            val displayName = if (isElectronicPassHolder) {
                                "$baseName (${data.passHolderNumber})"
                            } else {
                                baseName
                            }
                            Text(
                                text = displayName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            val birthDateStr = remember(data.birthDate) {
                                val b = data.birthDate ?: 0L
                                if (b > 0L) {
                                    DateTimeFormatter.ofPattern("yyyy.MM.dd.", Locale.getDefault())
                                        .withZone(ZoneId.of("Europe/Budapest"))
                                        .format(Instant.ofEpochMilli(b))
                                } else {
                                    null
                                }
                            }
                            if (!birthDateStr.isNullOrBlank()) {
                                Text(
                                    text = birthDateStr,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White
                                )
                            }

                            if (isElectronicPassHolder && !data.idCardNumber.isNullOrBlank()) {
                                Text(
                                    text = stringResource(R.string.mav_id_number, data.idCardNumber),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    if (isElectronicPassHolder) {
                        Spacer(Modifier.height(14.dp))
                        HorizontalDivider(
                            color = Color(0xFF2E3036),
                            thickness = 1.dp
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.mav_electronic_passholder_number, data.passHolderNumber!!),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Normal,
                            color = Color(0xFFD1D1D6)
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // 3. Ticket card: dark rounded frame with the ticket image on a plain white inset
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 26.dp),
                shape = cardShape,
                color = cardColor
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 9.dp, vertical = 9.dp)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        data.loading -> {
                            Column(
                                modifier = Modifier.padding(48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                CircularWavyProgressIndicator(modifier = Modifier.size(56.dp))
                                Text(
                                    stringResource(R.string.mav_loading_data),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.DarkGray
                                )
                                if (!data.notice.isNullOrBlank()) {
                                    Text(
                                        data.notice,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Center
                                    )
                                }
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
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                }
            }
        }

        // 4. Moving train GIF, sitting directly above the system navigation bar
        AnimatedTrainGif(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(560f / 112f)
        )
        // The host (MainNavigation) consumes the navigation-bar inset for its bottom tab bar, so
        // windowInsetsBottomHeight() would resolve to 0 here. Read the raw inset instead so the
        // system navigation bar gets its own strip below the GIF, like in the official app.
        val density = LocalDensity.current
        val navBarHeight = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(navBarHeight)
                .background(screenBackground)
        )
    }

    // 5. Info dialog
    if (showInfoDialog) {
        MavValidTicketInfoDialog(onDismiss = { showInfoDialog = false })
    }
}

/**
 * While a ticket is on screen: maximum brightness (so scanners can read the barcode) and portrait
 * orientation, like the official MÁV app. The previous values are restored when the view closes.
 */
@Composable
private fun TicketDisplayWindowEffect() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.findActivity()
        if (activity == null) {
            onDispose { }
        } else {
            val window = activity.window
            val previousBrightness = window.attributes.screenBrightness
            val previousOrientation = activity.requestedOrientation

            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            }
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

            onDispose {
                window.attributes = window.attributes.apply {
                    screenBrightness = previousBrightness
                }
                activity.requestedOrientation = previousOrientation
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
                        val source = ImageDecoder.createSource(context.resources, R.drawable.ticket_anim)
                        val drawable = ImageDecoder.decodeDrawable(source)
                        setImageDrawable(drawable)
                        if (drawable is AnimatedImageDrawable) {
                            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                            drawable.start()
                        }
                    } catch (e: Exception) {
                        setImageResource(R.drawable.ticket_anim)
                    }
                } else {
                    setImageResource(R.drawable.ticket_anim)
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
