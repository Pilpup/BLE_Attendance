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
import com.college.feature.attendance.LeaveApplicationScreen
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

    // Route unauthenticated users away from feature screens, allowing home and auth navigation
    val effectiveDestination = when {
        user != null -> if (destination == Destination.AUTH) Destination.HOME else destination
        destination == Destination.ATTENDANCE || destination == Destination.LIBRARY ||
        destination == Destination.LEAVE -> Destination.AUTH
        else -> destination
    }

    when (effectiveDestination) {
        Destination.HOME -> {
            HomeScreen(
                user = user,
                onNavigate = { route ->
                    destination = when {
                        route == "auth" -> Destination.AUTH
                        route.startsWith(AttendanceDashboardCard.NAV_ROUTE) -> {
                            when {
                                route.contains("older") -> {
                                    viewModel.teacherViewMode = TeacherViewMode.OLDER_SESSIONS
                                    viewModel.selectedSessionForDetails = null
                                    viewModel.loadSessionHistory()
                                }
                                route.contains("leave") -> {
                                    viewModel.teacherViewMode = TeacherViewMode.LEAVE_REQUESTS
                                    viewModel.selectedSessionForDetails = null
                                    viewModel.loadDepartmentLeaveApplications()
                                }
                                else -> {
                                    viewModel.teacherViewMode = TeacherViewMode.TAKE_ATTENDANCE
                                    viewModel.selectedSessionForDetails = null
                                }
                            }
                            Destination.ATTENDANCE
                        }
                        route == LibraryDashboardCard.NAV_ROUTE -> Destination.LIBRARY
                        route == "leave_application" -> Destination.LEAVE
                        else -> Destination.HOME
                    }
                },
                onRequestSignOut = { viewModel.logout() },
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
            // Handles back press navigation for attendance sessions and historical records
            val handleBack = remember(user, viewModel) {
                {
                    if (viewModel.selectedSessionForDetails != null) {
                        viewModel.selectedSessionForDetails = null
                    } else if (viewModel.isSessionActive) {
                        viewModel.resetSession()
                    } else if (viewModel.finalizedRecords.isNotEmpty()) {
                        // Teacher post-session attendance list: back to home.
                        destination = Destination.HOME
                    } else if (viewModel.teacherViewMode == TeacherViewMode.LEAVE_REQUESTS) {
                        // Sub-view inside take-attendance; back returns to the form.
                        viewModel.teacherViewMode = TeacherViewMode.TAKE_ATTENDANCE
                    } else if (
                        user.hasRole(com.college.core.model.Role.STUDENT) &&
                        viewModel.studentPhase != com.college.feature.attendance.viewmodel.StudentPhase.STANDBY
                    ) {
                        // Student check-in/status flow: back to home.
                        destination = Destination.HOME
                    } else {
                        // OLDER_SESSIONS list and take-attendance root return home.
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
            BackHandler(enabled = true) {
                destination = Destination.HOME
            }
            LibraryScreen(
                user = user,
                onBack = { destination = Destination.HOME }
            )
        } else {
            destination = Destination.HOME
        }

        Destination.LEAVE -> if (user != null) {
            BackHandler(enabled = true) {
                destination = Destination.HOME
            }
            LeaveApplicationScreen(
                applications = viewModel.myLeaveApplications,
                isLoading = viewModel.isLoadingLeave,
                isSubmitting = viewModel.isSubmittingLeave,
                message = viewModel.leaveMessage,
                onLoad = { viewModel.loadMyLeaveApplications() },
                onSubmit = { f, t, r -> viewModel.submitLeaveApplication(f, t, r) },
                onBack = { destination = Destination.HOME }
            )
        } else {
            destination = Destination.HOME
        }
    }
}

private enum class Destination { HOME, AUTH, ATTENDANCE, LIBRARY, LEAVE }
