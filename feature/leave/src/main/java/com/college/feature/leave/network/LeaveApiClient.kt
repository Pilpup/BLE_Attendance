package com.college.feature.leave.network

import com.college.core.network.NetworkClient
import com.college.core.network.ServerConfigManager

object LeaveApiClient {

    // Read-only: dynamic URL switching was removed by hardening (fixed
    // server endpoint), matching AttendanceApiClient.
    val baseUrl: String
        get() = ServerConfigManager.default.currentBaseUrl

    val service: LeaveApiService by lazy {
        NetworkClient.createService<LeaveApiService>()
    }
}
