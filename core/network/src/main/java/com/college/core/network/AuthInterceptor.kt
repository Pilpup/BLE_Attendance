package com.college.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that turns the active session into wire headers:
 *
 *  - Appends `Authorization: Bearer <token>` to every outbound call that is
 *    not an auth endpoint itself (login/register are anonymous by design).
 *  - On a 401 response for an endpoint where we DID attach a real JWT, the
 *    session is considered invalid (expired/revoked) and cleared — every
 *    screen collecting SessionManager.state reacts by returning to the
 *    login flow.
 *
 * A 401 only clears the session if we actually sent **real** credentials:
 * real JWTs are `header.payload.signature`; :app's development mock tokens
 * contain no dots, are local-only identities the backend can neither
 * validate nor judge, and must never evict a developer's mock session.
 * Anonymous or failed login attempts can't wipe an unrelated state either.
 */
class AuthInterceptor(
    private val sessionManager: SessionManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = sessionManager.activeToken
        val path = request.url.encodedPath.trimEnd('/')
        val isExplicitNoAuth = request.header(NO_AUTH_HEADER)?.equals("true", ignoreCase = true) == true
        val isAuthEndpoint = isExplicitNoAuth || path.contains("/auth/")

        val requestBuilder = request.newBuilder()
        if (isExplicitNoAuth) {
            requestBuilder.removeHeader(NO_AUTH_HEADER)
        }
        if (token != null && !isAuthEndpoint) {
            requestBuilder.header(AUTHORIZATION_HEADER, "$BEARER_PREFIX$token")
        }
        val authorized = requestBuilder.build()

        val isRealJwt = token?.contains(".") == true
        val response = chain.proceed(authorized)

        if (response.code == 401 && isRealJwt && !isAuthEndpoint) {
            sessionManager.endSession()
        }

        return response
    }

    companion object {
        const val NO_AUTH_HEADER = "No-Auth"
        private const val AUTHORIZATION_HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "
    }
}
