package com.college.core.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.college.core.model.Role
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

    // DEVELOPER-ONLY acting-role preview. When set, `currentUser` reports the
    // real account wearing the acting role; server authorization is untouched
    // (the token's account keeps its own server-side checks). Purely a local
    // lens for previewing student/teacher/staff app flows end to end.
    //
    // R8 (review §3.3): both the lens and the unmasked account are StateFlows
    // now — they used to be plain getters, and `realUser` even re-parsed the
    // user JSON from prefs with Gson on EVERY read. HomeScreen reads both
    // during composition: stale-prone UI plus a per-frame deserialization
    // cost. Compose now collects them like the main session state.
    private val _actingRole = MutableStateFlow<Role?>(null)
    val actingRole: StateFlow<Role?> = _actingRole.asStateFlow()

    private val _realUser = MutableStateFlow<User?>(null)

    // The signed-in account as the backend returned it, unmasked by any acting role
    val realUser: StateFlow<User?> = _realUser.asStateFlow()

    // Applies the acting-role lens: masks only the roles, keeping identity fields
    private fun applyActingLens(real: User): User {
        val acting = _actingRole.value
        if (acting == null || real.roles.contains(acting)) return real
        return real.copy(roles = setOf(acting))
    }

    fun setActingRole(role: Role?) {
        _actingRole.value = role
        prefs?.edit()?.putString(KEY_ACTING_ROLE, role?.name)?.apply()
        val authenticated = _state.value as? SessionState.Authenticated ?: return
        val real = _realUser.value ?: return
        _state.value = SessionState.Authenticated(
            user = applyActingLens(real),
            token = authenticated.token
        )
    }

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

    private fun createEncryptedPreferences(context: Context): SharedPreferences? {
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
        } catch (e: Exception) {
            // R8 (review §1.7.2): the old branch silently routed JWTs and user
            // records into a PLAINTEXT prefs file on any Keystore failure
            // (corrupt vendor keystore, OS upgrade). Fail closed instead:
            // no persistent store this process — every write/read path is
            // already null-safe, so the session stays RAM-only until process
            // death and the user signs in again next launch. Any plaintext
            // data an older (vulnerable) build left behind is wiped here.
            Log.w(TAG, "Keystore unavailable (${e.message}) — session is memory-only for this process; sign in again next launch")
            try {
                context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().clear().apply()
            } catch (_: Exception) {
                // Nothing further we can do; the plaintext file may not exist
            }
            null
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
                    // Acting-role lens survives process death for continuity; a
                    // fresh login always starts unmasked (startSession clears it)
                    _realUser.value = user
                    _actingRole.value = sp.getString(KEY_ACTING_ROLE, null)
                        ?.let { name -> Role.entries.firstOrNull { it.name == name } }
                    _state.value = SessionState.Authenticated(user = applyActingLens(user), token = token)
                }
            } catch (_: Exception) {
                sp.edit().clear().apply()
            }
        }
    }

    fun startSession(user: User, token: String) {
        // Fresh login always presents the real role; clear any leftover acting mask
        _realUser.value = user
        _actingRole.value = null
        _state.value = SessionState.Authenticated(user = user, token = token)
        prefs?.edit()
            ?.putString(KEY_TOKEN, token)
            ?.putString(KEY_USER, gson.toJson(user))
            ?.putString(KEY_ACTING_ROLE, null)
            ?.apply()
    }

    fun markLoading() {
        _state.value = SessionState.Loading
    }

    fun endSession() {
        _realUser.value = null
        _actingRole.value = null
        _state.value = SessionState.LoggedOut
        prefs?.edit()?.clear()?.apply()
    }

    companion object {
        private const val SECURE_PREFS_NAME = "college_auth_secure_prefs"

        // R8 (review §1.7.2): no longer usable as a credential store — the
        // constant survives solely as the wipe target for plaintext data left
        // by older builds.
        private const val FALLBACK_PREFS_NAME = "college_auth_fallback_prefs"
        private const val KEY_TOKEN = "auth_session_token"
        private const val KEY_USER = "auth_session_user_json"
        private const val KEY_ACTING_ROLE = "auth_session_acting_role"
        private const val TAG = "SessionManager"

        val default: SessionManager by lazy { SessionManager() }
    }
}
