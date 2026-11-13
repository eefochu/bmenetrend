package hu.app.bmenetrend.mav

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class LoginUiState {
    object Idle : LoginUiState()
    object Loading : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}

sealed class TicketsUiState {
    object Loading : TicketsUiState()
    data class Success(
        val activeOrders: List<MavOrder>,
        val pastOrders: List<MavOrder>,
        val isOffline: Boolean = false,
        val lastSyncTime: Long = 0L,
        /** The MÁV server rejected the stored token: cached data is shown, user should log in again. */
        val sessionExpired: Boolean = false
    ) : TicketsUiState() {
        val active: List<MavOrder> get() = activeOrders
        val past: List<MavOrder> get() = pastOrders
    }
    data class Error(val message: String) : TicketsUiState()
}

enum class MavTab {
    ACTIVE,
    PAST
}

data class TicketBarcodeData(
    val receiptId: String,
    val ticketNumber: String? = null,
    val title: String,
    val isPass: Boolean = false,
    val passengerName: String? = null,
    val birthDate: Long? = null,
    val passengerPhotoBase64: String? = null,
    val passHolderNumber: String? = null,
    val idCardNumber: String? = null,
    val bitmap: Bitmap? = null,
    val loading: Boolean = false,
    val error: String? = null,
    /** Extra explanation shown under the loading indicator (e.g. while waiting for MÁV to allow the download). */
    val notice: String? = null
) {
    fun toPassengerPhotoBitmap(): Bitmap? {
        val raw = passengerPhotoBase64 ?: return null
        return runCatching {
            val clean = if (raw.contains(",")) raw.substringAfter(",") else raw
            val bytes = Base64.decode(clean, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
}

class MavViewModel(
    application: Application,
    private val repository: MavRepository
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        MavRepository(application)
    )

    val session: StateFlow<MavSession?> = repository.session

    private val _loginState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val loginState: StateFlow<LoginUiState> = _loginState.asStateFlow()

    private val _ticketsState = MutableStateFlow<TicketsUiState>(TicketsUiState.Loading)
    val ticketsState: StateFlow<TicketsUiState> = _ticketsState.asStateFlow()

    private val _selectedTab = MutableStateFlow(MavTab.ACTIVE)
    val selectedTab: StateFlow<MavTab> = _selectedTab.asStateFlow()

    private val _barcodeData = MutableStateFlow<TicketBarcodeData?>(null)
    val barcodeData: StateFlow<TicketBarcodeData?> = _barcodeData.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private var loadTicketsJob: Job? = null

    init {
        if (session.value != null) {
            loadTickets()
        }
    }

    fun setTab(tab: MavTab) {
        _selectedTab.value = tab
    }

    fun login(email: String, pass: String) {
        if (email.isBlank() || pass.isBlank()) {
            _loginState.value = LoginUiState.Error("Kérjük adja meg az email címét és jelszavát!")
            return
        }
        viewModelScope.launch {
            _loginState.value = LoginUiState.Loading
            try {
                repository.login(email.trim(), pass)
                _loginState.value = LoginUiState.Idle
                loadTickets()
            } catch (e: Exception) {
                _loginState.value = LoginUiState.Error(e.message ?: "Bejelentkezési hiba történt")
            }
        }
    }

    fun loginWithGoogle(code: String) {
        if (code.isBlank()) return
        viewModelScope.launch {
            _loginState.value = LoginUiState.Loading
            try {
                repository.loginWithGoogle(code.trim())
                _loginState.value = LoginUiState.Idle
                loadTickets()
            } catch (e: Exception) {
                _loginState.value = LoginUiState.Error(e.message ?: "Google bejelentkezési hiba történt")
            }
        }
    }

    fun setLoginError(message: String) {
        _loginState.value = LoginUiState.Error(message)
    }

    fun onWebLoginSuccess(session: MavSession) {
        viewModelScope.launch {
            _loginState.value = LoginUiState.Idle
            loadTickets()
            // In the background, fetch user profile and passengers
            runCatching {
                repository.refreshUserData()
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            loadTicketsJob?.cancel()
            repository.logout()
            _ticketsState.value = TicketsUiState.Loading
            _barcodeData.value = null
        }
    }

    fun loadTickets(forceRefresh: Boolean = false) {
        loadTicketsJob?.cancel()
        loadTicketsJob = viewModelScope.launch {
            val current = _ticketsState.value
            val hasVisibleTickets = current is TicketsUiState.Success &&
                (current.activeOrders.isNotEmpty() || current.pastOrders.isNotEmpty())
            if (forceRefresh && hasVisibleTickets) {
                _isRefreshing.value = true
            } else if (!hasVisibleTickets) {
                // Also covers the state left behind by logout (an empty Success): without this the
                // next login showed "no tickets" with no loading indicator until the sync finished.
                _ticketsState.value = TicketsUiState.Loading
            }

            // Step 1: Immediately emit cached tickets if available
            val cached = repository.getCachedOrders()
            val lastSync = repository.getLastSyncTime()
            if (cached != null && (cached.first.isNotEmpty() || cached.second.isNotEmpty())) {
                val activeOrderIds = cached.first.map { it.orderId }.toSet()
                val activeReceiptIds = cached.first.flatMap { it.receipts }.map { it.receiptId }.toSet()
                val filteredPast = cached.second.filter { p ->
                    p.orderId !in activeOrderIds &&
                    p.receipts.none { r -> r.receiptId in activeReceiptIds } &&
                    !p.isValidNow
                }
                _ticketsState.value = TicketsUiState.Success(
                    activeOrders = cached.first,
                    pastOrders = filteredPast,
                    isOffline = false,
                    lastSyncTime = lastSync
                )
            }

            // Step 2: Sync with online server
            try {
                val (active, past) = repository.syncTicketsAndPasses()
                _ticketsState.value = TicketsUiState.Success(
                    activeOrders = active,
                    pastOrders = past,
                    isOffline = false,
                    lastSyncTime = repository.getLastSyncTime()
                )
            } catch (e: Exception) {
                val sessionExpired = (e as? MavApiException)?.isAuthError == true
                // If online sync fails, check if we have cached tickets
                val fallbackCache = repository.getCachedOrders()
                if (fallbackCache != null && (fallbackCache.first.isNotEmpty() || fallbackCache.second.isNotEmpty())) {
                    val activeOrderIds = fallbackCache.first.map { it.orderId }.toSet()
                    val activeReceiptIds = fallbackCache.first.flatMap { it.receipts }.map { it.receiptId }.toSet()
                    val filteredPast = fallbackCache.second.filter { p ->
                        p.orderId !in activeOrderIds &&
                        p.receipts.none { r -> r.receiptId in activeReceiptIds } &&
                        !p.isValidNow
                    }
                    _ticketsState.value = TicketsUiState.Success(
                        activeOrders = fallbackCache.first,
                        pastOrders = filteredPast,
                        isOffline = true,
                        lastSyncTime = repository.getLastSyncTime(),
                        sessionExpired = sessionExpired
                    )
                } else if (sessionExpired) {
                    _ticketsState.value = TicketsUiState.Error(
                        getApplication<Application>().getString(hu.app.bmenetrend.mav.R.string.mav_session_expired)
                    )
                } else {
                    _ticketsState.value = TicketsUiState.Error(e.message ?: "Nem sikerült a jegyek betöltése")
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refresh() {
        loadTickets(forceRefresh = true)
    }

    fun refreshTickets() {
        refresh()
    }

    fun showBarcode(
        receiptId: String,
        ticketNumber: String?,
        title: String,
        passengerName: String?
    ) {
        showBarcode(
            receiptId = receiptId,
            ticketNumber = ticketNumber,
            title = title,
            isPass = false,
            passengerName = passengerName,
            birthDate = null
        )
    }

    fun showBarcode(
        receiptId: String,
        ticketNumber: String?,
        title: String,
        isPass: Boolean,
        passengerName: String?,
        birthDate: Long?
    ) {
        showBarcode(
            receiptId = receiptId,
            ticketNumber = ticketNumber,
            title = title,
            isPass = isPass,
            passengerName = passengerName,
            birthDate = birthDate,
            passHolderId = null
        )
    }

    fun showBarcode(
        receiptId: String,
        ticketNumber: String?,
        title: String,
        isPass: Boolean,
        passengerName: String?,
        birthDate: Long?,
        passHolderId: String?
    ) {
        val passengers = repository.getPassengers()
        val matched = passengers.firstOrNull { p ->
            (receiptId.isNotBlank() && (p.id == receiptId || (p.businessId.isNotBlank() && p.businessId == receiptId))) ||
            (!ticketNumber.isNullOrBlank() && (p.id == ticketNumber || (p.businessId.isNotBlank() && p.businessId == ticketNumber))) ||
            (!passengerName.isNullOrBlank() && (
                p.name.equals(passengerName.trim(), ignoreCase = true) ||
                p.name.contains(passengerName.trim(), ignoreCase = true) ||
                passengerName.trim().contains(p.name, ignoreCase = true)
            ))
        } ?: passengers.firstOrNull { !it.photoBase64.isNullOrBlank() } ?: passengers.firstOrNull()

        // Check if this ticket/pass is associated with an electronic pass holder
        val passHolderCandidateIds = listOfNotNull(
            passHolderId?.takeIf { it.isNotBlank() },
            receiptId.takeIf { it.isNotBlank() },
            ticketNumber?.takeIf { it.isNotBlank() },
            if (receiptId.contains("#")) receiptId.substringAfterLast("#").takeIf { it.isNotBlank() } else null,
            if (receiptId.contains("#")) receiptId.substringBefore("#").takeIf { it.isNotBlank() } else null
        ).distinct()

        var cachedPassHolder: MavPassHolder? = null
        if (isPass || !passHolderId.isNullOrBlank()) {
            for (cid in passHolderCandidateIds) {
                val h = repository.getCachedPassHolder(cid)
                if (h != null) {
                    cachedPassHolder = h
                    break
                }
            }
        }

        val finalName: String?
        val finalBirthDate: Long?
        val finalPhoto: String?
        val finalPassHolderNumber: String?
        val finalIdCardNumber: String?

        if (cachedPassHolder != null) {
            finalName = cachedPassHolder.name ?: passengerName?.ifBlank { null } ?: matched?.name?.ifBlank { null }
            finalBirthDate = cachedPassHolder.birthDate ?: (if (birthDate != null && birthDate > 0L) birthDate else matched?.birthDate)
            finalPhoto = cachedPassHolder.photoBase64
                ?: matched?.photoBase64
                ?: repository.getCachedUser()?.photoBase64
            finalPassHolderNumber = cachedPassHolder.id.ifBlank { passHolderId }
            finalIdCardNumber = cachedPassHolder.idCardNumber
        } else {
            finalName = passengerName?.ifBlank { null } ?: matched?.name?.ifBlank { null }
            finalBirthDate = if (birthDate != null && birthDate > 0L) birthDate else matched?.birthDate
            val passHolderPhoto = if (isPass) {
                repository.getCachedPassHolder(receiptId)?.photoBase64
                    ?: ticketNumber?.let { repository.getCachedPassHolder(it)?.photoBase64 }
                    ?: (if (receiptId.contains("#")) repository.getCachedPassHolder(receiptId.substringAfterLast("#"))?.photoBase64 else null)
            } else null
            finalPhoto = matched?.photoBase64
                ?: passHolderPhoto
                ?: passengers.firstOrNull { !it.photoBase64.isNullOrBlank() }?.photoBase64
                ?: repository.getCachedUser()?.photoBase64
            finalPassHolderNumber = passHolderId
            finalIdCardNumber = null
        }

        val candidates = buildList {
            add(receiptId)
            if (!ticketNumber.isNullOrBlank() && ticketNumber != receiptId) add(ticketNumber)
            if (receiptId.contains("#")) {
                for (p in receiptId.split("#")) {
                    val trimmed = p.trim()
                    if (trimmed.isNotEmpty() && trimmed.all { it.isDigit() } && trimmed !in this) {
                        add(trimmed)
                    }
                }
            }
        }.distinct()

        val cachedTicketImage = candidates.firstNotNullOfOrNull { cid ->
            repository.getCachedTicketImage(cid)
        }
        val cachedBitmap = cachedTicketImage?.toBitmap()

        _barcodeData.value = TicketBarcodeData(
            receiptId = receiptId,
            ticketNumber = ticketNumber,
            title = title,
            isPass = isPass,
            passengerName = finalName,
            birthDate = finalBirthDate,
            passengerPhotoBase64 = finalPhoto,
            passHolderNumber = finalPassHolderNumber,
            idCardNumber = finalIdCardNumber,
            bitmap = cachedBitmap,
            loading = cachedBitmap == null
        )
        viewModelScope.launch {
            try {
                // If this is a pass or has passholder ID, ensure pass holder details are loaded
                if (isPass || !passHolderId.isNullOrBlank()) {
                    val holder = passHolderCandidateIds.firstNotNullOfOrNull { cid ->
                        runCatching { repository.getPassHolder(cid) }.getOrNull()
                    }
                    if (holder != null) {
                        _barcodeData.value = _barcodeData.value?.copy(
                            passengerPhotoBase64 = holder.photoBase64 ?: _barcodeData.value?.passengerPhotoBase64,
                            passengerName = holder.name ?: _barcodeData.value?.passengerName,
                            birthDate = holder.birthDate ?: _barcodeData.value?.birthDate,
                            passHolderNumber = holder.id.ifBlank { _barcodeData.value?.passHolderNumber },
                            idCardNumber = holder.idCardNumber ?: _barcodeData.value?.idCardNumber
                        )
                    }
                }

                var ticketImage: MavTicketImage? = cachedTicketImage
                var throttled = false
                if (ticketImage == null) {
                    for (attempt in 0 until TICKET_IMAGE_MAX_ATTEMPTS) {
                        throttled = false
                        for (id in candidates) {
                            ticketImage = try {
                                repository.getTicketImage(id)
                            } catch (e: MavTicketImageThrottledException) {
                                throttled = true
                                null
                            }
                            if (ticketImage != null) break
                        }
                        if (ticketImage != null || !throttled) break
                        if (attempt < TICKET_IMAGE_MAX_ATTEMPTS - 1) {
                            _barcodeData.value = _barcodeData.value?.copy(
                                notice = getApplication<Application>().getString(hu.app.bmenetrend.mav.R.string.mav_barcode_wait_notice)
                            )
                            delay(TICKET_IMAGE_RETRY_DELAY_MS)
                        }
                    }
                }

                val bitmap = ticketImage?.toBitmap() ?: _barcodeData.value?.bitmap
                if (bitmap != null) {
                    _barcodeData.value = _barcodeData.value?.copy(
                        bitmap = bitmap,
                        loading = false,
                        notice = null,
                        error = null
                    )
                } else if (throttled) {
                    _barcodeData.value = _barcodeData.value?.copy(
                        loading = false,
                        notice = null,
                        error = getApplication<Application>().getString(hu.app.bmenetrend.mav.R.string.mav_barcode_throttled)
                    )
                } else {
                    val session = repository.session.value
                    val errorMsg = if (session != null && session.vimToken.isNullOrBlank() && session.token.isBlank()) {
                        "A jegykép betöltéséhez kérjük jelentkezzen be újra."
                    } else {
                        "A jegykép / vonalkód nem érhető el"
                    }
                    _barcodeData.value = _barcodeData.value?.copy(
                        loading = false,
                        error = errorMsg
                    )
                }
            } catch (e: Exception) {
                if (_barcodeData.value?.bitmap == null) {
                    _barcodeData.value = _barcodeData.value?.copy(
                        loading = false,
                        error = e.message ?: "Hiba a jegykép betöltésekor"
                    )
                } else {
                    _barcodeData.value = _barcodeData.value?.copy(
                        loading = false
                    )
                }
            }
        }
    }

    private companion object {
        const val TICKET_IMAGE_MAX_ATTEMPTS = 5
        const val TICKET_IMAGE_RETRY_DELAY_MS = 6_000L
    }

    fun dismissBarcode() {
        _barcodeData.value = null
    }

    fun clearLoginError() {
        if (_loginState.value is LoginUiState.Error) {
            _loginState.value = LoginUiState.Idle
        }
    }
}
