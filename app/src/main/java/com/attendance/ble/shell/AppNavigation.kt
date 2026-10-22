package com.attendance.ble.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.college.core.model.SessionState
import com.college.core.network.SessionManager
import com.college.core.ui.DashboardRegistry
import com.college.feature.attendance.AttendanceDashboardCard
import com.college.feature.attendance.AttendanceScreen
import com.college.feature.attendance.AttendanceScreenCallbacks
import com.college.feature.attendance.viewmodel.AttendanceViewModel
import com.college.feature.library.LibraryDashboardCard
import com.college.feature.library.LibraryScreen

/**
 * The :app shell's navigation. Holds the active destination and maps the
 * routes featured cards emit ("feature_attendance", "feature_library") onto
 * the corresponding module screens.
 *
 * [:app] is the ONLY module that knows every feature, so it is also the one
 * place where each feature's DashboardCard is registered exactly once.
 */
@Composable
fun AppNavigation(
    viewModel: AttendanceViewModel,
    callbacks: AttendanceScreenCallbacks
) {
    var destination by remember { mutableStateOf(Destination.HOME) }
    val sessionState by SessionManager.default.state.collectAsState()
    val user = (sessionState as? SessionState.Authenticated)?.user

    // Register feature card contributions (registry de-duplicates by id).
    LaunchedEffect(Unit) {
        DashboardRegistry.register(AttendanceDashboardCard)
        DashboardRegistry.register(LibraryDashboardCard)
    }

    // If the session drops (mock switch / 401), the shell falls back to sign-in
    // and remembers where the user was heading.
    val effectiveDestination = if (user != null) destination else Destination.HOME

    when (effectiveDestination) {
        Destination.HOME -> {
            HomeScreen(
                user = user,
                onNavigate = { route ->
                    destination = when (route) {
                        AttendanceDashboardCard.NAV_ROUTE -> Destination.ATTENDANCE
                        LibraryDashboardCard.NAV_ROUTE -> Destination.LIBRARY
                        else -> Destination.HOME
                    }
                },
            )
        }

        Destination.ATTENDANCE -> if (user != null) {
            AttendanceScreen(
                user = user,
                viewModel = viewModel,
                callbacks = callbacks,
                onBack = { destination = Destination.HOME }
            )
        } else {
            SignInFallback()
        }

        Destination.LIBRARY -> if (user != null) {
            LibraryScreen(
                user = user,
                onBack = { destination = Destination.HOME }
            )
        } else {
            SignInFallback()
        }
    }
}

@Composable
private fun SignInFallback() {
    MockRoleSelector(
        onSelect = { mock ->
            SessionManager.default.startSession(mock, MockUsers.MOCK_DEV_TOKEN)
        }
    )
}

private enum class Destination { HOME, ATTENDANCE, LIBRARY }
