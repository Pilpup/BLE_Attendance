package com.attendance.ble.shell

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.college.core.model.SessionState
import com.college.core.network.SessionManager
import com.college.core.ui.DashboardRegistry
import com.college.feature.attendance.AttendanceDashboardCard
import com.college.feature.attendance.AttendanceScreen
import com.college.feature.attendance.AttendanceScreenCallbacks
import com.college.feature.attendance.viewmodel.AttendanceViewModel
import com.college.feature.attendance.viewmodel.TeacherViewMode
import com.college.feature.library.LibraryDashboardCard
import com.college.feature.library.LibraryScreen

// Shell navigation routing between dashboard and feature screens
@Composable
fun AppNavigation(
    viewModel: AttendanceViewModel,
    callbacks: AttendanceScreenCallbacks
) {
    var destination by rememberSaveable {
        mutableStateOf(if (SessionManager.default.isLoggedIn) Destination.HOME else Destination.AUTH)
    }
    val sessionState by SessionManager.default.state.collectAsState()
    val user = (sessionState as? SessionState.Authenticated)?.user

    // Register feature cards with the dashboard registry
    LaunchedEffect(Unit) {
        DashboardRegistry.register(AttendanceDashboardCard)
        DashboardRegistry.register(LibraryDashboardCard)
    }

    // Direct unauthenticated users to auth screen, authenticated to destination
    val effectiveDestination = if (user != null) {
        if (destination == Destination.AUTH) Destination.HOME else destination
    } else {
        Destination.AUTH
    }

    when (effectiveDestination) {
        Destination.HOME -> {
            HomeScreen(
                user = user,
                onNavigate = { route ->
                    destination = when {
                        route == "auth" -> Destination.AUTH
                        route.startsWith(AttendanceDashboardCard.NAV_ROUTE) -> {
                            if (route.contains("older")) {
                                viewModel.teacherViewMode = TeacherViewMode.OLDER_SESSIONS
                            } else if (route.contains("take")) {
                                viewModel.teacherViewMode = TeacherViewMode.TAKE_ATTENDANCE
                            } else {
                                viewModel.teacherViewMode = TeacherViewMode.MENU
                            }
                            Destination.ATTENDANCE
                        }
                        route == LibraryDashboardCard.NAV_ROUTE -> Destination.LIBRARY
                        else -> Destination.HOME
                    }
                },
            )
        }

        Destination.AUTH -> {
            BackHandler(enabled = true) {
                destination = Destination.HOME
            }
            AuthScreen(
                onBack = { destination = Destination.HOME },
                onAuthSuccess = { destination = Destination.HOME }
            )
        }

        Destination.ATTENDANCE -> if (user != null) {
            val handleBack = remember(user, viewModel) {
                {
                    if (user.canTakeAttendance() && !viewModel.isSessionActive && viewModel.finalizedRecords.isEmpty()) {
                        if (viewModel.selectedSessionForDetails != null) {
                            viewModel.selectedSessionForDetails = null
                        } else if (viewModel.teacherViewMode != TeacherViewMode.MENU) {
                            viewModel.teacherViewMode = TeacherViewMode.MENU
                        } else {
                            destination = Destination.HOME
                        }
                    } else {
                        destination = Destination.HOME
                    }
                }
            }

            BackHandler(enabled = true) {
                handleBack()
            }

            AttendanceScreen(
                user = user,
                viewModel = viewModel,
                callbacks = callbacks,
                onBack = handleBack
            )
        } else {
            destination = Destination.HOME
        }

        Destination.LIBRARY -> if (user != null) {
            LibraryScreen(
                user = user,
                onBack = { destination = Destination.HOME }
            )
        } else {
            destination = Destination.HOME
        }
    }
}

private enum class Destination { HOME, AUTH, ATTENDANCE, LIBRARY }
