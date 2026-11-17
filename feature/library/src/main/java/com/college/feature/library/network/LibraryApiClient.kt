package com.college.feature.library.network

import com.college.core.network.NetworkClient

object LibraryApiClient {

    val service: LibraryApiService by lazy {
        NetworkClient.createService<LibraryApiService>()
    }
}
