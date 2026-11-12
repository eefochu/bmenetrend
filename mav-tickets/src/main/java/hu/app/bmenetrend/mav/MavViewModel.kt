package hu.app.bmenetrend.mav

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
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
    val bitmap: Bitmap? = null,
    val loading: Boolean = false,
    val error: String? = null
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
            repository.logout()
            _ticketsState.value = TicketsUiState.Success(emptyList<MavOrder>(), emptyList<MavOrder>())
            _barcodeData.value = null
        }
    }

    fun loadTickets(forceRefresh: Boolean = false) {
        loadTicketsJob?.cancel()
        loadTicketsJob = viewModelScope.launch {
            if (forceRefresh) {
                _isRefreshing.value = true
            } else if (_ticketsState.value !is TicketsUiState.Success) {
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

        val finalName = passengerName?.ifBlank { null } ?: matched?.name?.ifBlank { null }
        val finalBirthDate = if (birthDate != null && birthDate > 0L) birthDate else matched?.birthDate
        val passHolderPhoto = if (isPass) {
            repository.getCachedPassHolder(receiptId)?.photoBase64
                ?: ticketNumber?.let { repository.getCachedPassHolder(it)?.photoBase64 }
                ?: (if (receiptId.contains("#")) repository.getCachedPassHolder(receiptId.substringAfterLast("#"))?.photoBase64 else null)
        } else null

        val passengerPhoto = matched?.photoBase64
            ?: passHolderPhoto
            ?: passengers.firstOrNull { !it.photoBase64.isNullOrBlank() }?.photoBase64
            ?: repository.getCachedUser()?.photoBase64

        _barcodeData.value = TicketBarcodeData(
            receiptId = receiptId,
            ticketNumber = ticketNumber,
            title = title,
            isPass = isPass,
            passengerName = finalName,
            birthDate = finalBirthDate,
            passengerPhotoBase64 = passengerPhoto,
            loading = true
        )
        viewModelScope.launch {
            try {
                // If passenger photo is not in cache yet, try loading pass holder if it is a pass
                if (isPass && _barcodeData.value?.passengerPhotoBase64.isNullOrBlank()) {
                    val holder = runCatching { repository.getPassHolder(receiptId) }.getOrNull()
                        ?: ticketNumber?.let { runCatching { repository.getPassHolder(it) }.getOrNull() }
                        ?: (if (receiptId.contains("#")) runCatching { repository.getPassHolder(receiptId.substringAfterLast("#")) }.getOrNull() else null)
                    if (holder?.photoBase64 != null) {
                        _barcodeData.value = _barcodeData.value?.copy(
                            passengerPhotoBase64 = holder.photoBase64,
                            passengerName = _barcodeData.value?.passengerName ?: holder.name,
                            birthDate = _barcodeData.value?.birthDate ?: holder.birthDate
                        )
                    }
                }

                var ticketImage = repository.getTicketImage(receiptId)
                if (ticketImage == null && !ticketNumber.isNullOrBlank()) {
                    ticketImage = repository.getTicketImage(ticketNumber)
                }
                if (ticketImage == null && receiptId.contains("#")) {
                    ticketImage = repository.getTicketImage(receiptId.substringAfterLast("#"))
                }
                if (ticketImage == null && receiptId.contains("#")) {
                    ticketImage = repository.getTicketImage(receiptId.substringBefore("#"))
                }
                val bitmap = ticketImage?.toBitmap()
                if (bitmap != null) {
                    _barcodeData.value = _barcodeData.value?.copy(
                        bitmap = bitmap,
                        loading = false
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
                _barcodeData.value = _barcodeData.value?.copy(
                    loading = false,
                    error = e.message ?: "Hiba a jegykép betöltésekor"
                )
            }
        }
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
