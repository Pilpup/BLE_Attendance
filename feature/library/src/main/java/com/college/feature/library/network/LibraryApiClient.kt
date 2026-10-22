package com.college.feature.library.network

import com.college.core.network.AuthInterceptor
import com.college.core.network.SessionManager
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Feature-local Retrofit engine, reused-by-contract from :core:network —
 * AuthInterceptor(SessionManager.default) injects `Authorization: Bearer`,
 * so every staff/developer mutation carries the token without manual plumbing.
 */
object LibraryApiClient {

    // Same college backend as attendance; switchable for self-hosted testing.
    var baseUrl: String = "https://ble-attendance-api-dbf1.onrender.com/"
        set(value) {
            field = if (value.endsWith("/")) value else "$value/"
            retrofitInstance = null
        }

    private var retrofitInstance: Retrofit? = null

    private fun getRetrofit(): Retrofit {
        return retrofitInstance ?: synchronized(this) {
            retrofitInstance ?: Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(
                    OkHttpClient.Builder()
                        .addInterceptor(AuthInterceptor(SessionManager.default))
                        .addInterceptor(
                            HttpLoggingInterceptor().apply {
                                level = HttpLoggingInterceptor.Level.BODY
                            }
                        )
                        .connectTimeout(60, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(60, TimeUnit.SECONDS)
                        .build()
                )
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .also { retrofitInstance = it }
        }
    }

    val service: LibraryApiService
        get() = getRetrofit().create(LibraryApiService::class.java)
}
