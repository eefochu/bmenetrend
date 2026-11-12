package hu.app.bmenetrend.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NetworkAccessPolicyTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: OfflinePackStore

    @Before
    fun setup() {
        store = OfflinePackStore(context)
        store.delete()
        NetworkAccessPolicy.setOffline(context, false)
        NetworkAccessPolicy.onConnectivityChanged(true)
    }

    @After
    fun reset() {
        NetworkAccessPolicy.setOffline(context, false)
        store.delete()
        NetworkAccessPolicy.onConnectivityChanged(true)
    }

    private fun installDummyPack(): OfflinePackMetadata {
        val staging = store.staging()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(staging, null).use { db ->
            db.version = OFFLINE_FORMAT
            db.execSQL("CREATE TABLE stops (stop_id TEXT)")
            db.execSQL("INSERT INTO stops VALUES ('1')")
            db.execSQL("CREATE TABLE trips (trip_id TEXT)")
            db.execSQL("INSERT INTO trips VALUES ('1')")
        }
        val meta = OfflinePackMetadata(
            id = "test-pack",
            builtAt = Instant.now(),
            validFrom = LocalDate.of(2026, 1, 1),
            validUntil = LocalDate.of(2026, 12, 31),
            installedBytes = staging.length(),
            downloadBytes = 1000,
            stops = 1,
            trips = 1,
            sources = listOf("Test"),
            sha256 = OfflinePackStore.digest(staging)
        )
        store.activate(staging, meta)
        return meta
    }

    @Test
    fun explicitOfflineModeBlocksBeforeAnyConnectionIsMade() {
        ServerSocket(0).use { server ->
            server.soTimeout = 150
            val client = NetworkAccessPolicy.protect(OkHttpClient())
            NetworkAccessPolicy.setOffline(context, true)
            try {
                client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()).execute()
                fail()
            } catch (error: IOException) {
                assertEquals("Offline mode", error.message)
            }
            try {
                server.accept().close()
                fail("Offline mode opened a socket")
            } catch (_: SocketTimeoutException) { }
        }
    }

    @Test
    fun restoringOfflinePreferenceWithoutPackRecoversToOnline() {
        store.delete()
        NetworkAccessPolicy.setOffline(context, true)
        NetworkAccessPolicy.initialize(context)
        assertFalse(NetworkAccessPolicy.offline.value)
        assertFalse(context.getSharedPreferences("offline", 0).getBoolean("enabled", true))
    }

    @Test
    fun modeGenerationChangesOnlyOnActualTransitions() {
        NetworkAccessPolicy.setOffline(context, false)
        val before = NetworkAccessPolicy.generation
        NetworkAccessPolicy.setOffline(context, false)
        assertEquals(before, NetworkAccessPolicy.generation)
        NetworkAccessPolicy.setOffline(context, true)
        assertTrue(NetworkAccessPolicy.generation > before)

        // Automatic transition also updates generation
        NetworkAccessPolicy.setOffline(context, false)
        installDummyPack()
        val afterManualOff = NetworkAccessPolicy.generation
        NetworkAccessPolicy.onConnectivityChanged(false)
        assertTrue(NetworkAccessPolicy.generation > afterManualOff)
        // Redundant connectivity loss does not advance generation
        val afterAutoOff = NetworkAccessPolicy.generation
        NetworkAccessPolicy.onConnectivityChanged(false)
        assertEquals(afterAutoOff, NetworkAccessPolicy.generation)
    }

    @Test
    fun networkLossTransitionsToOfflineWhenPackInstalled() {
        installDummyPack()
        NetworkAccessPolicy.onConnectivityChanged(true)
        assertFalse(NetworkAccessPolicy.offline.value)

        NetworkAccessPolicy.onConnectivityChanged(false)
        assertTrue(NetworkAccessPolicy.offline.value)
    }

    @Test
    fun recoveryTransitionsToOnlineWhenPackInstalled() {
        installDummyPack()
        NetworkAccessPolicy.onConnectivityChanged(false)
        assertTrue(NetworkAccessPolicy.offline.value)

        NetworkAccessPolicy.onConnectivityChanged(true)
        assertFalse(NetworkAccessPolicy.offline.value)
    }

    @Test
    fun manualOfflineSurvivesNetworkLossAndRecovery() {
        installDummyPack()
        NetworkAccessPolicy.setOffline(context, true)
        assertTrue(NetworkAccessPolicy.offline.value)

        // Network loss keeps it offline
        NetworkAccessPolicy.onConnectivityChanged(false)
        assertTrue(NetworkAccessPolicy.offline.value)

        // Network recovery keeps manual offline enabled
        NetworkAccessPolicy.onConnectivityChanged(true)
        assertTrue(NetworkAccessPolicy.offline.value)
        assertTrue(context.getSharedPreferences("offline", 0).getBoolean("enabled", false))
    }

    @Test
    fun automaticModeDoesNotPersistEnabledPreference() {
        installDummyPack()
        NetworkAccessPolicy.setOffline(context, false)
        assertFalse(context.getSharedPreferences("offline", 0).getBoolean("enabled", false))

        // Trigger automatic fallback via connection loss
        NetworkAccessPolicy.onConnectivityChanged(false)
        assertTrue(NetworkAccessPolicy.offline.value)

        // Preference in SharedPreferences must remain FALSE
        assertFalse(context.getSharedPreferences("offline", 0).getBoolean("enabled", false))
    }

    @Test
    fun missingPackEntersDisconnectedModeUntilInternetReturns() {
        store.delete()
        assertNull(store.metadata())
        NetworkAccessPolicy.initialize(context)
        NetworkAccessPolicy.onConnectivityChanged(false)

        // Lack of validated internet activates offline mode even without an installed pack
        assertTrue(NetworkAccessPolicy.offline.value)

        // Automatic mode returns online on internet recovery
        NetworkAccessPolicy.onConnectivityChanged(true)
        assertFalse(NetworkAccessPolicy.offline.value)
    }

    @Test
    fun packChangesDoNotOverrideMissingInternet() {
        store.delete()
        NetworkAccessPolicy.initialize(context)
        NetworkAccessPolicy.onConnectivityChanged(false)
        assertTrue(NetworkAccessPolicy.offline.value)

        // Activating a pack while disconnected maintains offline mode
        installDummyPack()
        assertTrue(NetworkAccessPolicy.offline.value)

        // Deleting the pack while disconnected must not pretend connectivity is restored
        store.delete()
        assertTrue(NetworkAccessPolicy.offline.value)

        // Automatic mode returns online on internet recovery
        NetworkAccessPolicy.onConnectivityChanged(true)
        assertFalse(NetworkAccessPolicy.offline.value)
    }

    @Test
    fun clearingManualPreferenceDuringNoInternetStaysOfflineUntilRecovery() {
        NetworkAccessPolicy.setOffline(context, true)
        assertTrue(NetworkAccessPolicy.offline.value)
        assertTrue(context.getSharedPreferences("offline", 0).getBoolean("enabled", false))

        NetworkAccessPolicy.onConnectivityChanged(false)
        assertTrue(NetworkAccessPolicy.offline.value)

        // Clearing manual preference while disconnected must not enable network
        NetworkAccessPolicy.setOffline(context, false)
        assertFalse(context.getSharedPreferences("offline", 0).getBoolean("enabled", false))
        assertTrue(NetworkAccessPolicy.offline.value)

        // Automatic mode returns online on internet recovery
        NetworkAccessPolicy.onConnectivityChanged(true)
        assertFalse(NetworkAccessPolicy.offline.value)
        assertFalse(context.getSharedPreferences("offline", 0).getBoolean("enabled", false))
    }

    @Test
    fun clearingAutomaticOfflineFallbackOverridesConnectivityWithoutPersistingPreference() {
        NetworkAccessPolicy.onConnectivityChanged(false)
        assertTrue(NetworkAccessPolicy.offline.value)

        NetworkAccessPolicy.setOffline(context, false)

        assertFalse(NetworkAccessPolicy.offline.value)
        assertFalse(context.getSharedPreferences("offline", 0).getBoolean("enabled", true))
    }

    @Test
    fun automaticOfflineOverrideSurvivesRepeatedProbeUntilConnectivityChanges() {
        NetworkAccessPolicy.onConnectivityChanged(false)
        NetworkAccessPolicy.setOffline(context, false)
        assertFalse(NetworkAccessPolicy.offline.value)

        NetworkAccessPolicy.onConnectivityChanged(false)
        assertFalse(NetworkAccessPolicy.offline.value)
        NetworkAccessPolicy.onConnectivityChanged(true)
        assertFalse(NetworkAccessPolicy.offline.value)
        NetworkAccessPolicy.onConnectivityChanged(false)
        assertTrue(NetworkAccessPolicy.offline.value)
    }

    @Test
    fun networkGoneEndsAutomaticOfflineOverride() {
        NetworkAccessPolicy.onConnectivityChanged(false)
        NetworkAccessPolicy.setOffline(context, false)
        assertFalse(NetworkAccessPolicy.offline.value)

        NetworkAccessPolicy.onConnectivityChanged(false, networkGone = true)

        assertTrue(NetworkAccessPolicy.offline.value)
    }

    @Test
    fun manualOfflineClearsOverrideAndReturnsToAutomaticDetectionWhenDisabled() {
        NetworkAccessPolicy.onConnectivityChanged(false)
        NetworkAccessPolicy.setOffline(context, false)
        assertFalse(NetworkAccessPolicy.offline.value)

        NetworkAccessPolicy.setOffline(context, true)
        assertTrue(NetworkAccessPolicy.offline.value)
        NetworkAccessPolicy.setOffline(context, false)

        assertTrue(NetworkAccessPolicy.offline.value)
        assertFalse(context.getSharedPreferences("offline", 0).getBoolean("enabled", true))
    }

    @Test
    fun requestGuardBlocksWhenInAutomaticOfflineMode() {
        installDummyPack()
        NetworkAccessPolicy.onConnectivityChanged(false)
        assertTrue(NetworkAccessPolicy.offline.value)

        // requireOnline must throw
        var thrown = false
        try {
            NetworkAccessPolicy.requireOnline()
        } catch (e: IOException) {
            assertEquals("Offline mode", e.message)
            thrown = true
        }
        assertTrue("requireOnline should have thrown IOException", thrown)

        // Restoring network allows requireOnline to pass
        NetworkAccessPolicy.onConnectivityChanged(true)
        assertFalse(NetworkAccessPolicy.offline.value)
        NetworkAccessPolicy.requireOnline() // does not throw
    }

    @Test
    fun initialDisconnectedStartupWithPack() {
        installDummyPack()
        // SharedPreferences manual offline is false
        context.getSharedPreferences("offline", 0).edit().putBoolean("enabled", false).commit()

        // Cold start initialization
        NetworkAccessPolicy.initialize(context)
        // Before connectivity check completes, internetAvailable is assumed true
        assertFalse(NetworkAccessPolicy.offline.value)

        // Initial connectivity check determines no validated internet
        NetworkAccessPolicy.onConnectivityChanged(false)
        assertTrue(NetworkAccessPolicy.offline.value)
        assertFalse(context.getSharedPreferences("offline", 0).getBoolean("enabled", false))
    }

    @Test
    fun connectivityManagerCallbackDispatchesToPolicy() {
        installDummyPack()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        assertNotNull(manager)

        val callbackField = NetworkAccessPolicy::class.java.getDeclaredField("callback").apply { isAccessible = true }
        val prevCallback = callbackField.get(NetworkAccessPolicy)
        callbackField.set(NetworkAccessPolicy, null)
        try {
            NetworkAccessPolicy.monitorConnectivity(context) { false }
            val observer = callbackField.get(NetworkAccessPolicy) as ConnectivityManager.NetworkCallback
            assertNotNull(observer)

            val network = ShadowNetwork.newInstance(100)
            val shadow = Shadows.shadowOf(manager)
            shadow.clearAllNetworks()
            observer.onLost(network)
            assertTrue("Should switch to offline when callback detects lost active network", NetworkAccessPolicy.offline.value)

            // Direct policy seam test for recovery
            NetworkAccessPolicy.onConnectivityChanged(true)
            assertFalse(NetworkAccessPolicy.offline.value)
        } finally {
            (callbackField.get(NetworkAccessPolicy) as? ConnectivityManager.NetworkCallback)?.let {
                it.onUnavailable()
                manager.unregisterNetworkCallback(it)
            }
            callbackField.set(NetworkAccessPolicy, prevCallback)
        }
    }

    @Test fun captiveNetworkAndDefaultNetworkHandoverUseCallbackCapabilities() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val field = NetworkAccessPolicy::class.java.getDeclaredField("callback").apply { isAccessible = true }
        val previous = field.get(NetworkAccessPolicy)
        field.set(NetworkAccessPolicy, null)
        try {
            NetworkAccessPolicy.monitorConnectivity(context) { false }
            val callback = field.get(NetworkAccessPolicy) as ConnectivityManager.NetworkCallback
            val wifi = ShadowNetwork.newInstance(100)
            val mobile = ShadowNetwork.newInstance(101)
            val captive = ShadowNetworkCapabilities.newInstance().also {
                Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            val validated = ShadowNetworkCapabilities.newInstance().also {
                Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            callback.onAvailable(wifi)
            callback.onCapabilitiesChanged(wifi, captive)
            assertTrue("Connected Wi-Fi without internet must use offline mode", NetworkAccessPolicy.offline.value)
            callback.onAvailable(mobile)
            callback.onCapabilitiesChanged(mobile, validated)
            assertFalse("Validated mobile data must restore online mode", NetworkAccessPolicy.offline.value)
            callback.onLost(wifi)
            assertFalse("An old network loss cannot override the new default network", NetworkAccessPolicy.offline.value)
            callback.onLost(mobile)
            assertTrue(NetworkAccessPolicy.offline.value)
        } finally {
            (field.get(NetworkAccessPolicy) as? ConnectivityManager.NetworkCallback)?.let {
                it.onUnavailable()
                manager.unregisterNetworkCallback(it)
            }
            field.set(NetworkAccessPolicy, previous)
        }
    }

    @Test fun verifiedProviderRecoversUnvalidatedInternetButManualModeSuppressesProbes() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        Shadows.shadowOf(manager).clearAllNetworks()
        val field = NetworkAccessPolicy::class.java.getDeclaredField("callback").apply { isAccessible = true }
        val previous = field.get(NetworkAccessPolicy)
        field.set(NetworkAccessPolicy, null)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val verified = kotlinx.coroutines.CompletableDeferred<Boolean>()
        NetworkAccessPolicy.setOffline(context, true)
        try {
            NetworkAccessPolicy.monitorConnectivity(context) {
                attempts.incrementAndGet()
                verified.await()
            }
            val observer = field.get(NetworkAccessPolicy) as ConnectivityManager.NetworkCallback
            val network = ShadowNetwork.newInstance(103)
            val capabilities = ShadowNetworkCapabilities.newInstance().also {
                Shadows.shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            observer.onAvailable(network)
            observer.onCapabilitiesChanged(network, capabilities)
            assertEquals("Manual offline must not start an HTTP health check", 0, attempts.get())
            NetworkAccessPolicy.setOffline(context, false)
            fun awaitCondition(condition: () -> Boolean) {
                val deadline = System.nanoTime() + 3_000_000_000L
                while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
                assertTrue(condition())
            }
            awaitCondition { attempts.get() == 1 }
            assertTrue(NetworkAccessPolicy.offline.value)
            verified.complete(true)
            awaitCondition { !NetworkAccessPolicy.offline.value }
            NetworkAccessPolicy.setOffline(context, true)
            assertTrue(NetworkAccessPolicy.offline.value)
            assertEquals(1, attempts.get())
            observer.onLost(network)
            NetworkAccessPolicy.setOffline(context, false)
            assertTrue("No network must override an old successful probe", NetworkAccessPolicy.offline.value)
        } finally {
            (field.get(NetworkAccessPolicy) as? ConnectivityManager.NetworkCallback)?.let {
                it.onUnavailable()
                manager.unregisterNetworkCallback(it)
            }
            field.set(NetworkAccessPolicy, previous)
        }
    }
}
