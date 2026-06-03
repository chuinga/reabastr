package com.reabastr.app.data.remote

import com.reabastr.app.auth.AuthRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp interceptor that injects `Authorization: Bearer <idToken>` into every
 * API request. Obtains the token from [AuthRepository.getValidIdToken], which
 * handles proactive refresh internally.
 *
 * If no valid token is available (user needs to re-authenticate), the request
 * proceeds without the header — the backend will return 401.
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val authRepository: dagger.Lazy<AuthRepository>
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val token = runBlocking {
            authRepository.get().getValidIdToken()
        }

        val request = if (token != null) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            originalRequest
        }

        return chain.proceed(request)
    }
}
