package hu.app.bmenetrend.mav

import android.util.Base64
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MavApiException(val errorMessage: String, val statusCode: Int = 0) : IOException(errorMessage) {
    /** True when the server rejected our stored token (session expired / invalid). */
    val isAuthError: Boolean get() = statusCode == 401 || statusCode == 403
}

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
        val isDefault: Boolean,
        val businessId: String = ""
    )

    private fun getSafeTokenHeader(token: String): String {
        val clean = token.trim()
        if (clean.isEmpty()) return ""
        return if (clean.startsWith("<")) {
            Base64.encodeToString(clean.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        } else {
            clean.replace("\r", "").replace("\n", "")
        }
    }

    private fun parseJsonBody(bodyString: String): JSONObject {
        val trimmed = bodyString.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return JSONObject(trimmed)
        }
        if (trimmed.startsWith(""") && trimmed.endsWith(""") && trimmed.length >= 2) {
            val unquoted = runCatching {
                org.json.JSONTokener(trimmed).nextValue() as? String
            }.getOrNull() ?: trimmed.substring(1, trimmed.length - 1)
            val inner = unquoted.trim()
            if (inner.startsWith("{") || inner.startsWith("[")) {
                return JSONObject(inner)
            }
        }
        return JSONObject()
    }

    private suspend fun executeJsonRequest(req: Request): JSONObject {
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(req)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bodyString = response.use { it.body?.string().orEmpty().trim() }
                        val parsedJson = runCatching { parseJsonBody(bodyString) }.getOrDefault(JSONObject())
                        if (!response.isSuccessful) {
                            val serverMsg = parsedJson.optString(
                                "errorMessage",
                                parsedJson.optString("message", parsedJson.optString("error", ""))
                            ).ifBlank {
                                if (bodyString.isNotBlank() && !bodyString.startsWith("<")) {
                                    bodyString.trim('"')
                                } else null
                            }
                            throw MavApiException(serverMsg ?: "MÁV API hiba: HTTP ${response.code}", response.code)
                        }
                        if (continuation.isActive) continuation.resume(parsedJson)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    suspend fun login(email: String, password: String, uaid: String): MavSession {
        val trimmedEmail = email.trim()

        // 1. First, try https://jegy-a.mav.hu/IK_API_PROD/api/ProfileApi/GetUserToken
        val ikResult = runCatching {
            val reqBody = JSONObject().apply {
                put("userEmail", trimmedEmail)
                put("password", password)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val req = Request.Builder()
                .url("https://jegy-a.mav.hu/IK_API_PROD/api/ProfileApi/GetUserToken")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .addHeader("Origin", "https://jegy.mav.hu")
                .addHeader("Referer", "https://jegy.mav.hu/")
                .addHeader("Language", "hu")
                .addHeader("User-Agent", MAV_APP_USER_AGENT)
                .post(reqBody)
                .build()

            executeJsonRequest(req)
        }.getOrNull()

        val ikUserData = ikResult?.optJSONObject("userData")
        val ikToken = ikUserData?.optString("userTokenXml", "").orEmpty().trim()

        // 2. Also authenticate to VIM Bejelentkezes to obtain vimToken for GetJegykep
        var vimToken: String? = null
        var vimExpiresAt = 0L
        var vimException: Throwable? = null

        try {
            val requestJson = JSONObject().apply {
                put("FelhasznaloAzonosito", trimmedEmail)
                put("Jelszo", password)
                put("Nyelv", "HU")
                put("UAID", uaid)
            }
            val response = postJson("Bejelentkezes", requestJson)
            val messages = response.optJSONArray("Uzenetek")
            val token = response.optString("Token", "").trim()
            if (token.isNotEmpty()) {
                vimToken = token
                val rawExpiry = response.opt("ErvenyessegVege")
                vimExpiresAt = when (rawExpiry) {
                    is Number -> rawExpiry.toLong().let { if (it > 10_000_000_000L) it / 1000L else it }
                    is String -> rawExpiry.toLongOrNull()?.let { if (it > 10_000_000_000L) it / 1000L else it }
                        ?: (parseTimestamp(rawExpiry) / 1000L)
                    else -> 0L
                }
            } else {
                val errorMsg = if (messages != null && messages.length() > 0) {
                    val first = messages.getJSONObject(0)
                    first.optString("Szoveg", first.optString("Uzenet", "Bejelentkezés sikertelen"))
                } else {
                    "Bejelentkezés sikertelen. Kérjük ellenőrizze az adatokat!"
                }
                vimException = MavApiException(errorMsg)
            }
        } catch (t: Throwable) {
            vimException = t
        }

        if (ikToken.isNotBlank()) {
            return MavSession(
                email = trimmedEmail,
                token = ikToken,
                vimToken = vimToken,
                uaid = uaid,
                expiresAt = if (vimExpiresAt > 0L) vimExpiresAt else System.currentTimeMillis() / 1000L + 86400 * 30L
            )
        }

        if (!vimToken.isNullOrBlank()) {
            return MavSession(
                email = trimmedEmail,
                token = vimToken!!,
                vimToken = vimToken,
                uaid = uaid,
                expiresAt = vimExpiresAt
            )
        }

        if (vimException != null) {
            throw vimException
        }

        throw MavApiException("Bejelentkezés sikertelen. Kérjük ellenőrizze az adatokat!")
    }

    suspend fun loginWithGoogleCode(code: String, uaid: String): MavSession {
        val cleanCode = code.trim()

        val reqBodyJson = JSONObject().apply {
            put("provider", "GOOGLE")
            put("socialLoginOneTimeCode", cleanCode)
            put("allowConnectoToJeUser", true)
            put("allowCreateJeUser", false)
            put("allowNonValidatedConnectoToJeUser", true)
            put("firstName", JSONObject.NULL)
            put("lastName", JSONObject.NULL)
            put("socialClientId", GOOGLE_CLIENT_ID)
        }
        val requestBody = reqBodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://jegy-a.mav.hu/IK_API_PROD/api/SocialLoginApi/SocialLogin")
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json; charset=utf-8")
            .addHeader("Origin", "https://jegy.mav.hu")
            .addHeader("Referer", "https://jegy.mav.hu/")
            .addHeader("Language", "hu")
            .addHeader("User-Agent", MAV_APP_USER_AGENT)
            .post(requestBody)
            .build()

        val responseJson = try {
            executeJsonRequest(request)
        } catch (e: MavApiException) {
            val msg = when {
                e.statusCode == 401 || e.statusCode == 403 || e.statusCode == 500 || e.errorMessage.contains("OSB", ignoreCase = true) ->
                    "Érvénytelen vagy lejárt Google azonosító. Kérjük próbálja újra!"
                e.errorMessage.isNotBlank() && !e.errorMessage.startsWith("MÁV API hiba") ->
                    e.errorMessage
                else -> "A Google azonosítás sikertelen. Kérjük próbálja újra!"
            }
            throw MavApiException(msg, e.statusCode)
        }

        val token = responseJson.optString(
            "userTokenXml",
            responseJson.optString("refreshToken", responseJson.optString("token", responseJson.optString("jwtToken", "")))
        ).trim()

        if (token.isEmpty()) {
            val errorMsg = responseJson.optString(
                "errorMessage",
                responseJson.optString("message", responseJson.optString("error", "A Google bejelentkezés sikertelen"))
            )
            throw MavApiException(errorMsg)
        }

        val email = responseJson.optString(
            "email",
            responseJson.optString("loginEmail", responseJson.optString("userEmail", ""))
        ).trim()
        val firstName = responseJson.optString("firstName", responseJson.optString("keresztNev", "")).trim()
        val lastName = responseJson.optString("lastName", responseJson.optString("vezetekNev", "")).trim()
        val regId = responseJson.optString(
            "registrationId",
            responseJson.optString("regisztraciosAzonosito", responseJson.optString("userGuid", ""))
        ).trim()

        val initialUser = if (email.isNotEmpty() || firstName.isNotEmpty() || lastName.isNotEmpty()) {
            MavUser(
                email = email,
                firstName = firstName,
                lastName = lastName,
                regId = regId
            )
        } else null

        return MavSession(
            email = email,
            token = token,
            vimToken = null,
            uaid = uaid,
            expiresAt = System.currentTimeMillis() / 1000L + 86400 * 30L,
            user = initialUser
        )
    }

    suspend fun getUserData(session: MavSession): MavUser {
        val safeToken = getSafeTokenHeader(session.token)
        var email = session.email
        var firstName = ""
        var lastName = ""
        var regId = ""
        var photoBase64: String? = null
        var anyBackendReached = false
        var lastFailure: Throwable? = null

        // 1. Try IK_API_PROD GetUserPersonalDatas
        if (safeToken.isNotBlank()) {
            runCatching {
                val reqBody = JSONObject().apply {
                    put("userEmail", session.email)
                }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                val req = Request.Builder()
                    .url("https://jegy-a.mav.hu/IK_API_PROD/api/ProfileApi/GetUserPersonalDatas")
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .addHeader("Origin", "https://jegy.mav.hu")
                    .addHeader("Referer", "https://jegy.mav.hu/")
                    .addHeader("Language", "hu")
                    .addHeader("UserTokenXml", safeToken)
                    .addHeader("Authorization", "Bearer $safeToken")
                    .addHeader("User-Agent", MAV_APP_USER_AGENT)
                    .post(reqBody)
                    .build()

                val res = executeJsonRequest(req)
                anyBackendReached = true
                val uData = res.optJSONObject("userData")
                if (uData != null) {
                    val fn = uData.optString("keresztNev", "").trim()
                    val ln = uData.optString("vezetekNev", "").trim()
                    val rId = uData.optString("regisztraciosAzonosito", "").trim()
                    val em = uData.optString("userEmail", "").trim()
                    if (fn.isNotBlank()) firstName = fn
                    if (ln.isNotBlank()) lastName = ln
                    if (rId.isNotBlank()) regId = rId
                    if (em.isNotBlank()) email = em
                }
            }.onFailure { lastFailure = it }
        }

        // 2. Try VIM GetFelhasznaloAdat
        if (firstName.isBlank() && lastName.isBlank()) {
            runCatching {
                val requestJson = JSONObject().apply {
                    put("FelhasznaloAzonosito", session.email)
                    put("Nyelv", "HU")
                    put("Token", session.token)
                    put("UAID", session.uaid)
                }
                val response = postJson("GetFelhasznaloAdat", requestJson)
                anyBackendReached = true
                val userObj = response.optJSONObject("FelhasznaloAdat") ?: response
                email = userObj.optString("EmailCim", email).ifBlank { email }
                val fn = userObj.optString("KeresztNev", "").trim()
                val ln = userObj.optString("VezetekNev", "").trim()
                val rId = userObj.optString("RegAzonosito", "").trim()
                val photo = userObj.optString("Fenykep", "").ifBlank { null }
                    ?: userObj.optString("Foto", "").ifBlank { null }
                    ?: userObj.optString("ProfilKep", "").ifBlank { null }

                if (fn.isNotBlank()) firstName = fn
                if (ln.isNotBlank()) lastName = ln
                if (rId.isNotBlank()) regId = rId
                if (photo != null) photoBase64 = photo
            }.onFailure { lastFailure = it }
        }

        // 3. Fallback: Parse SAML assertion token (decoded from Base64 if needed)
        if ((firstName.isBlank() && lastName.isBlank()) && session.token.isNotBlank()) {
            val saml = runCatching {
                val raw = session.token.trim()
                if (raw.startsWith("PHNhbWwx") || (!raw.startsWith("<") && raw.length > 50)) {
                    String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
                } else {
                    raw
                }
            }.getOrDefault(session.token)

            // Try surname
            val surnameRegex = Regex("""(?:AttributeName|Name)=["'](?:[^"']*(?:surname|family_name|last_name))["'][^>]*>[^<]*<[a-zA-Z0-9:]*AttributeValue[^>]*>([^<]+)<""", RegexOption.IGNORE_CASE)
            val surnameMatch = surnameRegex.find(saml)
            if (surnameMatch != null) {
                lastName = surnameMatch.groupValues[1].trim()
            }

            // Try given_name
            val givenNameRegex = Regex("""(?:AttributeName|Name)=["'](?:[^"']*(?:givenname|first_name))["'][^>]*>[^<]*<[a-zA-Z0-9:]*AttributeValue[^>]*>([^<]+)<""", RegexOption.IGNORE_CASE)
            val givenNameMatch = givenNameRegex.find(saml)
            if (givenNameMatch != null) {
                firstName = givenNameMatch.groupValues[1].trim()
            }

            // Try name / displayName
            if (firstName.isBlank() && lastName.isBlank()) {
                val nameRegex = Regex("""(?:AttributeName|Name)=["'](?:[^"']*(?:name|displayname|commonname))["'][^>]*>[^<]*<[a-zA-Z0-9:]*AttributeValue[^>]*>([^<]+)<""", RegexOption.IGNORE_CASE)
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

        // 4. Fetch account profile picture from HPT (IK_PROFIL is the account's own profile and picture)
        val hptItems = runCatching { fetchHptDatas(session) }.getOrNull().orEmpty()
        if (hptItems.isNotEmpty()) anyBackendReached = true

        // If no MÁV backend answered at all (e.g. the device is offline), fail loudly instead of
        // returning a stripped-down user (name parsed from the SAML token, no photo) that would
        // overwrite the cached profile.
        if (!anyBackendReached) {
            throw lastFailure as? Exception
                ?: MavApiException("A MÁV szerver nem érhető el")
        }

        val accountProfile = hptItems.firstOrNull { it.dataType.equals("IK_PROFIL", ignoreCase = true) }
        if (photoBase64.isNullOrBlank() && accountProfile != null && !accountProfile.photoBase64.isNullOrBlank()) {
            photoBase64 = accountProfile.photoBase64
        }
        if (firstName.isBlank() && lastName.isBlank() && accountProfile != null && accountProfile.name.isNotBlank()) {
            // Ignore placeholder names like "Profile Picture Passenger"
            if (!accountProfile.name.contains("Profile Picture", ignoreCase = true)) {
                val parts = accountProfile.name.split(" ").filter { it.isNotBlank() }
                if (parts.size >= 2) {
                    lastName = parts[0]
                    firstName = parts.drop(1).joinToString(" ")
                } else {
                    lastName = accountProfile.name
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
        val safeToken = getSafeTokenHeader(session.token)
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
                        .addHeader("Origin", "https://jegy.mav.hu")
                        .addHeader("Referer", "https://jegy.mav.hu/")
                        .apply {
                            if (safeToken.isNotBlank()) {
                                addHeader("UserTokenXml", safeToken)
                                addHeader("Authorization", "Bearer $safeToken")
                            }
                        }
                        .addHeader("je-api-key", MAV_JE_API_KEY)
                        .addHeader("User-Agent", MAV_APP_USER_AGENT)
                        .post(reqBody)
                        .build()

                    executeJsonRequest(req)
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
                                isDefault = item.optBoolean("isDefault", false),
                                businessId = item.optString("nevesitesUzletiAzonosito", item.optString("bussinessId", ""))
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
                                isDefault = item.optBoolean("isDefault", false),
                                businessId = item.optString("nevesitesUzletiAzonosito", item.optString("bussinessId", ""))
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
                    val existingIndex = result.indexOfFirst { it.name.equals(displayName, ignoreCase = true) }
                    val bDate = parseDateToTimestamp(item.bornDate)
                    if (existingIndex >= 0) {
                        val existing = result[existingIndex]
                        if (existing.photoBase64.isNullOrBlank() && !item.photoBase64.isNullOrBlank()) {
                            result[existingIndex] = existing.copy(photoBase64 = item.photoBase64)
                        }
                        if (existing.birthDate == null && bDate != null) {
                            result[existingIndex] = result[existingIndex].copy(
                                birthDate = bDate,
                                birthDateString = item.bornDate.ifBlank { null }
                            )
                        }
                        if (existing.businessId.isBlank() && item.businessId.isNotBlank()) {
                            result[existingIndex] = result[existingIndex].copy(businessId = item.businessId)
                        }
                    } else {
                        seenNames.add(key)
                        result.add(
                            MavPassenger(
                                id = item.id,
                                name = displayName,
                                birthDate = bDate,
                                birthDateString = item.bornDate.ifBlank { null },
                                photoBase64 = item.photoBase64,
                                isDefault = item.isDefault,
                                businessId = item.businessId
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
                    if (existing.businessId.isBlank() && holder.id.isNotBlank()) {
                        result[existingIndex] = result[existingIndex].copy(businessId = holder.id)
                    }
                } else {
                    seenNames.add(key)
                    result.add(
                        MavPassenger(
                            id = holder.id,
                            name = name,
                            birthDate = holder.birthDate,
                            photoBase64 = holder.photoBase64,
                            isDefault = false,
                            businessId = holder.id
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
            put("Token", session.vimToken ?: session.token)
            put("UAID", session.uaid)
        }
        val response = runCatching { postJson("BerletTokLekeres", requestJson) }.getOrNull() ?: return emptyList()
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
        val safeToken = getSafeTokenHeader(session.token)
        val orders = mutableListOf<MavOrder>()
        val seenOrderIds = mutableSetOf<String>()
        // Every backend call below is wrapped in runCatching. Without tracking whether
        // at least one of them actually answered, an offline device would get an empty
        // list back, which callers would then persist over the good offline cache.
        var anyBackendReached = false
        var lastFailure: Throwable? = null

        // 1. Fetch from IK_API_PROD GetPreviousPurchases
        if (safeToken.isNotBlank()) {
            runCatching {
                val reqBody = JSONObject().apply {
                    put("userEmail", session.email)
                }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                val req = Request.Builder()
                    .url("https://jegy-a.mav.hu/IK_API_PROD/api/ProfileApi/GetPreviousPurchases")
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .addHeader("Origin", "https://jegy.mav.hu")
                    .addHeader("Referer", "https://jegy.mav.hu/")
                    .addHeader("Language", "hu")
                    .addHeader("UserTokenXml", safeToken)
                    .addHeader("Authorization", "Bearer $safeToken")
                    .addHeader("User-Agent", MAV_APP_USER_AGENT)
                    .post(reqBody)
                    .build()

                val res = executeJsonRequest(req)
                anyBackendReached = true
                val purchases = res.optJSONArray("previousPurchases")
                if (purchases != null) {
                    val nowMs = System.currentTimeMillis()
                    for (i in 0 until purchases.length()) {
                        val p = purchases.optJSONObject(i) ?: continue
                        val id = p.optString("id", "")
                        val name = p.optString("name", "MÁV jegy / bérlet")
                        val offerName = p.optString("firstSeasonOfferName", "").ifBlank { null }
                        val isSeason = p.optBoolean("seasonTicket", false) || p.optString("offerKind", "") == "OrszagMegyeBerlet"
                        val validFrom = parseTimestamp(p.opt("validFrom"))
                        val validTo = parseTimestamp(p.opt("validTo"))
                        val priceObj = p.optJSONObject("price")
                        val price = priceObj?.optInt("amount", 0) ?: 0
                        val passHolderId = p.opt("passHolderId")?.toString()?.ifBlank { null }
                        val status = p.optString("status", "Ervenyes")

                        val isCurrent = validTo == 0L || validTo >= nowMs
                        if (validOnly != isCurrent) continue

                        val item = MavTicketItem(
                            id = id,
                            name = if (!offerName.isNullOrBlank()) offerName else name,
                            discount = if (isSeason) "Ország-/Vármegyebérlet" else null,
                            validFrom = validFrom,
                            validTo = validTo,
                            price = price,
                            isPass = isSeason,
                            status = status
                        )
                        val receipt = MavTicketReceipt(
                            ticketNumber = passHolderId ?: id,
                            receiptId = id,
                            items = listOf(item)
                        )
                        orders.add(
                            MavOrder(
                                orderId = passHolderId ?: id,
                                purchaseDate = validFrom,
                                deliveryMode = if (isSeason) "E-Bérlet" else "E-Jegy",
                                passHolder = passHolderId?.let { MavPassHolderSummary(it, it, 0) },
                                receipts = listOf(receipt)
                            )
                        )
                        seenOrderIds.add(id)
                        if (passHolderId != null) seenOrderIds.add(passHolderId)
                    }
                }
            }.onFailure { lastFailure = it }
        }

        // 2. Fetch from VIM MegrendelesKereses and merge any orders not already present
        runCatching {
            val requestJson = JSONObject().apply {
                put("FelhasznaloAzonosito", session.email)
                put("Ervenyes", validOnly)
                put("Token", session.vimToken ?: session.token)
                put("Nyelv", "HU")
                put("UAID", session.uaid)
            }
            val response = postJson("MegrendelesKereses", requestJson)
            anyBackendReached = true

            val ordersArray = response.optJSONArray("MegrendelesLista")
                ?: response.optJSONArray("Megrendelesek")
                ?: JSONArray()

            for (i in 0 until ordersArray.length()) {
                val orderObj = ordersArray.optJSONObject(i) ?: continue
                val orderId = orderObj.optString("MegrendelesAzonosito", orderObj.optString("Azonosito", ""))
                val csoportAzon = orderObj.optString("CsoportAzon", "").trim()
                if (orderId.isNotBlank() && orderId in seenOrderIds) continue

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

                    val ticketsArray = receiptObj.optJSONArray("Jegyek")
                        ?: receiptObj.optJSONArray("Jegylista")
                        ?: JSONArray()
                    val items = mutableListOf<MavTicketItem>()

                    for (k in 0 until ticketsArray.length()) {
                        val ticketObj = ticketsArray.optJSONObject(k) ?: continue
                        val ticketId = ticketObj.optString("JegyAzonosito", ticketObj.optString("Azonosito", ticketObj.optString("TetelAzonosito", "")))
                        val title = ticketObj.optString("Nev", ticketObj.optString("Viszonylat", ticketObj.optString("Megnevezes", "MÁV jegy / bérlet")))
                        val discount = ticketObj.optString("Kedvezmeny", ticketObj.optString("KedvezmenyNev", "")).ifBlank { null }
                        val validFrom = parseTimestamp(
                            ticketObj.opt("ErvenyessegKezdete")
                                ?: ticketObj.opt("ErvenyessegKezdet")
                                ?: ticketObj.opt("ErvenyessegEleje")
                                ?: ticketObj.opt("ErvKezd")
                        )
                        val validTo = parseTimestamp(
                            ticketObj.opt("ErvenyessegVege")
                                ?: ticketObj.opt("ErvenyessegVeg")
                                ?: ticketObj.opt("ErvVeg")
                        )
                        val price = ticketObj.optInt("Ar", ticketObj.optInt("Osszeg", 0))
                        val isPass = ticketObj.optBoolean("BerletJel", false) ||
                            ticketObj.optBoolean("Berlet", false) ||
                            title.contains("bérlet", ignoreCase = true) ||
                            title.contains("orszagberlet", ignoreCase = true) ||
                            title.contains("országbérlet", ignoreCase = true) ||
                            title.contains("varmegye", ignoreCase = true) ||
                            title.contains("vármegye", ignoreCase = true)
                        val status = ticketObj.optString("Allapot", ticketObj.optString("Statusz", "")).ifBlank { null }
                        val serviceStatus = ticketObj.optString("SzolgaltatasStatusz", ticketObj.optString("SzolgaltatasAllapot", "")).ifBlank { null }
                        val direction = ticketObj.optString("Irany", ticketObj.optString("UtazasIranya", "")).ifBlank { null }
                        val serviceId = ticketObj.optString("VonatSzam", ticketObj.optString("Jaratszam", ticketObj.optString("SzolgaltatasAzonosito", ""))).ifBlank { null }

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

                val builtOrder = MavOrder(
                    orderId = orderId,
                    purchaseDate = purchaseDate,
                    deliveryMode = deliveryMode.ifBlank { null },
                    passHolder = resolvedPassHolder,
                    receipts = receipts
                )
                if (validOnly != builtOrder.isValidNow) continue

                // Check if this VIM order matches an order already added (e.g. from IK GetPreviousPurchases)
                val matchingIdx = orders.indexOfFirst { ex ->
                    ex.orderId == orderId ||
                    (csoportAzon.isNotBlank() && (ex.orderId.contains(csoportAzon) || ex.receipts.any { it.receiptId.contains(csoportAzon) })) ||
                    (resolvedPassHolder?.id != null && (ex.orderId == resolvedPassHolder.id || ex.passHolder?.id == resolvedPassHolder.id)) ||
                    receipts.any { r ->
                        ex.receipts.any { exR ->
                            (exR.receiptId.isNotBlank() && (exR.receiptId == r.receiptId || exR.receiptId.contains(r.receiptId))) ||
                            (!exR.ticketNumber.isNullOrBlank() && exR.ticketNumber == r.ticketNumber)
                        }
                    } ||
                    (receipts.isNotEmpty() && ex.allItems.any { exItem ->
                        !exItem.serviceId.isNullOrBlank() && receipts.any { r -> r.items.any { it.serviceId == exItem.serviceId } }
                    })
                }

                if (matchingIdx != -1) {
                    val ex = orders[matchingIdx]
                    val mergedOrderId = if (ex.orderId.isNotBlank() && !ex.orderId.contains("#")) ex.orderId else orderId
                    val mergedPurchaseDate = if (purchaseDate > 0) purchaseDate else ex.purchaseDate
                    val mergedPassHolder = ex.passHolder ?: resolvedPassHolder
                    val mergedDeliveryMode = ex.deliveryMode ?: deliveryMode.ifBlank { null }

                    val mergedReceipts = ex.receipts.mapIndexed { idx, exR ->
                        val vimR = receipts.getOrNull(idx) ?: receipts.firstOrNull()
                        val passengerName = vimR?.passengerName?.ifBlank { null } ?: exR.passengerName
                        val birthDate = vimR?.birthDate?.takeIf { it > 0 } ?: exR.birthDate
                        val ticketNumber = vimR?.ticketNumber?.ifBlank { null } ?: exR.ticketNumber

                        val mergedItems = exR.items.mapIndexed { itemIdx, exItem ->
                            val vimItem = vimR?.items?.getOrNull(itemIdx) ?: vimR?.items?.firstOrNull()
                            val name = if (exItem.name.isNotBlank() && !exItem.name.equals("MÁV jegy / bérlet", ignoreCase = true)) {
                                exItem.name
                            } else {
                                vimItem?.name?.ifBlank { null } ?: exItem.name
                            }
                            val discount = when {
                                !exItem.discount.isNullOrBlank() && !vimItem?.discount.isNullOrBlank() &&
                                    !exItem.discount.equals(vimItem.discount, ignoreCase = true) ->
                                    "${exItem.discount} • ${vimItem.discount}"
                                !exItem.discount.isNullOrBlank() -> exItem.discount
                                else -> vimItem?.discount
                            }
                            val validFrom = if (exItem.validFrom > 0) exItem.validFrom else (vimItem?.validFrom ?: 0L)
                            val validTo = if (exItem.validTo > 0) exItem.validTo else (vimItem?.validTo ?: 0L)
                            val price = if (exItem.price > 0) exItem.price else (vimItem?.price ?: 0)
                            val isPass = exItem.isPass || (vimItem?.isPass == true)

                            exItem.copy(
                                name = name,
                                discount = discount,
                                validFrom = validFrom,
                                validTo = validTo,
                                price = price,
                                isPass = isPass
                            )
                        }

                        exR.copy(
                            passengerName = passengerName,
                            birthDate = birthDate,
                            ticketNumber = ticketNumber,
                            items = mergedItems
                        )
                    }

                    orders[matchingIdx] = ex.copy(
                        orderId = mergedOrderId,
                        purchaseDate = mergedPurchaseDate,
                        deliveryMode = mergedDeliveryMode,
                        passHolder = mergedPassHolder,
                        receipts = mergedReceipts
                    )
                } else {
                    orders.add(builtOrder)
                }
                seenOrderIds.add(orderId)
                if (csoportAzon.isNotBlank()) seenOrderIds.add(csoportAzon)
            }
        }.onFailure { lastFailure = it }

        if (!anyBackendReached) {
            throw lastFailure as? Exception
                ?: MavApiException("A MÁV szerver nem érhető el")
        }

        // 3. Check BerletTokok to make sure any standalone passes are listed!
        val passHolders = runCatching { getAllPassHolders(session) }.getOrNull().orEmpty()
        val existingReceiptIds = orders.flatMap { it.receipts }.map { it.receiptId }.toSet()
        val existingPassIds = orders.mapNotNull { it.passHolder?.id }.toSet()
        val nowMs = System.currentTimeMillis()

        for (holder in passHolders) {
            val isCurrent = holder.validTo == 0L || holder.validTo >= nowMs
            if (validOnly != isCurrent) continue
            if (holder.id.isNotBlank() && holder.id !in existingPassIds && holder.id !in existingReceiptIds && holder.id !in seenOrderIds) {
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
                seenOrderIds.add(holder.id)
            }
        }

        return orders
    }

    suspend fun getTicketImage(session: MavSession, receiptId: String): MavTicketImage? {
        val safeToken = getSafeTokenHeader(session.token)

        // 1. Try IK_API_PROD GetPreviousPurchaseDetails and GetCertificatesImages
        if (safeToken.isNotBlank()) {
            val result = runCatching {
                val detBody = JSONObject().apply {
                    put("userEmail", session.email)
                    put("id", receiptId)
                }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                val detReq = Request.Builder()
                    .url("https://jegy-a.mav.hu/IK_API_PROD/api/ProfileApi/GetPreviousPurchaseDetails")
                    .addHeader("Accept", "application/json")
                    .addHeader("UserSessionId", session.uaid.ifBlank { UUID.randomUUID().toString() })
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .addHeader("Origin", "https://jegy.mav.hu")
                    .addHeader("Referer", "https://jegy.mav.hu/")
                    .addHeader("Language", "hu")
                    .addHeader("UserTokenXml", safeToken)
                    .addHeader("Authorization", "Bearer $safeToken")
                    .addHeader("User-Agent", MAV_APP_USER_AGENT)
                    .post(detBody)
                    .build()

                val detRes = executeJsonRequest(detReq)

                // 1A. Try GetCertificatesImages with financialDatas if present (works without VIM token for all users)
                val financialDatas = detRes.optJSONArray("financialDatas")
                    ?: detRes.optJSONObject("previousPurchaseDetails")?.optJSONArray("financialDatas")
                if (financialDatas != null && financialDatas.length() > 0) {
                    val certResult = runCatching {
                        val certReqBody = JSONObject().apply {
                            val reqModel = JSONObject().apply {
                                put("certificateType", "Ticket")
                                put("financialDatas", financialDatas)
                            }
                            put("requestModel", reqModel)
                        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                        val certReq = Request.Builder()
                            .url("https://jegy-a.mav.hu/IK_API_PROD/api/OrderAndPaymentApi/GetCertificatesImages")
                            .addHeader("Accept", "application/json")
                            .addHeader("UserSessionId", session.uaid.ifBlank { UUID.randomUUID().toString() })
                            .addHeader("Content-Type", "application/json; charset=utf-8")
                            .addHeader("Origin", "https://jegy.mav.hu")
                            .addHeader("Referer", "https://jegy.mav.hu/")
                            .addHeader("Language", "hu")
                            .addHeader("UserTokenXml", safeToken)
                            .addHeader("Authorization", "Bearer $safeToken")
                            .addHeader("User-Agent", MAV_APP_USER_AGENT)
                            .post(certReqBody)
                            .build()

                        val certRes = executeJsonRequest(certReq)
                        val certs = certRes.optJSONArray("tiketsAndCertificates")
                            ?: certRes.optJSONArray("ticketsAndCertificates")
                        if (certs != null && certs.length() > 0) {
                            for (i in 0 until certs.length()) {
                                val c = certs.optJSONObject(i) ?: continue
                                val isCert = c.optBoolean("isCertificate", false)
                                val imgB64 = c.optString("image", "").trim()
                                if (!isCert && imgB64.isNotEmpty()) {
                                    val certId = c.optString("certificateId", receiptId)
                                    return@runCatching MavTicketImage(
                                        receiptId = receiptId,
                                        ticketNumber = certId,
                                        kind = "Ticket",
                                        imageBase64 = imgB64
                                    )
                                }
                            }
                        }
                        null
                    }.getOrNull()
                    if (certResult != null) return@runCatching certResult
                }

                // 1B. Fallback to ticketDatas with bizonylatTechnikaiAzonosito
                val prevDetails = detRes.optJSONObject("previousPurchaseDetails") ?: detRes
                val ticketDatas = prevDetails.optJSONArray("ticketDatas")
                if (ticketDatas != null && ticketDatas.length() > 0) {
                    val td = ticketDatas.getJSONObject(0)
                    val bizTechId = td.optString("bizonylatTechnikaiAzonosito", "").trim()
                    val jegySorszam = td.optString("jegySorszam", "").trim().ifBlank { null }
                    if (bizTechId.isNotBlank()) {
                        val vimImg = fetchVimTicketImage(session, bizTechId, jegySorszam, receiptId)
                        if (vimImg != null) return@runCatching vimImg
                    }
                }

                null
            }.getOrNull()
            if (result != null) return result
        }

        // 2. Direct / Fallback to VIM GetJegykep
        return fetchVimTicketImage(session, receiptId, null, receiptId)
    }

    private suspend fun fetchVimTicketImage(
        session: MavSession,
        bizonylatId: String,
        ticketNumber: String?,
        originalReceiptId: String
    ): MavTicketImage? {
        val tokenToUse = session.vimToken ?: session.token
        return runCatching {
            val requestJson = JSONObject().apply {
                put("FelhasznaloAzonosito", session.email)
                val array = JSONArray()
                val numericId = bizonylatId.toLongOrNull()
                if (numericId != null) {
                    array.put(numericId)
                } else {
                    array.put(bizonylatId)
                }
                put("BizonylatAzonosito", array)
                put("Token", tokenToUse)
                put("Nyelv", "HU")
                put("UAID", session.uaid)
            }
            val response = postJson("GetJegykep", requestJson)
            val images = response.optJSONArray("JegykepAdatok")
                ?: response.optJSONArray("JegykepLista")
                ?: response.optJSONArray("Bizonylatok")
                ?: JSONArray()
            if (images.length() == 0) return@runCatching null

            for (i in 0 until images.length()) {
                val imgObj = images.optJSONObject(i) ?: continue
                val b64 = imgObj.optString("Jegykep", imgObj.optString("Kep", "")).trim()
                if (b64.isNotEmpty() && !b64.startsWith("JVBER")) {
                    val sorszam = imgObj.optString("Jegysorszam", imgObj.optString("Jegyszam", imgObj.optString("Sorszam", ""))).ifBlank { ticketNumber }
                    val kind = imgObj.optString("BizonylatFajta", imgObj.optString("Tipus", "Jegykep")).ifBlank { "Jegykep" }
                    if (!kind.equals("Certificate", ignoreCase = true)) {
                        return@runCatching MavTicketImage(
                            receiptId = originalReceiptId,
                            ticketNumber = sorszam,
                            kind = kind,
                            imageBase64 = b64
                        )
                    }
                }
            }
            null
        }.getOrNull()
    }

    suspend fun getPassHolder(session: MavSession, nevesitesAzonosito: String): MavPassHolder? {
        val requestJson = JSONObject().apply {
            put("FelhasznaloAzonosito", session.email)
            put("NevesitesAzonosito", nevesitesAzonosito)
            put("Token", session.vimToken ?: session.token)
            put("Nyelv", "HU")
            put("UAID", session.uaid)
        }
        val response = runCatching { postJson("BerletTokLekeres", requestJson) }.getOrNull() ?: return null
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
                        vimErrorOnlyResponse(json)?.let { throw it }
                        if (continuation.isActive) continuation.resume(json)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    companion object {
        const val GOOGLE_CLIENT_ID = "989905229285-hqta0tjm61slvfn3mkic8qvgjob0mvqj.apps.googleusercontent.com"
        const val GOOGLE_REDIRECT_URI = "https://jegy.mav.hu/"
        const val GOOGLE_AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth?scope=email%20profile&redirect_uri=https%3A%2F%2Fjegy.mav.hu%2F&client_id=989905229285-hqta0tjm61slvfn3mkic8qvgjob0mvqj.apps.googleusercontent.com&response_type=code"
        const val MAV_JE_API_KEY = "232AC6FE-46BE-479B-894D-BFC96E548945"
        const val MAV_JE_PARTNER_NAME = "App2025"
        const val MAV_APP_USER_AGENT = "MAVApp/2.6.15-prod (hu.mav.emmapp; build: 950; Android 14)"
        const val PAPI_PROD_URL = "https://mvapi.mav.hu/IN/PROD/"
        const val DEFAULT_USER_PROFILE_PATH = "UserProfile/4_13_0_52/"
        const val CHROME_MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /**
         * VIM answers rejected requests (e.g. an expired token) with HTTP 200 and a body that only
         * holds an error message: {"Uzenetek":[{"Tipus":"R","Szoveg":"Hibas input: Nem megfelelo SAML token!"}]}.
         * Treat such a payload as a failure, otherwise callers would read it as "no tickets"
         * and overwrite the offline cache with an empty list.
         */
        internal fun vimErrorOnlyResponse(json: JSONObject): MavApiException? {
            val messages = json.optJSONArray("Uzenetek") ?: return null
            var errorText: String? = null
            for (i in 0 until messages.length()) {
                val msg = messages.optJSONObject(i) ?: continue
                if (msg.optString("Tipus", "").equals("R", ignoreCase = true)) {
                    errorText = msg.optString("Szoveg", msg.optString("Uzenet", "")).ifBlank { "MÁV API hiba" }
                    break
                }
            }
            if (errorText == null) return null
            val hasPayload = json.keys().asSequence().any { key ->
                key != "Uzenetek" && when (val v = json.opt(key)) {
                    null, JSONObject.NULL -> false
                    is JSONArray -> v.length() > 0
                    is JSONObject -> v.length() > 0
                    is String -> v.isNotBlank()
                    else -> true
                }
            }
            if (hasPayload) return null
            val isAuth = errorText.contains("token", ignoreCase = true)
            return MavApiException(errorText, if (isAuth) 401 else 0)
        }

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
