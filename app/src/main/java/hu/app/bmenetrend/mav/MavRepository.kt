package hu.app.bmenetrend.mav

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MavSessionStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreateUaid(): String {
        val stored = prefs.getString(KEY_UAID, null)
        if (!stored.isNullOrBlank()) return stored
        prefs.edit().putString(KEY_UAID, DEFAULT_UAID).apply()
        return DEFAULT_UAID
    }

    fun getStoredSession(): MavSession? {
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        if (token.isBlank()) return null
        val uaid = getOrCreateUaid()
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val firstName = prefs.getString(KEY_FIRST_NAME, "").orEmpty()
        val lastName = prefs.getString(KEY_LAST_NAME, "").orEmpty()
        val regId = prefs.getString(KEY_REG_ID, "").orEmpty()
        val photoBase64 = prefs.getString(KEY_PHOTO, null)

        val user = if (firstName.isNotEmpty() || lastName.isNotEmpty() || !photoBase64.isNullOrEmpty()) {
            MavUser(email = email, firstName = firstName, lastName = lastName, regId = regId, photoBase64 = photoBase64)
        } else {
            MavUser(email = email, regId = regId)
        }

        return MavSession(
            email = email,
            token = token,
            uaid = uaid,
            expiresAt = expiresAt,
            user = user
        )
    }

    fun saveSession(session: MavSession) {
        prefs.edit()
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_TOKEN, session.token)
            .putString(KEY_UAID, session.uaid)
            .putLong(KEY_EXPIRES_AT, session.expiresAt)
            .putString(KEY_FIRST_NAME, session.user?.firstName.orEmpty())
            .putString(KEY_LAST_NAME, session.user?.lastName.orEmpty())
            .putString(KEY_REG_ID, session.user?.regId.orEmpty())
            .putString(KEY_PHOTO, session.user?.photoBase64)
            .apply()
    }

    fun clearSession() {
        prefs.edit().clear().putString(KEY_UAID, DEFAULT_UAID).apply()
    }

    companion object {
        private const val PREFS_NAME = "mav_session_prefs"
        private const val KEY_UAID = "uaid"
        private const val KEY_EMAIL = "email"
        private const val KEY_TOKEN = "token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_FIRST_NAME = "first_name"
        private const val KEY_LAST_NAME = "last_name"
        private const val KEY_REG_ID = "reg_id"
        private const val KEY_PHOTO = "photo_base64"

        const val DEFAULT_UAID = "2Juija1mabqr24Blkx1qkXxJ105j"
    }
}

