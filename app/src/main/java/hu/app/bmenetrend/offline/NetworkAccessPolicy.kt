package hu.app.bmenetrend.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap

/** Initialized by Application before any repository, worker or map can issue a request. */
object NetworkAccessPolicy {
    private val mutable = MutableStateFlow(false)
    val offline = mutable.asStateFlow()
    private val clients = Collections.newSetFromMap(WeakHashMap<OkHttpClient, Boolean>())
    @Volatile private var epoch = 0L
    val generation: Long get() = epoch
    private var manualOffline = false
    private var packAvailable = false
    private var internetAvailable = true
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun initialize(context: Context) {
        val requested = context.getSharedPreferences("offline", 0).getBoolean("enabled", false)
        // An interrupted deletion/install cannot leave a nonfunctional mode enabled.
        val installed = OfflinePackStore(context).metadata() != null
        synchronized(this) {
            manualOffline = requested && installed
            packAvailable = installed
            internetAvailable = true
            publishMode()
        }
        if (requested && !installed) context.getSharedPreferences("offline", 0).edit().putBoolean("enabled", false).apply()
    }

    @Synchronized fun setOffline(context: Context, enabled: Boolean) {
        context.getSharedPreferences("offline", 0).edit().putBoolean("enabled", enabled).commit()
        manualOffline = enabled
        publishMode()
    }

    /** Application-lifetime observer: Wi-Fi, cellular and VPN are usable when validated. */
    @Synchronized fun monitorConnectivity(context: Context) {
        if (callback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val observer = object : ConnectivityManager.NetworkCallback() {
            private fun refresh() {
                val caps = manager.getNetworkCapabilities(manager.activeNetwork)
                onConnectivityChanged(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
        }
        manager.registerDefaultNetworkCallback(observer)
        callback = observer
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        onConnectivityChanged(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
    }

    @Synchronized internal fun onConnectivityChanged(available: Boolean) {
        internetAvailable = available
        publishMode()
    }

    /** Called after pack activation/deletion; never does database or file work. */
    @Synchronized internal fun onPackAvailabilityChanged(available: Boolean) {
        packAvailable = available
        if (!available) manualOffline = false
        publishMode()
    }

    private fun publishMode() {
        val enabled = manualOffline || (!internetAvailable && packAvailable)
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
}
