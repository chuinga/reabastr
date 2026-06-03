package com.reabastr.app.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reabastr.app.data.repository.HouseholdRepository
import com.reabastr.app.data.repository.HouseholdResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the onboarding flow.
 */
sealed interface OnboardState {
    /** Checking if user already belongs to a household. */
    data object Checking : OnboardState

    /** User has no household — show create/join options. */
    data object NeedsHousehold : OnboardState

    /** User successfully joined/created a household. */
    data class HasHousehold(val householdId: String) : OnboardState

    /** An error occurred. */
    data class Error(val message: String) : OnboardState
}

/**
 * Sub-state for the join flow (entering share code).
 */
data class JoinUiState(
    val code: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class OnboardViewModel @Inject constructor(
    private val householdRepository: HouseholdRepository
) : ViewModel() {

    private val _onboardState = MutableStateFlow<OnboardState>(OnboardState.Checking)
    val onboardState: StateFlow<OnboardState> = _onboardState.asStateFlow()

    private val _joinUiState = MutableStateFlow(JoinUiState())
    val joinUiState: StateFlow<JoinUiState> = _joinUiState.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    init {
        checkHousehold()
    }

    /**
     * Checks whether the current user belongs to a household.
     */
    fun checkHousehold() {
        viewModelScope.launch {
            _onboardState.value = OnboardState.Checking
            when (val result = householdRepository.checkHousehold()) {
                is HouseholdResult.Success -> {
                    _onboardState.value = OnboardState.HasHousehold(result.household.householdId)
                }
                is HouseholdResult.NoHousehold -> {
                    _onboardState.value = OnboardState.NeedsHousehold
                }
                is HouseholdResult.Error -> {
                    _onboardState.value = OnboardState.Error(result.message)
                }
            }
        }
    }

    /**
     * Creates a new household for the current user.
     */
    fun createHousehold() {
        viewModelScope.launch {
            _isCreating.value = true
            when (val result = householdRepository.createHousehold()) {
                is HouseholdResult.Success -> {
                    _onboardState.value = OnboardState.HasHousehold(result.household.householdId)
                }
                is HouseholdResult.Error -> {
                    _onboardState.value = OnboardState.Error(result.message)
                }
                is HouseholdResult.NoHousehold -> {
                    _onboardState.value = OnboardState.Error("Unexpected response")
                }
            }
            _isCreating.value = false
        }
    }

    /**
     * Updates the share code input.
     */
    fun onCodeChanged(code: String) {
        _joinUiState.value = _joinUiState.value.copy(code = code, errorMessage = null)
    }

    /**
     * Joins an existing household using the entered share code.
     */
    fun joinHousehold() {
        val code = _joinUiState.value.code.trim()
        if (code.isBlank()) {
            _joinUiState.value = _joinUiState.value.copy(errorMessage = "Please enter a share code")
            return
        }

        viewModelScope.launch {
            _joinUiState.value = _joinUiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = householdRepository.joinHousehold(code)) {
                is HouseholdResult.Success -> {
                    _joinUiState.value = _joinUiState.value.copy(isLoading = false)
                    _onboardState.value = OnboardState.HasHousehold(result.household.householdId)
                }
                is HouseholdResult.Error -> {
                    _joinUiState.value = _joinUiState.value.copy(
                        isLoading = false,
                        errorMessage = result.message
                    )
                }
                is HouseholdResult.NoHousehold -> {
                    _joinUiState.value = _joinUiState.value.copy(
                        isLoading = false,
                        errorMessage = "Unexpected response"
                    )
                }
            }
        }
    }

    /**
     * Clears the join error message.
     */
    fun clearJoinError() {
        _joinUiState.value = _joinUiState.value.copy(errorMessage = null)
    }
}
