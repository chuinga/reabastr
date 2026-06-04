package com.reabastr.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reabastr.app.auth.AuthRepository
import com.reabastr.app.auth.AuthState
import com.reabastr.app.data.remote.ApiService
import com.reabastr.app.data.remote.dto.HistoryItemDto
import com.reabastr.app.data.remote.dto.ShareCodeResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * UI state for the Settings page.
 */
data class SettingsUiState(
    val email: String = "",
    val displayName: String? = null,
    val selectedLocale: Locale? = null,
    val shareCode: String? = null,
    val shareCodeExpiresAt: String? = null,
    val isGeneratingShareCode: Boolean = false,
    val historyItems: List<HistoryItemDto> = emptyList(),
    val isLoadingHistory: Boolean = false,
    val historyError: Boolean = false,
    val hasMoreHistory: Boolean = false,
    val isLoadingMoreHistory: Boolean = false,
    val isLeavingHousehold: Boolean = false
)

/**
 * One-shot events emitted by SettingsViewModel.
 */
sealed interface SettingsEvent {
    /** User left household — navigate back to onboarding. */
    data object HouseholdLeft : SettingsEvent

    /** An error occurred (transient message). */
    data class ShowError(val message: String) : SettingsEvent

    /** User signed out — navigate to sign-in. */
    data object SignedOut : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val apiService: ApiService,
    private val localeManager: LocaleManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    private var historyCursor: String? = null

    init {
        loadAccountInfo()
        observeLocale()
        loadHistory()
    }

    private fun loadAccountInfo() {
        val authState = authRepository.authState.value
        if (authState is AuthState.Authenticated) {
            _uiState.value = _uiState.value.copy(
                email = authState.email,
                displayName = authState.displayName
            )
        }
    }

    private fun observeLocale() {
        viewModelScope.launch {
            localeManager.currentLocale.collect { locale ->
                _uiState.value = _uiState.value.copy(selectedLocale = locale)
            }
        }
    }

    /**
     * Changes the app language override. Null means "use system default".
     */
    fun setLanguage(locale: Locale?) {
        viewModelScope.launch {
            localeManager.setLocale(locale)
        }
    }

    /**
     * Generates a share code for the current household.
     */
    fun generateShareCode() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isGeneratingShareCode = true)
            try {
                val response = apiService.generateShareCode()
                if (response.isSuccessful) {
                    val body = response.body()
                    _uiState.value = _uiState.value.copy(
                        shareCode = body?.code,
                        shareCodeExpiresAt = body?.expiresAt,
                        isGeneratingShareCode = false
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isGeneratingShareCode = false)
                    _events.emit(SettingsEvent.ShowError("Failed to generate share code"))
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isGeneratingShareCode = false)
                _events.emit(SettingsEvent.ShowError("Network error"))
            }
        }
    }

    /**
     * Loads the first page of history (50 items, reverse chronological).
     */
    fun loadHistory() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoadingHistory = true,
                historyError = false
            )
            try {
                val response = apiService.getHistory(limit = 50, cursor = null)
                if (response.isSuccessful) {
                    val body = response.body()
                    historyCursor = body?.cursor
                    _uiState.value = _uiState.value.copy(
                        historyItems = body?.items ?: emptyList(),
                        isLoadingHistory = false,
                        hasMoreHistory = body?.cursor != null
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoadingHistory = false,
                        historyError = true
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoadingHistory = false,
                    historyError = true
                )
            }
        }
    }

    /**
     * Loads the next page of history using the cursor from the previous response.
     */
    fun loadMoreHistory() {
        val cursor = historyCursor ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMoreHistory = true)
            try {
                val response = apiService.getHistory(limit = 50, cursor = cursor)
                if (response.isSuccessful) {
                    val body = response.body()
                    historyCursor = body?.cursor
                    _uiState.value = _uiState.value.copy(
                        historyItems = _uiState.value.historyItems + (body?.items ?: emptyList()),
                        isLoadingMoreHistory = false,
                        hasMoreHistory = body?.cursor != null
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoadingMoreHistory = false)
                    _events.emit(SettingsEvent.ShowError("Failed to load more history"))
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingMoreHistory = false)
                _events.emit(SettingsEvent.ShowError("Network error"))
            }
        }
    }

    /**
     * Leaves the current household. On success, navigates back to onboarding.
     */
    fun leaveHousehold() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLeavingHousehold = true)
            try {
                val response = apiService.leaveHousehold()
                if (response.isSuccessful) {
                    _uiState.value = _uiState.value.copy(isLeavingHousehold = false)
                    _events.emit(SettingsEvent.HouseholdLeft)
                } else {
                    _uiState.value = _uiState.value.copy(isLeavingHousehold = false)
                    _events.emit(SettingsEvent.ShowError("Failed to leave household"))
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLeavingHousehold = false)
                _events.emit(SettingsEvent.ShowError("Network error"))
            }
        }
    }

    /**
     * Signs the user out.
     */
    fun signOut() {
        authRepository.signOut()
        _events.tryEmit(SettingsEvent.SignedOut)
    }
}
