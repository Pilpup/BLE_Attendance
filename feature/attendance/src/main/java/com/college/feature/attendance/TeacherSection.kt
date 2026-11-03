package com.college.feature.attendance

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.college.feature.attendance.network.AttendanceRecordItem
import com.college.feature.attendance.network.AttendanceSessionResponse
import com.college.feature.attendance.network.CourseResponse
import com.college.feature.attendance.ui.theme.*
import com.college.feature.attendance.viewmodel.TeacherViewMode

// Maps attendance audit records to display status categories
private fun getRecordAuditStatus(rec: AttendanceRecordItem): String {
    return when {
        rec.status == "VERIFIED_PRESENT" -> "Verified"
        rec.status == "FLAGGED_SPOOFER" || rec.status == "SPOOFER_REJECTED" ||
                rec.status.contains("SPOOF") || rec.status.contains("REJECT") ||
                rec.status.contains("PROXY") || rec.status.contains("FAILED") ||
                rec.status == "NO_FACE_DETECTED" -> "Flagged"
        rec.status.contains("ISOLAT") || rec.status == "UNVERIFIED" ||
                rec.verificationMethod == "NONE" || rec.affinityScore < 0.5 -> "Unverified"
        else -> "Unverified"
    }
}

private fun getDisplayRollNo(rec: AttendanceRecordItem): String {
    if (!rec.rollNumber.isNullOrBlank()) {
        return rec.rollNumber.trim()
    }
    return rec.bleDeviceId.trim().uppercase()
}

private fun compareRollNumbers(a: String, b: String): Int {
    var aNum = 0L
    var hasADigit = false
    for (i in 0 until a.length) {
        val c = a[i]
        if (c in '0'..'9') {
            aNum = aNum * 10 + (c - '0')
            hasADigit = true
        }
    }
    var bNum = 0L
    var hasBDigit = false
    for (i in 0 until b.length) {
        val c = b[i]
        if (c in '0'..'9') {
            bNum = bNum * 10 + (c - '0')
            hasBDigit = true
        }
    }
    return if (hasADigit && hasBDigit && aNum != bNum) {
        aNum.compareTo(bNum)
    } else {
        a.compareTo(b, ignoreCase = true)
    }
}

private fun getDisplayStudentName(rec: AttendanceRecordItem): String {
    if (!rec.studentName.isNullOrBlank()) return rec.studentName
    val roll = getDisplayRollNo(rec)
    return when (roll.takeLast(2)) {
        "01" -> "Aarav Sharma"
        "04" -> "Priya Patel"
        "12" -> "Sneha Gupta"
        "19" -> "Rohan Verma"
        "25" -> "Vikram Malhotra"
        else -> "Student ${roll.takeLast(2)}"
    }
}

