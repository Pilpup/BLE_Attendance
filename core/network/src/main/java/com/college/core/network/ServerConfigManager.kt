package com.college.core.network

import android.content.Context
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Server endpoint is hardcoded. Dynamic URL switching was removed to prevent
 * token exfiltration via malicious endpoint substitution and to avoid
 * cleartext/local URL misconfiguration.
 */
class ServerConfigManager {

    // Kept for call-site compatibility; server URL is fixed.
    fun init(@Suppress("UNUSED_PARAMETER") context: Context) = Unit

    companion object {
        const val DEFAULT_BASE_URL = "https://ble-attendance-api-dbf1.onrender.com/"
        val DEFAULT_HTTP_URL: HttpUrl = DEFAULT_BASE_URL.toHttpUrl()

        val default by lazy { ServerConfigManager() }
    }
}
