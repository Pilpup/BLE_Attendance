package com.college.core.network

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Interceptor that dynamically routes requests to the active [ServerConfigManager] host.
 *
 * Enables instant server switching at runtime without rebuilding Retrofit or invalidating
 * existing service proxies.
 */
class HostSelectionInterceptor(
    private val serverConfig: ServerConfigManager = ServerConfigManager.default
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val targetUrl = serverConfig.currentHttpUrl

        val newUrl = request.url.newBuilder()
            .scheme(targetUrl.scheme)
            .host(targetUrl.host)
            .port(targetUrl.port)
            .build()

        request = request.newBuilder()
            .url(newUrl)
            .build()

        return chain.proceed(request)
    }
}
