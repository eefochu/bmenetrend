package hu.app.bmenetrend.mav

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MavApiException(val errorMessage: String, val statusCode: Int = 0) : IOException(errorMessage)

class MavApiClient(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    data class RawHptItem(
        val id: String,
        val dataType: String,
        val name: String,
        val alias: String,
        val bornDate: String,
        val photoBase64: String?,
        val isDefault: Boolean
    )

    suspend fun login(email: String, password: String, uaid: String): MavSession {
        val requestJson = JSONObject().apply {
            put("FelhasznaloAzonosito", email.trim())
            put("Jelszo", password)
            put("Nyelv", "HU")
            put("UAID", uaid)
        }
        val response = postJson("Bejelentkezes", requestJson)
        val messages = response.optJSONArray("Uzenetek")
        val token = response.optString("Token", "").trim()
        if (token.isEmpty()) {
            val errorMsg = if (messages != null && messages.length() > 0) {
                val first = messages.getJSONObject(0)
                first.optString("Szoveg", first.optString("Uzenet", "Bejelentkezés sikertelen"))
            } else {
                "Bejelentkezés sikertelen. Kérjük ellenőrizze az adatokat!"
            }
            throw MavApiException(errorMsg)
        }
        val rawExpiry = response.opt("ErvenyessegVege")
        val expiresAt = when (rawExpiry) {
            is Number -> rawExpiry.toLong().let { if (it > 10_000_000_000L) it / 1000L else it }
            is String -> rawExpiry.toLongOrNull()?.let { if (it > 10_000_000_000L) it / 1000L else it }
                ?: (parseTimestamp(rawExpiry) / 1000L)
            else -> 0L
        }
        return MavSession(
            email = email.trim(),
            token = token,
            uaid = uaid,
            expiresAt = expiresAt
        )
    }

    suspend fun getUserData(session: MavSession): MavUser {
        val requestJson = JSONObject().apply {
            put("FelhasznaloAzonosito", session.email)
            put("Nyelv", "HU")
            put("Token", session.token)
            put("UAID", session.uaid)
        }
        val response = runCatching { postJson("GetFelhasznaloAdat", requestJson) }.getOrNull()
        val userObj = response?.optJSONObject("FelhasznaloAdat") ?: response

        var email = userObj?.optString("EmailCim", session.email)?.ifBlank { session.email } ?: session.email
        var firstName = userObj?.optString("KeresztNev", "").orEmpty()
        var lastName = userObj?.optString("VezetekNev", "").orEmpty()
        val regId = userObj?.optString("RegAzonosito", "").orEmpty()
        var photoBase64: String? = userObj?.optString("Fenykep", "")?.ifBlank { null }
            ?: userObj?.optString("Foto", "")?.ifBlank { null }
            ?: userObj?.optString("ProfilKep", "")?.ifBlank { null }

        // Fetch account profile from HPT / UserProfile (IK_PROFIL is the account's own profile and picture)
        val hptItems = runCatching { fetchHptDatas(session) }.getOrNull().orEmpty()
        val accountProfile = hptItems.firstOrNull { it.dataType.equals("IK_PROFIL", ignoreCase = true) }
        if (photoBase64.isNullOrBlank() && accountProfile != null && !accountProfile.photoBase64.isNullOrBlank()) {
            photoBase64 = accountProfile.photoBase64
        }
        if (firstName.isBlank() && lastName.isBlank() && accountProfile != null && accountProfile.name.isNotBlank()) {
            val parts = accountProfile.name.split(" ").filter { it.isNotBlank() }
            if (parts.size >= 2) {
                lastName = parts[0]
                firstName = parts.drop(1).joinToString(" ")
            } else {
                lastName = accountProfile.name
            }
        }

        // Fallback: Extract name and email from SAML Assertion token (e.g. from Google OAuth)
        if ((firstName.isBlank() && lastName.isBlank()) && session.token.isNotBlank()) {
            val saml = session.token

            // 1. Try family_name / surname
            val surnameRegex = Regex("""(?:AttributeName|Name)=["\'](?:[^"\']*(?:surname|family_name|last_name))["\'][^>]*>[^<]*<[a-zA-Z0-9:]*AttributeValue[^>]*>([^<]+)<""", RegexOption.IGNORE_CASE)
            val surnameMatch = surnameRegex.find(saml)
            if (surnameMatch != null) {
                lastName = surnameMatch.groupValues[1].trim()
            }

            // 2. Try given_name / first_name
            val givenNameRegex = Regex("""(?:AttributeName|Name)=["\'](?:[^"\']*(?:givenname|first_name))["\'][^>]*>[^<]*<[a-zA-Z0-9:]*AttributeValue[^>]*>([^<]+)<""", RegexOption.IGNORE_CASE)
            val givenNameMatch = givenNameRegex.find(saml)
            if (givenNameMatch != null) {
                firstName = givenNameMatch.groupValues[1].trim()
            }

            // 3. If still empty, try full name / displayName / name
            if (firstName.isBlank() && lastName.isBlank()) {
                val nameRegex = Regex("""(?:AttributeName|Name)=["\'](?:[^"\']*(?:name|displayname|commonname))["\'][^>]*>[^<]*<[a-zA-Z0-9:]*AttributeValue[^>]*>([^<]+)<""", RegexOption.IGNORE_CASE)
                val nameMatch = nameRegex.find(saml)
                if (nameMatch != null) {
                    val fullName = nameMatch.groupValues[1].trim()
                    val parts = fullName.split(" ").filter { it.isNotBlank() }
                    if (parts.size >= 2) {
                        lastName = parts[0]
                        firstName = parts.drop(1).joinToString(" ")
                    } else if (parts.size == 1) {
                        lastName = parts[0]
                    }
                }
            }

            if (email.isBlank() || email == session.email) {
                val emailRegex = Regex("""<[a-zA-Z0-9:]*NameIdentifier[^>]*>([^<]+)<""")
                val emailMatch = emailRegex.find(saml)
                if (emailMatch != null) {
                    email = emailMatch.groupValues[1].trim()
                }
            }
        }

        return MavUser(
            email = email,
            firstName = firstName,
            lastName = lastName,
            regId = regId,
            photoBase64 = photoBase64
        )
    }

    suspend fun fetchHptDatas(session: MavSession): List<RawHptItem> {
        val endpoints = listOf(
            "https://jegy-a.mav.hu/IK_API_PROD/api/ProfileApi/GetUserHPTDatas",
            "https://jegy.mav.hu/UserProfile/Profile/GetStoredPassenger"
        )
        val payloads = listOf(
            JSONObject().apply { put("userEmail", session.email) },
            JSONObject().apply { put("requestModel", JSONObject().apply { put("userEmail", session.email) }) },
            JSONObject()
        )

        for (url in endpoints) {
            for (body in payloads) {
                val result = runCatching {
                    val reqBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val req = Request.Builder()
                        .url(url)
                        .addHeader("Accept", "application/json, text/plain, */*")
                        .addHeader("Content-Type", "application/json; charset=utf-8")
                        .addHeader("Language", "hu")
                        .addHeader("language", "hu")
                        .addHeader("UserTokenXml", session.token)
                        .addHeader("Authorization", "Bearer ${session.token}")
                        .addHeader("je-api-key", "232AC6FE-46BE-479B-894D-BFC96E548945")
                        .addHeader("User-Agent", "MAVApp/2.6.15-prod (hu.mav.emmapp; build: 950; Android 14)")
                        .addHeader("Referer", "mavpluszmobile")
                        .post(reqBody)
                        .build()

                    suspendCancellableCoroutine<JSONObject> { cont ->
                        val call = client.newCall(req)
                        cont.invokeOnCancellation { call.cancel() }
                        call.enqueue(object : Callback {
                            override fun onFailure(call: Call, e: IOException) {
                                if (cont.isActive) cont.resumeWithException(e)
                            }
                            override fun onResponse(call: Call, response: Response) {
                                try {
                                    val bodyStr = response.use { it.body?.string().orEmpty().trim() }
                                    if (bodyStr.startsWith("{")) {
                                        cont.resume(JSONObject(bodyStr))
                                    } else {
                                        cont.resume(JSONObject())
                                    }
                                } catch (e: Exception) {
                                    if (cont.isActive) cont.resume(JSONObject())
                                }
                            }
                        })
                    }
                }.getOrNull()

                // Check hptData array (IK_API_PROD)
                val hptArray = result?.optJSONArray("hptData")
                if (hptArray != null && hptArray.length() > 0) {
                    val list = mutableListOf<RawHptItem>()
                    for (i in 0 until hptArray.length()) {
                        val item = hptArray.optJSONObject(i) ?: continue
                        val picObj = item.optJSONObject("profilePicture") ?: item.optJSONObject("picture")
                        val photo = picObj?.optString("binarisAllomany", "")?.ifBlank { null }
                            ?: picObj?.optString("image", "")?.ifBlank { null }
                            ?: item.optString("binarisAllomany", "").ifBlank { null }
                        list.add(
                            RawHptItem(
                                id = item.optString("id", item.optString("bussinessId", "")),
                                dataType = item.optString("dataType", ""),
                                name = item.optString("name", item.optString("passengerFullName", "")),
                                alias = item.optString("alias", ""),
                                bornDate = item.optString("bornDate", ""),
                                photoBase64 = photo,
                                isDefault = item.optBoolean("isDefault", false)
                            )
                        )
                    }
                    if (list.isNotEmpty()) return list
                }

                // Check storedPassengers array (PAPI GetStoredPassenger)
                val storedArray = result?.optJSONArray("storedPassengers")
                if (storedArray != null && storedArray.length() > 0) {
                    val list = mutableListOf<RawHptItem>()
                    for (i in 0 until storedArray.length()) {
                        val item = storedArray.optJSONObject(i) ?: continue
                        val picObj = item.optJSONObject("image") ?: item.optJSONObject("picture")
                        val photo = picObj?.optString("binarisAllomany", "")?.ifBlank { null }
                            ?: picObj?.optString("image", "")?.ifBlank { null }
                        list.add(
                            RawHptItem(
                                id = item.optString("bussinessId", item.optString("id", "")),
                                dataType = "MENTETT_UTAS",
                                name = item.optString("passengerFullName", item.optString("name", "")),
                                alias = item.optString("alias", ""),
                                bornDate = item.optString("bornDate", ""),
                                photoBase64 = photo,
                                isDefault = item.optBoolean("isDefault", false)
                            )
                        )
                    }
                    if (list.isNotEmpty()) return list
                }
            }
        }
        return emptyList()
    }

    suspend fun getPassengers(session: MavSession): List<MavPassenger> {
        val result = mutableListOf<MavPassenger>()
        val seenNames = mutableSetOf<String>()

        // 1. Fetch from HPT data (MÁV backend)
        val hptItems = runCatching { fetchHptDatas(session) }.getOrNull().orEmpty()
        for (item in hptItems) {
            // MENTETT_UTAS are saved passengers created in the account
            // BERLETTOK are pass tokens with passenger information
            if (item.dataType.equals("MENTETT_UTAS", ignoreCase = true) ||
                item.dataType.equals("BERLETTOK", ignoreCase = true)
            ) {
                val displayName = item.name.ifBlank { item.alias }.trim()
                if (displayName.isNotBlank()) {
                    val key = displayName.lowercase()
                    if (key !in seenNames) {
                        seenNames.add(key)
                        val bDate = parseDateToTimestamp(item.bornDate)
                        result.add(
                            MavPassenger(
                                id = item.id,
                                name = displayName,
                                birthDate = bDate,
                                birthDateString = item.bornDate.ifBlank { null },
                                photoBase64 = item.photoBase64,
                                isDefault = item.isDefault
                            )
                        )
                    }
                }
            }
        }

        // 2. Fetch pass holders from VIM API (BerletTokLekeres)
        val passHolders = runCatching { getAllPassHolders(session) }.getOrNull().orEmpty()
        for (holder in passHolders) {
            val name = holder.name?.trim()
            if (!name.isNullOrBlank()) {
                val key = name.lowercase()
                val existingIndex = result.indexOfFirst { it.name.equals(name, ignoreCase = true) }
                if (existingIndex >= 0) {
                    val existing = result[existingIndex]
                    if (existing.photoBase64.isNullOrBlank() && !holder.photoBase64.isNullOrBlank()) {
                        result[existingIndex] = existing.copy(photoBase64 = holder.photoBase64)
                    }
                    if (existing.birthDate == null && holder.birthDate != null) {
                        result[existingIndex] = result[existingIndex].copy(birthDate = holder.birthDate)
                    }
                } else {
                    seenNames.add(key)
                    result.add(
                        MavPassenger(
                            id = holder.id,
                            name = name,
                            birthDate = holder.birthDate,
                            photoBase64 = holder.photoBase64,
                            isDefault = false
                        )
                    )
                }
            }
        }

        return result
    }

    suspend fun getAllPassHolders(session: MavSession): List<MavPassHolder> {
        val requestJson = JSONObject().apply {
            put("FelhasznaloAzonosito", session.email)
            put("NevesitesAzonosito", "")
            put("Nyelv", "HU")
            put("Token", session.token)
            put("UAID", session.uaid)
        }
        val response = postJson("BerletTokLekeres", requestJson)
        val array = response.optJSONArray("BerletTokok") ?: JSONArray()
        val list = mutableListOf<MavPassHolder>()

        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val hptAdat = obj.optJSONObject("HPTAdat") ?: obj.optJSONObject("HptAdat")
            val nevesites = obj.optString("NevesitesAzonosito", "")
            val hptId = obj.optString("BerletMegnevezes",
                obj.optString("Megnevezes",
                    obj.optString("HptNev",
                        obj.optString("TipusNev",
                            obj.optString("HPTAzonosito", obj.optString("HptId", ""))))))
                .ifBlank { null }
            val photo = (obj.optString("Fenykep", "").ifBlank { null })
                ?: hptAdat?.optString("Fenykep", "")?.ifBlank { null }
                ?: obj.optString("Foto", "").ifBlank { null }
            val status = obj.optString("Allapot", obj.optString("Statusz", "")).ifBlank { null }
                ?: hptAdat?.optString("Allapot", "")?.ifBlank { null }
            val validTo = parseTimestamp(obj.opt("ErvenyessegVege") ?: hptAdat?.opt("ErvenyessegVege"))
            val name = (obj.optString("Nev", "").ifBlank { null })
                ?: hptAdat?.optString("Nev", "")?.ifBlank { null }
            val birthDate = parseTimestamp(obj.opt("SzuletesiDatum") ?: hptAdat?.opt("SzuletesiDatum"))
            val version = obj.optInt("VerzioSzam", obj.optInt("Verzio", 0))
            val idCard = (obj.optString("OkmanySzam", "").ifBlank { null })
                ?: hptAdat?.optString("OkmanySzam", "")?.ifBlank { null }

            list.add(
                MavPassHolder(
                    id = nevesites,
                    hptId = hptId,
                    photoBase64 = photo,
                    status = status,
                    validTo = validTo,
                    name = name,
                    birthDate = if (birthDate > 0) birthDate else null,
                    version = version,
                    idCardNumber = idCard
                )
            )
        }
        return list
    }

    suspend fun getOrders(session: MavSession, validOnly: Boolean): List<MavOrder> {
        val requestJson = JSONObject().apply {
            put("FelhasznaloAzonosito", session.email)
            put("Ervenyes", validOnly)
            put("Token", session.token)
            put("Nyelv", "HU")
            put("UAID", session.uaid)
        }
        val response = postJson("MegrendelesKereses", requestJson)
        val ordersArray = response.optJSONArray("Megrendelesek") ?: JSONArray()
        val orders = mutableListOf<MavOrder>()

        for (i in 0 until ordersArray.length()) {
            val orderObj = ordersArray.optJSONObject(i) ?: continue
            val orderId = orderObj.optString("MegrendelesAzonosito", orderObj.optString("Azonosito", ""))
            val purchaseDate = parseTimestamp(
                orderObj.opt("VasarlasDatuma")
                    ?: orderObj.opt("VasarlasIdopontja")
                    ?: orderObj.opt("UtazasDatuma")
            )
            val deliveryMode = orderObj.optString(
                "AtvetMod",
                orderObj.optString("KezbesitesiMod", orderObj.optString("EJegymediaTipus", ""))
            )

            val orderLevelPassHolder = (orderObj.optJSONObject("VasaroltBerletTok") ?: orderObj.optJSONObject("BerletTok"))?.let {
                MavPassHolderSummary(
                    hptId = it.optString("HPTAzonosito", it.optString("HptId", "")),
                    id = it.optString("NevesitesAzonosito", ""),
                    version = it.optInt("VerzioSzam", it.optInt("Verzio", 0))
                )
            }
            var resolvedPassHolder = orderLevelPassHolder

            // Receipts can be in JegykepAdatok or VasaroltBizonylatok
            val receiptsArray = orderObj.optJSONArray("JegykepAdatok")
                ?: orderObj.optJSONArray("VasaroltBizonylatok")
                ?: JSONArray()
            val receipts = mutableListOf<MavTicketReceipt>()

            for (j in 0 until receiptsArray.length()) {
                val receiptObj = receiptsArray.optJSONObject(j) ?: continue
                val receiptId = receiptObj.optString("BizonylatAzonosito", "")
                val ticketNumber = receiptObj.optString("Jegysorszam", receiptObj.optString("Jegyszam", ""))

                val passengerObj = receiptObj.optJSONObject("Utas")
                val passengerName = receiptObj.optString("UtasNeve", "").ifBlank {
                    passengerObj?.optString("Nev", "")?.ifBlank { null }
                }
                val birthDate = parseTimestamp(
                    receiptObj.opt("SzuletesiDatum") ?: passengerObj?.opt("SzuletesiDatum")
                )

                val passHolderObj = receiptObj.optJSONObject("BerletTok")
                    ?: orderObj.optJSONObject("VasaroltBerletTok")
                if (resolvedPassHolder == null && passHolderObj != null) {
                    resolvedPassHolder = MavPassHolderSummary(
                        hptId = passHolderObj.optString("HPTAzonosito", passHolderObj.optString("HptId", "")),
                        id = passHolderObj.optString("NevesitesAzonosito", ""),
                        version = passHolderObj.optInt("VerzioSzam", passHolderObj.optInt("Verzio", 0))
                    )
                }

                // Tickets inside receipt can be in Jegyek or Jegylista
                val ticketsArray = receiptObj.optJSONArray("Jegyek")
                    ?: receiptObj.optJSONArray("Jegylista")
                    ?: JSONArray()
                val items = mutableListOf<MavTicketItem>()

                for (k in 0 until ticketsArray.length()) {
                    val ticketObj = ticketsArray.optJSONObject(k) ?: continue
                    val ticketId = ticketObj.optString("JegyAzonosito", ticketObj.optString("Azonosito", ""))
                    val title = ticketObj.optString("Viszonylat", ticketObj.optString("Megnevezes", "MÁV jegy / bérlet"))
                    val discount = ticketObj.optString("Kedvezmeny", ticketObj.optString("KedvezmenyNev", "")).ifBlank { null }
                    val validFrom = parseTimestamp(
                        ticketObj.opt("ErvenyessegKezdete")
                            ?: ticketObj.opt("ErvenyessegKezdet")
                            ?: ticketObj.opt("ErvenyessegEleje")
                    )
                    val validTo = parseTimestamp(
                        ticketObj.opt("ErvenyessegVege")
                            ?: ticketObj.opt("ErvenyessegVeg")
                    )
                    val price = ticketObj.optInt("Ar", ticketObj.optInt("Osszeg", 0))
                    val isPass = ticketObj.optBoolean("Berlet", false) ||
                        title.contains("bérlet", ignoreCase = true) ||
                        title.contains("orszagberlet", ignoreCase = true) ||
                        title.contains("országbérlet", ignoreCase = true) ||
                        title.contains("varmegye", ignoreCase = true) ||
                        title.contains("vármegye", ignoreCase = true)
                    val status = ticketObj.optString("Allapot", ticketObj.optString("Statusz", "")).ifBlank { null }
                    val serviceStatus = ticketObj.optString("SzolgaltatasStatusz", "").ifBlank { null }
                    val direction = ticketObj.optString("Irany", "").ifBlank { null }
                    val serviceId = ticketObj.optString("VonatSzam", ticketObj.optString("Jaratszam", "")).ifBlank { null }

                    items.add(
                        MavTicketItem(
                            id = ticketId,
                            name = title,
                            discount = discount,
                            validFrom = validFrom,
                            validTo = validTo,
                            price = price,
                            isPass = isPass,
                            status = status,
                            serviceStatus = serviceStatus,
                            direction = direction,
                            serviceId = serviceId
                        )
                    )
                }

                receipts.add(
                    MavTicketReceipt(
                        passengerName = passengerName,
                        birthDate = if (birthDate > 0) birthDate else null,
                        ticketNumber = ticketNumber.ifBlank { null },
                        receiptId = receiptId,
                        items = items
                    )
                )
            }

            orders.add(
                MavOrder(
                    orderId = orderId,
                    purchaseDate = purchaseDate,
                    deliveryMode = deliveryMode.ifBlank { null },
                    passHolder = resolvedPassHolder,
                    receipts = receipts
                )
            )
        }

        // Check BerletTokok to make sure any standalone passes (e.g. County/Country passes) are listed!
        val passHolders = runCatching { getAllPassHolders(session) }.getOrNull().orEmpty()
        val existingReceiptIds = orders.flatMap { it.receipts }.map { it.receiptId }.toSet()
        val existingPassIds = orders.mapNotNull { it.passHolder?.id }.toSet()
        val nowMs = System.currentTimeMillis()

        for (holder in passHolders) {
            val isCurrent = holder.validTo == 0L || holder.validTo >= nowMs
            if (validOnly && !isCurrent) continue
            if (holder.id.isNotBlank() && holder.id !in existingPassIds && holder.id !in existingReceiptIds) {
                val passItem = MavTicketItem(
                    id = holder.id,
                    name = holder.hptId ?: "MÁV Bérlet",
                    discount = holder.idCardNumber?.let { "Okmány: $it" },
                    validFrom = 0L,
                    validTo = holder.validTo,
                    price = 0,
                    isPass = true,
                    status = holder.status ?: "Érvényes"
                )
                val receipt = MavTicketReceipt(
                    passengerName = holder.name,
                    birthDate = holder.birthDate,
                    ticketNumber = holder.id,
                    receiptId = holder.id,
                    items = listOf(passItem)
                )
                orders.add(
                    0,
                    MavOrder(
                        orderId = holder.id,
                        purchaseDate = 0L,
                        deliveryMode = "E-Bérlet",
                        passHolder = MavPassHolderSummary(holder.hptId, holder.id, holder.version),
                        receipts = listOf(receipt)
                    )
                )
            }
        }

        return orders
    }

    suspend fun getTicketImage(session: MavSession, receiptId: String): MavTicketImage? {
        val requestJson = JSONObject().apply {
            put("FelhasznaloAzonosito", session.email)
            val array = JSONArray()
            val numericId = receiptId.toLongOrNull()
            if (numericId != null) {
                array.put(numericId)
            } else {
                array.put(receiptId)
            }
            put("BizonylatAzonosito", array)
            val bizArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("BizonylatAzonosito", receiptId)
                })
            }
            put("Bizonylatok", bizArray)
            put("Token", session.token)
            put("Nyelv", "HU")
            put("UAID", session.uaid)
        }
        val response = postJson("GetJegykep", requestJson)
        val images = response.optJSONArray("JegykepAdatok")
            ?: response.optJSONArray("JegykepLista")
            ?: response.optJSONArray("Bizonylatok")
            ?: JSONArray()
        if (images.length() == 0) return null
        val first = images.getJSONObject(0)
        val imageBase64 = first.optString("Kep", first.optString("Jegykep", "")).trim()
        if (imageBase64.isEmpty()) return null

        return MavTicketImage(
            receiptId = first.optString("BizonylatAzonosito", receiptId),
            ticketNumber = first.optString("Jegyszam", first.optString("Jegysorszam", "")).ifBlank { null },
            kind = first.optString("Tipus", "").ifBlank { null },
            imageBase64 = imageBase64
        )
    }

    suspend fun getPassHolder(session: MavSession, nevesitesAzonosito: String): MavPassHolder? {
        val requestJson = JSONObject().apply {
            put("FelhasznaloAzonosito", session.email)
            put("NevesitesAzonosito", nevesitesAzonosito)
            put("Token", session.token)
            put("Nyelv", "HU")
            put("UAID", session.uaid)
        }
        val response = postJson("BerletTokLekeres", requestJson)
        val array = response.optJSONArray("BerletTokok") ?: JSONArray()
        if (array.length() == 0) return null
        val obj = array.getJSONObject(0)
        val hptAdat = obj.optJSONObject("HPTAdat") ?: obj.optJSONObject("HptAdat")
        val photo = (obj.optString("Fenykep", "").ifBlank { null })
            ?: hptAdat?.optString("Fenykep", "")?.ifBlank { null }
            ?: obj.optString("Foto", "").ifBlank { null }
        val name = (obj.optString("Nev", "").ifBlank { null })
            ?: hptAdat?.optString("Nev", "")?.ifBlank { null }
        val idCard = (obj.optString("OkmanySzam", "").ifBlank { null })
            ?: hptAdat?.optString("OkmanySzam", "")?.ifBlank { null }
        val birthDate = parseTimestamp(obj.opt("SzuletesiDatum") ?: hptAdat?.opt("SzuletesiDatum"))
        val validTo = parseTimestamp(obj.opt("ErvenyessegVege") ?: hptAdat?.opt("ErvenyessegVege"))
        val status = obj.optString("Allapot", obj.optString("Statusz", "")).ifBlank { null }
            ?: hptAdat?.optString("Allapot", "")?.ifBlank { null }
        val hptId = obj.optString("BerletMegnevezes",
            obj.optString("Megnevezes",
                obj.optString("HptNev",
                    obj.optString("TipusNev",
                        obj.optString("HPTAzonosito", obj.optString("HptId", ""))))))
            .ifBlank { null }

        return MavPassHolder(
            id = obj.optString("NevesitesAzonosito", nevesitesAzonosito),
            hptId = hptId,
            photoBase64 = photo,
            status = status,
            validTo = validTo,
            name = name,
            birthDate = if (birthDate > 0) birthDate else null,
            version = obj.optInt("VerzioSzam", obj.optInt("Verzio", 0)),
            idCardNumber = idCard
        )
    }

    private suspend fun postJson(endpoint: String, body: JSONObject): JSONObject {
        val url = if (baseUrl.endsWith("/")) "$baseUrl$endpoint" else "$baseUrl/$endpoint"
        val requestBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .addHeader("User-Agent", "BMenetrendAndroid/0.5 (MavApi)")
            .post(requestBody)
            .build()

        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val responseBody = response.use { resp ->
                            val bodyString = resp.body?.string().orEmpty().trim()
                            if (!resp.isSuccessful) {
                                throw MavApiException("MÁV API hiba: HTTP ${resp.code}", resp.code)
                            }
                            if (bodyString.startsWith("<") || bodyString.contains("<html", ignoreCase = true)) {
                                if (bodyString.contains("hozzáférési szabályzat", ignoreCase = true) ||
                                    bodyString.contains("access policy", ignoreCase = true)
                                ) {
                                    throw MavApiException("A MÁV szerver hozzáférési szabályzata elutasította a kérést. Kérjük ellenőrizze az internetkapcsolatot vagy próbálja újra később.")
                                }
                                throw MavApiException("A MÁV szerver érvénytelen választ adott (HTML válasz JSON helyett).")
                            }
                            bodyString
                        }
                        val json = if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
                        if (continuation.isActive) continuation.resume(json)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://vim.mav-start.hu/VIM/PR/20250529/MobileServiceS.svc/rest"

        private fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        internal fun parseTimestamp(raw: Any?): Long {
            if (raw == null) return 0L
            when (raw) {
                is Number -> {
                    val v = raw.toLong()
                    return if (v in 1L..10_000_000_000L) v * 1000L else v
                }
                is String -> {
                    val s = raw.trim()
                    if (s.isEmpty()) return 0L
                    s.toLongOrNull()?.let { return if (it in 1L..10_000_000_000L) it * 1000L else it }
                    if (s.contains("/Date(") && s.contains(")/")) {
                        val digits = s.substringAfter("/Date(").substringBefore(")/").substringBefore("+").substringBefore("-")
                        digits.toLongOrNull()?.let { return if (it in 1L..10_000_000_000L) it * 1000L else it }
                    }
                    return runCatching {
                        Instant.parse(s).toEpochMilli()
                    }.recoverCatching {
                        LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.getOrDefault(0L)
                }
                else -> return 0L
            }
        }

        internal fun parseDateToTimestamp(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            raw.toLongOrNull()?.let { return if (it in 1L..10_000_000_000L) it * 1000L else it }
            return runCatching {
                Instant.parse(raw).toEpochMilli()
            }.recoverCatching {
                val clean = raw.take(10) // "YYYY-MM-DD"
                LocalDate.parse(clean).atStartOfDay(ZoneId.of("Europe/Budapest")).toInstant().toEpochMilli()
            }.getOrNull()
        }
    }
}
