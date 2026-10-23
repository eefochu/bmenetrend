package hu.viabusz.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class EmmaFeedClientTest {
    private val empty = """{"data":{"vehicles":[]}}"""
    private fun client(handler: (okhttp3.Request) -> String) = OkHttpClient.Builder().addInterceptor { chain ->
        val body = handler(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody()).build()
    }.build()

    @Test fun concurrentPollsShareOneNationwideRequestAndOneSecondCache() = runBlocking {
        val calls = AtomicInteger()
        var now = Instant.parse("2026-10-22T18:33:28Z")
        val feed = EmmaFeedClient(client { request ->
            calls.incrementAndGet()
            assertEquals(EmmaParser.ENDPOINT, request.url.toString())
            assertEquals("application/json", request.header("Accept"))
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            assertTrue(buffer.readUtf8().contains("SUBWAY"))
            empty
        }, { now })
        val jobs = List(3) { async { feed.get() } }
        jobs.forEach { assertFalse(it.await().stale) }
        assertEquals(1, calls.get())
        now = now.plusMillis(1100)
        feed.get()
        assertEquals(2, calls.get())
        feed.get(force = true)
        assertEquals(3, calls.get())
    }
    @Test fun sourceFailureRetriesAndPreservesOriginalFetchTimeOnlyForTwoMinutes() = runBlocking {
        val calls = AtomicInteger()
        var fail = false
        var now = Instant.parse("2026-10-22T18:33:28Z")
        val first = now
        val feed = EmmaFeedClient(client {
            calls.incrementAndGet()
            if (fail) throw IOException("source offline")
            empty
        }, { now })
        feed.get(); fail = true; now = now.plusSeconds(10)
        val stale = feed.get()
        assertTrue(stale.stale)
        assertEquals(first, stale.fetchedAt)
        assertEquals(3, calls.get())
        now = first.plusSeconds(121)
        try { feed.get(); fail("Expired feed must fail") } catch (_: IOException) { }
        assertEquals(5, calls.get())
    }
    @Test fun graphqlErrorCannotOverwriteSuccessfulSnapshot() = runBlocking {
        var fail = false
        var now = Instant.parse("2026-10-22T18:33:28Z")
        val feed = EmmaFeedClient(client { if (fail) """{"errors":[{"message":"partial"}],"data":{"vehicles":[]}}""" else empty }, { now })
        val fresh = feed.get()
        now = now.plusSeconds(2); fail = true
        val stale = feed.get()
        assertTrue(stale.stale)
        assertEquals(fresh.fetchedAt, stale.fetchedAt)
    }
    @Test fun cancellingPollCancelsUnderlyingHttpCall() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val active = java.util.concurrent.atomic.AtomicReference<okhttp3.Call>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            active.set(chain.call()); entered.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(empty.toResponseBody()).build()
        }.build()
        val job = async(kotlinx.coroutines.Dispatchers.Default) { EmmaFeedClient(http).get() }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            job.cancel()
            job.join()
            assertTrue(active.get().isCanceled())
        } finally { release.countDown(); job.cancel() }
    }

    @Test fun deniedPollDoesNotRetryImmediatelyOrBypassCooldownOnManualRefresh() = runBlocking {
        val calls = AtomicInteger()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                .body("denied".toResponseBody()).build()
        }.build()
        val feed = EmmaFeedClient(http, backoff = EmmaRequestBackoff())
        try { feed.get(); fail("Denied source must fail") } catch (_: IOException) { }
        try { feed.get(force = true); fail("Manual refresh must respect source cooldown") } catch (_: IOException) { }
        assertEquals(1, calls.get())
    }

}
