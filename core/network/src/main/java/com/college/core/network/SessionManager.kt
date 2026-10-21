package com.college.core.network

import com.college.core.model.SessionState
import com.college.core.model.User
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the active SessionState.
 *
 * Holds the active user + JWT in memory behind a [StateFlow] so the Home
 * Dashboard, Attendance, and Library features can all react instantly when a
 * user logs in, switches accounts, or is force-logged-out by a 401 (see
 * [AuthInterceptor]). Features read `state` / `currentUser` / `activeToken`
 * — they never import any auth UI or implement token plumbing themselves.
 *
 * Deliberately in-memory for now: persistence (EncryptedSharedPreferences /
 * DataStore) gets added when :app hydrates this at app start, so the token is
 * never written to disk from a shared module without :app's knowledge.
 */
class SessionManager {

    private val _state = MutableStateFlow<SessionState>(SessionState.LoggedOut)

    /** Reactive stream of the global auth state. */
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val isLoggedIn: Boolean get() = _state.value is SessionState.Authenticated

    /** Raw JWT for the active session, or null when logged out / loading. */
    val activeToken: String?
        get() = (_state.value as? SessionState.Authenticated)?.token

    /** Active user, or null when logged out / loading. */
    val currentUser: User?
        get() = (_state.value as? SessionState.Authenticated)?.user

    /** Login or account switch: replaces the whole session atomically. */
    fun startSession(user: User, token: String) {
        _state.value = SessionState.Authenticated(user = user, token = token)
    }

    /** Session is being created/restored (UI can show a progress state). */
    fun markLoading() {
        _state.value = SessionState.Loading
    }

    /** Logout or forced 401 eviction — every observer flips back to LoggedOut. */
    fun endSession() {
        _state.value = SessionState.LoggedOut
    }

    companion object {
        /**
         * Shared instance for cross-module access (Retrofit, ViewModels, etc.).
         * Can still be instantiated independently for testing or mock sessions.
         */
        val default: SessionManager by lazy { SessionManager() }
    }
}
