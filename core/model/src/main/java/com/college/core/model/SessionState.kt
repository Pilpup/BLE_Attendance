package com.college.core.model

/**
 * Global auth state observed by the Home Dashboard and feature screens.
 * Features simply collect this state — they never implement login/logout UI themselves.
 */
sealed interface SessionState {

    /** No active session — UI shows the login entry point. */
    data object LoggedOut : SessionState

    /** Transient state: session being created or restored (e.g. validating a persisted token at app start). */
    data object Loading : SessionState

    /**
     * Active session. [token] is the raw backend JWT —
     * ":app" wiring never touches it; AuthInterceptor adds "Bearer " on the wire.
     */
    data class Authenticated(
        val user: User,
        val token: String
    ) : SessionState
}
