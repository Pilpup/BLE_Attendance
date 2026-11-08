package com.college.feature.attendance

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.college.core.model.Role
import com.college.core.model.User
import com.college.core.ui.DashboardCard
import com.college.feature.attendance.ui.theme.cardBg
import com.college.feature.attendance.ui.theme.cardBorder
import com.college.feature.attendance.ui.theme.childPurple
import com.college.feature.attendance.ui.theme.childPurpleBg
import com.college.feature.attendance.ui.theme.primaryBlue
import com.college.feature.attendance.ui.theme.primaryBlueBg
import com.college.feature.attendance.ui.theme.textPrimary
import com.college.feature.attendance.ui.theme.textSecondary

// Dashboard card entry point for the attendance feature
object AttendanceDashboardCard : DashboardCard {

    override val id: String = "attendance_card"
    override val priority: Int = 10

    const val NAV_ROUTE: String = "feature_attendance"

    override fun isVisibleFor(user: User): Boolean =
        user.hasRole(Role.STUDENT) || user.canTakeAttendance()

    @Composable
    override fun Content(user: User, onNavigate: (String) -> Unit) {
        val subtitle = when {
            user.canTakeAttendance() -> "Host classroom beacon · Automated mesh audit"
            user.hasRole(Role.STUDENT) -> "Check in to active class session"
            else -> "Classroom attendance portal"
        }

        Surface(
            onClick = { onNavigate(NAV_ROUTE) },
            shape = RoundedCornerShape(16.dp),
            color = cardBg,
            border = null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(primaryBlueBg, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.FactCheck,
                        contentDescription = null,
                        tint = primaryBlue
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp)
                ) {
                    Text(
                        text = "Classroom Attendance",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary
                    )
                    Text(
                        text = subtitle,
                        fontSize = 13.sp,
                        color = textSecondary
                    )
                    if (user.canTakeAttendance()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = childPurpleBg,
                            border = BorderStroke(0.5.dp, childPurple.copy(alpha = 0.5f)),
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            Text(
                                text = "Instructor Console Access",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = childPurple,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
