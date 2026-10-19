package com.attendance.ble.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendance.ble.network.AttendanceRecordItem
import com.attendance.ble.ui.theme.*
import com.attendance.ble.viewmodel.PeerData
import com.attendance.ble.viewmodel.StudentPhase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    isTeacherMode: Boolean,
    onToggleMode: (Boolean) -> Unit,

    // Teacher Props
    isSessionActive: Boolean,
    sessionId: String,
    sessionSalt: String,
    remainingSeconds: Int,
    broadcastPayload: String,
    finalizedRecords: List<AttendanceRecordItem> = emptyList(),
    isCloudSyncing: Boolean = false,
    cloudStatusMessage: String? = null,
    onStartTeacherSession: () -> Unit,
    onStopTeacherSession: () -> Unit,

    // Student Props
    studentId: String,
    studentPhase: StudentPhase,
    detectedSessionId: String,
    detectedSessionSalt: String,
    deskNeighbours: List<PeerData>,
    onStartStudentScanning: () -> Unit,
    onStopStudentScanning: () -> Unit,

    // Auth & Server Props
    isLoggedIn: Boolean = false,
    userName: String = "Guest",
    userRole: String = "STUDENT",
    isAuthLoading: Boolean = false,
    authErrorText: String? = null,
    serverUrl: String = "http://10.0.2.2:8000/",
    onLogin: (String, String) -> Unit = { _, _ -> },
    onLogout: () -> Unit = {},
    onUpdateServerUrl: (String) -> Unit = {},

    onRequestPermissions: () -> Unit
) {
    var showConfigDialog by remember { mutableStateOf(false) }

    if (showConfigDialog) {
        AuthServerDialog(
            isLoggedIn = isLoggedIn,
            userName = userName,
            userRole = userRole,
            rollNumber = studentId,
            serverUrl = serverUrl,
            isAuthLoading = isAuthLoading,
            authErrorText = authErrorText,
            onDismiss = { showConfigDialog = false },
            onLogin = onLogin,
            onLogout = onLogout,
            onUpdateServerUrl = onUpdateServerUrl
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (isTeacherMode) "Teacher Console" else "Student Attendance",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = if (isTeacherMode) "BLE Beacon Orchestrator" else "ID: $studentId • Proximity Node",
                            fontSize = 12.sp,
                            color = textSecondary
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showConfigDialog = true }) {
                        Text(if (isLoggedIn) userName else "Server / Login", fontSize = 13.sp)
                    }
                    TextButton(onClick = onRequestPermissions) {
                        Text("Permissions", fontSize = 13.sp)
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Role Switcher Tab
            TabRow(
                selectedTabIndex = if (isTeacherMode) 0 else 1,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)),
                containerColor = cardBg,
                divider = {}
            ) {
                Tab(
                    selected = isTeacherMode,
                    onClick = { onToggleMode(true) },
                    text = { Text("Teacher Mode", fontWeight = FontWeight.SemiBold) }
                )
                Tab(
                    selected = !isTeacherMode,
                    onClick = { onToggleMode(false) },
                    text = { Text("Student Mode", fontWeight = FontWeight.SemiBold) }
                )
            }

            if (isTeacherMode) {
                TeacherSection(
                    isSessionActive = isSessionActive,
                    sessionId = sessionId,
                    sessionSalt = sessionSalt,
                    remainingSeconds = remainingSeconds,
                    broadcastPayload = broadcastPayload,
                    finalizedRecords = finalizedRecords,
                    isCloudSyncing = isCloudSyncing,
                    cloudStatusMessage = cloudStatusMessage,
                    onStartSession = onStartTeacherSession,
                    onStopSession = onStopTeacherSession
                )
            } else {
                StudentSection(
                    studentPhase = studentPhase,
                    detectedSessionId = detectedSessionId,
                    detectedSessionSalt = detectedSessionSalt,
                    deskNeighbours = deskNeighbours,
                    onStartScanning = onStartStudentScanning,
                    onStopScanning = onStopStudentScanning
                )
            }
        }
    }
}

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
    onStartSession: () -> Unit,
    onStopSession: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
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
                    color = if (isSessionActive) successGreenBg else logBg
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
                            text = if (isSessionActive) "BROADCASTING" else "INACTIVE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSessionActive) successGreen else textMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = if (isSessionActive) "${remainingSeconds}s" else "--",
                    fontSize = 54.sp,
                    fontWeight = FontWeight.Black,
                    color = if (isSessionActive) primaryBlue else textMuted
                )
                Text(
                    text = if (isSessionActive) "Attendance window active" else "Ready to start session",
                    fontSize = 13.sp,
                    color = textSecondary
                )

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider(color = cardBorder)
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("SESSION ID", fontSize = 11.sp, color = textSecondary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(sessionId, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("IN-ROOM NONCE", fontSize = 11.sp, color = textSecondary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(sessionSalt, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // Cloud sync status banner
        if (cloudStatusMessage != null || isCloudSyncing) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = primaryBlueBg),
                border = BorderStroke(1.dp, primaryBlue)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isCloudSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = primaryBlue)
                    }
                    Text(
                        text = cloudStatusMessage ?: "Communicating with server...",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = primaryBlue
                    )
                }
            }
        }

        // Audit Records List or Live Payload Card
        if (finalizedRecords.isNotEmpty()) {
            Text(
                text = "AUDIT RESULTS (${finalizedRecords.size} STUDENTS)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = textSecondary
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(finalizedRecords) { rec ->
                    val isVerified = rec.status == "VERIFIED_PRESENT"
                    val isSpoofer = rec.status == "FLAGGED_SPOOFER"
                    val badgeBg = if (isVerified) successGreenBg else if (isSpoofer) alertRedBg else warningOrangeBg
                    val badgeColor = if (isVerified) successGreen else if (isSpoofer) alertRed else warningOrange

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Roll: ${rec.bleDeviceId}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "${rec.verificationMethod} • Score: ${(rec.affinityScore * 100).toInt()}%",
                                    fontSize = 11.sp,
                                    color = textSecondary
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = badgeBg
                            ) {
                                Text(
                                    text = if (isVerified) "VERIFIED" else if (isSpoofer) "SPOOFER" else "ISOLATED",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    color = badgeColor
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("LIVE BLE PAYLOAD", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(appBg, RoundedCornerShape(8.dp))
                            .border(1.dp, cardBorder, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = broadcastPayload,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = if (isSessionActive) textPrimary else textMuted
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Power: HIGH (+4 dBm) • Interval: 100ms", fontSize = 11.sp, color = textSecondary)
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }

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
                text = if (isSessionActive) "End Session" else "Start 60s Session",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StudentSection(
    studentPhase: StudentPhase,
    detectedSessionId: String,
    detectedSessionSalt: String,
    deskNeighbours: List<PeerData>,
    onStartScanning: () -> Unit,
    onStopScanning: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
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
                    color = when (studentPhase) {
                        StudentPhase.VERIFIED_PRESENT -> successGreenBg
                        StudentPhase.DISCOVERY_COMPLETE -> successGreenBg
                        StudentPhase.PROBING_NEIGHBORS -> primaryBlueBg
                        StudentPhase.LISTENING_FOR_TEACHER -> warningOrangeBg
                        StudentPhase.STANDBY -> logBg
                    }
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
                                        StudentPhase.VERIFIED_PRESENT -> successGreen
                                        StudentPhase.DISCOVERY_COMPLETE -> successGreen
                                        StudentPhase.PROBING_NEIGHBORS -> primaryBlue
                                        StudentPhase.LISTENING_FOR_TEACHER -> warningOrange
                                        StudentPhase.STANDBY -> textMuted
                                    }
                                )
                        )
                        Text(
                            text = when (studentPhase) {
                                StudentPhase.VERIFIED_PRESENT -> "ATTENDANCE CONFIRMED"
                                StudentPhase.DISCOVERY_COMPLETE -> "PROXIMITY VERIFIED"
                                StudentPhase.PROBING_NEIGHBORS -> "PROBING NEIGHBORS (2s)"
                                StudentPhase.LISTENING_FOR_TEACHER -> "LISTENING FOR TEACHER..."
                                StudentPhase.STANDBY -> "STANDBY"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = when (studentPhase) {
                                StudentPhase.VERIFIED_PRESENT -> successGreen
                                StudentPhase.DISCOVERY_COMPLETE -> successGreen
                                StudentPhase.PROBING_NEIGHBORS -> primaryBlue
                                StudentPhase.LISTENING_FOR_TEACHER -> warningOrange
                                StudentPhase.STANDBY -> textMuted
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("DETECTED SESSION", fontSize = 11.sp, color = textSecondary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(detectedSessionId, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("DETECTED NONCE", fontSize = 11.sp, color = textSecondary, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(detectedSessionSalt, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // Verified Air Verdict Confirmation Banner
        if (studentPhase == StudentPhase.VERIFIED_PRESENT) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = successGreenBg),
                border = BorderStroke(1.dp, successGreen)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(successGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✓", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text(
                            "AIR VERDICT VERIFIED",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = successGreen
                        )
                        Text(
                            "Attendance confirmed for Session $detectedSessionId",
                            fontSize = 12.sp,
                            color = textSecondary
                        )
                    }
                }
            }
        }

        // Desk Neighbors Section Header
        Text(
            text = "DESK NEIGHBORS (RSSI >= -70 dBm)",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = textSecondary
        )

        if (deskNeighbours.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(cardBg, RoundedCornerShape(12.dp))
                    .border(1.dp, cardBorder, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (studentPhase == StudentPhase.LISTENING_FOR_TEACHER) "Waiting for teacher to start session..." else "No neighbors detected yet",
                    color = textMuted,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(deskNeighbours) { peer ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Peer: ${peer.peerId}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "${peer.count} packets captured",
                                    fontSize = 11.sp,
                                    color = textSecondary
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = successGreenBg
                            ) {
                                Text(
                                    text = "${peer.medianRssi} dBm",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = successGreen
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
    onLogout: () -> Unit,
    onUpdateServerUrl: (String) -> Unit
) {
    var emailInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var serverUrlInput by remember { mutableStateOf(serverUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isLoggedIn) "Account & Server" else "Sign In / Server",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
