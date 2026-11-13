package com.college.core.network

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Server endpoint is hardcoded. Dynamic URL switching was removed to prevent
 * token exfiltration via malicious endpoint substitution and to avoid
 * cleartext/local URL misconfiguration.
 */
class ServerConfigManager {

    private val _baseUrl = MutableStateFlow(DEFAULT_BASE_URL)

    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    val currentBaseUrl: String
        get() = DEFAULT_BASE_URL

    val currentHttpUrl: HttpUrl
        get() = DEFAULT_HTTP_URL

    // Kept for call-site compatibility; server URL is fixed.
    fun init(@Suppress("UNUSED_PARAMETER") context: Context) = Unit

    companion object {
        const val DEFAULT_BASE_URL = "https://ble-attendance-api-dbf1.onrender.com/"
        val DEFAULT_HTTP_URL: HttpUrl = DEFAULT_BASE_URL.toHttpUrl()

        val default by lazy { ServerConfigManager() }
    }
}
