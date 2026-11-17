package com.college.feature.attendance.network

import com.college.core.network.NetworkClient

object AttendanceApiClient {

    val service: AttendanceApiService by lazy {
        NetworkClient.createService<AttendanceApiService>()
    }
}
