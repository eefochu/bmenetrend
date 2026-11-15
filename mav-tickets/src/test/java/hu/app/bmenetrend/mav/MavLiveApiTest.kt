package hu.app.bmenetrend.mav

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Live end-to-end check against the real MÁV backends. Skipped unless MAV_LIVE_EMAIL and
 * MAV_LIVE_PASSWORD are set, so it never runs in normal builds. Every HTTP exchange is appended
 * to MAV_LIVE_LOG (if set) so the request waterfall and its timing can be inspected.
 */
class MavLiveApiTest {

    private val email = System.getenv("MAV_LIVE_EMAIL").orEmpty()
    private val password = System.getenv("MAV_LIVE_PASSWORD").orEmpty()
    private val logFile = System.getenv("MAV_LIVE_LOG")?.let { File(it) }
    private val start = System.currentTimeMillis()

    private fun log(line: String) {
        val stamped = "+%6.1fs %s".format((System.currentTimeMillis() - start) / 1000.0, line)
        println(stamped)
        logFile?.appendText(stamped + "\n")
    }

    private val loggingClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            val endpoint = request.url.encodedPath.substringAfterLast('/')
            val sentBody = request.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            val idHint = Regex(""""(BizonylatAzonosito|id|Id)"\s*:\s*(\[[^\]]*]|"[^"]*")""").find(sentBody)?.value.orEmpty()
            val t0 = System.currentTimeMillis()
            val response = chain.proceed(request)
            val peek = response.peekBody(4096).string()
            val messages = Regex(""""Szoveg"\s*:\s*"([^"]*)"""").findAll(peek).map { it.groupValues[1] }.joinToString(" | ")
            val hasImage = peek.contains("\"Jegykep\":\"") && !peek.contains("\"Jegykep\":\"\"")
            log("HTTP ${response.code} $endpoint ${System.currentTimeMillis() - t0}ms ${idHint.take(90)}" +
                (if (endpoint == "GetJegykep") " image=$hasImage" else "") +
                (if (messages.isNotBlank()) " msg=$messages" else "") +
                (if (!response.isSuccessful) " body=${peek.take(160).replace('\n', ' ')}" else ""))
            response
        })
        .build()

    @Test
    fun emailLoginFetchesBarcodeOfActivePass() = runBlocking {
        assumeTrue("live MÁV credentials not provided", email.isNotBlank() && password.isNotBlank())
        val api = MavApiClient(client = loggingClient)

        log("=== email login ===")
        val session = api.login(email, password, MavSessionStore.DEFAULT_UAID)
        log("session token=${if (api.isSamlToken(session.token)) "SAML" else "other"} vimToken=${session.vimToken != null}")
        fetchFirstActiveBarcode(api, session)
    }

    /**
     * Uses a saved SocialLogin response (MAV_LIVE_GOOGLE_SESSION = path to its JSON), because a Google
     * one-time code needs an interactive sign-in. The session is built like loginWithGoogleCode does.
     */
    @Test
    fun googleSessionFetchesBarcodeOfActivePass() = runBlocking {
        val sessionFile = System.getenv("MAV_LIVE_GOOGLE_SESSION")?.let { File(it) }
        assumeTrue("saved Google SocialLogin response not provided", sessionFile?.exists() == true)
        val json = org.json.JSONObject(sessionFile!!.readText())
        val api = MavApiClient(client = loggingClient)
        val session = MavSession(
            email = json.getString("loginEmail"),
            token = json.getString("userTokenXml"),
            uaid = MavSessionStore.DEFAULT_UAID
        )
        log("=== google session (SocialLogin) token=${if (api.isSamlToken(session.token)) "SAML" else "other"} ===")
        fetchFirstActiveBarcode(api, session)
    }

    private suspend fun fetchFirstActiveBarcode(api: MavApiClient, session: MavSession) {
        val active = api.getOrders(session, validOnly = true)
        active.forEach { o -> log("order=${o.orderId} receipts=${o.receipts.map { it.receiptId }}") }
        val receipt = active.flatMap { it.receipts }.firstOrNull()
        assertNotNull("account has no active ticket/pass to test with", receipt)

        val passHolderId = receipt!!.passHolderId
        val berlettok = api.getAllPassHolders(session)
        val match = berlettok.firstOrNull { it.id == passHolderId || it.hptId == passHolderId }
        log("passHolderId=$passHolderId BERLETTOK ids=${berlettok.map { it.id }} -> electronic passholder shown=${match != null}" +
            (match?.let { " (name=${it.name != null} photo=${!it.photoBase64.isNullOrBlank()} idCard=${it.idCardNumber})" } ?: ""))

        log("=== ticket image ===")
        val t0 = System.currentTimeMillis()
        val image = runCatching { api.getTicketImage(session, receipt!!.receiptId) }
            .onFailure { log("getTicketImage threw ${it::class.simpleName}: ${it.message}") }
            .getOrNull()
        log("RESULT image=${image != null} in ${System.currentTimeMillis() - t0}ms")
        assertNotNull("barcode was not returned", image)
    }
}
