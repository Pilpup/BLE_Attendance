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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiTethering
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
import androidx.compose.ui.graphics.drawscope.rotate
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
import com.college.feature.attendance.viewmodel.AttendanceViewModel
import com.college.feature.attendance.viewmodel.PeerData
import com.college.feature.attendance.viewmodel.StudentPhase
import com.college.feature.attendance.viewmodel.TeacherViewMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Callbacks required by the attendance screen from the host activity.
 */
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

/**
 * Redesigned Attendance Interface with Obsidian Dark aesthetic:
 * - Adapts cleanly to Student (Beacon radar & peer mesh discovery)
 *   and Teacher (Course broadcasting & live cloud audit).
 * - Fast, minimal, high-contrast, clutter-free.
 */
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
    val selectedCourse = viewModel.selectedCourse
    val onSelectCourse: (CourseResponse) -> Unit = { viewModel.selectedCourse = it }

    val studentId = viewModel.studentId
    val studentPhase = viewModel.studentPhase
    val detectedSessionId = viewModel.detectedSessionId
    val deskNeighbours = viewModel.topDeskNeighbours
    val studentDiscoveryRemainingSeconds = viewModel.studentDiscoverySecondsRemaining
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

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = {
                        if (viewModel.selectedSessionForDetails != null) {
                            viewModel.selectedSessionForDetails = null
                        } else if (user.canTakeAttendance() && viewModel.teacherViewMode != TeacherViewMode.MENU && !viewModel.isSessionActive && viewModel.finalizedRecords.isEmpty()) {
                            viewModel.teacherViewMode = TeacherViewMode.MENU
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
                    selectedCourse = selectedCourse,
                    onSelectCourse = onSelectCourse,
                    onStartSession = callbacks.onStartTeacherSession,
                    onStopSession = callbacks.onStopTeacherSession,
                    onShareAttendance = callbacks.onShareAttendance,
                    onResetSession = callbacks.onResetSession,
                    onVerifyStudent = { rec -> viewModel.markStudentVerified(rec.bleDeviceId) },
                    teacherViewMode = viewModel.teacherViewMode,
                    onSetTeacherViewMode = { viewModel.teacherViewMode = it },
                    pastSessions = viewModel.pastSessions,
                    isLoadingHistory = viewModel.isLoadingHistory,
                    onLoadHistory = { viewModel.loadSessionHistory() },
                    selectedSessionForDetails = viewModel.selectedSessionForDetails,
                    onSelectSessionForDetails = { viewModel.selectedSessionForDetails = it },
                    onBackFromDetails = { viewModel.selectedSessionForDetails = null }
                )

                user.hasRole(Role.STUDENT) -> StudentSection(
                    studentPhase = studentPhase,
                    detectedSessionId = detectedSessionId,
                    deskNeighbours = deskNeighbours,
                    studentDiscoveryRemainingSeconds = studentDiscoveryRemainingSeconds,
                    studentStatusMessage = studentStatusMessage,
                    onStartScanning = callbacks.onStartStudentScanning,
                    onStopScanning = callbacks.onStopStudentScanning
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

// ─────────────────────────────────────────────────────────────────────────────
// STUDENT SECTION (PRECISION CYBER EMERALD RADAR)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun StudentSection(
    studentPhase: StudentPhase,
    detectedSessionId: String,
    deskNeighbours: List<PeerData>,
    studentDiscoveryRemainingSeconds: Int = 0,
    studentStatusMessage: String? = null,
    onStartScanning: () -> Unit,
    onStopScanning: () -> Unit
) {
    val isScanning = studentPhase == StudentPhase.LISTENING_FOR_TEACHER ||
            studentPhase == StudentPhase.PROBING_NEIGHBORS
    val isVerified = studentPhase == StudentPhase.VERIFIED_PRESENT ||
            studentPhase == StudentPhase.DISCOVERY_COMPLETE

    // 360-degree rotating precision radar sweep beam
    val infiniteTransition = rememberInfiniteTransition(label = "RadarSweep")
    val sweepAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing)
        ),
        label = "SweepAngle"
    )

    // Center pulse animation
    val centerPulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "CenterPulseScale"
    )
    val centerPulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.50f,
        targetValue = 0.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "CenterPulseAlpha"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top Status Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Student-friendly Status Pill
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = when {
                    isVerified -> Color(0x2800F5A0)
                    isScanning -> Color(0x2000F5A0)
                    else -> logBg
                },
                border = BorderStroke(
                    1.dp,
                    when {
                        isVerified -> Color(0xFF00F5A0)
                        isScanning -> Color(0xFF00F5A0)
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
                                    isVerified -> Color(0xFF00F5A0)
                                    isScanning -> Color(0xFF00F5A0)
                                    else -> textMuted
                                }
                            )
                    )
                    Text(
                        text = when (studentPhase) {
                            StudentPhase.VERIFIED_PRESENT, StudentPhase.DISCOVERY_COMPLETE -> "MARKED PRESENT ✓"
                            StudentPhase.PROBING_NEIGHBORS -> "CONFIRMING... (${studentDiscoveryRemainingSeconds}s)"
                            StudentPhase.LISTENING_FOR_TEACHER -> "CHECKING IN..."
                            StudentPhase.STANDBY -> "READY"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isVerified -> Color(0xFF00F5A0)
                            isScanning -> Color(0xFF00F5A0)
                            else -> textMuted
                        }
                    )
                }
            }

            // Clean Class Session Pill (non-technical)
            if (detectedSessionId.isNotEmpty() && detectedSessionId != "None") {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = cardBg,
                    border = BorderStroke(1.dp, cardBorder)
                ) {
                    Text(
                        text = "Session #$detectedSessionId",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // Error Notice (if any)
        if (studentStatusMessage != null) {
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

        // ─────────────────────────────────────────────────────────────────────
        // PRECISION CYBER EMERALD RADAR VIEWPORT (SHIFTED UP)
        // ─────────────────────────────────────────────────────────────────────
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.TopCenter
        ) {
            val density = LocalDensity.current
            val radarSizePx = (minOf(constraints.maxWidth, constraints.maxHeight).toFloat() * 0.94f)
                .coerceAtLeast(100f)
            val radarRadiusPx = radarSizePx / 2f
            val radarDiameterDp = with(density) { radarSizePx.toDp() }

            Box(
                modifier = Modifier
                    .size(radarDiameterDp)
                    .padding(top = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                // 1. Radar Screen Canvas (Pure Obsidian-Emerald Sonar Instrument)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val centerOffset = Offset(radarRadiusPx, radarRadiusPx)

                    // Deep Obsidian-Carbon Radial Core
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF091712),
                                Color(0xFF040A07),
                                Color(0xFF020403)
                            ),
                            center = centerOffset,
                            radius = radarRadiusPx
                        ),
                        radius = radarRadiusPx,
                        center = centerOffset
                    )

                    // Outer bezel ring with emerald accent
                    drawCircle(
                        color = if (isVerified) Color(0x9900F5A0) else Color(0x5500F5A0),
                        radius = radarRadiusPx,
                        center = centerOffset,
                        style = Stroke(width = 1.8.dp.toPx())
                    )

                    // 12 Azimuth Compass / Navigation Tick Marks around the rim
                    for (angle in 0 until 360 step 30) {
                        val isMajor = angle % 90 == 0
                        val tickLength = if (isMajor) 8.dp.toPx() else 4.dp.toPx()
                        val rad = Math.toRadians(angle.toDouble())
                        val cosVal = cos(rad).toFloat()
                        val sinVal = sin(rad).toFloat()
                        val startX = radarRadiusPx + (radarRadiusPx - tickLength) * cosVal
                        val startY = radarRadiusPx + (radarRadiusPx - tickLength) * sinVal
                        val endX = radarRadiusPx + radarRadiusPx * cosVal
                        val endY = radarRadiusPx + radarRadiusPx * sinVal
                        drawLine(
                            color = if (isMajor) Color(0x8800F5A0) else Color(0x3000F5A0),
                            start = Offset(startX, startY),
                            end = Offset(endX, endY),
                            strokeWidth = if (isMajor) 1.5.dp.toPx() else 1.dp.toPx()
                        )
                    }

                    // Concentric Range Rings (25%, 50%, 75%)
                    val ringCount = 4
                    for (i in 1 until ringCount) {
                        val ringRadius = radarRadiusPx * (i.toFloat() / ringCount)
                        drawCircle(
                            color = Color(0x1800F5A0),
                            radius = ringRadius,
                            center = centerOffset,
                            style = Stroke(width = 1.dp.toPx())
                        )
                    }

                    // Precision Crosshairs
                    val crosshairColor = Color(0x1800F5A0)
                    drawLine(
                        color = crosshairColor,
                        start = Offset(radarRadiusPx * 0.12f, radarRadiusPx),
                        end = Offset(radarRadiusPx * 1.88f, radarRadiusPx),
                        strokeWidth = 1.dp.toPx()
                    )
                    drawLine(
                        color = crosshairColor,
                        start = Offset(radarRadiusPx, radarRadiusPx * 0.12f),
                        end = Offset(radarRadiusPx, radarRadiusPx * 1.88f),
                        strokeWidth = 1.dp.toPx()
                    )

                    // Laser Phosphor Sweeping Beam (Ultra-tight, sharp laser sweep line)
                    if (isScanning) {
                        rotate(degrees = sweepAngle, pivot = centerOffset) {
                            val sweepGradient = Brush.sweepGradient(
                                0.0f to Color.Transparent,
                                0.80f to Color.Transparent,
                                0.88f to Color(0x0A00F5A0),
                                0.94f to Color(0x2200F5A0),
                                0.98f to Color(0x5500F5A0),
                                1.0f to Color(0xDD00F5A0),
                                center = centerOffset
                            )
                            drawCircle(
                                brush = sweepGradient,
                                radius = radarRadiusPx,
                                center = centerOffset
                            )
                            // Razor-sharp leading laser line
                            drawLine(
                                color = Color(0xFF00F5A0),
                                start = centerOffset,
                                end = Offset(radarRadiusPx * 2f, radarRadiusPx),
                                strokeWidth = 2.dp.toPx()
                            )
                        }
                    }

                    // Verified Emerald Pulse Bloom
                    if (isVerified) {
                        val verifiedBrush = Brush.radialGradient(
                            colors = listOf(
                                Color(0x4000F5A0),
                                Color(0x1000F5A0),
                                Color.Transparent
                            ),
                            center = centerOffset,
                            radius = radarRadiusPx
                        )
                        drawCircle(
                            brush = verifiedBrush,
                            radius = radarRadiusPx,
                            center = centerOffset
                        )
                    }
                }

                // 2. Center Node: YOU (Minimalist Tactical Target)
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(44.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isScanning) {
                            Box(
                                modifier = Modifier
                                    .size((44 * centerPulseScale).dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00F5A0).copy(alpha = centerPulseAlpha))
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF081811))
                                .border(
                                    1.5.dp,
                                    Color(0xFF00F5A0),
                                    CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isVerified) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Verified",
                                    tint = Color(0xFF00F5A0),
                                    modifier = Modifier.size(20.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF00F5A0))
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "YOU",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = Color(0xFFF0FDF4)
                    )
                }

                // 3. Classmates Plotted on Radar (Floating Tokens)
                deskNeighbours.forEachIndexed { index, peer ->
                    val clampedRssi = peer.medianRssi.coerceIn(-95, -45)
                    val distFraction = 0.30f + 0.52f * ((-45f - clampedRssi) / 50f)
                    val distPx = radarRadiusPx * distFraction

                    val count = deskNeighbours.size
                    val baseAngle = if (count == 1) {
                        (abs(peer.peerId.hashCode()) % 360).toFloat()
                    } else {
                        (index * (360f / count) + ((abs(peer.peerId.hashCode()) % 24) - 12)).toFloat()
                    }
                    val angleRad = Math.toRadians(baseAngle.toDouble())
                    val offsetX = (distPx * cos(angleRad)).toInt()
                    val offsetY = (distPx * sin(angleRad)).toInt()

                    val isStrong = peer.medianRssi >= -75
                    val nodeColor = if (isStrong) Color(0xFF00F5A0) else Color(0xFF34D399)

                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset { IntOffset(offsetX, offsetY) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF081811))
                                .border(1.5.dp, nodeColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = peer.peerId.takeLast(2),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = nodeColor
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xF2030A07),
                            border = BorderStroke(0.5.dp, nodeColor.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = "Roll ${peer.peerId.takeLast(3)}",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = nodeColor,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }

        // Bottom Classmate Counter / Summary
        if (isVerified || deskNeighbours.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.People,
                    contentDescription = null,
                    tint = Color(0xFF00F5A0),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isVerified) "Attendance recorded successfully" else "${deskNeighbours.size} classmate${if (deskNeighbours.size > 1) "s" else ""} nearby",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = textPrimary
                )
            }
        }

        // Student Bottom Action Button
        Button(
            onClick = {
                if (studentPhase != StudentPhase.STANDBY) onStopScanning() else onStartScanning()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    studentPhase != StudentPhase.STANDBY -> alertRed
                    isVerified -> Color(0xFF059669)
                    else -> Color(0xFF059669)
                }
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = when {
                        studentPhase != StudentPhase.STANDBY -> Icons.Default.Sensors
                        isVerified -> Icons.Default.Check
                        else -> Icons.Default.Radar
                    },
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = when {
                        studentPhase != StudentPhase.STANDBY -> "Cancel"
                        isVerified -> "Check In Again"
                        else -> "Check In"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// AUDIT HELPERS
// ─────────────────────────────────────────────────────────────────────────────
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
        val r = rec.rollNumber.trim()
        if (r.length <= 3 && r.all { it.isDigit() }) {
            return "2514301010" + String.format("%02d", r.toInt())
        }
        return r
    }
    val digits = rec.bleDeviceId.filter { it.isDigit() }
    val lastNum = digits.takeLast(2).toIntOrNull() ?: 19
    return "2514301010${String.format("%02d", lastNum)}"
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

// ─────────────────────────────────────────────────────────────────────────────
// TEACHER SECTION
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeacherSection(
    isSessionActive: Boolean,
    remainingSeconds: Int,
    finalizedRecords: List<AttendanceRecordItem>,
    isCloudSyncing: Boolean,
    cloudStatusMessage: String?,
    selectedCourse: CourseResponse?,
    onSelectCourse: (CourseResponse) -> Unit,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onShareAttendance: () -> Unit,
    onResetSession: () -> Unit,
    onVerifyStudent: (AttendanceRecordItem) -> Unit,
    teacherViewMode: TeacherViewMode,
    onSetTeacherViewMode: (TeacherViewMode) -> Unit,
    pastSessions: List<AttendanceSessionResponse>,
    isLoadingHistory: Boolean,
    onLoadHistory: () -> Unit,
    selectedSessionForDetails: AttendanceSessionResponse? = null,
    onSelectSessionForDetails: (AttendanceSessionResponse) -> Unit = {},
    onBackFromDetails: () -> Unit = {}
) {
    if (finalizedRecords.isNotEmpty()) {
        // ==========================================
        // AUDIT RESULTS SCREEN
        // ==========================================
        val verifiedCount = finalizedRecords.count { getRecordAuditStatus(it) == "Verified" }
        val unverifiedCount = finalizedRecords.count { getRecordAuditStatus(it) == "Unverified" }
        val flaggedCount = finalizedRecords.count { getRecordAuditStatus(it) == "Flagged" }

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

            // Sorted Attendance List: Unverified & Flagged at the top, Verified at the bottom; sorted by roll number
            val sortedList = finalizedRecords.sortedWith(
                compareBy<AttendanceRecordItem> { rec ->
                    if (getRecordAuditStatus(rec) == "Verified") 1 else 0
                }.thenBy { rec ->
                    getDisplayRollNo(rec)
                }
            )

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
        // ==========================================
        // COOL LOADING SCREEN (ACTIVE 60s BROADCAST)
        // ==========================================
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
                            .clip(CircleShape)
                            .background(successGreen.copy(alpha = pulseAlpha))
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

            // High-Tech Pulsing Radar Aura & Circular Progress Countdown (lifted up directly under pill)
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
                        color = Color(0xFF00F5A0).copy(alpha = wave1Alpha),
                        radius = baseRadius * wave1Scale,
                        center = centerOffset,
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(
                        color = Color(0xFF00F5A0).copy(alpha = wave2Alpha),
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
                                Color(0xFF00F5A0),
                                Color(0xFF3B82F6),
                                Color(0xFF00F5A0)
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
                // ==========================================
                // TEACHER OPTIONS HUB (HOMESCREEN-LIKE WINDOW)
                // ==========================================
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
                        // 1. Take Attendance
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
                                        .background(Color(0xFF1E3A8A), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.FactCheck,
                                        contentDescription = "Take Attendance",
                                        tint = Color(0xFF60A5FA),
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

                        // 2. Older Sessions
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
                                        .background(Color(0xFF064E3B), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.History,
                                        contentDescription = "Older Sessions",
                                        tint = Color(0xFF34D399),
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
                // ==========================================
                // TAKE ATTENDANCE (ACADEMIC SELECTION WINDOW)
                // ==========================================
                TakeAttendanceSelectionView(
                    onStartSession = onStartSession,
                    onSelectCourse = onSelectCourse,
                    isCloudSyncing = isCloudSyncing,
                    cloudStatusMessage = cloudStatusMessage
                )
            }

            TeacherViewMode.OLDER_SESSIONS -> {
                // ==========================================
                // OLDER SESSIONS VIEW
                // ==========================================
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

@Composable
private fun formatSessionDate(raw: String?): String {
    if (raw.isNullOrBlank()) return "Past session"
    val trimmed = raw.trim()
    if (trimmed.length >= 10 && trimmed[4] == '-' && trimmed[7] == '-') {
        val datePart = trimmed.substring(0, 10)
        val timePart = if (trimmed.length >= 16 && trimmed[10] == 'T') {
            trimmed.substring(11, 16)
        } else ""
        return if (timePart.isNotEmpty()) datePart + " " + timePart else datePart
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

// ─────────────────────────────────────────────────────────────────────────────
// SESSION DETAIL SCREEN (AUDIT ROSTER & CONFIGURATION)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SessionDetailScreen(
    session: AttendanceSessionResponse
) {
    val details = getSessionAcademicDetails(session)
    val records = session.records ?: emptyList()
    val sortedRecords = remember(records) {
        records.sortedBy { getDisplayRollNo(it) }
    }
    val verifiedCount = sortedRecords.count { getRecordAuditStatus(it) == "Verified" }
    val unverifiedCount = sortedRecords.count { getRecordAuditStatus(it) == "Unverified" }
    val flaggedCount = sortedRecords.count { getRecordAuditStatus(it) == "Flagged" }

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

private fun getSessionAcademicDetails(session: AttendanceSessionResponse): SessionAcademicDetails {
    val courseProg = session.courseProgram
        ?: if (session.courseCode?.contains("BCA", ignoreCase = true) == true) "BCA"
        else if (session.courseCode?.contains("B-Tech", ignoreCase = true) == true || session.courseCode?.contains("BTECH", ignoreCase = true) == true) "B-Tech"
        else session.courseCode?.takeIf { it.isNotBlank() } ?: "B-Tech"
    val sem = session.semester
        ?: Regex("Semester\\s*\\d", RegexOption.IGNORE_CASE).find(session.courseCode ?: "")?.value
        ?: Regex("Sem\\s*\\d", RegexOption.IGNORE_CASE).find(session.courseCode ?: "")?.value
        ?: if (session.courseCode?.isNotBlank() == true) session.courseCode else "Semester 1"
    val clsType = session.classType ?: when {
        session.courseName?.contains("Extra", ignoreCase = true) == true -> "Extra"
        session.courseName?.contains("Adjustment", ignoreCase = true) == true -> "Adjustment"
        else -> "Regular"
    }
    val batch = session.batchType ?: if (session.courseName?.contains("Lab", ignoreCase = true) == true) "Lab" else "Lecture"
    val unit = session.unit
        ?: Regex("Unit\\s*\\d", RegexOption.IGNORE_CASE).find(session.courseName ?: "")?.value
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
    return SessionAcademicDetails(clsType, courseProg ?: "B-Tech", sem ?: "Semester 1", batch, unit, topic)
}

// ─────────────────────────────────────────────────────────────────────────────
// AUTH & SETTINGS DIALOG (DARK THEMED)
// ─────────────────────────────────────────────────────────────────────────────
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

// ─────────────────────────────────────────────────────────────────────────────
// ADD COURSE DIALOG (DARK THEMED)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun AddCourseDialog(
    onDismiss: () -> Unit,
    onConfirm: (code: String, name: String) -> Unit
) {
    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cardBg,
        titleContentColor = textPrimary,
        title = { Text("Create Course", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Course Code (e.g. CS401)") },
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
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Course Name") },
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
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(code.trim().uppercase(), name.trim()) },
                enabled = code.isNotBlank() && name.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = primaryBlue)
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = textSecondary)
            }
        }
    )
}
