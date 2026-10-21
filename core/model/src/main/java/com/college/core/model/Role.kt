package com.college.core.model

/**
 * Roles a user can hold. A user may hold MULTIPLE roles at once
 * (e.g. STUDENT + STAFF), which is why [User.roles] is a Set.
 */
enum class Role(val priority: Int) {
    STUDENT(priority = 1),
    STAFF(priority = 2),
    TEACHER(priority = 3),
    DEVELOPER(priority = 4);

    /** Grants access to library-staff tooling (records, circulation). */
    fun canAccessStaffTools(): Boolean = this == STAFF || this == DEVELOPER

    /** Internal/debug surfaces: mock session switcher, diagnostics. */
    fun isDeveloper(): Boolean = this == DEVELOPER

    /** Allowed to run BLE attendance sessions for a course. */
    fun canTakeAttendance(): Boolean = this == TEACHER || this == DEVELOPER

    companion object {
        /**
         * The backend persists a single role string ("TEACHER", ...).
         * Maps it safely into the set-based model; unknown names resolve to null.
         */
        fun fromBackendName(name: String?): Role? =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}
