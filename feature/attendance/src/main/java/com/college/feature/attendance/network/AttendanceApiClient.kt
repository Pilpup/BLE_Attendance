package com.college.feature.attendance.network

import com.college.core.network.AuthInterceptor
import com.college.core.network.SessionManager
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Feature-local Retrofit engine, constructed from the shared :core:network
 * session instead of :app's hardcoded ApiClient.
 *
 * Every request automatically carries `Authorization: Bearer <token>` because
 * [AuthInterceptor] reads the active session; behavior on 401 (session clear)
 * is also handled there. Guest students simply send no auth header.
 */
object AttendanceApiClient {

    // Live Render cloud backend; switchable from the in-app Server dialog.
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

    val service: AttendanceApiService
        get() = getRetrofit().create(AttendanceApiService::class.java)
}
