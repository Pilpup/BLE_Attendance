package com.college.core.ui

import androidx.compose.runtime.Composable
import com.college.core.model.User

interface DashboardCard {
    val id: String
    val priority: Int
    fun isVisibleFor(user: User): Boolean

    @Composable
    fun Content(user: User, onNavigate: (String) -> Unit)
}
