package hu.app.bmenetrend.debug

import hu.app.bmenetrend.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

data class HttpHeader(
    val name: String,
    val value: String
)

data class HttpPacket(
    val id: Long,
    val timestamp: Instant,
    val method: String,
    val url: String,
    val durationMs: Long,
    val requestHeaders: List<HttpHeader>,
    val requestBody: String?,
    val statusCode: Int,
    val statusMessage: String,
    val responseHeaders: List<HttpHeader>,
    val responseBody: String?,
    val isSuccessful: Boolean,
    val error: String? = null
)

object PacketTracer {
    val isDebugBuild: Boolean get() = BuildConfig.DEBUG || BuildConfig.BUILD_TYPE != "release"

    @Volatile
    var enabled: Boolean = isDebugBuild

    private val counter = AtomicLong(0)
    private const val MAX_PACKETS = 500
    private const val MAX_BODY_CHARS = 250_000

    private val packetsList = Collections.synchronizedList(mutableListOf<HttpPacket>())
    private val _packets = MutableStateFlow<List<HttpPacket>>(emptyList())
    val packets = _packets.asStateFlow()

    fun clear() {
        packetsList.clear()
        _packets.value = emptyList()
    }

    private fun addPacket(packet: HttpPacket) {
        synchronized(packetsList) {
            packetsList.add(0, packet)
            while (packetsList.size > MAX_PACKETS) {
                packetsList.removeAt(packetsList.size - 1)
            }
            _packets.value = packetsList.toList()
        }
    }

    fun createInterceptor(): Interceptor = Interceptor { chain ->
        val request = chain.request()
        val id = counter.incrementAndGet()
        val startTime = System.currentTimeMillis()
        val timestamp = Instant.now()
        val method = request.method
        val url = request.url.toString()
        val requestHeaders = extractHeaders(request.headers)
        val requestBody = readRequestBody(request)

        var response: Response? = null
        try {
            response = chain.proceed(request)
            val durationMs = System.currentTimeMillis() - startTime
            val statusCode = response.code
            val statusMessage = response.message
            val responseHeaders = extractHeaders(response.headers)
            val isSuccess = response.isSuccessful // true for 200..299

            val responseBody = readResponseBody(response)

            addPacket(
                HttpPacket(
                    id = id,
                    timestamp = timestamp,
                    method = method,
                    url = url,
                    durationMs = durationMs,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    statusCode = statusCode,
                    statusMessage = statusMessage,
                    responseHeaders = responseHeaders,
                    responseBody = responseBody,
                    isSuccessful = isSuccess
                )
            )

            response
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            addPacket(
                HttpPacket(
                    id = id,
                    timestamp = timestamp,
                    method = method,
                    url = url,
                    durationMs = durationMs,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    statusCode = 0,
                    statusMessage = "FAILED",
                    responseHeaders = emptyList(),
                    responseBody = null,
                    isSuccessful = false,
                    error = e.message ?: e.javaClass.simpleName
                )
            )
            throw e
        }
    }

    private fun extractHeaders(headers: Headers): List<HttpHeader> {
        val list = ArrayList<HttpHeader>(headers.size)
        for (i in 0 until headers.size) {
            list.add(HttpHeader(headers.name(i), headers.value(i)))
        }
        return list
    }

    private fun readRequestBody(request: Request): String? {
        val body = request.body ?: return null
        return try {
            val buffer = Buffer()
            body.writeTo(buffer)
            val contentType = body.contentType()
            val charset: Charset = contentType?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
            if (isPlaintext(buffer)) {
                val text = buffer.readString(charset)
                truncateIfNeeded(text)
            } else {
                "[binary data: ${body.contentLength()} bytes]"
            }
        } catch (e: Exception) {
            "[unable to read request body: ${e.message}]"
        }
    }

    private fun readResponseBody(response: Response): String? {
        val body = response.body ?: return null
        val source = body.source()
        return try {
            // Buffer up to 1 MB into memory for reading
            source.request(1_048_576L)
            val buffer = source.buffer.clone()
            val contentType = body.contentType()
            val charset: Charset = contentType?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
            if (isPlaintext(buffer)) {
                val text = buffer.readString(charset)
                truncateIfNeeded(text)
            } else {
                "[binary data: ${body.contentLength()} bytes]"
            }
        } catch (e: Exception) {
            "[unable to read response body: ${e.message}]"
        }
    }

    private fun truncateIfNeeded(text: String): String {
        return if (text.length > MAX_BODY_CHARS) {
            text.substring(0, MAX_BODY_CHARS) + "\n... [truncated at $MAX_BODY_CHARS characters]"
        } else {
            text
        }
    }

    private fun isPlaintext(buffer: Buffer): Boolean {
        return try {
            val prefix = Buffer()
            val byteCount = if (buffer.size < 64) buffer.size else 64
            buffer.copyTo(prefix, 0, byteCount)
            for (i in 0 until 16) {
                if (prefix.exhausted()) {
                    break
                }
                val codePoint = prefix.readUtf8CodePoint()
                if (Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)) {
                    return false
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
