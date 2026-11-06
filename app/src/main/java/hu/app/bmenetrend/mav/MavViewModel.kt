package hu.app.bmenetrend.mav

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class LoginUiState {
    data object Idle : LoginUiState()
    data object Loading : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}

sealed class TicketsUiState {
    data object Loading : TicketsUiState()
    data class Success(
        val activeOrders: List<MavOrder> = emptyList(),
        val pastOrders: List<MavOrder> = emptyList(),
        val isOffline: Boolean = false,
        val lastSyncTime: Long = 0L
    ) : TicketsUiState()
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
    val bitmap: Bitmap? = null,
    val loading: Boolean = false,
    val error: String? = null
)

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

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            _ticketsState.value = TicketsUiState.Success()
            _loginState.value = LoginUiState.Idle
            _barcodeData.value = null
        }
    }

    fun refreshTickets() {
        if (session.value == null) return
        viewModelScope.launch {
            _isRefreshing.value = true
            loadTicketsInternal()
            _isRefreshing.value = false
        }
    }

    private fun loadTickets() {
        loadTicketsJob?.cancel()
        loadTicketsJob = viewModelScope.launch {
            _ticketsState.value = TicketsUiState.Loading
            loadTicketsInternal()
        }
    }

    private suspend fun loadTicketsInternal() {
        // Step 1: Immediately show cached data if available (instant offline display)
        val cached = repository.getCachedOrders()
        val lastSync = repository.getLastSyncTime()
        if (cached != null && (cached.first.isNotEmpty() || cached.second.isNotEmpty())) {
            _ticketsState.value = TicketsUiState.Success(
                activeOrders = cached.first,
                pastOrders = cached.second,
                isOffline = true,
                lastSyncTime = lastSync
            )
        }

        // Step 2: Sync fresh orders and pre-cache barcodes while online
        try {
            val (active, past) = repository.syncTicketsAndPasses()
            _ticketsState.value = TicketsUiState.Success(
                activeOrders = active,
                pastOrders = past,
                isOffline = false,
                lastSyncTime = repository.getLastSyncTime()
            )
        } catch (e: Exception) {
            // Step 3: On network error or offline, fallback to cached data if we have any
            val currentCached = repository.getCachedOrders()
            if (currentCached != null && (currentCached.first.isNotEmpty() || currentCached.second.isNotEmpty())) {
                _ticketsState.value = TicketsUiState.Success(
                    activeOrders = currentCached.first,
                    pastOrders = currentCached.second,
                    isOffline = true,
                    lastSyncTime = repository.getLastSyncTime()
                )
            } else {
                _ticketsState.value = TicketsUiState.Error(e.message ?: "Nem sikerült a jegyek betöltése")
            }
        }
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
        _barcodeData.value = TicketBarcodeData(
            receiptId = receiptId,
            ticketNumber = ticketNumber,
            title = title,
            isPass = isPass,
            passengerName = passengerName,
            birthDate = birthDate,
            loading = true
        )
        viewModelScope.launch {
            try {
                val ticketImage = repository.getTicketImage(receiptId)
                val bitmap = ticketImage?.toBitmap()
                if (bitmap != null) {
                    _barcodeData.value = _barcodeData.value?.copy(
                        bitmap = bitmap,
                        loading = false
                    )
                } else {
                    _barcodeData.value = _barcodeData.value?.copy(
                        loading = false,
                        error = "A jegykép / vonalkód nem érhető el"
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
