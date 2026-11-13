package com.college.feature.attendance.network

import com.college.core.network.NetworkClient
import com.college.core.network.ServerConfigManager

object AttendanceApiClient {

    val baseUrl: String
        get() = ServerConfigManager.default.currentBaseUrl

    val service: AttendanceApiService by lazy {
        NetworkClient.createService<AttendanceApiService>()
    }
}
