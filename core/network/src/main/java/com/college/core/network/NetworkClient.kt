package com.college.core.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Central HTTP client provider delivering a unified OkHttpClient and Retrofit engine.
 */
object NetworkClient {

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(HostSelectionInterceptor(ServerConfigManager.default))
            .addInterceptor(AuthInterceptor(SessionManager.default))
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                }
            )
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(ServerConfigManager.DEFAULT_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    private val serviceCache = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getService(serviceClass: Class<T>): T {
        return serviceCache.computeIfAbsent(serviceClass) {
            retrofit.create(it)
        } as T
    }

    inline fun <reified T : Any> createService(): T = getService(T::class.java)

    /**
     * Non-blocking probe of the backend /health endpoint to detect service readiness.
     */
    suspend fun checkServerHealth(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val request = okhttp3.Request.Builder()
                .url(ServerConfigManager.default.currentBaseUrl + "health")
                .header("No-Auth", "true")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (_: Exception) {
            false
        }
    }
}