class MavRepository(
    private val sessionStore: MavSessionStore,
    private val cacheStore: MavCacheStore,
    private val apiClient: MavApiClient = MavApiClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(
        context: Context,
        apiClient: MavApiClient = MavApiClient(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        sessionStore = MavSessionStore(context),
        cacheStore = MavCacheStore(context),
        apiClient = apiClient,
        ioDispatcher = ioDispatcher
    )

    private val _session = MutableStateFlow<MavSession?>(sessionStore.getStoredSession())
    val session: StateFlow<MavSession?> = _session.asStateFlow()

    suspend fun login(email: String, password: String): MavSession = withContext(ioDispatcher) {
        val uaid = sessionStore.getOrCreateUaid()
        val baseSession = apiClient.login(email.trim(), password, uaid)
        val user = runCatching { apiClient.getUserData(baseSession) }.getOrNull()
        val fullSession = baseSession.copy(user = user)
        sessionStore.saveSession(fullSession)
        _session.value = fullSession
        fullSession
    }

    suspend fun loginWithToken(email: String, tokenXml: String): MavSession =
        loginWithGoogle(MavGoogleLoginPayload(email = email, tokenXml = tokenXml, sessionId = ""))

    suspend fun loginWithGoogle(payload: MavGoogleLoginPayload): MavSession = withContext(ioDispatcher) {
        val uaid = payload.sessionId.ifBlank { sessionStore.getOrCreateUaid() }

        // 1. Parse name from personalDataJson
        var firstName = ""
        var lastName = ""
        var regId = ""
        if (!payload.personalDataJson.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(payload.personalDataJson)
                val userObj = root.optJSONObject("userData") ?: root
                lastName = userObj.optString("vezetekNev", userObj.optString("VezetekNev", "")).trim()
                firstName = userObj.optString("keresztNev", userObj.optString("KeresztNev", "")).trim()
                regId = userObj.optString("regisztraciosAzonosito", userObj.optString("RegAzonosito", "")).trim()
            }
        }

        // 2. Parse photo and standalone passes from hptDataJson
        var photoBase64: String? = null
        val passOrders = mutableListOf<MavOrder>()
        if (!payload.hptDataJson.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(payload.hptDataJson)
                val array = root.optJSONArray("hptData") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    if (photoBase64.isNullOrBlank()) {
                        val p = item.optString("photo", item.optString("fenykep", item.optString("Fenykep", ""))).trim()
                        if (p.isNotBlank()) photoBase64 = p
                    }
                    if (firstName.isBlank() && lastName.isBlank()) {
                        val name = item.optString("name", item.optString("nev", item.optString("Nev", ""))).trim()
                        if (name.isNotBlank()) {
                            val parts = name.split(" ").filter { it.isNotBlank() }
                            if (parts.size >= 2) {
                                lastName = parts[0]
                                firstName = parts.drop(1).joinToString(" ")
                            } else {
                                lastName = name
                            }
                        }
                    }

                    val passHolderId = item.optString("id", "")
                    val passName = item.optString("name", item.optString("alias", "MÁV Bérlet"))
                    val bornDate = MavApiClient.parseTimestamp(item.opt("bornDate"))
                    val passList = item.optJSONArray("passDataList")
                    if (passList != null && passList.length() > 0) {
                        for (pIdx in 0 until passList.length()) {
                            val passObj = passList.optJSONObject(pIdx) ?: continue
                            val pName = passObj.optString("name", passName)
                            val vStart = MavApiClient.parseTimestamp(passObj.opt("validityStart"))
                            val vEnd = MavApiClient.parseTimestamp(passObj.opt("validityEnd"))
                            val startSt = passObj.optString("startStation", "")
                            val endSt = passObj.optString("endStation", "")
                            val direction = if (startSt.isNotBlank() && endSt.isNotBlank()) "$startSt – $endSt" else null

                            val ticketItem = MavTicketItem(
                                id = passHolderId.ifBlank { "HPT-$i-$pIdx" },
                                name = pName,
                                discount = direction,
                                validFrom = vStart,
                                validTo = vEnd,
                                price = 0,
                                isPass = true,
                                status = "Érvényes",
                                direction = direction
                            )
                            val receipt = MavTicketReceipt(
                                passengerName = item.optString("name", "").ifBlank { null },
                                birthDate = if (bornDate > 0) bornDate else null,
                                ticketNumber = passHolderId.ifBlank { null },
                                receiptId = passHolderId.ifBlank { "HPT-$i-$pIdx" },
                                items = listOf(ticketItem)
                            )
                            passOrders.add(
                                MavOrder(
                                    orderId = passHolderId.ifBlank { "HPT-$i" },
                                    purchaseDate = vStart,
                                    deliveryMode = "E-Bérlet",
                                    passHolder = MavPassHolderSummary(id = passHolderId),
                                    receipts = listOf(receipt)
                                )
                            )
                        }
                    } else if (item.optBoolean("hasValidTicket", false) || item.optString("dataTypeString") == "BERLETTOK") {
                        val ticketItem = MavTicketItem(
                            id = passHolderId.ifBlank { "HPT-$i" },
                            name = passName,
                            price = 0,
                            isPass = true,
                            status = "Érvényes"
                        )
                        val receipt = MavTicketReceipt(
                            passengerName = item.optString("name", "").ifBlank { null },
                            birthDate = if (bornDate > 0) bornDate else null,
                            ticketNumber = passHolderId.ifBlank { null },
                            receiptId = passHolderId.ifBlank { "HPT-$i" },
                            items = listOf(ticketItem)
                        )
                        passOrders.add(
                            MavOrder(
                                orderId = passHolderId.ifBlank { "HPT-$i" },
                                purchaseDate = 0L,
                                deliveryMode = "E-Bérlet",
                                passHolder = MavPassHolderSummary(id = passHolderId),
                                receipts = listOf(receipt)
                            )
                        )
                    }
                }
            }
        }

        // 3. Fallback name and photo to SAML token if still blank
        if (payload.tokenXml.isNotBlank()) {
            runCatching {
                val saml = payload.tokenXml
                val attrRegex = Regex("""<(?:[a-zA-Z0-9:]+)?Attribute[^>]*\b(?:AttributeName|Name)=["']([^"']+)["'][^>]*>[\s\S]*?<[a-zA-Z0-9:]*AttributeValue[^>]*>([^<]+)<""", RegexOption.IGNORE_CASE)
                val matches = attrRegex.findAll(saml)
                var rawFullName: String? = null
                var rawGivenName: String? = null
                var rawSurname: String? = null
                for (m in matches) {
                    val attr = m.groupValues[1].lowercase()
                    val value = m.groupValues[2].trim()
                    if (value.isBlank()) continue
                    when {
                        attr.endsWith("surname") || attr.endsWith("sn") -> rawSurname = value
                        attr.endsWith("givenname") || attr.endsWith("given_name") -> rawGivenName = value
                        attr.endsWith("name") || attr.endsWith("displayname") || attr.endsWith("commonname") -> rawFullName = value
                        photoBase64.isNullOrBlank() && (attr.contains("photo") || attr.contains("avatar") || attr.contains("picture")) -> photoBase64 = value
                    }
                }
                if (firstName.isBlank() && lastName.isBlank()) {
                    if (!rawSurname.isNullOrBlank() && !rawGivenName.isNullOrBlank()) {
                        lastName = rawSurname
                        firstName = rawGivenName
                    } else if (!rawFullName.isNullOrBlank()) {
                        val parts = rawFullName.split(" ").filter { it.isNotBlank() }
                        if (parts.size >= 2) {
                            lastName = parts[0]
                            firstName = parts.drop(1).joinToString(" ")
                        } else if (parts.size == 1) {
                            lastName = parts[0]
                        }
                    }
                }
            }
        }

        // 4. Photo fallback from personalDataJson if not yet found
        if (photoBase64.isNullOrBlank() && !payload.personalDataJson.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(payload.personalDataJson)
                val userObj = root.optJSONObject("userData") ?: root
                val p = userObj.optString("photo", userObj.optString("fenykep", userObj.optString("profileImage", ""))).trim()
                if (p.isNotBlank()) photoBase64 = p
            }
        }

        // 5. Parse previous purchases
        val purchaseOrders = mutableListOf<MavOrder>()
        if (!payload.purchasesJson.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(payload.purchasesJson)
                val array = root.optJSONArray("previousPurchases") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val p = array.optJSONObject(i) ?: continue
                    val orderId = p.optString("id", "ORD-$i")
                    val isPass = p.optBoolean("seasonTicket", false) || p.optString("offerKind", "").contains("BERLET", ignoreCase = true)
                    val pName = p.optString("firstSeasonOfferName", p.optString("name", "MÁV jegy"))
                    val vStart = MavApiClient.parseTimestamp(p.opt("validityStart"))
                    val vEnd = MavApiClient.parseTimestamp(p.opt("validityEnd"))
                    val price = p.optJSONObject("price")?.optInt("amount", 0) ?: p.optInt("price", 0)
                    val startSt = p.optString("startStation", "")
                    val endSt = p.optString("endStation", "")
                    val direction = if (startSt.isNotBlank() && endSt.isNotBlank()) "$startSt – $endSt" else null

                    val item = MavTicketItem(
                        id = orderId,
                        name = pName,
                        discount = direction,
                        validFrom = vStart,
                        validTo = vEnd,
                        price = price,
                        isPass = isPass,
                        status = "Érvényes",
                        direction = direction
                    )
                    val receipt = MavTicketReceipt(
                        ticketNumber = orderId,
                        receiptId = orderId,
                        items = listOf(item)
                    )
                    purchaseOrders.add(
                        MavOrder(
                            orderId = orderId,
                            purchaseDate = vStart,
                            deliveryMode = if (isPass) "E-Bérlet" else "E-Jegy",
                            receipts = listOf(receipt)
                        )
                    )
                }
            }
        }

        val allOrders = passOrders + purchaseOrders
        if (allOrders.isNotEmpty()) {
            val active = allOrders.filter { it.isValidNow }
            val past = allOrders.filter { !it.isValidNow }
            cacheStore.saveOrders(active, past)
        }

        // 6. Fallback: If still incomplete, try apiClient.getUserData
        var user = MavUser(
            email = payload.email.trim(),
            firstName = firstName,
            lastName = lastName,
            regId = regId,
            photoBase64 = photoBase64
        )
        if (user.firstName.isBlank() && user.lastName.isBlank()) {
            val fetchedUser = runCatching {
                apiClient.getUserData(MavSession(payload.email.trim(), payload.tokenXml, uaid))
            }.getOrNull()
            if (fetchedUser != null) {
                user = user.copy(
                    firstName = fetchedUser.firstName.ifBlank { user.firstName },
                    lastName = fetchedUser.lastName.ifBlank { user.lastName },
                    photoBase64 = fetchedUser.photoBase64 ?: user.photoBase64
                )
            }
        }

        val fullSession = MavSession(
            email = payload.email.trim(),
            token = payload.tokenXml,
            uaid = uaid,
            expiresAt = System.currentTimeMillis() / 1000 + 86400 * 30,
            user = user
        )
        sessionStore.saveSession(fullSession)
        _session.value = fullSession
        fullSession
    }

    suspend fun refreshUserData(): MavUser? = withContext(ioDispatcher) {
        val currentSession = _session.value ?: return@withContext null
        val user = runCatching { apiClient.getUserData(currentSession) }.getOrNull()
        if (user != null) {
            val updated = currentSession.copy(user = user)
            sessionStore.saveSession(updated)
            _session.value = updated
        }
        user
    }

    suspend fun logout() = withContext(ioDispatcher) {
        sessionStore.clearSession()
        cacheStore.clear()
        _session.value = null
    }

    fun getCachedOrders(): Pair<List<MavOrder>, List<MavOrder>>? {
        return cacheStore.getOrders()
    }

    fun getLastSyncTime(): Long {
        return cacheStore.getLastSyncTime()
    }

    suspend fun syncTicketsAndPasses(): Pair<List<MavOrder>, List<MavOrder>> = withContext(ioDispatcher) {
        val currentSession = _session.value ?: throw MavApiException("Nincs bejelentkezve MÁV fiókba")

        // If user profile is incomplete, refresh it
        if (currentSession.user?.firstName.isNullOrBlank() || currentSession.user?.photoBase64.isNullOrBlank()) {
            runCatching { refreshUserData() }
        }

        try {
            // 1. Fetch active orders (valid tickets and passes)
            val active = apiClient.getOrders(currentSession, validOnly = true)

            // 2. Fetch past orders
            val past = runCatching { apiClient.getOrders(currentSession, validOnly = false) }.getOrDefault(emptyList())

            // 3. Cache orders to disk
            cacheStore.saveOrders(active, past)

            // 4. Pre-fetch ticket images and pass holders for active tickets
            for (order in active) {
                order.passHolder?.id?.let { phId ->
                    if (phId.isNotBlank() && cacheStore.getPassHolder(phId) == null) {
                        runCatching {
                            val holder = apiClient.getPassHolder(currentSession, phId)
                            if (holder != null) cacheStore.savePassHolder(phId, holder)
                        }
                    }
                }
                for (receipt in order.receipts) {
                    val receiptId = receipt.receiptId
                    if (receiptId.isNotBlank()) {
                        runCatching {
                            val img = apiClient.getTicketImage(currentSession, receiptId)
                            if (img != null) {
                                cacheStore.saveTicketImage(receiptId, img)
                            }
                        }
                    }
                }
            }

            Pair(active, past)
        } catch (e: Exception) {
            // If online fetch fails (e.g. Google OAuth session or offline), fall back to cached orders
            val cached = cacheStore.getOrders()
            if (cached != null && (cached.first.isNotEmpty() || cached.second.isNotEmpty())) {
                cached
            } else {
                throw e
            }
        }
    }

    suspend fun getOrders(validOnly: Boolean): List<MavOrder> = withContext(ioDispatcher) {
        val currentSession = _session.value ?: throw MavApiException("Nincs bejelentkezve MÁV fiókba")
        try {
            val orders = apiClient.getOrders(currentSession, validOnly)
            val existing = cacheStore.getOrders()
            if (validOnly) {
                cacheStore.saveOrders(orders, existing?.second ?: emptyList())
            } else {
                cacheStore.saveOrders(existing?.first ?: emptyList(), orders)
            }
            orders
        } catch (e: Exception) {
            val cached = cacheStore.getOrders()
            if (cached != null) {
                if (validOnly) cached.first else cached.second
            } else {
                throw e
            }
        }
    }

    suspend fun getTicketImage(receiptId: String): MavTicketImage? = withContext(ioDispatcher) {
        val cached = cacheStore.getTicketImage(receiptId)
        if (cached != null) return@withContext cached

        val currentSession = _session.value ?: throw MavApiException("Nincs bejelentkezve MÁV fiókba")
        val fetched = runCatching { apiClient.getTicketImage(currentSession, receiptId) }.getOrNull()
        if (fetched != null) {
            cacheStore.saveTicketImage(receiptId, fetched)
        }
        fetched
    }

    suspend fun getPassHolder(nevesitesAzonosito: String): MavPassHolder? = withContext(ioDispatcher) {
        val cached = cacheStore.getPassHolder(nevesitesAzonosito)
        if (cached != null) return@withContext cached

        val currentSession = _session.value ?: throw MavApiException("Nincs bejelentkezve MÁV fiókba")
        val fetched = runCatching { apiClient.getPassHolder(currentSession, nevesitesAzonosito) }.getOrNull()
        if (fetched != null) {
            cacheStore.savePassHolder(nevesitesAzonosito, fetched)
        }
        fetched
    }
}
