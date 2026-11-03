package com.college.feature.attendance.network

import com.college.core.network.NetworkClient
import com.college.core.network.ServerConfigManager

object AttendanceApiClient {

    var baseUrl: String
        get() = ServerConfigManager.default.currentBaseUrl
        set(value) {
            ServerConfigManager.default.setServerUrl(value)
        }

    val service: AttendanceApiService by lazy {
        NetworkClient.createService<AttendanceApiService>()
    }
}
