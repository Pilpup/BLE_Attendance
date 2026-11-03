package com.college.core.model

sealed interface SessionState {
    data object LoggedOut : SessionState
    data object Loading : SessionState
    data class Authenticated(
        val user: User,
        val token: String
    ) : SessionState
}
