package com.college.feature.library.network

import com.college.core.network.NetworkClient
import com.college.core.network.ServerConfigManager

object LibraryApiClient {

    val baseUrl: String
        get() = ServerConfigManager.default.currentBaseUrl

    val service: LibraryApiService by lazy {
        NetworkClient.createService<LibraryApiService>()
    }
}
