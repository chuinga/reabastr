package com.reabastr.app.auth

/**
 * Represents the current authentication state of the user.
 */
sealed interface AuthState {
    /** Checking stored tokens on app launch. */
    data object Loading : AuthState

    /** User is authenticated with a valid session. */
    data class Authenticated(
        val userId: String,
        val email: String,
        val displayName: String?
    ) : AuthState

    /** No valid session — user must sign in. */
    data object Unauthenticated : AuthState
}
