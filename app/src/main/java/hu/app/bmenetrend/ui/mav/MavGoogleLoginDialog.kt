package hu.app.bmenetrend.ui.mav

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.Keep
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import hu.app.bmenetrend.R
import hu.app.bmenetrend.mav.MavGoogleLoginPayload
import org.json.JSONObject

private const val CHROME_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
private const val MAV_LOGIN_URL = "https://jegy.mav.hu/bejelentkezes"

@Keep
private class MavJsBridge(
    private val onLoginSuccess: (payload: MavGoogleLoginPayload) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onGoogleLoginCompleted(payloadJson: String?) {
        if (payloadJson.isNullOrBlank()) return
        runCatching {
            val obj = JSONObject(payloadJson)
            val email = obj.optString("email", "")
            val tokenXml = obj.optString("tokenXml", "")
            val sessionId = obj.optString("sessionId", "")
            val personalData = obj.optString("personalData", "").ifBlank { null }
            val hptData = obj.optString("hptData", "").ifBlank { null }
            val purchasesData = obj.optString("purchasesData", "").ifBlank { null }

            if (tokenXml.isNotBlank()) {
                handler.post {
                    onLoginSuccess(
                        MavGoogleLoginPayload(
                            email = email,
                            tokenXml = tokenXml,
                            sessionId = sessionId,
                            personalDataJson = personalData,
                            hptDataJson = hptData,
                            purchasesJson = purchasesData
                        )
                    )
                }
            }
        }
    }

    @JavascriptInterface
    fun onSessionExtracted(email: String?, tokenXml: String?, sessionId: String?) {
        var finalEmail = email.orEmpty()
        val token = tokenXml.orEmpty()
        val sId = sessionId.orEmpty()
        if (finalEmail.isBlank() && token.isNotBlank()) {
            val emailRegex = Regex("""<[a-zA-Z0-9:]*NameIdentifier[^>]*>([^<]+)<""")
            val match = emailRegex.find(token)
            if (match != null) {
                finalEmail = match.groupValues[1].trim()
            }
        }
        if (token.isNotBlank()) {
            handler.post {
                onLoginSuccess(
                    MavGoogleLoginPayload(
                        email = finalEmail,
                        tokenXml = token,
                        sessionId = sId
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MavGoogleLoginDialog(
    onDismiss: () -> Unit,
    onSuccess: (payload: MavGoogleLoginPayload) -> Unit
) {
    var progress by remember { mutableStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.mav_google_login_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.mav_cancel))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                if (isLoading) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            createMavWebViewContainer(
                                context = context,
                                onProgress = { p, loading ->
                                    progress = p
                                    isLoading = loading
                                },
                                onSuccess = onSuccess
                            )
                        }
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createMavWebViewContainer(
    context: Context,
    onProgress: (progress: Int, loading: Boolean) -> Unit,
    onSuccess: (payload: MavGoogleLoginPayload) -> Unit
): FrameLayout {
    val container = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    val mainWebView = WebView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    val handler = Handler(Looper.getMainLooper())
    var completed = false

    fun handleSuccess(payload: MavGoogleLoginPayload) {
        if (!completed) {
            completed = true
            onSuccess(payload)
        }
    }

    mainWebView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        userAgentString = CHROME_USER_AGENT
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true)
    }

    mainWebView.addJavascriptInterface(
        MavJsBridge { payload ->
            handleSuccess(payload)
        },
        "MavBridge"
    )

    fun injectSessionWatcher(view: WebView?) {
        val script = """
            (async function() {
                if (window.__mavWatcherActive) return;
                window.__mavWatcherActive = true;
                var finished = false;

                async function check() {
                    if (finished) return true;
                    try {
                        var raw = sessionStorage.getItem("userData");
                        if (!raw) return false;
                        var d = JSON.parse(raw);
                        if (!d || !d.userTokenXml) return false;

                        finished = true;
                        var em = d.userEmail || "";
                        var tokenXml = d.userTokenXml || "";
                        var sessionId = sessionStorage.getItem("sessionId") || d.userSessionId || "";

                        var pData = "";
                        var hData = "";
                        var purData = "";

                        try {
                            var results = await Promise.allSettled([
                                fetch("/api/ProfileApi/GetUserPersonalDatas", {
                                    method: "POST",
                                    headers: {
                                        "Content-Type": "application/json",
                                        "UserSessionId": sessionId,
                                        "UserTokenXml": tokenXml,
                                        "Language": "hu"
                                    },
                                    body: JSON.stringify({ userEmail: em })
                                }).then(function(r) { return r.ok ? r.text() : ""; }),

                                fetch("/api/ProfileApi/GetUserHPTDatas", {
                                    method: "POST",
                                    headers: {
                                        "Content-Type": "application/json",
                                        "UserSessionId": sessionId,
                                        "UserTokenXml": tokenXml,
                                        "Language": "hu"
                                    },
                                    body: JSON.stringify({ userEmail: em })
                                }).then(function(r) { return r.ok ? r.text() : ""; }),

                                fetch("/api/ProfileApi/GetPreviousPurchases", {
                                    method: "POST",
                                    headers: {
                                        "Content-Type": "application/json",
                                        "UserSessionId": sessionId,
                                        "UserTokenXml": tokenXml,
                                        "Language": "hu"
                                    },
                                    body: JSON.stringify({ userEmail: em })
                                }).then(function(r) { return r.ok ? r.text() : ""; })
                            ]);

                            if (results[0] && results[0].status === "fulfilled") pData = results[0].value || "";
                            if (results[1] && results[1].status === "fulfilled") hData = results[1].value || "";
                            if (results[2] && results[2].status === "fulfilled") purData = results[2].value || "";
                        } catch(err) {}

                        var payload = JSON.stringify({
                            email: em,
                            tokenXml: tokenXml,
                            sessionId: sessionId,
                            personalData: pData,
                            hptData: hData,
                            purchasesData: purData
                        });

                        window.MavBridge.onGoogleLoginCompleted(payload);
                        return true;
                    } catch(e) {
                        finished = false;
                    }
                    return false;
                }

                if (!await check()) {
                    var itv = setInterval(async function() {
                        if (await check()) clearInterval(itv);
                    }, 700);
                }
            })();
        """.trimIndent()
        view?.evaluateJavascript(script, null)
    }

    // Periodic evaluation fallback
    val checkRunnable = object : Runnable {
        override fun run() {
            if (!completed) {
                injectSessionWatcher(mainWebView)
                handler.postDelayed(this, 1200)
            }
        }
    }
    handler.postDelayed(checkRunnable, 1000)

    mainWebView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            onProgress(100, false)
            injectSessionWatcher(view)
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            return false
        }
    }

    mainWebView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            onProgress(newProgress, newProgress < 100)
            if (newProgress > 60) {
                injectSessionWatcher(view)
            }
        }

        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            val popupWebView = WebView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = CHROME_USER_AGENT
            }

            popupWebView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectSessionWatcher(mainWebView)
                }
            }

            popupWebView.webChromeClient = object : WebChromeClient() {
                override fun onCloseWindow(window: WebView?) {
                    container.removeView(window)
                    window?.destroy()
                    injectSessionWatcher(mainWebView)
                }
            }

            container.addView(popupWebView)
            val transport = resultMsg?.obj as? WebView.WebViewTransport
            transport?.webView = popupWebView
            resultMsg?.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView?) {
            container.removeView(window)
            window?.destroy()
        }
    }

    container.addView(mainWebView)
    mainWebView.loadUrl(MAV_LOGIN_URL)

    return container
}
