package com.college.core.network

import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor(
    private val sessionManager: SessionManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = sessionManager.activeToken
        val path = request.url.encodedPath.trimEnd('/')
        val isExplicitNoAuth = request.header(NO_AUTH_HEADER)?.equals("true", ignoreCase = true) == true
        val isAuthEndpoint = isExplicitNoAuth || (path.contains("/auth/") && !path.endsWith("/me"))

        val requestBuilder = request.newBuilder()
        if (isExplicitNoAuth) {
            requestBuilder.removeHeader(NO_AUTH_HEADER)
        }
        if (token != null && !isAuthEndpoint) {
            requestBuilder.header(AUTHORIZATION_HEADER, "$BEARER_PREFIX$token")
        }
        val authorized = requestBuilder.build()

        val isRealJwt = token?.contains(".") == true
        var response = chain.proceed(authorized)

        if (response.code == 401 && isRealJwt && !isAuthEndpoint) {
            // A single 401 can be transient (clock skew, cold-start hiccup):
            // drain the failed body and retry once before purging the session
            // — a one-off 401 should not dump the user to login mid-lecture.
            response.close()
            response = chain.proceed(authorized)
            if (response.code == 401) {
                sessionManager.endSession()
            }
        }

        return response
    }

    companion object {
        const val NO_AUTH_HEADER = "No-Auth"
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
    }
}
