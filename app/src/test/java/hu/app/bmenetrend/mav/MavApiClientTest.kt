package hu.app.bmenetrend.mav

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class MavApiClientTest {

    private fun createMockClient(handler: (Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                handler(chain.request())
            }
            .build()
    }

    private fun jsonResponse(request: Request, body: String, code: Int = 200): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    @Test
    fun loginSuccessReturnsSession() = runBlocking {
        val mockJson = """
            {
                "Token": "mock_token_abc_123",
                "ErvenyessegVege": 1772733979
            }
        """.trimIndent()

        val client = createMockClient { req ->
            assertTrue(req.url.encodedPath.contains("Bejelentkezes"))
            jsonResponse(req, mockJson)
        }

        val api = MavApiClient(client = client, baseUrl = "https://mock.mav.hu/rest")
        val session = api.login("test@example.com", "secret", "uaid_123456789012345678901234")

        assertEquals("test@example.com", session.email)
        assertEquals("mock_token_abc_123", session.token)
        assertEquals("uaid_123456789012345678901234", session.uaid)
        assertEquals(1772733979L, session.expiresAt)
    }

    @Test
    fun loginFailureThrowsMavApiExceptionWithUzenetek() = runBlocking {
        val mockJson = """
            {
                "Token": "",
                "Uzenetek": [
                    { "Szoveg": "Hibás felhasználónév vagy jelszó!" }
                ]
            }
        """.trimIndent()

        val client = createMockClient { req ->
            jsonResponse(req, mockJson)
        }

        val api = MavApiClient(client = client, baseUrl = "https://mock.mav.hu/rest")
        try {
            api.login("test@example.com", "wrongpass", "uaid_123")
            fail("Expected MavApiException")
        } catch (e: MavApiException) {
            assertEquals("Hibás felhasználónév vagy jelszó!", e.errorMessage)
        }
    }

    @Test
    fun htmlResponseThrowsMavApiExceptionRatherThanJsonException() = runBlocking {
        val mockHtml = "<html><body><b>A kért oldal nem jeleníthető meg, mert nem felel meg a hozzáférési szabályzat követelményeinek</b></body></html>"

        val client = createMockClient { req ->
            jsonResponse(req, mockHtml, 200)
        }

        val api = MavApiClient(client = client, baseUrl = "https://mock.mav.hu/rest")
        try {
            api.login("test@example.com", "secret", "invalid_uaid")
            fail("Expected MavApiException")
        } catch (e: MavApiException) {
            assertTrue(e.errorMessage.contains("hozzáférési szabályzat") || e.errorMessage.contains("HTML válasz"))
        }
    }

    @Test
    fun getUserDataParsesCorrectly() = runBlocking {
        val mockJson = """
            {
                "FelhasznaloAdat": {
                    "EmailCim": "peter@example.com",
                    "KeresztNev": "Péter",
                    "VezetekNev": "Kovács",
                    "RegAzonosito": "REG-987"
                }
            }
        """.trimIndent()

        val client = createMockClient { req ->
            assertTrue(req.url.encodedPath.contains("GetFelhasznaloAdat"))
            jsonResponse(req, mockJson)
        }

        val api = MavApiClient(client = client, baseUrl = "https://mock.mav.hu/rest")
        val session = MavSession("peter@example.com", "tok", "uaid")
        val user = api.getUserData(session)

        assertEquals("peter@example.com", user.email)
        assertEquals("Péter", user.firstName)
        assertEquals("Kovács", user.lastName)
        assertEquals("Kovács Péter", user.fullName)
        assertEquals("REG-987", user.regId)
    }

    @Test
    fun getOrdersParsesActiveAndPastOrders() = runBlocking {
        val mockJson = """
            {
                "Megrendelesek": [
                    {
                        "MegrendelesAzonosito": "ORD-101",
                        "VasarlasIdopontja": 1726000000,
                        "KezbesitesiMod": "E-Ticket",
                        "VasaroltBizonylatok": [
                            {
                                "BizonylatAzonosito": "BIZ-501",
                                "Jegyszam": "12345-67890",
                                "Utas": {
                                    "Nev": "Kovács Péter",
                                    "SzuletesiDatum": 631152000
                                },
                                "Jegyek": [
                                    {
                                        "JegyAzonosito": "J-1",
                                        "Viszonylat": "Pest vármegyebérlet",
                                        "Kedvezmeny": "Teljesárú",
                                        "ErvenyessegKezdete": 1726000000,
                                        "ErvenyessegVege": 2000000000,
                                        "Ar": 9450,
                                        "Berlet": true,
                                        "Statusz": "Érvényes"
                                    }
                                ]
                            }
                        ]
                    }
                ]
            }
        """.trimIndent()

        val client = createMockClient { req ->
            assertTrue(req.url.encodedPath.contains("MegrendelesKereses"))
            jsonResponse(req, mockJson)
        }

        val api = MavApiClient(client = client, baseUrl = "https://mock.mav.hu/rest")
        val session = MavSession("test@example.com", "tok", "uaid")
        val orders = api.getOrders(session, validOnly = true)

        assertEquals(1, orders.size)
        val order = orders.first()
        assertEquals("ORD-101", order.orderId)
        assertEquals(1, order.receipts.size)

        val receipt = order.receipts.first()
        assertEquals("BIZ-501", receipt.receiptId)
        assertEquals("12345-67890", receipt.ticketNumber)
        assertEquals("Kovács Péter", receipt.passengerName)

        val item = receipt.items.first()
        assertEquals("Pest vármegyebérlet", item.name)
        assertEquals("Teljesárú", item.discount)
        assertEquals(9450, item.price)
        assertTrue(item.isPass)
        assertTrue(order.isValidNow)
    }

    @Test
    fun getTicketImageParsesBase64() = runBlocking {
        val mockJson = """
            {
                "Bizonylatok": [
                    {
                        "BizonylatAzonosito": "BIZ-501",
                        "Jegyszam": "12345-67890",
                        "Tipus": "QR",
                        "Jegykep": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
                    }
                ]
            }
        """.trimIndent()

        val client = createMockClient { req ->
            assertTrue(req.url.encodedPath.contains("GetJegykep"))
            jsonResponse(req, mockJson)
        }

        val api = MavApiClient(client = client, baseUrl = "https://mock.mav.hu/rest")
        val session = MavSession("test@example.com", "tok", "uaid")
        val image = api.getTicketImage(session, "BIZ-501")

        assertNotNull(image)
        assertEquals("BIZ-501", image?.receiptId)
        assertEquals("12345-67890", image?.ticketNumber)
        assertTrue(image?.imageBase64?.isNotEmpty() == true)
    }

    @Test
    fun parseTimestampHandlesMultipleFormats() {
        // Unix seconds converted to milliseconds
        assertEquals(1726000000000L, MavApiClient.parseTimestamp(1726000000L))
        // Unix milliseconds kept as milliseconds
        assertEquals(1726000000000L, MavApiClient.parseTimestamp(1726000000000L))
        // .NET /Date(...)/
        assertEquals(1726000000000L, MavApiClient.parseTimestamp("/Date(1726000000000)/"))
        // String digits (seconds converted to ms)
        assertEquals(1726000000000L, MavApiClient.parseTimestamp("1726000000"))
        assertEquals(0L, MavApiClient.parseTimestamp(null))
        assertEquals(0L, MavApiClient.parseTimestamp(""))
    }
}
