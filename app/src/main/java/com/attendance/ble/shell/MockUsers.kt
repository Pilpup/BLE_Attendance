package com.attendance.ble.shell

import com.college.core.model.Role
import com.college.core.model.User

/**
 * Mock accounts behind the dashboard's development role-switcher (Step 3D).
 * Selecting one starts a session instantly — no real backend login happens,
 * so role-gated UI can be verified with 1 tap per account.
 */
object MockUsers {

    const val MOCK_DEV_TOKEN: String = "mock-dev-token-not-a-real-jwt"

    val alice = User(
        id = "mock-alice",
        name = "Alice Anderson",
        email = "alice.anderson@college.edu",
        roles = setOf(Role.STUDENT)
    )

    val profSmith = User(
        id = "mock-smith",
        name = "Prof. Smith",
        email = "j.smith@college.edu",
        roles = setOf(Role.TEACHER)
    )

    val sarah = User(
        id = "mock-sarah",
        name = "Sarah Chen",
        email = "sarah.chen@college.edu",
        roles = setOf(Role.STAFF)
    )

    val alex = User(
        id = "mock-alex",
        name = "Alex Rivera",
        email = "alex.rivera@college.edu",
        roles = setOf(Role.DEVELOPER)
    )

    val sam = User(
        id = "mock-sam",
        name = "Sam Okafor",
        email = "sam.okafor@college.edu",
        roles = setOf(Role.STUDENT, Role.STAFF) // multi-role: student who also works at the library
    )

    val all: List<User> = listOf(alice, profSmith, sarah, alex, sam)
}
