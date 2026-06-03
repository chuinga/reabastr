package com.reabastr.app.auth

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AuthRepositoryTest {

    private lateinit var tokenManager: TokenManager
    private lateinit var cognitoAuthService: CognitoAuthService
    private lateinit var authRepository: AuthRepository

    @Before
    fun setUp() {
        tokenManager = mockk(relaxed = true)
        cognitoAuthService = mockk(relaxed = true)
        authRepository = AuthRepository(tokenManager, cognitoAuthService)
    }

    @Test
    fun `checkSession emits Unauthenticated when no refresh token`() = runTest {
        every { tokenManager.isRefreshTokenMissing() } returns true

        authRepository.checkSession()

        assertEquals(AuthState.Unauthenticated, authRepository.authState.value)
    }

    @Test
    fun `checkSession emits Authenticated when tokens valid`() = runTest {
        every { tokenManager.isRefreshTokenMissing() } returns false
        every { tokenManager.isAccessTokenExpired() } returns false
        every { tokenManager.getUserClaims() } returns UserClaims(
            sub = "user-123",
            email = "test@example.com",
            name = "Test User"
        )

        authRepository.checkSession()

        val state = authRepository.authState.value
        assertTrue(state is AuthState.Authenticated)
        assertEquals("user-123", (state as AuthState.Authenticated).userId)
        assertEquals("test@example.com", state.email)
        assertEquals("Test User", state.displayName)
    }

    @Test
    fun `checkSession refreshes token when expired`() = runTest {
        every { tokenManager.isRefreshTokenMissing() } returns false
        every { tokenManager.isAccessTokenExpired() } returns true
        every { tokenManager.refreshToken } returns "refresh-token"
        coEvery { cognitoAuthService.refreshTokens("refresh-token") } returns TokenResponse(
            idToken = "new-id-token",
            accessToken = "new-access-token",
            refreshToken = null
        )
        every { tokenManager.getUserClaims() } returns UserClaims(
            sub = "user-456",
            email = "refreshed@example.com",
            name = null
        )

        authRepository.checkSession()

        verify { tokenManager.storeTokens("new-id-token", "new-access-token", null) }
        val state = authRepository.authState.value
        assertTrue(state is AuthState.Authenticated)
    }

    @Test
    fun `checkSession emits Unauthenticated when refresh fails`() = runTest {
        every { tokenManager.isRefreshTokenMissing() } returns false
        every { tokenManager.isAccessTokenExpired() } returns true
        every { tokenManager.refreshToken } returns "bad-refresh-token"
        coEvery { cognitoAuthService.refreshTokens("bad-refresh-token") } throws
            AuthException("Token refresh failed")

        authRepository.checkSession()

        assertEquals(AuthState.Unauthenticated, authRepository.authState.value)
        verify { tokenManager.clearTokens() }
    }

    @Test
    fun `signInWithEmailPassword stores tokens on success`() = runTest {
        coEvery {
            cognitoAuthService.signInWithEmailPassword("user@test.com", "password123")
        } returns TokenResponse(
            idToken = "id-token",
            accessToken = "access-token",
            refreshToken = "refresh-token"
        )
        every { tokenManager.getUserClaims() } returns UserClaims(
            sub = "sub-1",
            email = "user@test.com",
            name = "User"
        )

        val result = authRepository.signInWithEmailPassword("user@test.com", "password123")

        assertTrue(result.isSuccess)
        verify { tokenManager.storeTokens("id-token", "access-token", "refresh-token") }
        assertTrue(authRepository.authState.value is AuthState.Authenticated)
    }

    @Test
    fun `signInWithEmailPassword returns failure on error`() = runTest {
        coEvery {
            cognitoAuthService.signInWithEmailPassword("user@test.com", "wrong")
        } throws AuthException("Incorrect email or password")

        val result = authRepository.signInWithEmailPassword("user@test.com", "wrong")

        assertTrue(result.isFailure)
        assertEquals("Incorrect email or password", result.exceptionOrNull()?.message)
    }

    @Test
    fun `signOut clears tokens and emits Unauthenticated`() {
        every { cognitoAuthService.buildSignOutUrl() } returns "https://logout.url"

        val logoutUrl = authRepository.signOut()

        verify { tokenManager.clearTokens() }
        assertEquals(AuthState.Unauthenticated, authRepository.authState.value)
        assertEquals("https://logout.url", logoutUrl)
    }

    @Test
    fun `getValidIdToken refreshes when expired`() = runTest {
        every { tokenManager.isAccessTokenExpired() } returns true
        every { tokenManager.refreshToken } returns "refresh-token"
        coEvery { cognitoAuthService.refreshTokens("refresh-token") } returns TokenResponse(
            idToken = "fresh-id",
            accessToken = "fresh-access",
            refreshToken = null
        )
        every { tokenManager.idToken } returns "fresh-id"

        val token = authRepository.getValidIdToken()

        assertEquals("fresh-id", token)
    }

    @Test
    fun `getValidIdToken returns null when refresh fails`() = runTest {
        every { tokenManager.isAccessTokenExpired() } returns true
        every { tokenManager.refreshToken } returns "bad-token"
        coEvery { cognitoAuthService.refreshTokens("bad-token") } throws AuthException("Failed")

        val token = authRepository.getValidIdToken()

        assertEquals(null, token)
        assertEquals(AuthState.Unauthenticated, authRepository.authState.value)
    }
}
