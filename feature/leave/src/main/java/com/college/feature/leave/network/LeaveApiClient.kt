package com.college.feature.leave.network

import com.college.core.network.NetworkClient

object LeaveApiClient {

    val service: LeaveApiService by lazy {
        NetworkClient.createService<LeaveApiService>()
    }
}
