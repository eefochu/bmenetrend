package hu.app.bmenetrend.debug

import hu.app.bmenetrend.offline.NetworkAccessPolicy
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PacketTracerTest {

    private var previousEnabled: Boolean = false

    @Before
    fun setUp() {
        previousEnabled = PacketTracer.enabled
        PacketTracer.enabled = true
        PacketTracer.clear()
    }

    @After
    fun tearDown() {
        PacketTracer.clear()
        PacketTracer.enabled = previousEnabled
    }

    @Test
    fun testGetRequestTracing() {
        val mockHandler = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", "application/json")
                .body("{\"status\":\"ok\"}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(PacketTracer.createInterceptor())
            .addInterceptor(mockHandler)
            .build()

        val request = Request.Builder()
            .url("https://example.com/api/v1/test?param=123")
            .header("X-Custom-Header", "HelloTracer")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string()
        assertEquals("{\"status\":\"ok\"}", body)

        val packets = PacketTracer.packets.value
        assertEquals(1, packets.size)

        val packet = packets[0]
        assertEquals("GET", packet.method)
        assertEquals("https://example.com/api/v1/test?param=123", packet.url)
        assertEquals(200, packet.statusCode)
        assertTrue(packet.isSuccessful)
        assertNull(packet.error)
        assertTrue(packet.requestHeaders.any { it.name.equals("X-Custom-Header", ignoreCase = true) && it.value == "HelloTracer" })
        assertEquals("{\"status\":\"ok\"}", packet.responseBody)
        assertNull(packet.requestBody)
    }

    @Test
    fun testPostRequestTracingWithPayload() {
        val postJson = "{\"name\":\"test_passenger\",\"id\":42}"
        val mockHandler = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(201)
                .message("Created")
                .header("Content-Type", "application/json")
                .body("{\"created\":true}".toResponseBody("application/json".toMediaType()))
                .build()
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(PacketTracer.createInterceptor())
            .addInterceptor(mockHandler)
            .build()

        val request = Request.Builder()
            .url("https://jegy-a.mav.hu/IK_API_PROD/api/ProfileApi/GetUserPersonalDatas")
            .header("Content-Type", "application/json")
            .post(postJson.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        response.body?.string()

        val packets = PacketTracer.packets.value
        assertEquals(1, packets.size)

        val packet = packets[0]
        assertEquals("POST", packet.method)
        assertEquals(201, packet.statusCode)
        assertTrue(packet.isSuccessful)
        assertEquals(postJson, packet.requestBody)
        assertEquals("{\"created\":true}", packet.responseBody)
    }

    @Test
    fun testFailedHttpRequestTracing() {
        val mockHandler = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Internal Server Error")
                .header("Content-Type", "text/plain")
                .body("Internal Server Error".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(PacketTracer.createInterceptor())
            .addInterceptor(mockHandler)
            .build()

        val request = Request.Builder()
            .url("https://bmenetrend-bkk-proxy.bmenetrend-backend.workers.dev/error")
            .get()
            .build()

        val response = client.newCall(request).execute()
        response.body?.string()

        val packets = PacketTracer.packets.value
        assertEquals(1, packets.size)

        val packet = packets[0]
        assertEquals(500, packet.statusCode)
        assertFalse(packet.isSuccessful)
        assertEquals("Internal Server Error", packet.responseBody)
    }

    @Test
    fun testNetworkAccessPolicyIntegration() {
        val mockHandler = Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("bkk_worker_response".toResponseBody("text/plain".toMediaType()))
                .build()
        }

        val baseClient = NetworkAccessPolicy.protect(OkHttpClient())
        val client = baseClient.newBuilder()
            .addInterceptor(mockHandler)
            .build()
        val request = Request.Builder()
            .url("https://bmenetrend-bkk-proxy.bmenetrend-backend.workers.dev/bkk/arrivals")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val text = response.body?.string()
        assertEquals("bkk_worker_response", text)

        val packets = PacketTracer.packets.value
        assertEquals(1, packets.size)
        val packet = packets[0]
        assertTrue(packet.url.contains("/bkk/arrivals"))
        assertEquals(200, packet.statusCode)
        assertTrue(packet.isSuccessful)
    }
}
