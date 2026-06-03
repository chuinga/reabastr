package com.reabastr.app.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Cognito JWT tokens in EncryptedSharedPreferences (AES-256).
 * Provides proactive refresh detection — tokens are considered expired
 * 5 minutes before their actual expiry.
 */
@Singleton
class TokenManager @Inject constructor(
    context: Context
) {
    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var idToken: String?
        get() = prefs.getString(KEY_ID_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ID_TOKEN, value).apply()

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    /** Returns true if the access token will expire within the refresh buffer window. */
    fun isAccessTokenExpired(): Boolean {
        val token = accessToken ?: return true
        val expiry = getTokenExpiry(token) ?: return true
        return System.currentTimeMillis() >= (expiry - AuthConfig.TOKEN_REFRESH_BUFFER_MS)
    }

    /** Returns true if there is no refresh token stored. */
    fun isRefreshTokenMissing(): Boolean = refreshToken == null

    /** Extracts user claims from the ID token. Returns null if not available. */
    fun getUserClaims(): UserClaims? {
        val token = idToken ?: return null
        return try {
            val payload = decodeJwtPayload(token)
            UserClaims(
                sub = payload.getString("sub"),
                email = payload.optString("email", ""),
                name = if (payload.has("name")) payload.getString("name") else null
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Stores all tokens from a successful auth response. */
    fun storeTokens(idToken: String, accessToken: String, refreshToken: String?) {
        prefs.edit()
            .putString(KEY_ID_TOKEN, idToken)
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .apply {
                if (refreshToken != null) {
                    putString(KEY_REFRESH_TOKEN, refreshToken)
                }
            }
            .apply()
    }

    /** Clears all stored tokens (sign-out). */
    fun clearTokens() {
        prefs.edit().clear().apply()
    }

    private fun getTokenExpiry(jwt: String): Long? {
        return try {
            val payload = decodeJwtPayload(jwt)
            // "exp" is seconds since epoch
            payload.getLong("exp") * 1000L
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeJwtPayload(jwt: String): JSONObject {
        val parts = jwt.split(".")
        require(parts.size == 3) { "Invalid JWT format" }
        val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP))
        return JSONObject(payload)
    }

    companion object {
        private const val PREFS_FILE_NAME = "reabastr_auth_tokens"
        private const val KEY_ID_TOKEN = "id_token"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}

data class UserClaims(
    val sub: String,
    val email: String,
    val name: String?
)
