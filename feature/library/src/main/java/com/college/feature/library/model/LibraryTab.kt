package com.college.feature.library.model

/**
 * The three role tiers of the Library Explorer. Which tabs exist depends on
 * the active session (see LibraryViewModel):
 *  - CATALOGUE: everyone (students, teachers, staff, developers)
 *  - STAFF_INVENTORY: STAFF & DEVELOPER only (add / edit / remove books)
 *  - DEV_MAINTENANCE: DEVELOPER only (seed / clear the test catalogue)
 */
enum class LibraryTab {
    CATALOGUE,
    STAFF_INVENTORY,
    DEV_MAINTENANCE
}
