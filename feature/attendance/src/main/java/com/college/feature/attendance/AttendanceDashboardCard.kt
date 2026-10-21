package com.college.feature.attendance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.college.core.model.Role
import com.college.core.model.User
import com.college.core.ui.DashboardCard

/**
 * Attendance feature's contribution to the Home Dashboard.
 * Visible for STUDENT, TEACHER or DEVELOPER — hidden for pure STAFF.
 */
object AttendanceDashboardCard : DashboardCard {

    override val id: String = "attendance_card"
    override val priority: Int = 10 // everyday tool → top of the dashboard

    const val NAV_ROUTE: String = "feature_attendance"

    override fun isVisibleFor(user: User): Boolean =
        user.hasRole(Role.STUDENT) || user.canTakeAttendance()

    @Composable
    override fun Content(user: User, onNavigate: (String) -> Unit) {
        val actionLabel = when {
            user.canTakeAttendance() -> "Take Attendance"
            user.hasRole(Role.STUDENT) -> "Check In"
            else -> "Browse"
        }

        Surface(
            onClick = { onNavigate(NAV_ROUTE) },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.FactCheck,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(
                        text = "Classroom Attendance",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = actionLabel,
                        fontSize = 13.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }
        }
    }
}
