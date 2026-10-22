package com.college.core.model

/**
 * Core identity contract shared by every module. Kept serializer-free on purpose:
 * JSON mapping for the wire lives in :core:network (Gson DTOs), never here.
 */
data class User(
    val id: String,
    val name: String,
    val email: String,
    val roles: Set<Role>,
    val avatarUrl: String? = null,
    val rollNumber: String? = null,
    val department: String? = null,
    val year: String? = null,
    val program: String? = null
) {
    /** Highest-privilege role of the user (resolved via [Role.priority]). */
    val primaryRole: Role? get() = roles.maxByOrNull { it.priority }

    // Set-based conveniences mirroring the Role helpers (any-role semantics)

    fun hasRole(role: Role): Boolean = role in roles
    fun isDeveloper(): Boolean = roles.any { it.isDeveloper() }
    fun canAccessStaffTools(): Boolean = roles.any { it.canAccessStaffTools() }
    fun canTakeAttendance(): Boolean = roles.any { it.canTakeAttendance() }
}
