package com.reabastr.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignInUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val email: String = "",
    val password: String = ""
)

/**
 * UI state for the sign-up flow, including the email-confirmation step.
 */
data class SignUpUiState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val confirmationCode: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val infoMessage: String? = null,
    /** True once SignUp succeeded and a verification code was emailed. */
    val awaitingConfirmation: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SignInUiState())
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    private val _signUpState = MutableStateFlow(SignUpUiState())
    val signUpState: StateFlow<SignUpUiState> = _signUpState.asStateFlow()

    val authState: StateFlow<AuthState> = authRepository.authState

    init {
        viewModelScope.launch {
            authRepository.checkSession()
        }
    }

    fun onEmailChanged(email: String) {
        _uiState.value = _uiState.value.copy(email = email, errorMessage = null)
    }

    fun onPasswordChanged(password: String) {
        _uiState.value = _uiState.value.copy(password = password, errorMessage = null)
    }

    /**
     * Returns the Google OAuth URL for the Chrome Custom Tab.
     */
    fun getGoogleSignInUrl(): String {
        return authRepository.getGoogleSignInUrl()
    }

    /**
     * Handles the OAuth callback after returning from Chrome Custom Tab.
     */
    fun handleOAuthCallback(code: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = authRepository.handleOAuthCallback(code)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = result.exceptionOrNull()?.message
            )
        }
    }

    /**
     * Surfaces an OAuth error returned on the callback redirect (e.g. user
     * denied consent or the provider returned an error).
     */
    fun onOAuthError(message: String) {
        _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = message)
    }

    /**
     * Signs in with email and password.
     */
    fun signInWithEmailPassword() {
        val state = _uiState.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Email and password are required")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val result = authRepository.signInWithEmailPassword(state.email, state.password)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = result.exceptionOrNull()?.message
            )
        }
    }

    /**
     * Signs out and returns the logout URL for hosted UI cleanup.
     */
    fun signOut(): String {
        return authRepository.signOut()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    // --- Sign-up flow ---

    fun onSignUpNameChanged(name: String) {
        _signUpState.value = _signUpState.value.copy(name = name, errorMessage = null)
    }

    fun onSignUpEmailChanged(email: String) {
        _signUpState.value = _signUpState.value.copy(email = email, errorMessage = null)
    }

    fun onSignUpPasswordChanged(password: String) {
        _signUpState.value = _signUpState.value.copy(password = password, errorMessage = null)
    }

    fun onConfirmationCodeChanged(code: String) {
        _signUpState.value = _signUpState.value.copy(confirmationCode = code, errorMessage = null)
    }

    /**
     * Validates and submits the sign-up form. On success, moves to the
     * email-confirmation step (or signs in directly if no confirmation needed).
     */
    fun signUp() {
        val state = _signUpState.value
        val email = state.email.trim()
        val password = state.password

        if (email.isBlank() || password.isBlank()) {
            _signUpState.value = state.copy(errorMessage = "Email and password are required")
            return
        }
        if (password.length < 8) {
            _signUpState.value = state.copy(errorMessage = "Password must be at least 8 characters")
            return
        }

        viewModelScope.launch {
            _signUpState.value = _signUpState.value.copy(isLoading = true, errorMessage = null)
            val result = authRepository.signUp(email, password, state.name.trim().ifBlank { null })
            result.fold(
                onSuccess = { needsConfirmation ->
                    if (needsConfirmation) {
                        _signUpState.value = _signUpState.value.copy(
                            isLoading = false,
                            awaitingConfirmation = true
                        )
                    } else {
                        // Pool auto-confirmed — sign in directly
                        authRepository.signInWithEmailPassword(email, password)
                        _signUpState.value = _signUpState.value.copy(isLoading = false)
                    }
                },
                onFailure = { error ->
                    _signUpState.value = _signUpState.value.copy(
                        isLoading = false,
                        errorMessage = error.message
                    )
                }
            )
        }
    }

    /**
     * Confirms the account with the emailed code, then auto signs in.
     */
    fun confirmSignUp() {
        val state = _signUpState.value
        if (state.confirmationCode.isBlank()) {
            _signUpState.value = state.copy(errorMessage = "Enter the verification code")
            return
        }

        viewModelScope.launch {
            _signUpState.value = _signUpState.value.copy(isLoading = true, errorMessage = null)
            val result = authRepository.confirmSignUp(
                email = state.email.trim(),
                password = state.password,
                code = state.confirmationCode.trim()
            )
            _signUpState.value = _signUpState.value.copy(
                isLoading = false,
                errorMessage = result.exceptionOrNull()?.message
            )
            // On success, authState flips to Authenticated and navigation reacts.
        }
    }

    /**
     * Resends the verification code to the sign-up email.
     */
    fun resendConfirmationCode() {
        val email = _signUpState.value.email.trim()
        if (email.isBlank()) return
        viewModelScope.launch {
            _signUpState.value = _signUpState.value.copy(isLoading = true, errorMessage = null)
            val result = authRepository.resendConfirmationCode(email)
            _signUpState.value = _signUpState.value.copy(
                isLoading = false,
                errorMessage = result.exceptionOrNull()?.message,
                infoMessage = if (result.isSuccess) "Code resent" else null
            )
        }
    }

    /**
     * Resets the sign-up state (e.g. when leaving the sign-up flow).
     */
    fun resetSignUp() {
        _signUpState.value = SignUpUiState()
    }

    fun clearSignUpMessages() {
        _signUpState.value = _signUpState.value.copy(errorMessage = null, infoMessage = null)
    }
}
