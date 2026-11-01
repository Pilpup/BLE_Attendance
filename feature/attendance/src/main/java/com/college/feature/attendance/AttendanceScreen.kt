package com.college.feature.attendance


import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.ui.graphics.graphicsLayer
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Psychology
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.college.core.model.Role
import com.college.core.model.User
import com.college.feature.attendance.network.AttendanceApiClient
import com.college.feature.attendance.network.AttendanceRecordItem
import com.college.feature.attendance.network.AttendanceSessionResponse
import com.college.feature.attendance.network.CourseResponse
import com.college.feature.attendance.network.RegisterRequest
import com.college.feature.attendance.ui.theme.*
import com.college.feature.attendance.game.OrbPoppingGame
import com.college.feature.attendance.game.GameTelemetryEngine
import com.college.feature.attendance.camera.PassiveFaceCaptureManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.college.feature.attendance.telemetry.CognitiveFocusReport
import com.college.feature.attendance.telemetry.FocusClassification
import com.college.feature.attendance.viewmodel.AttendanceViewModel
import com.college.feature.attendance.viewmodel.PeerData
import com.college.feature.attendance.viewmodel.StudentPhase
import com.college.feature.attendance.viewmodel.TeacherViewMode

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
    val isSessionActive = viewModel.isSessionActive
    val remainingSeconds = viewModel.remainingSeconds
    val finalizedRecords = viewModel.finalizedRecords
    val isCloudSyncing = viewModel.isCloudSyncing
    val cloudStatusMessage = viewModel.cloudStatusMessage
    val onSelectCourse: (CourseResponse) -> Unit = { viewModel.selectedCourse = it }

    val studentId = viewModel.studentId
    val studentPhase = viewModel.studentPhase
    val detectedSessionId = viewModel.detectedSessionId
    val deskNeighbours = viewModel.topDeskNeighbours
    val studentStatusMessage = viewModel.studentStatusMessage

    val isLoggedIn = viewModel.isLoggedIn
    val userName = viewModel.currentUserName
    val userRole = viewModel.currentUserRole
    val isAuthLoading = viewModel.isAuthLoading
    val authErrorText = viewModel.authErrorText
    val serverUrl = AttendanceApiClient.baseUrl
    val showAuthDialog = viewModel.showAuthDialog
    val onDismissAuthDialog: () -> Unit = { viewModel.showAuthDialog = false }
    val onOpenAuthDialog: () -> Unit = { viewModel.showAuthDialog = true }
    val onLogin: (String, String) -> Unit = { email, pass -> viewModel.login(email, pass) }
    val onRegister: (RegisterRequest) -> Unit = { viewModel.register(it) }
    val onLogout: () -> Unit = { viewModel.logout() }
    val onUpdateServerUrl: (String) -> Unit = { viewModel.setServerUrl(it) }

    val isTeacherPrivileged = user.canTakeAttendance()

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
                            tint = textPrimary
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
                        color = textPrimary
                    )
                },
                actions = {
                    val displayDeviceId = studentId.ifEmpty { user.rollNumber ?: user.id }
                    Surface(
                        onClick = onOpenAuthDialog,
                        shape = RoundedCornerShape(10.dp),
                        color = cardBg,
                        border = BorderStroke(1.dp, cardBorder)
                    ) {
                        Text(
                            text = "ID: $displayDeviceId",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = textSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = appBg,
                    titleContentColor = textPrimary
                )
            )
        },
        containerColor = appBg
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            when {
                isTeacherPrivileged -> TeacherSection(
                    isSessionActive = isSessionActive,
                    remainingSeconds = remainingSeconds,
                    finalizedRecords = finalizedRecords,
                    isCloudSyncing = isCloudSyncing,
                    cloudStatusMessage = cloudStatusMessage,
                    onSelectCourse = onSelectCourse,
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
                    studentPhase = studentPhase,
                    detectedSessionId = detectedSessionId,
                    sessionSalt = viewModel.detectedSessionSalt,
                    studentId = viewModel.studentShortId,
                    deskNeighbours = deskNeighbours,
                    studentStatusMessage = studentStatusMessage,
                    lastFocusReport = viewModel.lastFocusReport,
                    capturedFacePayload = viewModel.capturedFaceBase64,
                    faceProxyDetected = viewModel.faceProxyDetected,
                    ultrasonicProximityDetected = viewModel.ultrasonicProximityDetected,
                    coLocatedPeerId = viewModel.coLocatedPeerId,
                    auditedRecord = viewModel.auditedRecord,
                    ultrasonicWasTriggered = viewModel.ultrasonicWasTriggered,
                    onRefreshAudit = { viewModel.refreshAuditStatus() },
                    isRefreshingAudit = viewModel.isRefreshingAudit,
                    onChallengeResult = { hit, total -> viewModel.recordChallengeResult(hit, total) },
                    onBehavioralReport = { report -> viewModel.recordBehavioralReport(report) },
                    onFaceCaptured = { viewModel.capturedFaceBase64 = it },
                    onStartScanning = callbacks.onStartStudentScanning,
                    onStopScanning = callbacks.onStopStudentScanning,
                    onNext = { viewModel.proceedStudentToNext() },
                    onResetStudentSession = { viewModel.resetStudentSession() }
                )

                else -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(cardBg, RoundedCornerShape(16.dp))
                        .border(1.dp, cardBorder, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Attendance is available for students and teachers.",
                        color = textMuted,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun StudentSection(
    studentPhase: StudentPhase,
    detectedSessionId: String,
    sessionSalt: String = "",
    studentId: String = "",
    deskNeighbours: List<PeerData>,
    studentStatusMessage: String? = null,
    lastFocusReport: CognitiveFocusReport? = null,
    capturedFacePayload: String? = null,
    faceProxyDetected: Boolean = false,
    ultrasonicProximityDetected: Boolean = false,
    coLocatedPeerId: String? = null,
    auditedRecord: AttendanceRecordItem? = null,
    ultrasonicWasTriggered: Boolean = false,
    onRefreshAudit: (() -> Unit)? = null,
    isRefreshingAudit: Boolean = false,
    onChallengeResult: (hit: Int, total: Int) -> Unit = { _, _ -> },
    onBehavioralReport: (CognitiveFocusReport) -> Unit = {},
    onFaceCaptured: (String) -> Unit = {},
    onStartScanning: () -> Unit,
    onStopScanning: () -> Unit,
    onNext: () -> Unit = {},
    onResetStudentSession: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val faceCaptureManager = remember { PassiveFaceCaptureManager(context, lifecycleOwner) }
    var liveFaceSharpness by remember { mutableDoubleStateOf(0.0) }
    var lastCapturedFaceBase64 by remember { mutableStateOf<String?>(null) }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.CAMERA] == true && studentPhase == StudentPhase.PROBING_NEIGHBORS) {
            faceCaptureManager.startCapture { sharpness ->
                liveFaceSharpness = sharpness
            }
        }
    }

    DisposableEffect(studentPhase) {
        if (studentPhase == StudentPhase.PROBING_NEIGHBORS) {
            val hasCamera = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            val hasAudio = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            val missing = mutableListOf<String>()
            if (!hasCamera) missing.add(Manifest.permission.CAMERA)
            if (!hasAudio) missing.add(Manifest.permission.RECORD_AUDIO)

            if (missing.isNotEmpty()) {
                permissionsLauncher.launch(missing.toTypedArray())
            } else {
                faceCaptureManager.startCapture { sharpness ->
                    liveFaceSharpness = sharpness
                }
            }
        }
        onDispose {
            faceCaptureManager.release()
            liveFaceSharpness = 0.0
        }
    }

    val isScanning = studentPhase == StudentPhase.LISTENING_FOR_TEACHER ||
            studentPhase == StudentPhase.PROBING_NEIGHBORS
    val isVerified = studentPhase == StudentPhase.VERIFIED_PRESENT
    val isUnverified = studentPhase == StudentPhase.UNVERIFIED_FLAGGED

    val orbTotalTargets = 18
    var targetsHit by remember { mutableIntStateOf(0) }
    var totalTargetsSpawned by remember { mutableIntStateOf(orbTotalTargets) }
    var isGameOver by remember { mutableStateOf(false) }
    var hasProceededToNext by remember { mutableStateOf(false) }
    var localGameReport by remember { mutableStateOf<CognitiveFocusReport?>(null) }

    val activeReport = localGameReport ?: lastFocusReport
    val isDividedAttention = isUnverified && (activeReport?.classification == FocusClassification.DIVIDED_ATTENTION_PROXY ||
            studentStatusMessage?.contains("Divided attention", ignoreCase = true) == true)


    LaunchedEffect(studentPhase) {
        if (studentPhase == StudentPhase.PROBING_NEIGHBORS) {
            targetsHit = 0
            totalTargetsSpawned = orbTotalTargets
            isGameOver = false
            hasProceededToNext = false
            localGameReport = null
            onChallengeResult(0, orbTotalTargets)
        } else if (studentPhase == StudentPhase.STANDBY || studentPhase == StudentPhase.LISTENING_FOR_TEACHER) {
            isGameOver = false
            hasProceededToNext = false
            targetsHit = 0
            totalTargetsSpawned = orbTotalTargets
            localGameReport = null
            lastCapturedFaceBase64 = null
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Status bar displaying connection state and roll identifier
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Student attendance status chip
            if (studentPhase != StudentPhase.PROBING_NEIGHBORS && studentPhase != StudentPhase.WAITING_FOR_AUDIT &&
                studentPhase != StudentPhase.LISTENING_FOR_TEACHER &&
                studentPhase != StudentPhase.VERIFIED_PRESENT && studentPhase != StudentPhase.UNVERIFIED_FLAGGED) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = when {
                        isVerified -> neonMintBg
                        isDividedAttention -> crimsonRedBg
                        isUnverified -> solarAmberBg
                        isScanning -> neonMintSubtle
                        else -> logBg
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            isVerified -> neonMint
                            isDividedAttention -> crimsonRed
                            isUnverified -> solarAmber
                            isScanning -> neonMint
                            else -> cardBorder
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isVerified -> neonMint
                                        isDividedAttention -> crimsonRed
                                        auditedRecord?.status == "ISOLATED_FLAGGED" -> solarAmber
                                        isUnverified -> solarAmber
                                        studentPhase == StudentPhase.WAITING_FOR_AUDIT -> Color(0xFF00E5FF)
                                        isScanning -> neonMint
                                        else -> textMuted
                                    }
                                )
                        )
                        Text(
                            text = when {
                                isVerified -> "MARKED PRESENT ✓"
                                auditedRecord?.status == "ISOLATED_FLAGGED" -> "ISOLATED SEAT"
                                isDividedAttention -> "DIVIDED ATTENTION PROXY"
                                isUnverified -> "UNVERIFIED"
                                studentPhase == StudentPhase.WAITING_FOR_AUDIT -> "AWAITING TEACHER AUDIT..."
                                studentPhase == StudentPhase.LISTENING_FOR_TEACHER -> "LOCKING BEACON..."
                                studentPhase == StudentPhase.DISCOVERY_COMPLETE -> "PROBES COMPLETE"
                                else -> "READY TO SCAN"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                isVerified -> neonMint
                                auditedRecord?.status == "ISOLATED_FLAGGED" -> solarAmber
                                isDividedAttention -> crimsonRed
                                isUnverified -> solarAmber
                                studentPhase == StudentPhase.WAITING_FOR_AUDIT -> Color(0xFF00E5FF)
                                isScanning -> neonMint
                                else -> textMuted
                            }
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }
        }

        // Error banner for check-in rejection or network failures
        if (studentStatusMessage != null && !isGameOver && studentPhase != StudentPhase.PROBING_NEIGHBORS &&
            studentPhase != StudentPhase.WAITING_FOR_AUDIT &&
            studentPhase != StudentPhase.LISTENING_FOR_TEACHER &&
            studentPhase != StudentPhase.VERIFIED_PRESENT &&
            studentPhase != StudentPhase.UNVERIFIED_FLAGGED) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = alertRedBg,
                border = BorderStroke(1.dp, alertRed)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = alertRed,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = studentStatusMessage,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = alertRed
                    )
                }
            }
        }

        // Student interactive viewport hosting game and status screens
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF07120D),
                            Color(0xFF040A07),
                            Color(0xFF0B1410)
                        )
                    )
                )
                .border(1.dp, neonMintGlow, RoundedCornerShape(16.dp))
        ) {
            when {
                studentPhase == StudentPhase.PROBING_NEIGHBORS && !isGameOver -> {
                    OrbPoppingGame(
                        modifier = Modifier.fillMaxSize(),
                        seedKey = "$detectedSessionId:$sessionSalt:$studentId",
                        onProgress = { hits, total ->
                            targetsHit = hits
                            totalTargetsSpawned = total
                            onChallengeResult(hits, total)
                        },
                        onComplete = { taps, expired, totalGoal ->
                            isGameOver = true
                            val faceB64 = faceCaptureManager.stopAndExtract()
                            if (faceB64 != null) {
                                lastCapturedFaceBase64 = faceB64
                                onFaceCaptured(faceB64)
                            }
                            val report = GameTelemetryEngine.analyze(
                                taps = taps,
                                expired = expired,
                                totalTargetsGoal = totalGoal
                            )
                            localGameReport = report
                            targetsHit = report.hits
                            totalTargetsSpawned = report.totalSpawns
                            onBehavioralReport(report)
                            onChallengeResult(report.hits, report.totalSpawns)
                        }
                    )

                    // Live Biometric Camera HUD Badge
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (liveFaceSharpness > 40.0) neonMintGlow else solarAmberBg,
                        border = BorderStroke(1.dp, if (liveFaceSharpness > 40.0) neonMint else solarAmber),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Face,
                                contentDescription = null,
                                tint = if (liveFaceSharpness > 40.0) neonMint else solarAmber,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (liveFaceSharpness > 40.0) "Face Locked (${liveFaceSharpness.toInt()})" else "Scanning Face...",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (liveFaceSharpness > 40.0) neonMint else solarAmber
                            )
                        }
                    }
                }

                isGameOver && !hasProceededToNext -> {
                    // Performance score screen with cheat detection status
                    val isCheaterCaught = activeReport?.classification == FocusClassification.DIVIDED_ATTENTION_PROXY ||
                            activeReport?.classification == FocusClassification.AUTOMATED_SCRIPT

                    val total = totalTargetsSpawned.coerceAtLeast(1)
                    val ratio = targetsHit.toFloat() / total
                    val (ratingText, ratingColor) = when {
                        isCheaterCaught -> "nice try lil bro" to crimsonRed
                        ratio >= 0.80f -> "Excellent" to neonMint
                        ratio >= 0.55f -> "Good" to warningOrange
                        else -> "Git Gud" to crimsonRed
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = ratingText,
                            fontSize = if (ratingText.length > 10) 28.sp else 36.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = ratingColor,
                            textAlign = TextAlign.Center
                        )

                        if (isCheaterCaught) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = crimsonRedBg,
                                border = BorderStroke(1.dp, crimsonRed)
                            ) {
                                Text(
                                    text = if (activeReport?.classification == FocusClassification.DIVIDED_ATTENTION_PROXY) {
                                        "DIVIDED ATTENTION PROXY"
                                    } else {
                                        "AUTOMATED SCRIPT FLAGGED"
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp,
                                    color = crimsonRed,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "$targetsHit / $totalTargetsSpawned",
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = textPrimary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "SCORE",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            color = textMuted
                        )
                    }
                }

                studentPhase == StudentPhase.WAITING_FOR_AUDIT -> {
                    WaitingForAuditScreen()
                }

                studentPhase == StudentPhase.VERIFIED_PRESENT || studentPhase == StudentPhase.UNVERIFIED_FLAGGED -> {
                    AntiProxyDefenseResultsScreen(
                        isVerified = studentPhase == StudentPhase.VERIFIED_PRESENT || auditedRecord?.status == "VERIFIED_PRESENT",
                        deskNeighbours = deskNeighbours,
                        targetsHit = targetsHit,
                        activeReport = activeReport,
                        studentStatusMessage = studentStatusMessage,
                        capturedFaceBase64 = lastCapturedFaceBase64 ?: capturedFacePayload,
                        faceProxyDetected = faceProxyDetected,
                        ultrasonicProximityDetected = ultrasonicProximityDetected,
                        auditedRecord = auditedRecord,
                        ultrasonicWasTriggered = ultrasonicWasTriggered
                    )
                }

                studentPhase == StudentPhase.LISTENING_FOR_TEACHER -> {
                    ListeningForTeacherScreen()
                }

                else -> {
                    // Standby state
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0B2117))
                                .border(1.5.dp, neonMint.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.TouchApp,
                                contentDescription = null,
                                tint = neonMint,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Press 'Check In' to start attendance",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = textMuted,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Student Bottom Action Button
        val isWaitingForAudit = studentPhase == StudentPhase.WAITING_FOR_AUDIT
        val showNextButton = isGameOver && !hasProceededToNext
        val isResultsScreen = studentPhase == StudentPhase.VERIFIED_PRESENT || studentPhase == StudentPhase.UNVERIFIED_FLAGGED

        Button(
            onClick = {
                when {
                    isResultsScreen -> onRefreshAudit?.invoke()
                    showNextButton -> {
                        hasProceededToNext = true
                        onNext()
                    }
                    isScanning -> onStopScanning()
                    else -> {
                        hasProceededToNext = false
                        isGameOver = false
                        onStartScanning()
                    }
                }
            },
            enabled = !isWaitingForAudit && (!isResultsScreen || !isRefreshingAudit),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    isResultsScreen -> Color(0xFF059669)
                    showNextButton -> Color(0xFF059669)
                    isScanning -> alertRed
                    isDividedAttention -> crimsonRed
                    isUnverified -> warningOrange
                    isVerified -> Color(0xFF059669)
                    else -> Color(0xFF059669)
                },
                disabledContainerColor = cardBg,
                disabledContentColor = textMuted
            ),
            border = if (isWaitingForAudit) BorderStroke(1.dp, cardBorder) else null
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                when {
                    isResultsScreen -> {
                        if (isRefreshingAudit) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Refreshing...",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Refresh",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                    showNextButton || isWaitingForAudit -> {
                        Text(
                            text = "Next",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isWaitingForAudit) textMuted else Color.White
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = if (isWaitingForAudit) textMuted else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = when {
                                isScanning -> Icons.Default.Close
                                isDividedAttention -> Icons.Default.Warning
                                else -> Icons.Default.Sensors
                            },
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = when {
                                isScanning -> "Cancel Scan"
                                isDividedAttention -> "Proxy Flagged (Retry)"
                                else -> "Check In"
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// Displays pulsing sensor animation while waiting for instructor
@Composable
private fun ListeningForTeacherScreen() {
    val infiniteTransition = rememberInfiniteTransition(label = "listening_radar_anim")

    // Pulsing radar wave 1
    val waveScale1 by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "listen_wave1_scale"
    )
    val waveAlpha1 by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "listen_wave1_alpha"
    )

    // Pulsing radar wave 2 (staggered)
    val waveScale2 by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, delayMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "listen_wave2_scale"
    )
    val waveAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, delayMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "listen_wave2_alpha"
    )

    // Subtle breathing text alpha
    val textAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "listen_text_alpha"
    )

    // Animated dots cycle (1..3 dots)
    val dotCycle by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "listen_dot_cycle"
    )
    val animatedDots = ".".repeat(dotCycle.toInt().coerceIn(1, 3))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Balanced glowing sensor radar icon
        Box(
            modifier = Modifier.size(110.dp),
            contentAlignment = Alignment.Center
        ) {
            // Wave 1
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = waveScale1
                        scaleY = waveScale1
                        alpha = waveAlpha1
                    }
                    .clip(CircleShape)
                    .background(neonMint.copy(alpha = 0.12f))
                    .border(1.5.dp, neonMint.copy(alpha = waveAlpha1), CircleShape)
            )

            // Wave 2
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = waveScale2
                        scaleY = waveScale2
                        alpha = waveAlpha2
                    }
                    .clip(CircleShape)
                    .background(neonMint.copy(alpha = 0.08f))
                    .border(1.dp, neonMint.copy(alpha = waveAlpha2), CircleShape)
            )

            // Center glowing icon badge
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(cardBg)
                    .border(1.5.dp, neonMint.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Sensors,
                    contentDescription = null,
                    tint = neonMint,
                    modifier = Modifier.size(42.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Text: "Waiting for teacher..." with animated dots and glowing mint accent
        Text(
            text = "Waiting for teacher$animatedDots",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            color = neonMint,
            modifier = Modifier.graphicsLayer { alpha = textAlpha },
            textAlign = TextAlign.Center
        )
    }
}

