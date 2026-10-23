package com.college.core.network

import android.content.Context
import android.content.SharedPreferences
import com.college.core.model.SessionState
import com.college.core.model.User
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for the active SessionState.
 *
 * Holds the active user + JWT behind a [StateFlow] so the Home
 * Dashboard, Attendance, and Library features can all react instantly when a
 * user logs in, switches accounts, or is force-logged-out by a 401 (see
 * [AuthInterceptor]). Features read `state` / `currentUser` / `activeToken`
 * — they never import any auth UI or implement token plumbing themselves.
 *
 * Automatically persists to SharedPreferences when [init] is called with the
 * Application Context, ensuring user login state survives app restarts.
 */
class SessionManager {

    private var prefs: SharedPreferences? = null
    private val gson by lazy { Gson() }

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

    /**
     * Initializes persistence with the application context and hydrates
     * any existing authenticated session from SharedPreferences.
     */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            restoreSession()
        }
    }

    private fun restoreSession() {
        val sp = prefs ?: return
        val token = sp.getString(KEY_TOKEN, null)
        val userJson = sp.getString(KEY_USER, null)
        if (!token.isNullOrBlank() && !userJson.isNullOrBlank()) {
            try {
                val user = gson.fromJson(userJson, User::class.java)
                if (user != null && user.id.isNotBlank()) {
                    _state.value = SessionState.Authenticated(user = user, token = token)
                }
            } catch (_: Exception) {
                // If corrupted, clear
                sp.edit().clear().apply()
            }
        }
    }

    /** Login or account switch: replaces the whole session atomically and persists it. */
    fun startSession(user: User, token: String) {
        _state.value = SessionState.Authenticated(user = user, token = token)
        prefs?.edit()
            ?.putString(KEY_TOKEN, token)
            ?.putString(KEY_USER, gson.toJson(user))
            ?.apply()
    }

    /** Session is being created/restored (UI can show a progress state). */
    fun markLoading() {
        _state.value = SessionState.Loading
    }

    /** Logout or forced 401 eviction — every observer flips back to LoggedOut and clears storage. */
    fun endSession() {
        _state.value = SessionState.LoggedOut
        prefs?.edit()?.clear()?.apply()
    }

    companion object {
        private const val PREFS_NAME = "college_auth_session_prefs"
        private const val KEY_TOKEN = "auth_session_token"
        private const val KEY_USER = "auth_session_user_json"

        /**
         * Shared instance for cross-module access (Retrofit, ViewModels, etc.).
         * Can still be instantiated independently for testing or mock sessions.
         */
        val default: SessionManager by lazy { SessionManager() }
    }
}
