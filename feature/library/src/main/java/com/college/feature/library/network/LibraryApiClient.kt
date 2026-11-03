package com.college.feature.library.network

import com.college.core.network.NetworkClient
import com.college.core.network.ServerConfigManager

object LibraryApiClient {

    var baseUrl: String
        get() = ServerConfigManager.default.currentBaseUrl
        set(value) {
            ServerConfigManager.default.setServerUrl(value)
        }

    val service: LibraryApiService by lazy {
        NetworkClient.createService<LibraryApiService>()
    }
}
