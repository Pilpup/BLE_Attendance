package com.college.feature.leave

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.college.core.ui.theme.AppTheme
import com.college.feature.leave.network.LeaveApplicationResponse

// Standalone leave application screen (student): form (From/To date range,
// overlap guard, reason) and history (badges, reviewer feedback, cancel).
@Composable
fun LeaveApplicationScreen(
    applications: List<LeaveApplicationResponse>,
    isLoading: Boolean,
    isSubmitting: Boolean,
    isCancelling: Boolean,
    message: String?,
    onLoad: () -> Unit,
    onSubmit: (fromDate: String, toDate: String, reason: String) -> Unit,
    onCancel: (String) -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) { onLoad() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .systemBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = AppTheme.colors.textPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Leave Application",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.textPrimary,
                letterSpacing = (-0.5).sp
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            LeaveApplicationForm(
                applications = applications,
                isSubmitting = isSubmitting,
                message = message,
                onSubmit = onSubmit
            )

            Spacer(modifier = Modifier.height(12.dp))

            LeaveHistoryTracker(
                applications = applications,
                isLoading = isLoading,
                isCancelling = isCancelling,
                onCancel = onCancel
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
