package com.reabastr.app.data.remote

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import retrofit2.Response

/**
 * Structured error response from the backend.
 * Shape: { "error": "ERROR_CODE", "message": "Human-readable description", "details": {} }
 */
data class ApiError(
    @SerializedName("error") val error: String,
    @SerializedName("message") val message: String,
    @SerializedName("details") val details: Map<String, Any>? = null
)

/**
 * Exception carrying structured error information from the API.
 */
class ApiException(
    val httpCode: Int,
    val apiError: ApiError?
) : Exception(
    apiError?.message ?: "HTTP $httpCode"
) {
    val errorCode: String get() = apiError?.error ?: "UNKNOWN"

    val isUnauthorized: Boolean get() = httpCode == 401
    val isForbidden: Boolean get() = httpCode == 403
    val isNotFound: Boolean get() = httpCode == 404
    val isConflict: Boolean get() = httpCode == 409
    val isThrottled: Boolean get() = httpCode == 429

    companion object {
        private val gson = Gson()

        /**
         * Parses a non-successful Retrofit response into an [ApiException].
         */
        fun <T> fromResponse(response: Response<T>): ApiException {
            val errorBody = response.errorBody()?.string()
            val apiError = errorBody?.let {
                try {
                    gson.fromJson(it, ApiError::class.java)
                } catch (_: Exception) {
                    // Fallback if response isn't valid JSON
                    ApiError(error = "UNKNOWN", message = it.take(200))
                }
            }
            return ApiException(httpCode = response.code(), apiError = apiError)
        }
    }
}
