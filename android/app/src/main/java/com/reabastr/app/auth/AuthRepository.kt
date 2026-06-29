package com.reabastr.app.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mediates all authentication operations. Exposes a [StateFlow] of [AuthState]
 * that the UI layer observes to determine sign-in state. Handles:
 * - Google OAuth2 PKCE sign-in (via Chrome Custom Tab)
 * - Email/password sign-in (via Cognito USER_PASSWORD_AUTH)
 * - Proactive token refresh (5 min before expiry)
 * - Refresh token handling
 * - Sign-out (clear tokens + Cognito logout)
 */
@Singleton
class AuthRepository @Inject constructor(
    private val tokenManager: TokenManager,
    private val cognitoAuthService: CognitoAuthService
) {
    private val _authState = MutableStateFlow<AuthState>(AuthState.Loading)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /**
     * Checks stored tokens on startup and emits the appropriate state.
     * Call this once from the app's root composable or Application.
     */
    suspend fun checkSession() {
        if (tokenManager.isRefreshTokenMissing()) {
            _authState.value = AuthState.Unauthenticated
            return
        }

        // If access token is expired, try to refresh
        if (tokenManager.isAccessTokenExpired()) {
            val refreshed = tryRefreshTokens()
            if (!refreshed) {
                _authState.value = AuthState.Unauthenticated
                return
            }
        }

        // Extract user info from ID token
        val claims = tokenManager.getUserClaims()
        if (claims != null) {
            _authState.value = AuthState.Authenticated(
                userId = claims.sub,
                email = claims.email,
                displayName = claims.name
            )
        } else {
            _authState.value = AuthState.Unauthenticated
        }
    }

    /**
     * Returns the Google OAuth2 URL to open in Chrome Custom Tab.
     */
    fun getGoogleSignInUrl(): String {
        return cognitoAuthService.buildGoogleAuthUrl()
    }

    /**
     * Handles the OAuth2 callback redirect. Extracts the authorization code
     * from the URI and exchanges it for tokens.
     */
    suspend fun handleOAuthCallback(code: String): Result<Unit> {
        return try {
            val tokens = cognitoAuthService.exchangeCodeForTokens(code)
            tokenManager.storeTokens(
                idToken = tokens.idToken,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken
            )

            val claims = tokenManager.getUserClaims()
            _authState.value = if (claims != null) {
                AuthState.Authenticated(
                    userId = claims.sub,
                    email = claims.email,
                    displayName = claims.name
                )
            } else {
                AuthState.Unauthenticated
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Signs in with email and password.
     */
    suspend fun signInWithEmailPassword(email: String, password: String): Result<Unit> {
        return try {
            val tokens = cognitoAuthService.signInWithEmailPassword(email, password)
            tokenManager.storeTokens(
                idToken = tokens.idToken,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken
            )

            val claims = tokenManager.getUserClaims()
            _authState.value = if (claims != null) {
                AuthState.Authenticated(
                    userId = claims.sub,
                    email = claims.email,
                    displayName = claims.name
                )
            } else {
                AuthState.Unauthenticated
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Registers a new user. Returns [Result] wrapping whether email confirmation
     * is required (true) before the user can sign in.
     */
    suspend fun signUp(email: String, password: String, name: String?): Result<Boolean> {
        return try {
            val needsConfirmation = cognitoAuthService.signUp(email, password, name)
            Result.success(needsConfirmation)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Confirms a newly registered account with the emailed code, then signs in.
     */
    suspend fun confirmSignUp(email: String, password: String, code: String): Result<Unit> {
        return try {
            cognitoAuthService.confirmSignUp(email, code)
            // Auto sign-in after successful confirmation
            signInWithEmailPassword(email, password)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Resends the email verification code for an unconfirmed account.
     */
    suspend fun resendConfirmationCode(email: String): Result<Unit> {
        return try {
            cognitoAuthService.resendConfirmationCode(email)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Returns a valid ID token for API calls, refreshing if needed.
     * Returns null if the user needs to re-authenticate.
     */
    suspend fun getValidIdToken(): String? {
        if (tokenManager.isAccessTokenExpired()) {
            val refreshed = tryRefreshTokens()
            if (!refreshed) {
                _authState.value = AuthState.Unauthenticated
                return null
            }
        }
        return tokenManager.idToken
    }

    /**
     * Signs the user out: clears local tokens and returns the logout URL
     * for the hosted UI session invalidation (optional).
     */
    fun signOut(): String {
        tokenManager.clearTokens()
        _authState.value = AuthState.Unauthenticated
        return cognitoAuthService.buildSignOutUrl()
    }

    /**
     * Attempts to refresh tokens using the stored refresh token.
     * Returns true if successful, false if re-authentication is needed.
     */
    private suspend fun tryRefreshTokens(): Boolean {
        val refreshToken = tokenManager.refreshToken ?: return false
        return try {
            val tokens = cognitoAuthService.refreshTokens(refreshToken)
            tokenManager.storeTokens(
                idToken = tokens.idToken,
                accessToken = tokens.accessToken,
                // Keep existing refresh token (Cognito doesn't return a new one on refresh)
                refreshToken = null
            )
            true
        } catch (_: Exception) {
            // Refresh failed — token likely revoked or expired
            tokenManager.clearTokens()
            false
        }
    }
}
