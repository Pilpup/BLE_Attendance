package com.college.feature.leave

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.college.core.ui.theme.AppTheme
import com.college.feature.leave.network.LeaveApplicationResponse

// HOD department leave queue: lists applications and opens the review dialog
// (feedback optional on approve, mandatory on reject). Owned by the leave
// feature and shown in the HOD leave approvals portal (and teacher flow).
@Composable
fun HodLeaveRequestsView(
    applications: List<LeaveApplicationResponse>,
    isLoading: Boolean,
    isReviewing: Boolean,
    message: String?,
    onReview: (String, String, String?) -> Unit,
    modifier: Modifier = Modifier
) {
    var reviewTarget by remember { mutableStateOf<LeaveApplicationResponse?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!message.isNullOrBlank()) {
            val ok = message.contains("approved", true) || message.contains("rejected", true)
            Text(
                text = message,
                fontSize = 14.sp,
                color = if (ok) AppTheme.colors.successGreen else AppTheme.colors.dangerRed
            )
        }

        if (isLoading) {
            Text("Loading leave requests…", fontSize = 14.sp, color = AppTheme.colors.textSecondary)
        } else if (applications.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = AppTheme.colors.surface,
                border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "No leave applications for your department.",
                    fontSize = 14.sp,
                    color = AppTheme.colors.textSecondary,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            applications.forEach { app ->
                DepartmentLeaveCard(
                    app = app,
                    isReviewing = isReviewing,
                    onOpenReview = { reviewTarget = app }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    reviewTarget?.let { target ->
        LeaveReviewDialog(
            app = target,
            isReviewing = isReviewing,
            onDismiss = { reviewTarget = null },
            onConfirm = { decision, comment ->
                onReview(target.id, decision, comment)
                reviewTarget = null
            }
        )
    }
}

@Composable
private fun DepartmentLeaveCard(
    app: LeaveApplicationResponse,
    isReviewing: Boolean,
    onOpenReview: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = AppTheme.colors.surface,
        border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = (app.studentName ?: "Student").let { name ->
                            if (!app.studentRoll.isNullOrBlank()) "$name (${app.studentRoll})" else name
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppTheme.colors.textPrimary
                    )
                    Text(
                        text = if (app.fromDate == app.toDate) prettyLeaveDate(app.fromDate)
                        else "${prettyLeaveDate(app.fromDate)} → ${prettyLeaveDate(app.toDate)}",
                        fontSize = 13.sp,
                        color = AppTheme.colors.textSecondary
                    )
                }
                LeaveStatusBadge(app.status)
            }
            if (app.reason.isNotBlank()) {
                Text(app.reason, fontSize = 13.sp, color = AppTheme.colors.textSecondary)
            }
            if (!app.reviewComment.isNullOrBlank()) {
                Text("HOD: ${app.reviewComment}", fontSize = 12.sp, color = AppTheme.colors.textMuted)
            }

            if (app.canReview && app.status.uppercase() == "PENDING") {
                Surface(
                    onClick = { if (!isReviewing) onOpenReview() },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isReviewing) AppTheme.colors.surfaceBorder else AppTheme.colors.primaryPurple.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, AppTheme.colors.primaryPurple.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "Review request",
                            fontWeight = FontWeight.SemiBold,
                            color = AppTheme.colors.primaryPurple
                        )
                    }
                }
            } else if (app.status.uppercase() == "PENDING") {
                Text(
                    text = "Awaiting HOD decision",
                    fontSize = 12.sp,
                    color = AppTheme.colors.textMuted
                )
            }
        }
    }
}
