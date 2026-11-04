package com.college.feature.attendance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
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
import com.college.core.model.Role
import com.college.core.model.User
import com.college.core.ui.theme.AppTheme
import com.college.feature.attendance.network.AttendanceApiClient
import com.college.feature.attendance.network.RegisterRequest
import com.college.feature.attendance.ui.theme.*
import com.college.feature.attendance.viewmodel.AttendanceViewModel
import com.college.feature.attendance.viewmodel.StudentPhase
import com.college.feature.attendance.viewmodel.TeacherViewMode

@Immutable
data class AttendanceScreenCallbacks(
    val onStartTeacherSession: () -> Unit,
    val onStopTeacherSession: () -> Unit,
    val onShareAttendance: () -> Unit,
    val onResetSession: () -> Unit,
    val onStartStudentScanning: () -> Unit,
    val onStopStudentScanning: () -> Unit,
    val onUpdateRollNumber: (String) -> Unit,
    val onRequestPermissions: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceScreen(
    user: User,
    viewModel: AttendanceViewModel,
    callbacks: AttendanceScreenCallbacks,
    onBack: () -> Unit
) {
    val isTeacherPrivileged = user.canTakeAttendance()

    // Student attendance portal
    if (user.hasRole(Role.STUDENT)) {
        BackHandler {
            callbacks.onStopStudentScanning()
            onBack()
        }
        StudentSection(
            user = user,
            studentPhase = viewModel.studentPhase,
            detectedSessionId = viewModel.detectedSessionId,
            sessionSalt = viewModel.detectedSessionSalt,
            studentId = viewModel.studentShortId,
            studentStatusMessage = viewModel.studentStatusMessage,
            lastFocusReport = viewModel.lastFocusReport,
            capturedFacePayload = viewModel.capturedFaceBase64,
            auditedRecord = viewModel.auditedRecord,
            onRefreshAudit = { viewModel.refreshAuditStatus() },
            isRefreshingAudit = viewModel.isRefreshingAudit,
            onChallengeResult = { hit, total -> viewModel.recordChallengeResult(hit, total) },
            onBehavioralReport = { report -> viewModel.recordBehavioralReport(report) },
            onFaceCaptured = { b64, bytes -> viewModel.recordFaceCapture(b64, bytes) },
            onStartScanning = callbacks.onStartStudentScanning,
            onStopScanning = callbacks.onStopStudentScanning,
            onNext = { viewModel.proceedStudentToNext() },
            onDone = {
                callbacks.onStopStudentScanning()
                onBack()
            }
        )
        return
    }

    if (viewModel.showAuthDialog) {
        AuthServerDialog(
            isLoggedIn = viewModel.isLoggedIn,
            userName = viewModel.currentUserName,
            userRole = viewModel.currentUserRole,
            rollNumber = viewModel.studentId,
            serverUrl = AttendanceApiClient.baseUrl,
            isAuthLoading = viewModel.isAuthLoading,
            authErrorText = viewModel.authErrorText,
            onDismiss = { viewModel.showAuthDialog = false },
            onLogin = { email, pass -> viewModel.login(email, pass) },
            onRegister = { viewModel.register(it) },
            onLogout = { viewModel.logout() },
            onUpdateServerUrl = { viewModel.setServerUrl(it) },
            onUpdateRollNumber = callbacks.onUpdateRollNumber
        )
    }

    // Hardware/gesture back press resets student session to homepage standby if in progress
    BackHandler(enabled = user.hasRole(Role.STUDENT) && viewModel.studentPhase != StudentPhase.STANDBY) {
        viewModel.resetStudentSession()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (viewModel.selectedSessionForDetails != null) {
                            viewModel.selectedSessionForDetails = null
                        } else if (user.canTakeAttendance() && viewModel.teacherViewMode != TeacherViewMode.MENU && !viewModel.isSessionActive && viewModel.finalizedRecords.isEmpty()) {
                            viewModel.teacherViewMode = TeacherViewMode.MENU
                        } else if (user.hasRole(Role.STUDENT) && viewModel.studentPhase != StudentPhase.STANDBY) {
                            viewModel.resetStudentSession()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = AppTheme.colors.textPrimary
                        )
                    }
                },
                title = {
                    val screenTitle = when {
                        viewModel.selectedSessionForDetails != null -> "Session Details"
                        viewModel.finalizedRecords.isNotEmpty() -> "Audit Results"
                        user.canTakeAttendance() -> {
                            when (viewModel.teacherViewMode) {
                                TeacherViewMode.TAKE_ATTENDANCE -> "Take Attendance"
                                TeacherViewMode.OLDER_SESSIONS -> "Older Sessions"
                                TeacherViewMode.MENU -> "Attendance"
                            }
                        }
                        else -> "Attendance"
                    }
                    Text(
                        text = screenTitle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = AppTheme.colors.textPrimary
                    )
                },
                actions = {
                    val displayDeviceId = viewModel.studentId.ifEmpty { user.rollNumber ?: user.id }
                    Surface(
                        onClick = { viewModel.showAuthDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        color = AppTheme.colors.surface,
                        border = BorderStroke(1.dp, AppTheme.colors.surfaceBorder)
                    ) {
                        Text(
                            text = "ID: $displayDeviceId",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = AppTheme.colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppTheme.colors.background,
                    titleContentColor = AppTheme.colors.textPrimary
                )
            )
        },
        containerColor = AppTheme.colors.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            when {
                isTeacherPrivileged -> TeacherSection(
                    isSessionActive = viewModel.isSessionActive,
                    remainingSeconds = viewModel.remainingSeconds,
                    finalizedRecords = viewModel.finalizedRecords,
                    isCloudSyncing = viewModel.isCloudSyncing,
                    cloudStatusMessage = viewModel.cloudStatusMessage,
                    onSelectCourse = { viewModel.selectedCourse = it },
                    onStartSession = callbacks.onStartTeacherSession,
                    onStopSession = callbacks.onStopTeacherSession,
                    onResetSession = callbacks.onResetSession,
                    onVerifyStudent = { rec -> viewModel.markStudentVerified(rec.bleDeviceId) },
                    teacherViewMode = viewModel.teacherViewMode,
                    onSetTeacherViewMode = { viewModel.teacherViewMode = it },
                    pastSessions = viewModel.pastSessions,
                    isLoadingHistory = viewModel.isLoadingHistory,
                    onLoadHistory = { viewModel.loadSessionHistory() },
                    selectedSessionForDetails = viewModel.selectedSessionForDetails,
                    onSelectSessionForDetails = { viewModel.selectedSessionForDetails = it }
                )

                user.hasRole(Role.STUDENT) -> StudentSection(
                    user = user,
                    studentPhase = viewModel.studentPhase,
                    detectedSessionId = viewModel.detectedSessionId,
                    sessionSalt = viewModel.detectedSessionSalt,
                    studentId = viewModel.studentShortId,
                    studentStatusMessage = viewModel.studentStatusMessage,
                    lastFocusReport = viewModel.lastFocusReport,
                    capturedFacePayload = viewModel.capturedFaceBase64,
                    auditedRecord = viewModel.auditedRecord,
                    onRefreshAudit = { viewModel.refreshAuditStatus() },
                    isRefreshingAudit = viewModel.isRefreshingAudit,
                    onChallengeResult = { hit, total -> viewModel.recordChallengeResult(hit, total) },
                    onBehavioralReport = { report -> viewModel.recordBehavioralReport(report) },
                    onFaceCaptured = { b64, bytes -> viewModel.recordFaceCapture(b64, bytes) },
                    onStartScanning = callbacks.onStartStudentScanning,
                    onStopScanning = callbacks.onStopStudentScanning,
                    onNext = { viewModel.proceedStudentToNext() },
                    onDone = onBack
                )

                else -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AppTheme.colors.surface, RoundedCornerShape(16.dp))
                        .border(1.dp, AppTheme.colors.surfaceBorder, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Attendance is available for students and teachers.",
                        color = AppTheme.colors.textMuted,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}


// Authentication and backend connection configuration dialog
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
        containerColor = cardBg,
        titleContentColor = textPrimary,
        textContentColor = textSecondary,
        title = {
            Text(
                text = if (isLoggedIn) "Account & Settings" else if (isRegisterMode) "Register" else "Sign In",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("STUDENT ROLL NUMBER", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textSecondary)
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
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryBlue,
                            unfocusedBorderColor = cardBorder,
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onUpdateRollNumber(rollNumberInput) },
                        enabled = rollNumberInput.isNotBlank() && rollNumberInput.trim().uppercase() != rollNumber,
                        colors = ButtonDefaults.buttonColors(containerColor = primaryBlue)
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
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryBlue,
                                unfocusedBorderColor = cardBorder,
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = { emailInput = it },
                            label = { Text("Email") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryBlue,
                                unfocusedBorderColor = cardBorder,
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("Password (min 6 chars)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryBlue,
                                unfocusedBorderColor = cardBorder,
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = roleInput == "STUDENT",
                                onClick = { roleInput = "STUDENT" },
                                label = { Text("Student") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = primaryBlueBg,
                                    selectedLabelColor = primaryBlue
                                )
                            )
                            FilterChip(
                                selected = roleInput == "TEACHER",
                                onClick = { roleInput = "TEACHER" },
                                label = { Text("Teacher") },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = primaryBlueBg,
                                    selectedLabelColor = primaryBlue
                                )
                            )
                        }

                        OutlinedTextField(
                            value = departmentInput,
                            onValueChange = { departmentInput = it },
                            label = { Text("Department (e.g. CSE)") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryBlue,
                                unfocusedBorderColor = cardBorder,
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (roleInput == "TEACHER") {
                            OutlinedTextField(
                                value = facultyCodeInput,
                                onValueChange = { facultyCodeInput = it },
                                label = { Text("Faculty Signup Passcode") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = primaryBlue,
                                    unfocusedBorderColor = cardBorder,
                                    focusedTextColor = textPrimary,
                                    unfocusedTextColor = textPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = emailInput,
                            onValueChange = { emailInput = it },
                            label = { Text("Email") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryBlue,
                                unfocusedBorderColor = cardBorder,
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryBlue,
                                unfocusedBorderColor = cardBorder,
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary
                            ),
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
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = primaryBlue,
                        unfocusedBorderColor = cardBorder,
                        focusedTextColor = textPrimary,
                        unfocusedTextColor = textPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = { serverUrlInput = "https://ble-attendance-api-dbf1.onrender.com/" },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Cloud Render", fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = { serverUrlInput = "http://10.115.186.134:53317/" },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("Local LAN (10.115.186.134:53317)", fontSize = 10.sp)
                    }
                }
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
                        enabled = !isAuthLoading && nameInput.isNotBlank() && emailInput.isNotBlank() && passwordInput.length >= 6,
                        colors = ButtonDefaults.buttonColors(containerColor = primaryBlue)
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
                        enabled = !isAuthLoading && emailInput.isNotBlank() && passwordInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = primaryBlue)
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
                Text("Close", color = textSecondary)
            }
        }
    )
}
