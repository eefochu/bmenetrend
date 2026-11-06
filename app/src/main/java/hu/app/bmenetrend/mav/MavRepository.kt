package hu.app.bmenetrend.mav

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

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

    private val _session = MutableStateFlow<MavSession?>(
        sessionStore.getStoredSession() ?: cacheStore.getUser()?.let { cachedUser ->
            MavSession(
                email = cachedUser.email,
                token = "",
                uaid = sessionStore.getOrCreateUaid(),
                expiresAt = 0L,
                user = cachedUser
            )
        }
    )
    val session: StateFlow<MavSession?> = _session.asStateFlow()

    suspend fun login(email: String, password: String): MavSession = withContext(ioDispatcher) {
        val uaid = sessionStore.getOrCreateUaid()
        val baseSession = apiClient.login(email.trim(), password, uaid)
        val user = runCatching { apiClient.getUserData(baseSession) }.getOrNull()
        val fullSession = baseSession.copy(user = user)
        sessionStore.saveSession(fullSession)
        if (user != null) cacheStore.saveUser(user)
        _session.value = fullSession

        // Pre-fetch and cache passengers right after login
        runCatching {
            val passengers = apiClient.getPassengers(fullSession)
            if (passengers.isNotEmpty()) {
                cacheStore.savePassengers(passengers)
            }
        }

        fullSession
    }

    suspend fun refreshUserData(): MavUser? = withContext(ioDispatcher) {
        val currentSession = _session.value ?: return@withContext null
        val user = runCatching { apiClient.getUserData(currentSession) }.getOrNull()
        if (user != null) {
            val updated = currentSession.copy(user = user)
            sessionStore.saveSession(updated)
            cacheStore.saveUser(user)
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

    fun getCachedUser(): MavUser? {
        return cacheStore.getUser()
    }

    fun getPassengers(): List<MavPassenger> {
        return cacheStore.getPassengers()
    }

    fun getCachedPassHolder(id: String): MavPassHolder? {
        return cacheStore.getPassHolder(id)
    }

    fun getLastSyncTime(): Long {
        return cacheStore.getLastSyncTime()
    }

    suspend fun syncTicketsAndPasses(): Pair<List<MavOrder>, List<MavOrder>> = withContext(ioDispatcher) {
        val currentSession = _session.value ?: throw MavApiException("Nincs bejelentkezve MÁV fiókba")

        // 1. If user profile is incomplete, refresh it
        if (currentSession.user?.firstName.isNullOrBlank() || currentSession.user?.photoBase64.isNullOrBlank()) {
            runCatching { refreshUserData() }
        }

        try {
            // 2. Fetch and cache passengers
            runCatching {
                val passengers = apiClient.getPassengers(currentSession)
                if (passengers.isNotEmpty()) {
                    cacheStore.savePassengers(passengers)
                }
            }

            // 3. Fetch active orders (valid tickets and passes)
            val active = apiClient.getOrders(currentSession, validOnly = true)

            // 4. Fetch past orders
            val past = runCatching { apiClient.getOrders(currentSession, validOnly = false) }.getOrDefault(emptyList())

            // 5. Cache orders to disk
            cacheStore.saveOrders(active, past)

            // 6. Pre-fetch ticket images and pass holders for active tickets
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
