package com.college.core.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.college.core.model.SessionState
import com.college.core.model.User
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionManager {

    private var prefs: SharedPreferences? = null
    private val gson by lazy { Gson() }

    private val _state = MutableStateFlow<SessionState>(SessionState.LoggedOut)

    val state: StateFlow<SessionState> = _state.asStateFlow()

    val isLoggedIn: Boolean get() = _state.value is SessionState.Authenticated

    val activeToken: String?
        get() = (_state.value as? SessionState.Authenticated)?.token

    val currentUser: User?
        get() = (_state.value as? SessionState.Authenticated)?.user

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    val appContext = context.applicationContext
                    prefs = createEncryptedPreferences(appContext)
                    restoreSession()
                }
            }
        }
    }

    private fun createEncryptedPreferences(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
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
                sp.edit().clear().apply()
            }
        }
    }

    fun startSession(user: User, token: String) {
        _state.value = SessionState.Authenticated(user = user, token = token)
        prefs?.edit()
            ?.putString(KEY_TOKEN, token)
            ?.putString(KEY_USER, gson.toJson(user))
            ?.apply()
    }

    fun markLoading() {
        _state.value = SessionState.Loading
    }

    fun endSession() {
        _state.value = SessionState.LoggedOut
        prefs?.edit()?.clear()?.apply()
    }

    companion object {
        private const val SECURE_PREFS_NAME = "college_auth_secure_prefs"
        private const val FALLBACK_PREFS_NAME = "college_auth_fallback_prefs"
        private const val KEY_TOKEN = "auth_session_token"
        private const val KEY_USER = "auth_session_user_json"

        val default: SessionManager by lazy { SessionManager() }
    }
}
