package hu.app.bmenetrend.offline

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ServerSocket
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NetworkAccessPolicyTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @After fun reset() { NetworkAccessPolicy.setOffline(context, false); OfflinePackStore(context).delete() }
    @Test fun explicitOfflineModeBlocksBeforeAnyConnectionIsMade() {
        ServerSocket(0).use { server ->
            server.soTimeout = 150
            val client = NetworkAccessPolicy.protect(OkHttpClient())
            NetworkAccessPolicy.setOffline(context, true)
            try { client.newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build()).execute(); fail() }
            catch (error: IOException) { assertEquals("Offline mode", error.message) }
            try { server.accept().close(); fail("Offline mode opened a socket") } catch (_: SocketTimeoutException) { }
        }
    }
    @Test fun restoringOfflinePreferenceWithoutPackRecoversToOnline() {
        OfflinePackStore(context).delete()
        NetworkAccessPolicy.setOffline(context, true)
        NetworkAccessPolicy.initialize(context)
        assertFalse(NetworkAccessPolicy.offline.value)
        assertFalse(context.getSharedPreferences("offline", 0).getBoolean("enabled", true))
    }
    @Test fun modeGenerationChangesOnlyOnActualTransitions() {
        NetworkAccessPolicy.setOffline(context, false)
        val before = NetworkAccessPolicy.generation
        NetworkAccessPolicy.setOffline(context, false)
        assertEquals(before, NetworkAccessPolicy.generation)
        NetworkAccessPolicy.setOffline(context, true)
        assertTrue(NetworkAccessPolicy.generation > before)
    }
}
