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
    fun loginSuccessWithGetUserTokenReturnsSession() = runBlocking {
        val mockJson = """
            {
                "userData": {
                    "userTokenXml": "mock_saml_token_xyz",
                    "regisztraciosAzonosito": "1001",
                    "userEmail": "test@example.com"
                }
            }
        """.trimIndent()

        val client = createMockClient { req ->
            if (req.url.encodedPath.contains("GetUserToken")) {
                jsonResponse(req, mockJson)
            } else {
                jsonResponse(req, "{}", 404)
            }
        }

        val api = MavApiClient(client = client, baseUrl = "https://mock.mav.hu/rest")
        val session = api.login("test@example.com", "secret", "uaid_123456789012345678901234")

        assertEquals("test@example.com", session.email)
        assertEquals("mock_saml_token_xyz", session.token)
        assertEquals("uaid_123456789012345678901234", session.uaid)
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
            if (req.url.encodedPath.contains("GetUserToken")) {
                jsonResponse(req, "{}", 500)
            } else {
                assertTrue(req.url.encodedPath.contains("Bejelentkezes"))
                jsonResponse(req, mockJson)
            }
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
            if (req.url.encodedPath.contains("GetUserToken")) {
                jsonResponse(req, "{}", 500)
            } else {
                jsonResponse(req, mockJson)
            }
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
    fun loginWithGoogleCodeSuccessReturnsSessionWithJwtAndUser() = runBlocking {
        val mockJson = """
            {
                "userTokenXml": "mock_jwt_refresh_token_123",
                "loginEmail": "googleuser@gmail.com",
                "firstName": "Béla",
                "lastName": "Kovács",
                "registrationId": "999888"
            }
        """.trimIndent()

        var capturedOrigin: String? = null
        var capturedReferer: String? = null

        val client = createMockClient { req ->
            assertTrue(req.url.encodedPath.contains("SocialLogin"))
            capturedOrigin = req.header("Origin")
            capturedReferer = req.header("Referer")
            jsonResponse(req, mockJson)
        }

        val api = MavApiClient(client = client, baseUrl = "https://mock.mav.hu/rest")
        val session = api.loginWithGoogleCode("mock_auth_code_xyz", "uaid_test_google")

        assertEquals("mock_jwt_refresh_token_123", session.token)
        assertEquals("googleuser@gmail.com", session.email)
        assertEquals("uaid_test_google", session.uaid)
        assertNotNull(session.user)
        assertEquals("Béla", session.user?.firstName)
        assertEquals("Kovács", session.user?.lastName)
        assertEquals("Kovács Béla", session.user?.fullName)
        assertEquals("999888", session.user?.regId)

        assertEquals("https://jegy.mav.hu", capturedOrigin)
        assertEquals("https://jegy.mav.hu/", capturedReferer)
    }

    @Test
    fun loginWithGoogleCodeFailureThrowsMavApiException() = runBlocking {
        val mockErrorJson = """
            {
                "errorCode": 401,
                "errorMessage": "Invalid User Key",
                "errorDetails": null
            }
        """.trimIndent()

        val client = createMockClient { req ->
            assertTrue(req.url.encodedPath.contains("SocialLogin"))
            jsonResponse(req, mockErrorJson, 401)
        }

        val api = MavApiClient(client = client, baseUrl = "https://mock.mav.hu/rest")
        try {
            api.loginWithGoogleCode("invalid_code", "uaid_123")
            fail("Expected MavApiException")
        } catch (e: MavApiException) {
            assertTrue(e.errorMessage.contains("Google"))
        }
    }

    @Test
    fun htmlResponseThrowsMavApiExceptionRatherThanJsonException() = runBlocking {
        val mockHtml = "<html><body><b>A kért oldal nem jeleníthető meg, mert nem felel meg a hozzáférési szabályzat követelményeinek</b></body></html>"

        val client = createMockClient { req ->
            if (req.url.encodedPath.contains("GetUserToken")) {
                jsonResponse(req, "{}", 500)
            } else {
                jsonResponse(req, mockHtml, 200)
            }
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
            if (req.url.encodedPath.contains("GetUserPersonalDatas")) {
                jsonResponse(req, "{}", 404)
            } else if (req.url.encodedPath.contains("GetUserHPTDatas")) {
                jsonResponse(req, """{"hptData":[]}""")
            } else {
                assertTrue(req.url.encodedPath.contains("GetFelhasznaloAdat"))
                jsonResponse(req, mockJson)
            }
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
    fun getUserDataWithPersonalDataAndAccountPhoto() = runBlocking {
        val personalJson = """
            {
                "userData": {
                    "vezetekNev": "Kókai",
                    "keresztNev": "Balázs",
                    "regisztraciosAzonosito": "17601334",
                    "userEmail": "ilikehacking.py@gmail.com"
                }
            }
        """.trimIndent()

        val hptJson = """
            {
                "hptData": [
                    {
                        "id": "7270388",
                        "dataType": "IK_PROFIL",
                        "name": "Profile Picture Passenger",
                        "alias": "Profile Picture",
                        "profilePicture": {
                            "binarisAllomany": "profile_b64_data"
                        }
                    },
                    {
                        "id": "7265084",
                        "dataType": "MENTETT_UTAS",
                        "name": "Kókai Balázs",
                        "profilePicture": {
                            "binarisAllomany": "passenger_selfie_b64"
                        }
                    }
                ]
            }
        """.trimIndent()

        val client = createMockClient { req ->
            if (req.url.encodedPath.contains("GetUserPersonalDatas")) {
                jsonResponse(req, personalJson)
            } else if (req.url.encodedPath.contains("GetUserHPTDatas")) {
                jsonResponse(req, hptJson)
            } else {
                jsonResponse(req, "{}", 404)
            }
        }

        val api = MavApiClient(client = client, baseUrl = "https://mock.mav.hu/rest")
        val session = MavSession("ilikehacking.py@gmail.com", "tok", "uaid")
        val user = api.getUserData(session)

        assertEquals("ilikehacking.py@gmail.com", user.email)
        assertEquals("Balázs", user.firstName)
        assertEquals("Kókai", user.lastName)
        assertEquals("Kókai Balázs", user.fullName)
        assertEquals("17601334", user.regId)
        assertEquals("profile_b64_data", user.photoBase64)

        // Verify getPassengers returns passenger selfie, not account profile
        val passengers = api.getPassengers(session)
        assertEquals(1, passengers.size)
        val p = passengers.first()
        assertEquals("Kókai Balázs", p.name)
        assertEquals("passenger_selfie_b64", p.photoBase64)
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
            if (req.url.encodedPath.contains("GetPreviousPurchases")) {
                jsonResponse(req, """{"previousPurchases":[]}""")
            } else if (req.url.encodedPath.contains("BerletTokLekeres")) {
                jsonResponse(req, """{"BerletTokok":[]}""")
            } else {
                assertTrue(req.url.encodedPath.contains("MegrendelesKereses"))
                jsonResponse(req, mockJson)
            }
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
        assertEquals(1726000000000L, MavApiClient.parseTimestamp(1726000000L))
        assertEquals(1726000000000L, MavApiClient.parseTimestamp(1726000000000L))
        assertEquals(1726000000000L, MavApiClient.parseTimestamp("/Date(1726000000000)/"))
        assertEquals(1726000000000L, MavApiClient.parseTimestamp("1726000000"))
        assertEquals(0L, MavApiClient.parseTimestamp(null))
        assertEquals(0L, MavApiClient.parseTimestamp(""))
    }

    @Test
    fun getOrdersWithValidOnlyFalseSkipsActivePurchases() = runBlocking {
        val now = 1726000000000L
        val future = 1736000000000L
        val past = 1716000000000L

        val mockPurchases = """
            {
                "previousPurchases": [
                    {
                        "id": "PURCHASE-ACTIVE",
                        "name": "Active Pass",
                        "validFrom": "1726000000",
                        "validTo": "2000000000",
                        "seasonTicket": true
                    },
                    {
                        "id": "PURCHASE-PAST",
                        "name": "Expired Pass",
                        "validFrom": "1700000000",
                        "validTo": "1710000000",
                        "seasonTicket": true
                    }
                ]
            }
        """.trimIndent()

        val client = createMockClient { req ->
            if (req.url.encodedPath.contains("GetPreviousPurchases")) {
                jsonResponse(req, mockPurchases)
            } else if (req.url.encodedPath.contains("BerletTokLekeres")) {
                jsonResponse(req, """{"BerletTokok":[]}""")
            } else {
                jsonResponse(req, """{"Megrendelesek":[]}""")
            }
        }

        val api = MavApiClient(client = client, baseUrl = "https://mock.mav.hu/rest")
        val session = MavSession("test@example.com", "tok", "uaid")

        val activeOrders = api.getOrders(session, validOnly = true)
        assertEquals(1, activeOrders.size)
        assertEquals("PURCHASE-ACTIVE", activeOrders.first().orderId)

        val pastOrders = api.getOrders(session, validOnly = false)
        assertEquals(1, pastOrders.size)
        assertEquals("PURCHASE-PAST", pastOrders.first().orderId)
    }

    @Test
    fun getTicketImageWithCompositeIdResolvesFromPurchaseDetails() = runBlocking {
        val detailsJson = """
            {
                "previousPurchaseDetails": {
                    "ticketDatas": [
                        {
                            "bizonylatTechnikaiAzonosito": "1779935735",
                            "jegySorszam": "5594003G000448895"
                        }
                    ]
                }
            }
        """.trimIndent()

        val vimJson = """
            {
                "Bizonylatok": [
                    {
                        "BizonylatTechnikaiAzonosito": "1779935735",
                        "BizonylatFajta": "Orszag-varmegye berlet MAVapp",
                        "Jegysorszam": "5594003G000448895",
                        "Jegykep": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
                    }
                ]
            }
        """.trimIndent()

        val client = createMockClient { req ->
            if (req.url.encodedPath.contains("GetPreviousPurchaseDetails")) {
                jsonResponse(req, detailsJson)
            } else if (req.url.encodedPath.contains("GetJegykep")) {
                jsonResponse(req, vimJson)
            } else {
                jsonResponse(req, "{}", 404)
            }
        }

        val api = MavApiClient(client = client, baseUrl = "https://mock.mav.hu/rest")
        val session = MavSession("test@example.com", "tok", "uaid", vimToken = "mock_vim_token")
        val image = api.getTicketImage(session, "ODAUTI#CSCSAZON#123#0#1023744049")

        assertNotNull(image)
        assertEquals("ODAUTI#CSCSAZON#123#0#1023744049", image?.receiptId)
        assertEquals("5594003G000448895", image?.ticketNumber)
        assertEquals("Orszag-varmegye berlet MAVapp", image?.kind)
        assertTrue(image?.imageBase64?.isNotEmpty() == true)
    }
    @Test
    fun getRawSamlTokenDecodesBase64ToXml() {
        val api = MavApiClient(client = OkHttpClient(), baseUrl = "https://mock.mav.hu/rest")
        val rawXml = "<saml:Assertion>AlreadyRaw</saml:Assertion>"
        assertEquals(rawXml, api.getRawSamlToken(rawXml))

        val b64Xml = "PHNhbWw6QXNzZXJ0aW9uPkJhc2U2NDwvc2FtbDpBc3NlcnRpb24+"
        assertEquals("<saml:Assertion>Base64</saml:Assertion>", api.getRawSamlToken(b64Xml))

        assertEquals("regular_token", api.getRawSamlToken("regular_token"))
    }

    @Test
    fun getTicketImageWithGoogleOAuthTokenDecodesSamlAndFetchesAztecBarcodeFromGetJegykep() = runBlocking {
        val detailsJson = """
            {
                "previousPurchaseDetails": {
                    "ticketDatas": [
                        {
                            "bizonylatTechnikaiAzonosito": "1779935735",
                            "jegySorszam": "5594003G000448895"
                        }
                    ]
                }
            }
        """.trimIndent()

        val vimJson = """
            {
                "Bizonylatok": [
                    {
                        "BizonylatTechnikaiAzonosito": "1779935735",
                        "BizonylatFajta": "Orszag-varmegye berlet MAVapp",
                        "Jegysorszam": "5594003G000448895",
                        "Jegykep": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
                    }
                ]
            }
        """.trimIndent()

        var jegykepReqBody = ""
        val client = createMockClient { req ->
            if (req.url.encodedPath.contains("GetPreviousPurchaseDetails")) {
                jsonResponse(req, detailsJson)
            } else if (req.url.encodedPath.contains("GetJegykep")) {
                val buf = okio.Buffer()
                req.body?.writeTo(buf)
                jegykepReqBody = buf.readUtf8()
                jsonResponse(req, vimJson)
            } else {
                jsonResponse(req, "{}", 404)
            }
        }

        val api = MavApiClient(client = client, baseUrl = "https://mock.mav.hu/rest")
        // Base64 of <saml:Assertion>GoogleSAML</saml:Assertion>
        val b64Saml = "PHNhbWw6QXNzZXJ0aW9uPkdvb2dsZVNBTUw8L3NhbWw6QXNzZXJ0aW9uPg=="
        val session = MavSession("googleuser@gmail.com", b64Saml, "uaid_abc", vimToken = null)
        val image = api.getTicketImage(session, "1023744049")

        assertNotNull(image)
        assertEquals("1023744049", image?.receiptId)
        assertEquals("5594003G000448895", image?.ticketNumber)
        assertEquals("Orszag-varmegye berlet MAVapp", image?.kind)
        assertTrue(image?.imageBase64?.isNotEmpty() == true)

        // Verify GetJegykep received raw unencoded SAML XML and numeric bizonylatId
        assertTrue(jegykepReqBody.contains("<saml:Assertion>GoogleSAML"))
        assertTrue(jegykepReqBody.contains("1779935735"))
        assertTrue(jegykepReqBody.contains("googleuser@gmail.com"))
    }
    @Test
    fun getTicketImageRejectsPdfAndCertificate() = runBlocking {
        val detailsJson = """
            {
                "previousPurchaseDetails": {
                    "ticketDatas": [
                        {
                            "bizonylatTechnikaiAzonosito": "1779935735",
                            "jegySorszam": "5594003G000448895"
                        }
                    ]
                }
            }
        """.trimIndent()

        val vimPdfJson = """
            {
                "Bizonylatok": [
                    {
                        "BizonylatTechnikaiAzonosito": "1779935735",
                        "BizonylatFajta": "Certificate",
                        "Jegysorszam": "5594003G000448895",
                        "Jegykep": "JVBERi0xLjQKJeLjz9MKN..."
                    }
                ]
            }
        """.trimIndent()

        val client = createMockClient { req ->
            if (req.url.encodedPath.contains("GetPreviousPurchaseDetails")) {
                jsonResponse(req, detailsJson)
            } else if (req.url.encodedPath.contains("GetJegykep")) {
                jsonResponse(req, vimPdfJson)
            } else {
                jsonResponse(req, "{}", 404)
            }
        }

        val api = MavApiClient(client = client, baseUrl = "https://mock.mav.hu/rest")
        val session = MavSession("test@example.com", "<saml:Assertion>XML</saml:Assertion>", "uaid")
        val image = api.getTicketImage(session, "1023744049")

        assertNull("PDF certificates should be rejected", image)
    }
}