// Waiting for teacher to finalize attendance session and execute cloud social-mesh graph audit
@Composable
private fun WaitingForAuditScreen() {
    val infiniteTransition = rememberInfiniteTransition(label = "waiting_signal_anim")

    // Pulsing radar wave 1
    val waveScale1 by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave1_scale"
    )
    val waveAlpha1 by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave1_alpha"
    )

    // Pulsing radar wave 2 (staggered)
    val waveScale2 by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, delayMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave2_scale"
    )
    val waveAlpha2 by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, delayMillis = 600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave2_alpha"
    )

    // Subtle breathing text alpha
    val textAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "text_alpha"
    )

    // Animated dots cycle (1..3 dots)
    val dotCycle by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "dot_cycle"
    )
    val animatedDots = ".".repeat(dotCycle.toInt().coerceIn(1, 3))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Balanced glowing sensor radar icon
        Box(
            modifier = Modifier.size(110.dp),
            contentAlignment = Alignment.Center
        ) {
            // Wave 1
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = waveScale1
                        scaleY = waveScale1
                        alpha = waveAlpha1
                    }
                    .clip(CircleShape)
                    .background(Color(0xFF00E5FF).copy(alpha = 0.12f))
                    .border(1.5.dp, Color(0xFF00E5FF).copy(alpha = waveAlpha1), CircleShape)
            )

            // Wave 2
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = waveScale2
                        scaleY = waveScale2
                        alpha = waveAlpha2
                    }
                    .clip(CircleShape)
                    .background(Color(0xFF00E5FF).copy(alpha = 0.08f))
                    .border(1.dp, Color(0xFF00E5FF).copy(alpha = waveAlpha2), CircleShape)
            )

            // Center glowing icon badge
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(cardBg)
                    .border(1.5.dp, Color(0xFF00E5FF).copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Sensors,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(42.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Text: "Checking in..." with animated dots and glowing cyan accent
        Text(
            text = "Checking in$animatedDots",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp,
            color = Color(0xFF00E5FF),
            modifier = Modifier.graphicsLayer { alpha = textAlpha },
            textAlign = TextAlign.Center
        )
    }
}

