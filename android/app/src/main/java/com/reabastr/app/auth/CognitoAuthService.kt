package com.reabastr.app.auth

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Low-level Cognito auth operations: OAuth2 PKCE flow, SRP email/password sign-in,
 * and token refresh. Uses the Cognito hosted OAuth2 endpoints directly via OkHttp
 * (no AWS SDK dependency — the app never holds AWS credentials).
 */
@Singleton
class CognitoAuthService @Inject constructor(
    private val httpClient: OkHttpClient
) {
    private val tokenEndpoint = "https://${AuthConfig.DOMAIN}/oauth2/token"
    private val authorizeEndpoint = "https://${AuthConfig.DOMAIN}/oauth2/authorize"
    private val cognitoEndpoint = "https://cognito-idp.${AuthConfig.REGION}.amazonaws.com"

    // PKCE state kept in memory for the current auth flow
    private var codeVerifier: String? = null

    /**
     * Builds the Google OAuth2 authorization URL for Chrome Custom Tab.
     * Uses PKCE (S256 code challenge).
     */
    fun buildGoogleAuthUrl(): String {
        val verifier = generateCodeVerifier()
        codeVerifier = verifier
        val challenge = generateCodeChallenge(verifier)

        return Uri.parse(authorizeEndpoint).buildUpon()
            .appendQueryParameter("client_id", AuthConfig.CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", AuthConfig.SCOPES)
            .appendQueryParameter("redirect_uri", AuthConfig.REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("identity_provider", "Google")
            .build()
            .toString()
    }

    /**
     * Exchanges an authorization code (from OAuth redirect) for tokens.
     * Returns the token response or throws on failure.
     */
    suspend fun exchangeCodeForTokens(code: String): TokenResponse = withContext(Dispatchers.IO) {
        val verifier = codeVerifier ?: throw IllegalStateException("No code verifier — start auth flow first")
        codeVerifier = null

        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", AuthConfig.CLIENT_ID)
            .add("code", code)
            .add("redirect_uri", AuthConfig.REDIRECT_URI)
            .add("code_verifier", verifier)
            .build()

        val request = Request.Builder()
            .url(tokenEndpoint)
            .post(body)
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw AuthException("Empty response from token endpoint")

        if (!response.isSuccessful) {
            throw AuthException("Token exchange failed: ${response.code} - $responseBody")
        }

        parseTokenResponse(responseBody)
    }

    /**
     * Signs in with email and password using Cognito USER_SRP_AUTH flow.
     * This uses the InitiateAuth API directly via HTTP POST to the Cognito endpoint.
     *
     * For simplicity, we use USER_PASSWORD_AUTH (which the pool allows) since SRP
     * requires a full challenge-response implementation. The password is sent over TLS.
     */
    suspend fun signInWithEmailPassword(email: String, password: String): TokenResponse =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("AuthFlow", "USER_PASSWORD_AUTH")
                put("ClientId", AuthConfig.CLIENT_ID)
                put("AuthParameters", JSONObject().apply {
                    put("USERNAME", email)
                    put("PASSWORD", password)
                })
            }

            val request = Request.Builder()
                .url(cognitoEndpoint)
                .addHeader("Content-Type", "application/x-amz-json-1.1")
                .addHeader("X-Amz-Target", "AWSCognitoIdentityProviderService.InitiateAuth")
                .post(payload.toString().toRequestBody(
                    "application/x-amz-json-1.1".toMediaTypeOrNull()
                ))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: throw AuthException("Empty response")

            if (!response.isSuccessful) {
                val error = try {
                    JSONObject(responseBody)
                } catch (_: Exception) { null }
                val errorType = error?.optString("__type", "") ?: ""
                val errorMessage = error?.optString("message", responseBody) ?: responseBody

                throw when {
                    errorType.contains("NotAuthorized") ->
                        AuthException("Incorrect email or password")
                    errorType.contains("UserNotFound") ->
                        AuthException("No account found with this email")
                    errorType.contains("UserNotConfirmed") ->
                        AuthException("Please verify your email before signing in")
                    else ->
                        AuthException("Sign-in failed: $errorMessage")
                }
            }

            val json = JSONObject(responseBody)
            val authResult = json.getJSONObject("AuthenticationResult")

            TokenResponse(
                idToken = authResult.getString("IdToken"),
                accessToken = authResult.getString("AccessToken"),
                refreshToken = if (authResult.has("RefreshToken")) authResult.getString("RefreshToken") else null
            )
        }

    /**
     * Registers a new user with email and password via the Cognito SignUp API.
     * Returns true if a confirmation code was sent (user must confirm), false if
     * the user is already confirmed (auto-confirmed pools).
     */
    suspend fun signUp(email: String, password: String, name: String?): Boolean =
        withContext(Dispatchers.IO) {
            val userAttributes = mutableListOf<JSONObject>()
            userAttributes.add(JSONObject().apply {
                put("Name", "email")
                put("Value", email)
            })
            if (!name.isNullOrBlank()) {
                userAttributes.add(JSONObject().apply {
                    put("Name", "name")
                    put("Value", name)
                })
            }

            val payload = JSONObject().apply {
                put("ClientId", AuthConfig.CLIENT_ID)
                put("Username", email)
                put("Password", password)
                put("UserAttributes", org.json.JSONArray(userAttributes))
            }

            val response = postToCognito("SignUp", payload.toString())
            val responseBody = response.body?.string() ?: throw AuthException("Empty response")

            if (!response.isSuccessful) {
                throw mapCognitoError(responseBody, fallback = "Sign-up failed")
            }

            val json = JSONObject(responseBody)
            // UserConfirmed=false means a verification code was emailed.
            !json.optBoolean("UserConfirmed", false)
        }

    /**
     * Confirms a newly registered user with the emailed verification code.
     */
    suspend fun confirmSignUp(email: String, code: String): Unit = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("ClientId", AuthConfig.CLIENT_ID)
            put("Username", email)
            put("ConfirmationCode", code)
        }

        val response = postToCognito("ConfirmSignUp", payload.toString())
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw mapCognitoError(responseBody, fallback = "Confirmation failed")
        }
    }

    /**
     * Resends the email verification code for an unconfirmed user.
     */
    suspend fun resendConfirmationCode(email: String): Unit = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("ClientId", AuthConfig.CLIENT_ID)
            put("Username", email)
        }

        val response = postToCognito("ResendConfirmationCode", payload.toString())
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw mapCognitoError(responseBody, fallback = "Could not resend code")
        }
    }

    /**
     * Posts a JSON payload to the Cognito IDP endpoint for the given operation.
     */
    private fun postToCognito(operation: String, jsonPayload: String): okhttp3.Response {
        val request = Request.Builder()
            .url(cognitoEndpoint)
            .addHeader("Content-Type", "application/x-amz-json-1.1")
            .addHeader("X-Amz-Target", "AWSCognitoIdentityProviderService.$operation")
            .post(jsonPayload.toRequestBody("application/x-amz-json-1.1".toMediaTypeOrNull()))
            .build()
        return httpClient.newCall(request).execute()
    }

    /**
     * Maps a Cognito error response body to a user-friendly [AuthException].
     */
    private fun mapCognitoError(responseBody: String, fallback: String): AuthException {
        val error = try {
            JSONObject(responseBody)
        } catch (_: Exception) {
            null
        }
        val errorType = error?.optString("__type", "") ?: ""
        val errorMessage = error?.optString("message", fallback) ?: fallback

        return when {
            errorType.contains("UsernameExists") ->
                AuthException("An account with this email already exists")
            errorType.contains("InvalidPassword") ->
                AuthException("Password does not meet the requirements")
            errorType.contains("CodeMismatch") ->
                AuthException("Incorrect verification code")
            errorType.contains("ExpiredCode") ->
                AuthException("The verification code has expired")
            errorType.contains("InvalidParameter") ->
                AuthException(errorMessage)
            errorType.contains("LimitExceeded") ->
                AuthException("Too many attempts. Please wait and try again.")
            else -> AuthException("$fallback: $errorMessage")
        }
    }

    /**
     * Refreshes tokens using the stored refresh token.
     */
    suspend fun refreshTokens(refreshToken: String): TokenResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("AuthFlow", "REFRESH_TOKEN_AUTH")
            put("ClientId", AuthConfig.CLIENT_ID)
            put("AuthParameters", JSONObject().apply {
                put("REFRESH_TOKEN", refreshToken)
            })
        }

        val request = Request.Builder()
            .url(cognitoEndpoint)
            .addHeader("Content-Type", "application/x-amz-json-1.1")
            .addHeader("X-Amz-Target", "AWSCognitoIdentityProviderService.InitiateAuth")
            .post(payload.toString().toRequestBody(
                    "application/x-amz-json-1.1".toMediaTypeOrNull()
            ))
            .build()

        val response = httpClient.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw AuthException("Empty response")

        if (!response.isSuccessful) {
            throw AuthException("Token refresh failed: ${response.code}")
        }

        val json = JSONObject(responseBody)
        val authResult = json.getJSONObject("AuthenticationResult")

        TokenResponse(
            idToken = authResult.getString("IdToken"),
            accessToken = authResult.getString("AccessToken"),
            // Refresh token is not returned on refresh — keep the existing one
            refreshToken = null
        )
    }

    /**
     * Builds the Cognito hosted sign-out URL.
     */
    fun buildSignOutUrl(): String {
        return Uri.parse("https://${AuthConfig.DOMAIN}/logout").buildUpon()
            .appendQueryParameter("client_id", AuthConfig.CLIENT_ID)
            .appendQueryParameter("logout_uri", AuthConfig.SIGN_OUT_URI)
            .build()
            .toString()
    }

    // --- PKCE helpers ---

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun parseTokenResponse(body: String): TokenResponse {
        val json = JSONObject(body)
        return TokenResponse(
            idToken = json.getString("id_token"),
            accessToken = json.getString("access_token"),
            refreshToken = if (json.has("refresh_token")) json.getString("refresh_token") else null
        )
    }
}

data class TokenResponse(
    val idToken: String,
    val accessToken: String,
    val refreshToken: String?
)

class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
