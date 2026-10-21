package com.college.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that turns the active session into wire headers:
 *
 *  - Appends `Authorization: Bearer <token>` to every outbound call that is
 *    not an auth endpoint itself (login/register are anonymous by design).
 *  - On a 401 response for an endpoint where we DID attach a token, the session
 *    is considered invalid (expired/revoked) and cleared — every screen
 *    collecting SessionManager.state reacts by returning to the login flow.
 *
 * A 401 only clears the session if we actually sent credentials, so anonymous
 * or failed login attempts (401 bad credentials) can't wipe an unrelated state.
 */
class AuthInterceptor(
    private val sessionManager: SessionManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = sessionManager.activeToken
        val path = request.url.encodedPath.trimEnd('/')
        val isAuthEndpoint = path.endsWith(AUTH_LOGIN_PATH) || path.endsWith(AUTH_REGISTER_PATH)

        val authorized = request.newBuilder().apply {
            if (token != null && !isAuthEndpoint) {
                // header() (not addHeader) so any stale header is replaced, never duplicated
                header(AUTHORIZATION_HEADER, "$BEARER_PREFIX$token")
            }
        }.build()

        val response = chain.proceed(authorized)

        if (response.code == 401 && token != null && !isAuthEndpoint) {
            sessionManager.endSession()
        }

        return response
    }

    private companion object {
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val AUTH_LOGIN_PATH = "/auth/login"
        const val AUTH_REGISTER_PATH = "/auth/register"
    }
}
