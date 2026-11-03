package com.college.core.model

enum class Role(val priority: Int) {
    STUDENT(priority = 1),
    STAFF(priority = 2),
    TEACHER(priority = 3),
    DEVELOPER(priority = 4);

    fun canAccessStaffTools(): Boolean = this == STAFF || this == DEVELOPER
    fun isDeveloper(): Boolean = this == DEVELOPER
    fun canTakeAttendance(): Boolean = this == TEACHER || this == DEVELOPER

    companion object {
        fun fromBackendName(name: String?): Role? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
