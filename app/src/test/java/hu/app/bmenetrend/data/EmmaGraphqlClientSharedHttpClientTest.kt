package hu.app.bmenetrend.data

import hu.app.bmenetrend.offline.NetworkAccessPolicy
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * [EmmaGraphqlClient] now exposes one lazily created process-wide OkHttp client so repositories,
 * the feed client and MapLibre share connections, TLS sessions and dispatcher threads.
 *
 * These tests pin that sharing and the `newBuilder()` escape hatch used by
 * [hu.app.bmenetrend.PlannerViewModel]: a derived client may change timeouts but must keep the
 * connection pool, dispatcher and cache of the shared client.
 */
class EmmaGraphqlClientSharedHttpClientTest {

    @Test
    fun defaultHttpClientIsASingleProcessWideInstance() {
        assertSame(EmmaGraphqlClient.defaultHttpClient(), EmmaGraphqlClient.defaultHttpClient())
    }

    @Test
    fun clientDefaultConstructorUsesTheSharedInstance() {
        val first = EmmaGraphqlClient()
        val second = EmmaGraphqlClient()
        assertSame(sharedClientOf(first), sharedClientOf(second))
        assertSame(EmmaGraphqlClient.defaultHttpClient(), sharedClientOf(first))
    }

    @Test
    fun derivedClientKeepsSharedConnectionPoolAndDispatcher() {
        val shared = EmmaGraphqlClient.defaultHttpClient()
        val derived = shared.newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()

        assertNotSame("newBuilder must produce a distinct configuration object", shared, derived)
        assertSame("connection pool must be reused", shared.connectionPool, derived.connectionPool)
        assertSame("dispatcher threads must be reused", shared.dispatcher, derived.dispatcher)
        assertSame("cache must be reused", shared.cache, derived.cache)
        assertSame(shared.authenticator, derived.authenticator)
        assertSame(shared.cookieJar, derived.cookieJar)
        assertSame(shared.dns, derived.dns)
        assertTrue(derived.connectTimeoutMillis < shared.connectTimeoutMillis)
    }

    @Test
    fun protectingTheSharedClientIsIdempotentAndKeepsPools() {
        val shared = EmmaGraphqlClient.defaultHttpClient()
        // EmmaGraphqlClient.execute() calls protect() on every request; it must not clone the
        // client (and therefore its pools) once the offline guard is already installed.
        assertSame(shared, NetworkAccessPolicy.protect(shared))
        assertSame(shared, NetworkAccessPolicy.protect(NetworkAccessPolicy.protect(shared)))
        assertSame(shared.connectionPool, NetworkAccessPolicy.protect(shared).connectionPool)
    }

    private fun sharedClientOf(client: EmmaGraphqlClient): okhttp3.OkHttpClient {
        val field = EmmaGraphqlClient::class.java.getDeclaredField("client")
        field.isAccessible = true
        return field.get(client) as okhttp3.OkHttpClient
    }
}
