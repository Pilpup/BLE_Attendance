package com.college.feature.leave.network

import com.college.core.network.NetworkClient
import com.college.core.network.ServerConfigManager

object LeaveApiClient {

    var baseUrl: String
        get() = ServerConfigManager.default.currentBaseUrl
        set(value) {
            ServerConfigManager.default.setServerUrl(value)
        }

    val service: LeaveApiService by lazy {
        NetworkClient.createService<LeaveApiService>()
    }
}