private enum class DefenseStatus(val label: String) {
    PASSED("Pass"),
    FAILED("Fail"),
    DORMANT("Dormant")
}

// Displays multi-layer anti-proxy verification audit results across radio, reflex, biometric, and acoustic layers
@Composable
private fun AntiProxyDefenseResultsScreen(
    isVerified: Boolean,
    deskNeighbours: List<PeerData>,
    targetsHit: Int,
    activeReport: CognitiveFocusReport?,
    studentStatusMessage: String?,
    capturedFaceBase64: String? = null,
    faceProxyDetected: Boolean = false,
    ultrasonicProximityDetected: Boolean = false,
    auditedRecord: AttendanceRecordItem? = null,
    ultrasonicWasTriggered: Boolean = false
) {
    val isDividedAttention = activeReport?.classification == FocusClassification.DIVIDED_ATTENTION_PROXY ||
            studentStatusMessage?.contains("Divided attention", ignoreCase = true) == true
    val isFaceProxy = faceProxyDetected ||
            studentStatusMessage?.contains("Biometric", ignoreCase = true) == true ||
            studentStatusMessage?.contains("Duplicate face", ignoreCase = true) == true
    val isUltrasonicProxy = ultrasonicProximityDetected ||
            studentStatusMessage?.contains("Ultrasonic", ignoreCase = true) == true ||
            studentStatusMessage?.contains("same desk", ignoreCase = true) == true

    // Decode normalized captured face bitmap for visual verification
    val faceBitmap = remember(capturedFaceBase64) {
        capturedFaceBase64?.let { b64 ->
            try {
                val cleanB64 = if (b64.contains(",")) b64.substringAfter(",") else b64
                val bytes = Base64.decode(cleanB64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    // Layer 1: BLE Mesh
    val blePass = if (auditedRecord != null) {
        auditedRecord.status == "VERIFIED_PRESENT"
    } else {
        isVerified || deskNeighbours.isNotEmpty()
    }

    // Layer 2: Behaviour Analysis
    val reflexPass = !isDividedAttention && targetsHit >= 6

    // Layer 3: Face Matching
    val facePass = !isFaceProxy

    // Layer 4: Ultrasonic Distance
    val ultrasonicStatus = when {
        !ultrasonicWasTriggered -> DefenseStatus.DORMANT
        isUltrasonicProxy -> DefenseStatus.FAILED
        else -> DefenseStatus.PASSED
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val heroColor = if (isVerified) neonMint else crimsonRed
        val heroIcon = if (isVerified) Icons.Default.CheckCircle else Icons.Default.Warning

        // Centered Glowing Status Badge
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(heroColor.copy(alpha = 0.12f))
                .border(2.dp, heroColor.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = heroIcon,
                contentDescription = null,
                tint = heroColor,
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (isVerified) "VERIFIED" else "UNVERIFIED",
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
            color = heroColor,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Security Defense Cards
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SecurityDefenseCard(
                title = "BLE Mesh",
                status = if (blePass) DefenseStatus.PASSED else DefenseStatus.FAILED
            )
            SecurityDefenseCard(
                title = "Behaviour Analysis",
                status = if (reflexPass) DefenseStatus.PASSED else DefenseStatus.FAILED
            )
            SecurityDefenseCard(
                title = "Face Matching",
                status = if (facePass) DefenseStatus.PASSED else DefenseStatus.FAILED,
                photoBitmap = faceBitmap
            )
            SecurityDefenseCard(
                title = "Ultrasonic Distance",
                status = ultrasonicStatus
            )
        }
    }
}

// Sleek modern security layer card with clean tick/cross indicator and optional face photo preview
@Composable
private fun SecurityDefenseCard(
    title: String,
    status: DefenseStatus,
    photoBitmap: androidx.compose.ui.graphics.ImageBitmap? = null
) {
    val tintColor = when (status) {
        DefenseStatus.PASSED -> neonMint
        DefenseStatus.FAILED -> crimsonRed
        DefenseStatus.DORMANT -> textMuted
    }
    val badgeBg = when (status) {
        DefenseStatus.PASSED -> neonMint.copy(alpha = 0.12f)
        DefenseStatus.FAILED -> crimsonRed.copy(alpha = 0.12f)
        DefenseStatus.DORMANT -> Color.White.copy(alpha = 0.04f)
    }
    val badgeBorder = when (status) {
        DefenseStatus.PASSED -> neonMint.copy(alpha = 0.35f)
        DefenseStatus.FAILED -> crimsonRed.copy(alpha = 0.35f)
        DefenseStatus.DORMANT -> cardBorder
    }
    val badgeIcon = when (status) {
        DefenseStatus.PASSED -> Icons.Default.Check
        DefenseStatus.FAILED -> Icons.Default.Close
        DefenseStatus.DORMANT -> null
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardBg,
        border = BorderStroke(1.dp, cardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textPrimary
                )

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = badgeBg,
                    border = BorderStroke(1.dp, badgeBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (badgeIcon != null) {
                            Icon(
                                imageVector = badgeIcon,
                                contentDescription = null,
                                tint = tintColor,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Text(
                            text = status.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = tintColor
                        )
                    }
                }
            }

            if (photoBitmap != null) {
                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = photoBitmap,
                        contentDescription = "Face Capture",
                        modifier = Modifier
                            .size(112.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.5.dp, tintColor.copy(alpha = 0.6f), RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
    }
}

// Maps attendance audit records to display status categories
private fun getRecordAuditStatus(rec: AttendanceRecordItem): String {
    return when {
        rec.status == "FLAGGED_SPOOFER" || rec.status == "SPOOFER_REJECTED" ||
                rec.status.contains("SPOOF") || rec.status.contains("REJECT") -> "Flagged"
        rec.status.contains("ISOLAT") || rec.status == "UNVERIFIED" ||
                rec.verificationMethod == "NONE" || rec.affinityScore < 0.5 -> "Unverified"
        else -> "Verified"
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
private fun TeacherSection(
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
                                    text = "Unverified",
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
                                                text = "Unverified",
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
                                            text = status,
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
                items(pastSessions) { session ->
                    val details = getSessionAcademicDetails(session)
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
                items(sortedRecords) { rec ->
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
                    val shortRoll = rollNo.takeLast(2)
                    val studentName = getDisplayStudentName(rec)

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
