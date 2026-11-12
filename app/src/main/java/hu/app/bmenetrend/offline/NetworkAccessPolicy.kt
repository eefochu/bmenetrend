package hu.app.bmenetrend.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** Initialized by Application before any repository, worker or map can issue a request. */
object NetworkAccessPolicy {
    private val mutable = MutableStateFlow(false)
    val offline = mutable.asStateFlow()
    private val clients = Collections.newSetFromMap(WeakHashMap<OkHttpClient, Boolean>())
    @Volatile private var epoch = 0L
    val generation: Long get() = epoch
    @Volatile private var manualOffline = false
    /**
     * The user switched off the automatic offline fallback while connectivity looked unusable (an
     * unvalidated network, a failed probe). Their choice wins until connectivity actually changes, so a wrong check can
     * never lock the app offline. Not persisted.
     */
    @Volatile private var forceOnline = false
    private val connectivity = MutableStateFlow(true)
    val internetAvailable = connectivity.asStateFlow()
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var refreshConnectivity: (() -> Unit)? = null
    private val connectivityScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun initialize(context: Context) {
        val requested = context.getSharedPreferences("offline", 0).getBoolean("enabled", false)
        // An interrupted deletion/install cannot leave a nonfunctional mode enabled.
        val installed = OfflinePackStore(context).metadata() != null
        synchronized(this) {
            manualOffline = requested && installed
            if (callback == null) connectivity.value = true
            publishMode()
        }
        if (requested && !installed) context.getSharedPreferences("offline", 0).edit().putBoolean("enabled", false).apply()
    }

    fun setOffline(context: Context, enabled: Boolean) {
        synchronized(this) {
            context.getSharedPreferences("offline", 0).edit().putBoolean("enabled", enabled).apply()
            // Switching off the app's own automatic fallback overrides the connectivity check; switching
            // off a manual offline mode just returns to automatic detection.
            forceOnline = !enabled && !manualOffline && !connectivity.value
            manualOffline = enabled
            publishMode()
        }
        refreshConnectivity?.invoke()
    }

    /** Application-lifetime observer: Wi-Fi, cellular and VPN are usable when validated. */
    fun monitorConnectivity(context: Context) = monitorConnectivity(context, ::verifyInternet)

    @Synchronized internal fun monitorConnectivity(context: Context, verify: suspend (Network) -> Boolean) {
        if (callback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val observer = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var current = manager.activeNetwork
            @Volatile private var internet = false
            @Volatile private var validated = false
            private val probe = AtomicReference<Job?>()

            fun recheck() {
                probe.getAndSet(null)?.cancel()
                val network = current ?: return
                if (!internet || validated || manualOffline) return
                val job = connectivityScope.launch(start = CoroutineStart.LAZY) {
                    while (isActive && current == network && internet && !validated && !manualOffline) {
                        val reachable = try { verify(network) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { false }
                        if (current == network && internet && !validated && !manualOffline) onConnectivityChanged(reachable)
                        delay(30_000)
                    }
                }
                probe.getAndSet(job)?.cancel()
                job.start()
            }

            override fun onAvailable(network: Network) {
                if (current != network) {
                    probe.getAndSet(null)?.cancel()
                    current = network
                    internet = false
                    validated = false
                }
            }
            override fun onLost(network: Network) {
                if (current == network || current == null) {
                    current = null
                    internet = false
                    probe.getAndSet(null)?.cancel()
                    onConnectivityChanged(false, networkGone = true)
                }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // Consume the callback's capabilities, rather than racing a second
                // Binder query against network teardown or Wi-Fi/cellular handover.
                if (current == network) {
                    current = network
                    val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    val isValidated = usable(caps)
                    val changed = hasInternet != internet || isValidated != validated
                    internet = hasInternet
                    validated = isValidated
                    if (isValidated || !hasInternet) onConnectivityChanged(isValidated)
                    else if (changed) onConnectivityChanged(false)
                    if (changed) recheck()
                }
            }
            override fun onUnavailable() {
                current = null
                internet = false
                probe.getAndSet(null)?.cancel()
                onConnectivityChanged(false, networkGone = true)
            }
        }
        val initial = manager.activeNetwork
        val capabilities = manager.getNetworkCapabilities(initial)
        onConnectivityChanged(usable(capabilities))
        if (initial != null && capabilities != null) observer.onCapabilitiesChanged(initial, capabilities)
        manager.registerDefaultNetworkCallback(observer)
        callback = observer
        refreshConnectivity = observer::recheck
    }

    private fun usable(caps: NetworkCapabilities?) =
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    @Synchronized internal fun onConnectivityChanged(available: Boolean, networkGone: Boolean = false) {
        // A real change (back online, a drop after being online, no network at all) ends the user's
        // override; the probe repeating "still unreachable" does not.
        if (connectivity.value != available || networkGone) forceOnline = false
        connectivity.value = available
        publishMode()
    }

    /** Called after pack activation/deletion; never does database or file work. */
    internal fun onPackAvailabilityChanged(available: Boolean) {
        val changed = synchronized(this) {
            val changed = !available && manualOffline
            if (!available) manualOffline = false
            publishMode()
            changed
        }
        if (changed) refreshConnectivity?.invoke()
    }

    private fun publishMode() {
        val enabled = manualOffline || !connectivity.value && !forceOnline
        if (mutable.value != enabled) epoch++
        val changed = mutable.value != enabled
        mutable.value = enabled
        if (enabled && changed) clients.toList().forEach { it.dispatcher.cancelAll(); it.connectionPool.evictAll() }
    }

    fun requireOnline() { if (mutable.value) throw IOException("Offline mode") }

    @Synchronized fun protect(client: OkHttpClient): OkHttpClient {
        if (client.interceptors.any { it === guard }) { clients.add(client); return client }
        return client.newBuilder().addInterceptor(guard).build().also { clients.add(it) }
    }

    private val guard = Interceptor { chain ->
        requireOnline()
        val started = epoch
        val response: Response = chain.proceed(chain.request())
        if (mutable.value || started != epoch) {
            response.close()
            throw IOException("Network mode changed")
        }
        response
    }

    /** Only for an unvalidated default network, never during manual offline mode.
     * HEAD + TLS + expected ZIP content type avoids mistaking a captive portal for
     * internet. Both providers are tried; no timetable payload is downloaded here.
     */
    private val probeClient by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(3, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).callTimeout(4, TimeUnit.SECONDS).build()
    }

    private suspend fun verifyInternet(network: Network): Boolean {
        val client = probeClient.newBuilder().socketFactory(network.socketFactory)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
            })
            .build()
        for (source in OfflineSource.defaults.asReversed()) {
            currentCoroutineContext().ensureActive()
            val reachable = suspendCancellableCoroutine { continuation ->
                val call = client.newCall(Request.Builder().url(source.url).head().build())
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resume(false) }
                    override fun onResponse(call: Call, response: Response) {
                        val usable = response.use { it.code in 200..499 }
                        if (continuation.isActive) continuation.resume(usable)
                    }
                })
            }
            if (reachable) return true
        }
        return false
    }
}