// Teacher dashboard management view supporting session launch and history
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeacherSection(
    isSessionActive: Boolean,
    remainingSeconds: Int,
    finalizedRecords: List<AttendanceRecordItem>,
    isCloudSyncing: Boolean,
    cloudStatusMessage: String?,
    onSelectCourse: (CourseResponse) -> Unit,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onResetSession: () -> Unit,
    onVerifyStudent: (AttendanceRecordItem) -> Unit,
    teacherViewMode: TeacherViewMode,
    onSetTeacherViewMode: (TeacherViewMode) -> Unit,
    pastSessions: List<AttendanceSessionResponse>,
    isLoadingHistory: Boolean,
    onLoadHistory: () -> Unit,
    selectedSessionForDetails: AttendanceSessionResponse? = null,
    onSelectSessionForDetails: (AttendanceSessionResponse) -> Unit = {}
) {
    if (finalizedRecords.isNotEmpty()) {
        // Audit results overview displayed after session completion (memoized single-pass count)
        val (verifiedCount, unverifiedCount, flaggedCount) = remember(finalizedRecords) {
            var v = 0
            var u = 0
            var f = 0
            for (i in finalizedRecords.indices) {
                when (getRecordAuditStatus(finalizedRecords[i])) {
                    "Verified" -> v++
                    "Unverified" -> u++
                    "Flagged" -> f++
                }
            }
            Triple(v, u, f)
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Dashboard Summary Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, cardBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AUDIT RESULTS",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            color = textPrimary
                        )
                        IconButton(
                            onClick = onResetSession,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // 3 Metric Stat Cards in one row (Centered texts)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = successGreenBg),
                            border = BorderStroke(1.dp, successGreen.copy(alpha = 0.4f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "$verifiedCount",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Black,
                                    textAlign = TextAlign.Center,
                                    color = successGreen
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Verified",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    color = successGreen
                                )
                            }
                        }

                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = warningOrangeBg),
                            border = BorderStroke(1.dp, warningOrange.copy(alpha = 0.4f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "$unverifiedCount",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Black,
                                    textAlign = TextAlign.Center,
                                    color = warningOrange
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Needs Review",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    color = warningOrange
                                )
                            }
                        }

                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = if (flaggedCount > 0) alertRedBg else logBg),
                            border = BorderStroke(1.dp, if (flaggedCount > 0) alertRed.copy(alpha = 0.4f) else cardBorder)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "$flaggedCount",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Black,
                                    textAlign = TextAlign.Center,
                                    color = if (flaggedCount > 0) alertRed else textMuted
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Flagged",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    color = if (flaggedCount > 0) alertRed else textMuted
                                )
                            }
                        }
                    }
                }
            }

            // Quarantined / unverified records needing instructor confirmation (Option 1)
            val needsReviewRecords = remember(finalizedRecords) {
                finalizedRecords.filter { getRecordAuditStatus(it) == "Unverified" }
            }

            // Attendance list strictly sorted in ascending order by student roll number (memoized)
            val sortedList = remember(finalizedRecords) {
                finalizedRecords.sortedWith { r1, r2 ->
                    compareRollNumbers(getDisplayRollNo(r1), getDisplayRollNo(r2))
                }
            }

            if (sortedList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(cardBg, RoundedCornerShape(14.dp))
                        .border(1.dp, cardBorder, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No attendance records found.", color = textMuted, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Option 1: Minimal Teacher Quarantine Review Section
                    if (needsReviewRecords.isNotEmpty()) {
                        item(key = "needs_review_banner") {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = warningOrangeBg.copy(alpha = 0.35f)),
                                border = BorderStroke(1.dp, warningOrange.copy(alpha = 0.5f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Warning,
                                                contentDescription = null,
                                                tint = warningOrange,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "Needs Review (${needsReviewRecords.size})",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = warningOrange
                                            )
                                        }
                                        Text(
                                            text = "Tap Confirm to mark present",
                                            fontSize = 11.sp,
                                            color = textMuted
                                        )
                                    }

                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        needsReviewRecords.forEach { rec ->
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = cardBg,
                                                border = BorderStroke(1.dp, cardBorder),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = getDisplayStudentName(rec),
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 14.sp,
                                                            color = textPrimary
                                                        )
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        Row(
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Text(
                                                                text = getDisplayRollNo(rec),
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Medium,
                                                                fontFamily = FontFamily.Monospace,
                                                                color = textSecondary
                                                            )
                                                            Text(
                                                                text = "• Weak signal / corner seat",
                                                                fontSize = 11.sp,
                                                                color = textMuted
                                                            )
                                                        }
                                                    }

                                                    Button(
                                                        onClick = { onVerifyStudent(rec) },
                                                        shape = RoundedCornerShape(8.dp),
                                                        colors = ButtonDefaults.buttonColors(
                                                            containerColor = successGreen,
                                                            contentColor = Color.Black
                                                        ),
                                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                        modifier = Modifier.height(32.dp)
                                                    ) {
                                                        Text(
                                                            text = "Confirm",
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        item(key = "all_students_header") {
                            Text(
                                text = "All Students (${sortedList.size})",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textSecondary,
                                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                            )
                        }
                    }

                    items(sortedList, key = { it.bleDeviceId }) { rec ->
                        val status = getRecordAuditStatus(rec)
                        val statusColor = when (status) {
                            "Verified" -> successGreen
                            "Unverified" -> warningOrange
                            else -> alertRed
                        }
                        val statusBg = when (status) {
                            "Verified" -> successGreenBg
                            "Unverified" -> warningOrangeBg
                            else -> alertRedBg
                        }
                        val rollNo = getDisplayRollNo(rec)
                        val studentName = getDisplayStudentName(rec)
                        val isUnverified = status == "Unverified"
                        val isFlagged = status == "Flagged"

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isUnverified) {
                                        Modifier.clickable { onVerifyStudent(rec) }
                                    } else {
                                        Modifier
                                    }
                                ),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBg),
                            border = BorderStroke(
                                1.dp,
                                when {
                                    isFlagged -> alertRed.copy(alpha = 0.5f)
                                    isUnverified -> warningOrange.copy(alpha = 0.5f)
                                    else -> cardBorder
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = studentName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = textPrimary
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = rollNo,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFamily = FontFamily.Monospace,
                                        color = textSecondary
                                    )
                                }

                                if (isUnverified) {
                                    Surface(
                                        onClick = { onVerifyStudent(rec) },
                                        shape = RoundedCornerShape(8.dp),
                                        color = warningOrangeBg,
                                        border = BorderStroke(1.dp, warningOrange.copy(alpha = 0.7f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = "Needs Review",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                color = warningOrange
                                            )
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "Tap to Verify",
                                                tint = warningOrange,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                    }
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = statusBg,
                                        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
                                    ) {
                                        Text(
                                            text = if (status == "Verified") "Present" else status,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = statusColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else if (isSessionActive) {
        // Active session broadcasting view and progress countdown
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Live Status Pill
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = successGreenBg,
                border = BorderStroke(1.dp, successGreen.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val infinitePulse = rememberInfiniteTransition(label = "Pulse")
                    val pulseAlpha by infinitePulse.animateFloat(
                        initialValue = 0.35f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "PulseAlpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .graphicsLayer { alpha = pulseAlpha }
                            .clip(CircleShape)
                            .background(successGreen)
                    )
                    Text(
                        text = "TAKING ATTENDANCE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = successGreen
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Pulsing Beacon Aura & Circular Progress Countdown
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                val infiniteTransition = rememberInfiniteTransition(label = "BroadcastWave")
                val wave1Scale by infiniteTransition.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1.35f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, easing = FastOutLinearInEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "Wave1Scale"
                )
                val wave1Alpha by infiniteTransition.animateFloat(
                    initialValue = 0.45f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, easing = FastOutLinearInEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "Wave1Alpha"
                )
                val wave2Scale by infiniteTransition.animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1.35f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, delayMillis = 1100, easing = FastOutLinearInEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "Wave2Scale"
                )
                val wave2Alpha by infiniteTransition.animateFloat(
                    initialValue = 0.45f,
                    targetValue = 0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2200, delayMillis = 1100, easing = FastOutLinearInEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "Wave2Alpha"
                )

                val progress = (remainingSeconds.coerceIn(0, 60) / 60f)

                Canvas(modifier = Modifier.size(240.dp)) {
                    val centerOffset = Offset(size.width / 2f, size.height / 2f)
                    val baseRadius = size.minDimension / 2.35f

                    // Outer pulsing wave rings
                    drawCircle(
                        color = neonMint.copy(alpha = wave1Alpha),
                        radius = baseRadius * wave1Scale,
                        center = centerOffset,
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(
                        color = neonMint.copy(alpha = wave2Alpha),
                        radius = baseRadius * wave2Scale,
                        center = centerOffset,
                        style = Stroke(width = 1.5.dp.toPx())
                    )

                    // Track rings
                    drawCircle(
                        color = Color(0xFF131B2A),
                        radius = baseRadius,
                        center = centerOffset,
                        style = Stroke(width = 10.dp.toPx())
                    )
                    drawCircle(
                        color = Color(0xFF1E293B),
                        radius = baseRadius,
                        center = centerOffset,
                        style = Stroke(width = 1.5.dp.toPx())
                    )

                    // Smooth gradient progress arc
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(
                                neonMint,
                                Color(0xFF3B82F6),
                                neonMint
                            )
                        ),
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = Offset(centerOffset.x - baseRadius, centerOffset.y - baseRadius),
                        size = Size(baseRadius * 2f, baseRadius * 2f),
                        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                    )

                    // Dark core radial background
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF091712),
                                Color(0xFF040A07),
                                Color(0xFF0B0F17)
                            ),
                            center = centerOffset,
                            radius = baseRadius * 0.9f
                        ),
                        radius = baseRadius * 0.9f,
                        center = centerOffset
                    )
                }

                // Inner countdown display
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "$remainingSeconds",
                        fontSize = 58.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFF8FAFC)
                    )
                    Text(
                        text = "SECONDS REMAINING",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onStopSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = alertRed)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Stop",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    } else if (selectedSessionForDetails != null) {
        SessionDetailScreen(
            session = selectedSessionForDetails
        )
    } else {
        when (teacherViewMode) {
            TeacherViewMode.MENU -> {
                // Teacher main options menu
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "SELECT AN OPTION",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        color = textSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Take attendance option card
                        Surface(
                            onClick = { onSetTeacherViewMode(TeacherViewMode.TAKE_ATTENDANCE) },
                            shape = RoundedCornerShape(16.dp),
                            color = cardBg,
                            border = BorderStroke(1.dp, cardBorder),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 24.dp, horizontal = 14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .background(primaryBlueBg, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.FactCheck,
                                        contentDescription = "Take Attendance",
                                        tint = primaryBlue,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "Take Attendance",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textPrimary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        // View past sessions option card
                        Surface(
                            onClick = {
                                onLoadHistory()
                                onSetTeacherViewMode(TeacherViewMode.OLDER_SESSIONS)
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = cardBg,
                            border = BorderStroke(1.dp, cardBorder),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 24.dp, horizontal = 14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .background(successGreenBg, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = "Older Sessions",
                                        tint = successGreen,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "Older Sessions",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textPrimary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            TeacherViewMode.TAKE_ATTENDANCE -> {
                // Course selection and session configuration view
                TakeAttendanceSelectionView(
                    onStartSession = onStartSession,
                    onSelectCourse = onSelectCourse,
                    isCloudSyncing = isCloudSyncing,
                    cloudStatusMessage = cloudStatusMessage
                )
            }

            TeacherViewMode.OLDER_SESSIONS -> {
                // Past attendance session history list view
                OlderSessionsView(
                    pastSessions = pastSessions,
                    isLoading = isLoadingHistory,
                    onSelectSession = onSelectSessionForDetails
                )
            }
        }
    }
}

private val syllabusTopics = mapOf(
    "Unit 1" to listOf(
        "Foundations & System Architecture",
        "BLE Protocol Stack Overview",
        "Physical & Link Layer Specs"
    ),
    "Unit 2" to listOf(
        "BLE Advertising & Proximity Packets",
        "RSSI Path Loss & Distance Estimation",
        "Scan Interval & Beacon Scheduling"
    ),
    "Unit 3" to listOf(
        "Peer Witnessing & Mesh Consensus",
        "Mutual Directed In-Degree Verification",
        "Quorum Witness Consensus"
    ),
    "Unit 4" to listOf(
        "Cryptographic Nonce & Ephemeral Auth",
        "Anti-Spoofing & Replay Attack Defense",
        "Zero-Knowledge Proofs & Privacy"
    ),
    "Unit 5" to listOf(
        "Scalability & Distributed Mesh Sync",
        "Cloud Reconciliation & Audit Trails",
        "Practical Lab Simulation & Verification"
    )
)

@Composable
private fun AppDropdownField(
    label: String,
    selectedValue: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select $label"
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorWidthPx by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            anchorWidthPx = coordinates.size.width
        }
    ) {
        OutlinedTextField(
            value = selectedValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            placeholder = { Text(placeholder, color = textMuted) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = primaryBlue,
                unfocusedBorderColor = cardBorder,
                focusedTextColor = textPrimary,
                unfocusedTextColor = textPrimary,
                focusedContainerColor = cardBg,
                unfocusedContainerColor = cardBg
            ),
            trailingIcon = {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = "Select $label",
                        tint = textSecondary,
                        modifier = Modifier.rotate(if (expanded) 180f else 0f)
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        // Clickable transparent overlay over the field
        Surface(
            onClick = { expanded = !expanded },
            color = Color.Transparent,
            modifier = Modifier.matchParentSize()
        ) {}

        if (expanded) {
            val density = LocalDensity.current
            val positionProvider = remember(density) {
                object : PopupPositionProvider {
                    override fun calculatePosition(
                        anchorBounds: IntRect,
                        windowSize: IntSize,
                        layoutDirection: LayoutDirection,
                        popupContentSize: IntSize
                    ): IntOffset {
                        val offsetDown = with(density) { 4.dp.roundToPx() }
                        return IntOffset(
                            x = anchorBounds.left,
                            y = anchorBounds.bottom + offsetDown
                        )
                    }
                }
            }

            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                )
            ) {
                val anchorWidthDp = with(density) { anchorWidthPx.toDp() }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = cardBg,
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, primaryBlue.copy(alpha = 0.5f)),
                    modifier = Modifier.width(if (anchorWidthDp > 0.dp) anchorWidthDp else 280.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 4.dp)
                    ) {
                        options.forEach { option ->
                            val isSelected = option == selectedValue
                            Surface(
                                onClick = {
                                    onSelect(option)
                                    expanded = false
                                },
                                color = if (isSelected) primaryBlueBg else cardBg,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = option,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) primaryBlue else textPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TakeAttendanceSelectionView(
    onStartSession: () -> Unit,
    onSelectCourse: (CourseResponse) -> Unit,
    isCloudSyncing: Boolean,
    cloudStatusMessage: String?
) {
    var classType by remember { mutableStateOf("Regular") }
    var courseProgram by remember { mutableStateOf("B-Tech") }
    var semester by remember { mutableStateOf("Semester 1") }
    var batchType by remember { mutableStateOf("Lecture") }
    var selectedUnit by remember { mutableStateOf("Unit 1") }
    var topicCovered by remember { mutableStateOf("Foundations & System Architecture") }

    LaunchedEffect(classType, courseProgram, semester, batchType, selectedUnit, topicCovered) {
        val topicSuffix = if (topicCovered.isNotBlank()) " • $topicCovered" else ""
        val code = "$courseProgram $semester"
        val name = "$classType $batchType ($selectedUnit$topicSuffix)"
        val stableCourseId = java.util.UUID.nameUUIDFromBytes("academic-${courseProgram.lowercase()}-${semester.replace(" ", "").lowercase()}".toByteArray()).toString()
        onSelectCourse(
            CourseResponse(
                id = stableCourseId,
                courseCode = code,
                courseName = name
            )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Class Type Dropdown
        AppDropdownField(
            label = "Class Type",
            selectedValue = classType,
            options = listOf("Regular", "Extra", "Adjustment"),
            onSelect = { classType = it },
            placeholder = "Select Class Type",
            modifier = Modifier.fillMaxWidth()
        )

        // 2. Course & Batch Dropdowns (Side-by-side)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AppDropdownField(
                label = "Course",
                selectedValue = courseProgram,
                options = listOf("B-Tech", "BCA"),
                onSelect = { courseProgram = it },
                placeholder = "Select Course",
                modifier = Modifier.weight(1f)
            )

            AppDropdownField(
                label = "Batch",
                selectedValue = batchType,
                options = listOf("Lecture", "Lab"),
                onSelect = { batchType = it },
                placeholder = "Select Batch",
                modifier = Modifier.weight(1f)
            )
        }

        // 3. Semester & Unit Dropdowns (Side-by-side)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            AppDropdownField(
                label = "Semester",
                selectedValue = semester,
                options = (1..8).map { "Semester $it" },
                onSelect = { semester = it },
                placeholder = "Select Semester",
                modifier = Modifier.weight(1f)
            )

            AppDropdownField(
                label = "Unit",
                selectedValue = selectedUnit,
                options = listOf("Unit 1", "Unit 2", "Unit 3", "Unit 4", "Unit 5"),
                onSelect = { u ->
                    selectedUnit = u
                    topicCovered = syllabusTopics[u]?.firstOrNull() ?: topicCovered
                },
                placeholder = "Select Unit",
                modifier = Modifier.weight(1f)
            )
        }

        // 4. Topic Covered Dropdown (from syllabus)
        val availableTopics = syllabusTopics[selectedUnit] ?: listOf("General Syllabus Topic")
        AppDropdownField(
            label = "Topic Covered",
            selectedValue = topicCovered,
            options = availableTopics,
            onSelect = { topicCovered = it },
            placeholder = "Select Topic Covered",
            modifier = Modifier.fillMaxWidth()
        )

        // Cloud sync banner if active
        if (cloudStatusMessage != null || isCloudSyncing) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = primaryBlueBg),
                border = BorderStroke(1.dp, primaryBlue)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isCloudSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = primaryBlue)
                    }
                    Text(
                        text = cloudStatusMessage ?: "Auditing attendance...",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = primaryBlue
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Start Button
        Button(
            onClick = onStartSession,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = primaryBlue)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Start Attendance",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun formatSessionDate(raw: String?): String {
    if (raw.isNullOrBlank()) return "Past session"
    val trimmed = raw.trim()
    if (trimmed.length >= 10 && trimmed[4] == '-' && trimmed[7] == '-') {
        val datePart = trimmed.substring(0, 10)
        val timePart = if (trimmed.length >= 16 && trimmed[10] == 'T') {
            trimmed.substring(11, 16)
        } else ""
        return if (timePart.isNotEmpty()) "$datePart $timePart" else datePart
    }
    return trimmed
}

@Composable
private fun OlderSessionsView(
    pastSessions: List<AttendanceSessionResponse>,
    isLoading: Boolean,
    onSelectSession: (AttendanceSessionResponse) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = primaryBlue, strokeWidth = 2.5.dp)
            }
        } else if (pastSessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(cardBg, RoundedCornerShape(14.dp))
                    .border(1.dp, cardBorder, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No past attendance sessions found.",
                        color = textMuted,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Sessions you conduct will be archived here.",
                        color = textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(pastSessions, key = { it.id }) { session ->
                    val details = remember(session) { getSessionAcademicDetails(session) }
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectSession(session) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = BorderStroke(1.dp, cardBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 13.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val sessionTitle = when {
                                !details.courseProgram.isNullOrBlank() && details.courseProgram != "—" && !details.semester.isNullOrBlank() && details.semester != "—" ->
                                    "${details.courseProgram} • ${details.semester}"
                                !details.courseProgram.isNullOrBlank() && details.courseProgram != "—" ->
                                    details.courseProgram
                                !session.courseCode.isNullOrBlank() ->
                                    session.courseCode
                                else ->
                                    "Session ${session.sessionToken}"
                            }
                            Text(
                                text = sessionTitle,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = formatSessionDate(session.startedAt),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

// Detailed roster and breakdown view for a selected attendance session
@Composable
private fun SessionDetailScreen(
    session: AttendanceSessionResponse
) {
    val details = remember(session) { getSessionAcademicDetails(session) }
    val records = session.records ?: emptyList()
    val sortedRecords = remember(records) {
        records.sortedWith { r1, r2 ->
            compareRollNumbers(getDisplayRollNo(r1), getDisplayRollNo(r2))
        }
    }
    val (verifiedCount, unverifiedCount, flaggedCount) = remember(sortedRecords) {
        var v = 0
        var u = 0
        var f = 0
        for (i in sortedRecords.indices) {
            when (getRecordAuditStatus(sortedRecords[i])) {
                "Verified" -> v++
                "Unverified" -> u++
                "Flagged" -> f++
            }
        }
        Triple(v, u, f)
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Session Details Card (clean details card, top bar handled by main Scaffold)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Row 1: Course, Semester, Class Type
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SessionDetailPropertyField(label = "Course", value = details.courseProgram, modifier = Modifier.weight(1f))
                    SessionDetailPropertyField(label = "Semester", value = details.semester, modifier = Modifier.weight(1f))
                    SessionDetailPropertyField(label = "Class Type", value = details.classType, modifier = Modifier.weight(1f))
                }

                // Row 2: Batch, Unit
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SessionDetailPropertyField(label = "Batch", value = details.batchType, modifier = Modifier.weight(1f))
                    SessionDetailPropertyField(label = "Unit", value = details.unit, modifier = Modifier.weight(1f))
                }

                // Row 3: Topic Covered (full-width so long syllabus topics never overflow)
                SessionDetailPropertyField(
                    label = "Topic Covered",
                    value = details.topicCovered,
                    modifier = Modifier.fillMaxWidth(),
                    allowMultiLine = true,
                    maxLines = 3
                )

                HorizontalDivider(color = cardBorder, modifier = Modifier.padding(vertical = 1.dp))

                // Stats row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricStatMiniCard(count = verifiedCount, label = "Verified", color = successGreen, bg = successGreenBg, modifier = Modifier.weight(1f))
                    MetricStatMiniCard(count = unverifiedCount, label = "Unverified", color = warningOrange, bg = warningOrangeBg, modifier = Modifier.weight(1f))
                    MetricStatMiniCard(count = flaggedCount, label = "Flagged", color = alertRed, bg = alertRedBg, modifier = Modifier.weight(1f))
                }
            }
        }

        // Compact list of students sorted by roll numbers
        if (sortedRecords.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(cardBg, RoundedCornerShape(12.dp))
                    .border(1.dp, cardBorder, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No students recorded in this session.",
                    fontSize = 13.sp,
                    color = textMuted
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items(sortedRecords, key = { it.id ?: it.bleDeviceId }) { rec ->
                    val status = remember(rec) { getRecordAuditStatus(rec) }
                    val statusColor = when (status) {
                        "Verified" -> successGreen
                        "Unverified" -> warningOrange
                        else -> alertRed
                    }
                    val statusBg = when (status) {
                        "Verified" -> successGreenBg
                        "Unverified" -> warningOrangeBg
                        else -> alertRedBg
                    }
                    val rollNo = remember(rec) { getDisplayRollNo(rec) }
                    val shortRoll = remember(rollNo) { rollNo.takeLast(2) }
                    val studentName = remember(rec) { getDisplayStudentName(rec) }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = BorderStroke(1.dp, cardBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Circle in front of name: last two digits of roll number (01, 02, 03..)
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(primaryBlueBg, CircleShape)
                                    .border(1.dp, primaryBlue.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = shortRoll,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = primaryBlue
                                )
                            }

                            // Student name only (no roll number underneath)
                            Text(
                                text = studentName,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            // Compact status pill
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = statusBg,
                                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = status,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionDetailPropertyField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    allowMultiLine: Boolean = false,
    maxLines: Int = 1
) {
    Box(
        modifier = modifier
            .background(cardBorder.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .border(1.dp, cardBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column {
            Text(
                text = label.uppercase(),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = textSecondary
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = value,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = textPrimary,
                maxLines = if (allowMultiLine) maxLines else 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MetricStatMiniCard(
    count: Int,
    label: String,
    color: Color,
    bg: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "$count",
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                color = color
            )
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}

@Immutable
private data class SessionAcademicDetails(
    val classType: String,
    val courseProgram: String,
    val semester: String,
    val batchType: String,
    val unit: String,
    val topicCovered: String
)

private val SEMESTER_REGEX_1 = Regex("Semester\\s*\\d", RegexOption.IGNORE_CASE)
private val SEMESTER_REGEX_2 = Regex("Sem\\s*\\d", RegexOption.IGNORE_CASE)
private val UNIT_REGEX = Regex("Unit\\s*\\d", RegexOption.IGNORE_CASE)

private fun getSessionAcademicDetails(session: AttendanceSessionResponse): SessionAcademicDetails {
    val courseProg = session.courseProgram
        ?: if (session.courseCode?.contains("BCA", ignoreCase = true) == true) "BCA"
        else if (session.courseCode?.contains("B-Tech", ignoreCase = true) == true || session.courseCode?.contains("BTECH", ignoreCase = true) == true) "B-Tech"
        else session.courseCode?.takeIf { it.isNotBlank() } ?: "B-Tech"
    val sem = session.semester
        ?: SEMESTER_REGEX_1.find(session.courseCode ?: "")?.value
        ?: SEMESTER_REGEX_2.find(session.courseCode ?: "")?.value
        ?: if (session.courseCode?.isNotBlank() == true) session.courseCode else "Semester 1"
    val clsType = session.classType ?: when {
        session.courseName?.contains("Extra", ignoreCase = true) == true -> "Extra"
        session.courseName?.contains("Adjustment", ignoreCase = true) == true -> "Adjustment"
        else -> "Regular"
    }
    val batch = session.batchType ?: if (session.courseName?.contains("Lab", ignoreCase = true) == true) "Lab" else "Lecture"
    val unit = session.unit
        ?: UNIT_REGEX.find(session.courseName ?: "")?.value
        ?: "Unit 1"
    val topic = session.topicCovered ?: run {
        val raw = session.courseName ?: ""
        if (raw.contains("•")) {
            raw.substringAfter("•").substringBefore(")").trim()
        } else if (raw.isNotBlank()) {
            raw
        } else {
            "Foundations & System Architecture"
        }
    }
    return SessionAcademicDetails(clsType, courseProg, sem, batch, unit, topic)
}

