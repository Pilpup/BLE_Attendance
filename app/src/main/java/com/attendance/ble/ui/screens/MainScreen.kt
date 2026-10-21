package com.attendance.ble.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendance.ble.network.AttendanceRecordItem
import com.attendance.ble.network.CourseResponse
import com.attendance.ble.network.RegisterRequest
import com.attendance.ble.ui.theme.*
import com.attendance.ble.viewmodel.PeerData
import com.attendance.ble.viewmodel.StudentPhase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    // Teacher Props
    isSessionActive: Boolean,
    sessionId: String,
    sessionSalt: String,
    remainingSeconds: Int,
    broadcastPayload: String,
    finalizedRecords: List<AttendanceRecordItem> = emptyList(),
    isCloudSyncing: Boolean = false,
    cloudStatusMessage: String? = null,
    availableCourses: List<CourseResponse> = emptyList(),
    selectedCourse: CourseResponse? = null,
    onSelectCourse: (CourseResponse) -> Unit = {},
    onCreateCourse: (String, String) -> Unit = { _, _ -> },
    onStartTeacherSession: () -> Unit,
    onStopTeacherSession: () -> Unit,
    onShareAttendance: () -> Unit = {},
    onResetSession: () -> Unit = {},

    // Student Props
    studentId: String,
    studentPhase: StudentPhase,
    detectedSessionId: String,
    detectedSessionSalt: String,
    deskNeighbours: List<PeerData>,
    studentDiscoveryRemainingSeconds: Int = 0,
    studentStatusMessage: String? = null,
    onStartStudentScanning: () -> Unit,
    onStopStudentScanning: () -> Unit,
    onUpdateRollNumber: (String) -> Unit = {},

    // Auth & Server Props
    isLoggedIn: Boolean = false,
    userName: String = "Guest",
    userRole: String = "STUDENT",
    isAuthLoading: Boolean = false,
    authErrorText: String? = null,
    serverUrl: String = "https://ble-attendance-api-dbf1.onrender.com/",
    onLogin: (String, String) -> Unit = { _, _ -> },
    onRegister: (RegisterRequest) -> Unit = {},
    onLogout: () -> Unit = {},
    onUpdateServerUrl: (String) -> Unit = {},

    showAuthDialog: Boolean = false,
    onDismissAuthDialog: () -> Unit = {},
    onOpenAuthDialog: () -> Unit = {},

    onRequestPermissions: () -> Unit
) {
    val isTeacherPrivileged = isLoggedIn && userRole == "TEACHER"

    if (showAuthDialog) {
        AuthServerDialog(
            isLoggedIn = isLoggedIn,
            userName = userName,
            userRole = userRole,
            rollNumber = studentId,
            serverUrl = serverUrl,
            isAuthLoading = isAuthLoading,
            authErrorText = authErrorText,
            onDismiss = onDismissAuthDialog,
            onLogin = onLogin,
            onRegister = onRegister,
            onLogout = onLogout,
            onUpdateServerUrl = onUpdateServerUrl,
            onUpdateRollNumber = onUpdateRollNumber
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (isTeacherPrivileged) "Teacher Console" else "Student Attendance",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = if (isTeacherPrivileged) {
                                if (isLoggedIn) "$userName • Orchestrator" else "BLE Orchestrator"
                            } else {
                                "Roll: $studentId • Proximity Node"
                            },
                            fontSize = 12.sp,
                            color = textSecondary
                        )
                    }
                },
                actions = {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isTeacherPrivileged) childPurpleBg else if (isLoggedIn) primaryBlueBg else logBg,
                        border = BorderStroke(1.dp, if (isTeacherPrivileged) childPurple else if (isLoggedIn) primaryBlue else cardBorder),
                        modifier = Modifier.clickable { onOpenAuthDialog() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isLoggedIn) successGreen else textMuted)
                            )
                            Text(
                                text = if (isLoggedIn) {
                                    if (isTeacherPrivileged) "TEACHER" else "STUDENT"
                                } else "GUEST",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isTeacherPrivileged) childPurple else if (isLoggedIn) primaryBlue else textSecondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    TextButton(
                        onClick = onRequestPermissions,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("Permissions", fontSize = 12.sp, color = textSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = cardBg,
                    titleContentColor = textPrimary
                )
            )
        },
        containerColor = appBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isTeacherPrivileged) {
                TeacherSection(
                    isSessionActive = isSessionActive,
                    sessionId = sessionId,
                    sessionSalt = sessionSalt,
                    remainingSeconds = remainingSeconds,
                    broadcastPayload = broadcastPayload,
                    finalizedRecords = finalizedRecords,
                    isCloudSyncing = isCloudSyncing,
                    cloudStatusMessage = cloudStatusMessage,
                    availableCourses = availableCourses,
                    selectedCourse = selectedCourse,
                    onSelectCourse = onSelectCourse,
                    onCreateCourse = onCreateCourse,
                    onStartSession = onStartTeacherSession,
                    onStopSession = onStopTeacherSession,
                    onShareAttendance = onShareAttendance,
                    onResetSession = onResetSession
                )
            } else {
                StudentSection(
                    studentPhase = studentPhase,
                    detectedSessionId = detectedSessionId,
                    detectedSessionSalt = detectedSessionSalt,
                    deskNeighbours = deskNeighbours,
                    studentDiscoveryRemainingSeconds = studentDiscoveryRemainingSeconds,
                    studentStatusMessage = studentStatusMessage,
                    onStartScanning = onStartStudentScanning,
                    onStopScanning = onStopStudentScanning
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeacherSection(
    isSessionActive: Boolean,
    sessionId: String,
    sessionSalt: String,
    remainingSeconds: Int,
    broadcastPayload: String,
    finalizedRecords: List<AttendanceRecordItem>,
    isCloudSyncing: Boolean,
    cloudStatusMessage: String?,
    availableCourses: List<CourseResponse>,
    selectedCourse: CourseResponse?,
    onSelectCourse: (CourseResponse) -> Unit,
    onCreateCourse: (String, String) -> Unit,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onShareAttendance: () -> Unit,
    onResetSession: () -> Unit
) {
    if (finalizedRecords.isNotEmpty()) {
        // ==========================================
        // AUDIT RESULTS SCREEN (Full Height Scrollable)
        // ==========================================
        val verifiedCount = finalizedRecords.count { it.status == "VERIFIED_PRESENT" || it.status == "PRESENT" }
        val witnessedCount = finalizedRecords.count { it.verificationMethod.contains("WITNESS") }
        val spooferCount = finalizedRecords.count { it.status.contains("SPOOF") || it.status.contains("REJECT") }

        var selectedFilter by remember { mutableStateOf("ALL") }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Dashboard Summary Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, cardBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
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
                        Column {
                            Text(
                                text = "AUDIT RESULTS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = textSecondary
                            )
                            Text(
                                text = selectedCourse?.courseCode ?: "Session #$sessionId",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = successGreenBg,
                            border = BorderStroke(1.dp, successGreen)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(successGreen)
                                )
                                Text(
                                    text = "SAVED TO CLOUD",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = successGreen
                                )
                            }
                        }
                    }

                    // 3 Metric Stat Cards
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = successGreenBg),
                            border = BorderStroke(1.dp, Color(0xFFBBF7D0))
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("$verifiedCount", fontSize = 20.sp, fontWeight = FontWeight.Black, color = successGreen)
                                Text("Verified", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = successGreen)
                            }
                        }

                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = primaryBlueBg),
                            border = BorderStroke(1.dp, Color(0xFFBFDBFE))
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("$witnessedCount", fontSize = 20.sp, fontWeight = FontWeight.Black, color = primaryBlue)
                                Text("Witnessed", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = primaryBlue)
                            }
                        }

                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = if (spooferCount > 0) alertRedBg else logBg),
                            border = BorderStroke(1.dp, if (spooferCount > 0) Color(0xFFFECACA) else cardBorder)
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    "$spooferCount",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (spooferCount > 0) alertRed else textMuted
                                )
                                Text(
                                    "Flagged",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (spooferCount > 0) alertRed else textMuted
                                )
                            }
                        }
                    }

                    // Action buttons: Export CSV & New Session
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onShareAttendance,
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Export CSV", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onResetSession,
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = primaryBlue),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("+ New Session", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Filter Chips
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedFilter == "ALL",
                        onClick = { selectedFilter = "ALL" },
                        label = { Text("All (${finalizedRecords.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == "VERIFIED",
                        onClick = { selectedFilter = "VERIFIED" },
                        label = { Text("Verified ($verifiedCount)", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == "WITNESSED",
                        onClick = { selectedFilter = "WITNESSED" },
                        label = { Text("Witnessed ($witnessedCount)", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    )
                }
                if (spooferCount > 0) {
                    item {
                        FilterChip(
                            selected = selectedFilter == "FLAGGED",
                            onClick = { selectedFilter = "FLAGGED" },
                            label = { Text("Flagged ($spooferCount)", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        )
                    }
                }
            }

            // Filtered Attendance List - Takes all remaining screen height!
            val filteredList = when (selectedFilter) {
                "VERIFIED" -> finalizedRecords.filter { it.status == "VERIFIED_PRESENT" || it.status == "PRESENT" }
                "WITNESSED" -> finalizedRecords.filter { it.verificationMethod.contains("WITNESS") }
                "FLAGGED" -> finalizedRecords.filter { it.status.contains("SPOOF") || it.status.contains("REJECT") }
                else -> finalizedRecords
            }

            if (filteredList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(cardBg, RoundedCornerShape(12.dp))
                        .border(1.dp, cardBorder, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No records match the selected filter.", color = textMuted, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredList) { rec ->
                        val isVerified = rec.status == "VERIFIED_PRESENT" || rec.status == "PRESENT"
                        val isSpoofer = rec.status == "FLAGGED_SPOOFER" || rec.status == "SPOOFER_REJECTED"
                        val badgeBg = if (isVerified) successGreenBg else if (isSpoofer) alertRedBg else warningOrangeBg
                        val badgeColor = if (isVerified) successGreen else if (isSpoofer) alertRed else warningOrange
                        val badgeText = if (isVerified) "VERIFIED" else if (isSpoofer) "FLAGGED" else "ISOLATED"

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = cardBg),
                            border = BorderStroke(1.dp, if (isSpoofer) alertRed.copy(alpha = 0.3f) else cardBorder),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(if (isVerified) successGreenBg else if (isSpoofer) alertRedBg else logBg)
                                                .border(1.dp, badgeColor.copy(alpha = 0.5f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = rec.bleDeviceId.takeLast(2),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = badgeColor
                                            )
                                        }
                                        Column {
                                            Text(
                                                text = "Roll: ${rec.bleDeviceId}",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = textPrimary
                                            )
                                            Text(
                                                text = "${rec.verificationMethod} • Score: ${(rec.affinityScore * 100).toInt()}%",
                                                fontSize = 11.sp,
                                                color = textSecondary
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = badgeBg,
                                        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.4f))
                                    ) {
                                        Text(
                                            text = badgeText,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            color = badgeColor
                                        )
                                    }
                                }

                                if (!rec.remarks.isNullOrBlank()) {
                                    Text(
                                        text = "Note: ${rec.remarks}",
                                        fontSize = 11.sp,
                                        color = textSecondary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(logBg, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        // ==========================================
        // SESSION SETUP & BROADCAST ORCHESTRATION
        // ==========================================
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Course Selector Card
            if (!isSessionActive) {
                var showAddCourseDialog by remember { mutableStateOf(false) }
                if (showAddCourseDialog) {
                    AddCourseDialog(
                        onDismiss = { showAddCourseDialog = false },
                        onConfirm = { code, name ->
                            onCreateCourse(code, name)
                            showAddCourseDialog = false
                        }
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = BorderStroke(1.dp, cardBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("SELECT COURSE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textSecondary)
                            TextButton(
                                onClick = { showAddCourseDialog = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("+ New Course", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = primaryBlue)
                            }
                        }
                        if (availableCourses.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(availableCourses) { course ->
                                    val isSelected = selectedCourse?.id == course.id
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { onSelectCourse(course) },
                                        label = { Text(course.courseCode, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                                    )
                                }
                            }
                        } else {
                            Text(
                                "No courses created yet. Tap '+ New Course' to add one.",
                                fontSize = 12.sp,
                                color = textMuted
                            )
                        }
                    }
                }
            }

            // Session Broadcast Hero Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, cardBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSessionActive) successGreenBg else logBg,
                        border = BorderStroke(1.dp, if (isSessionActive) successGreen else cardBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isSessionActive) successGreen else textMuted)
                            )
                            Text(
                                text = if (isSessionActive) "BROADCASTING ACTIVE" else "READY TO START",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSessionActive) successGreen else textMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = if (isSessionActive) "${remainingSeconds}s" else "60s",
                        fontSize = 52.sp,
                        fontWeight = FontWeight.Black,
                        color = if (isSessionActive) primaryBlue else textMuted
                    )
                    Text(
                        text = if (isSessionActive) "Attendance window running" else "Standard session duration",
                        fontSize = 13.sp,
                        color = textSecondary
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = cardBorder)
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("SESSION ID", fontSize = 11.sp, color = textSecondary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = sessionId.ifEmpty { "--" },
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = textPrimary
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("IN-ROOM NONCE", fontSize = 11.sp, color = textSecondary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = sessionSalt.ifEmpty { "--" },
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = textPrimary
                            )
                        }
                    }
                }
            }

            // Live Payload Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, cardBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("BLE ADVERTISER PAYLOAD", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(logBg, RoundedCornerShape(8.dp))
                            .border(1.dp, cardBorder, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = broadcastPayload.ifEmpty { "Idle (no token active)" },
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = if (isSessionActive) textPrimary else textMuted
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Tx Power: HIGH (+4 dBm) • BLE Advert Interval: 100ms", fontSize = 11.sp, color = textSecondary)
                }
            }

            // Cloud sync progress banner
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
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = primaryBlue)
                        }
                        Text(
                            text = cloudStatusMessage ?: "Auditing attendance...",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = primaryBlue
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { if (isSessionActive) onStopSession() else onStartSession() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSessionActive) alertRed else primaryBlue
                )
            ) {
                Text(
                    text = if (isSessionActive) "End Session & Run Cloud Audit" else "Start 60s Session",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StudentSection(
    studentPhase: StudentPhase,
    detectedSessionId: String,
    detectedSessionSalt: String,
    deskNeighbours: List<PeerData>,
    studentDiscoveryRemainingSeconds: Int = 0,
    studentStatusMessage: String? = null,
    onStartScanning: () -> Unit,
    onStopScanning: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Hero Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = when (studentPhase) {
                    StudentPhase.VERIFIED_PRESENT, StudentPhase.DISCOVERY_COMPLETE -> successGreenBg
                    StudentPhase.PROBING_NEIGHBORS -> primaryBlueBg
                    StudentPhase.LISTENING_FOR_TEACHER -> warningOrangeBg
                    StudentPhase.STANDBY -> cardBg
                }
            ),
            border = BorderStroke(
                1.dp,
                when (studentPhase) {
                    StudentPhase.VERIFIED_PRESENT, StudentPhase.DISCOVERY_COMPLETE -> successGreen
                    StudentPhase.PROBING_NEIGHBORS -> primaryBlue
                    StudentPhase.LISTENING_FOR_TEACHER -> warningOrange
                    StudentPhase.STANDBY -> cardBorder
                }
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.85f),
                    border = BorderStroke(
                        1.dp,
                        when (studentPhase) {
                            StudentPhase.VERIFIED_PRESENT, StudentPhase.DISCOVERY_COMPLETE -> successGreen
                            StudentPhase.PROBING_NEIGHBORS -> primaryBlue
                            StudentPhase.LISTENING_FOR_TEACHER -> warningOrange
                            StudentPhase.STANDBY -> cardBorder
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when (studentPhase) {
                                        StudentPhase.VERIFIED_PRESENT, StudentPhase.DISCOVERY_COMPLETE -> successGreen
                                        StudentPhase.PROBING_NEIGHBORS -> primaryBlue
                                        StudentPhase.LISTENING_FOR_TEACHER -> warningOrange
                                        StudentPhase.STANDBY -> textMuted
                                    }
                                )
                        )
                        Text(
                            text = when (studentPhase) {
                                StudentPhase.VERIFIED_PRESENT -> "ATTENDANCE CONFIRMED"
                                StudentPhase.DISCOVERY_COMPLETE -> "PEER PROXIMITY RECORDED"
                                StudentPhase.PROBING_NEIGHBORS -> "DISCOVERING PEERS (${studentDiscoveryRemainingSeconds}s)"
                                StudentPhase.LISTENING_FOR_TEACHER -> "LISTENING FOR TEACHER..."
                                StudentPhase.STANDBY -> "STANDBY"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (studentPhase) {
                                StudentPhase.VERIFIED_PRESENT, StudentPhase.DISCOVERY_COMPLETE -> successGreen
                                StudentPhase.PROBING_NEIGHBORS -> primaryBlue
                                StudentPhase.LISTENING_FOR_TEACHER -> warningOrange
                                StudentPhase.STANDBY -> textMuted
                            }
                        )
                    }
                }

                when (studentPhase) {
                    StudentPhase.VERIFIED_PRESENT, StudentPhase.DISCOVERY_COMPLETE -> {
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(successGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("✓", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Attendance Verified!",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = successGreen
                        )
                        Text(
                            "Classroom peer witnessing recorded. Your presence is verified for Session $detectedSessionId.",
                            fontSize = 12.sp,
                            color = textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                    StudentPhase.PROBING_NEIGHBORS -> {
                        Text(
                            text = "${studentDiscoveryRemainingSeconds}s",
                            fontSize = 46.sp,
                            fontWeight = FontWeight.Black,
                            color = primaryBlue
                        )
                        Text(
                            "Broadcasting ID & probing nearby classmate devices",
                            fontSize = 13.sp,
                            color = textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                    StudentPhase.LISTENING_FOR_TEACHER -> {
                        Text(
                            "Scanning Classroom Beacon...",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = warningOrange
                        )
                        Text(
                            "Keep Bluetooth on and stay inside the classroom.",
                            fontSize = 12.sp,
                            color = textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                    StudentPhase.STANDBY -> {
                        Text(
                            "Ready to Record",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = textPrimary
                        )
                        Text(
                            "Tap 'Listen for Teacher' when attendance begins.",
                            fontSize = 12.sp,
                            color = textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Server rejection notice (e.g. remote check-in blocked by the beacon salt guard)
                if (studentStatusMessage != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = alertRedBg,
                        border = BorderStroke(1.dp, alertRed)
                    ) {
                        Text(
                            text = studentStatusMessage!!,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = alertRed,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                if (detectedSessionId.isNotEmpty() && detectedSessionId != "None") {
                    HorizontalDivider(color = cardBorder)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("DETECTED SESSION", fontSize = 10.sp, color = textSecondary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(detectedSessionId, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("ROOM NONCE", fontSize = 10.sp, color = textSecondary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(detectedSessionSalt, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }

        // Desk Neighbors Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DESK NEIGHBORS (RSSI >= -90 dBm)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = textSecondary
            )
            if (deskNeighbours.isNotEmpty()) {
                Text(
                    text = "${deskNeighbours.size} detected",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = successGreen
                )
            }
        }

        if (deskNeighbours.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(cardBg, RoundedCornerShape(12.dp))
                    .border(1.dp, cardBorder, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (studentPhase == StudentPhase.LISTENING_FOR_TEACHER)
                            "Waiting for teacher beacon..."
                        else if (studentPhase == StudentPhase.PROBING_NEIGHBORS)
                            "Scanning for nearby classmates..."
                        else
                            "No neighbors detected yet",
                        color = textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Classmate devices will appear here automatically",
                        color = textMuted,
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
                items(deskNeighbours) { peer ->
                    val isStrong = peer.medianRssi >= -75
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = BorderStroke(1.dp, cardBorder),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(primaryBlueBg)
                                        .border(1.dp, primaryBlue.copy(alpha = 0.4f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = peer.peerId.takeLast(2),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = primaryBlue
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Roll: ${peer.peerId}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = textPrimary
                                    )
                                    Text(
                                        text = "${peer.count} packets captured",
                                        fontSize = 11.sp,
                                        color = textSecondary
                                    )
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isStrong) successGreenBg else warningOrangeBg,
                                border = BorderStroke(1.dp, if (isStrong) successGreen else warningOrange)
                            ) {
                                Text(
                                    text = "${peer.medianRssi} dBm",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = if (isStrong) successGreen else warningOrange
                                )
                            }
                        }
                    }
                }
            }
        }

        // Student Action Button
        Button(
            onClick = {
                if (studentPhase != StudentPhase.STANDBY) onStopScanning() else onStartScanning()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (studentPhase != StudentPhase.STANDBY) alertRed else primaryBlue
            )
        ) {
            Text(
                text = if (studentPhase != StudentPhase.STANDBY) "Stop Listening" else "Listen for Teacher",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun AuthServerDialog(
    isLoggedIn: Boolean,
    userName: String,
    userRole: String,
    rollNumber: String,
    serverUrl: String,
    isAuthLoading: Boolean,
    authErrorText: String?,
    onDismiss: () -> Unit,
    onLogin: (String, String) -> Unit,
    onRegister: (RegisterRequest) -> Unit,
    onLogout: () -> Unit,
    onUpdateServerUrl: (String) -> Unit,
    onUpdateRollNumber: (String) -> Unit
) {
    var isRegisterMode by remember { mutableStateOf(false) }
    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var roleInput by remember { mutableStateOf("STUDENT") }
    var rollNumberInput by remember { mutableStateOf(rollNumber) }
    var departmentInput by remember { mutableStateOf("CSE") }
    var facultyCodeInput by remember { mutableStateOf("") }
    var serverUrlInput by remember { mutableStateOf(serverUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isLoggedIn) "Account & Settings" else if (isRegisterMode) "Register New Account" else "Sign In & Settings",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Local Roll Number Config (Critical for Offline Mesh)
                Text("STUDENT IDENTITY (BLE ROLL NUMBER)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = rollNumberInput,
                        onValueChange = { rollNumberInput = it },
                        label = { Text("Roll Number") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onUpdateRollNumber(rollNumberInput) },
                        enabled = rollNumberInput.isNotBlank() && rollNumberInput.trim().uppercase() != rollNumber
                    ) {
                        Text("Save")
                    }
                }

                HorizontalDivider(color = cardBorder)

                if (isLoggedIn) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = primaryBlueBg)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("User: $userName", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = primaryBlue)
                            Text("Role: $userRole", fontSize = 12.sp, color = textSecondary)
                            Text("Roll: $rollNumber", fontSize = 12.sp, color = textSecondary)
                        }
                    }
                } else {
                    // Auth Mode Toggle: Sign In vs Register
                    TabRow(
                        selectedTabIndex = if (isRegisterMode) 1 else 0,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                        containerColor = logBg,
                        divider = {}
                    ) {
                        Tab(
                            selected = !isRegisterMode,
                            onClick = { isRegisterMode = false },
                            text = { Text("Sign In", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
                        )
                        Tab(
                            selected = isRegisterMode,
                            onClick = { isRegisterMode = true },
                            text = { Text("Register", fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
                        )
                    }

                    if (isRegisterMode) {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { nameInput = it },
                            label = { Text("Full Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = { emailInput = it },
                            label = { Text("Email") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("Password (min 6 chars)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Role Selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = roleInput == "STUDENT",
                                onClick = { roleInput = "STUDENT" },
                                label = { Text("Student") }
                            )
                            FilterChip(
                                selected = roleInput == "TEACHER",
                                onClick = { roleInput = "TEACHER" },
                                label = { Text("Teacher") }
                            )
                        }

                        OutlinedTextField(
                            value = departmentInput,
                            onValueChange = { departmentInput = it },
                            label = { Text("Department (e.g. CSE)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (roleInput == "TEACHER") {
                            OutlinedTextField(
                                value = facultyCodeInput,
                                onValueChange = { facultyCodeInput = it },
                                label = { Text("Faculty Signup Passcode") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = { emailInput = it },
                            label = { Text("Email") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (authErrorText != null) {
                        Text(authErrorText, color = alertRed, fontSize = 12.sp)
                    }
                }

                HorizontalDivider(color = cardBorder)

                Text("BACKEND SERVER URL", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textSecondary)
                OutlinedTextField(
                    value = serverUrlInput,
                    onValueChange = { serverUrlInput = it },
                    label = { Text("Server Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isLoggedIn) {
                    Button(
                        onClick = {
                            onUpdateServerUrl(serverUrlInput)
                            onLogout()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = alertRed)
                    ) {
                        Text("Log Out")
                    }
                } else if (isRegisterMode) {
                    Button(
                        onClick = {
                            onUpdateServerUrl(serverUrlInput)
                            onRegister(
                                RegisterRequest(
                                    name = nameInput,
                                    email = emailInput,
                                    password = passwordInput,
                                    role = roleInput,
                                    rollNumber = rollNumberInput,
                                    department = departmentInput,
                                    facultyCode = if (roleInput == "TEACHER") facultyCodeInput else null
                                )
                            )
                        },
                        enabled = !isAuthLoading && nameInput.isNotBlank() && emailInput.isNotBlank() && passwordInput.length >= 6
                    ) {
                        if (isAuthLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                        } else {
                            Text("Register")
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            onUpdateServerUrl(serverUrlInput)
                            onLogin(emailInput, passwordInput)
                        },
                        enabled = !isAuthLoading && emailInput.isNotBlank() && passwordInput.isNotBlank()
                    ) {
                        if (isAuthLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White)
                        } else {
                            Text("Sign In")
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onUpdateServerUrl(serverUrlInput)
                onDismiss()
            }) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun AddCourseDialog(
    onDismiss: () -> Unit,
    onConfirm: (code: String, name: String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Course", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Course Code (e.g. CS401)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Course Name (e.g. Distributed Systems)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(code.trim().uppercase(), name.trim()) },
                enabled = code.isNotBlank() && name.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

