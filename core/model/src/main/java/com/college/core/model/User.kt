package com.college.core.model

data class User(
    val id: String,
    val name: String,
    val email: String,
    val roles: Set<Role>,
    val rollNumber: String? = null,
    val department: String? = null,
    val program: String? = null,
    val semester: Int? = null
) {
    val primaryRole: Role? get() = roles.maxByOrNull { it.priority }

    fun hasRole(role: Role): Boolean = role in roles
    fun isDeveloper(): Boolean = roles.any { it.isDeveloper() }
    fun canAccessStaffTools(): Boolean = roles.any { it.canAccessStaffTools() }
    fun canTakeAttendance(): Boolean = roles.any { it.canTakeAttendance() }
}
