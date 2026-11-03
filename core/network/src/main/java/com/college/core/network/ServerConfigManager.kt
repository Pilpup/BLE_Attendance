package com.college.core.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ServerConfigManager {

    private var prefs: SharedPreferences? = null
    private val _baseUrl = MutableStateFlow(DEFAULT_BASE_URL)

    @Volatile
    private var _cachedHttpUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl()

    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    val currentBaseUrl: String
        get() = _baseUrl.value

    val currentHttpUrl: HttpUrl
        get() = _cachedHttpUrl

    fun init(context: Context) {
        if (prefs == null) {
            synchronized(this) {
                if (prefs == null) {
                    prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val saved = prefs?.getString(KEY_SERVER_URL, null)
                    if (!saved.isNullOrBlank()) {
                        val formatted = formatBaseUrl(saved)
                        updateUrl(formatted)
                    }
                }
            }
        }
    }

    fun setServerUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isNotBlank()) {
            val formatted = formatBaseUrl(trimmed)
            updateUrl(formatted)
            prefs?.edit()?.putString(KEY_SERVER_URL, formatted)?.apply()
        }
    }

    private fun updateUrl(formatted: String) {
        formatted.toHttpUrlOrNull()?.let { parsed ->
            _cachedHttpUrl = parsed
            _baseUrl.value = formatted
        }
    }

    private fun formatBaseUrl(raw: String): String {
        var url = raw.trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = if (url.startsWith("localhost") || url.startsWith("10.") || url.startsWith("192.168.") || url.startsWith("127.")) {
                "http://$url"
            } else {
                "https://$url"
            }
        }
        return if (url.endsWith("/")) url else "$url/"
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://ble-attendance-api-dbf1.onrender.com/"
        private const val PREFS_NAME = "ble_attendance_prefs"
        private const val KEY_SERVER_URL = "server_url"

        val default by lazy { ServerConfigManager() }
    }
}
