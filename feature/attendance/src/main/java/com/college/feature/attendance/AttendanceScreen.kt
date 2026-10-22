package com.college.feature.attendance

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhoneAndroid
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.college.core.model.Role
import com.college.core.model.User
import com.college.feature.attendance.network.AttendanceApiClient
import com.college.feature.attendance.network.AttendanceRecordItem
import com.college.feature.attendance.network.CourseResponse
import com.college.feature.attendance.network.RegisterRequest
import com.college.feature.attendance.ui.theme.*
import com.college.feature.attendance.viewmodel.AttendanceViewModel
import com.college.feature.attendance.viewmodel.PeerData
import com.college.feature.attendance.viewmodel.StudentPhase
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
    val sessionId = viewModel.sessionId
    val sessionSalt = viewModel.sessionSalt
    val remainingSeconds = viewModel.remainingSeconds
    val broadcastPayload = viewModel.currentBroadcastToken
    val finalizedRecords = viewModel.finalizedRecords
    val isCloudSyncing = viewModel.isCloudSyncing
    val cloudStatusMessage = viewModel.cloudStatusMessage
    val availableCourses = viewModel.availableCourses
    val selectedCourse = viewModel.selectedCourse
    val onSelectCourse: (CourseResponse) -> Unit = { viewModel.selectedCourse = it }
    val onCreateCourse: (String, String) -> Unit = { code, name -> viewModel.createCourse(code, name) }

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
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = textPrimary
                        )
                    }
                },
                title = {
                    Text(
                        text = "Attendance",
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
                    onStartSession = callbacks.onStartTeacherSession,
                    onStopSession = callbacks.onStopTeacherSession,
                    onShareAttendance = callbacks.onShareAttendance,
                    onResetSession = callbacks.onResetSession
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
// TEACHER SECTION
// ─────────────────────────────────────────────────────────────────────────────
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
        // AUDIT RESULTS SCREEN
        // ==========================================
        val verifiedCount = finalizedRecords.count { it.status == "VERIFIED_PRESENT" || it.status == "PRESENT" }
        val witnessedCount = finalizedRecords.count { it.verificationMethod.contains("WITNESS") }
        val spooferCount = finalizedRecords.count { it.status.contains("SPOOF") || it.status.contains("REJECT") }

        var selectedFilter by remember { mutableStateOf("ALL") }

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
                        Column {
                            Text(
                                text = "AUDIT RESULTS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = textSecondary
                            )
                            Text(
                                text = selectedCourse?.courseCode ?: "Session #$sessionId",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
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

                    // 3 Metric Stat Cards in one row
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
                            border = BorderStroke(1.dp, primaryBlue.copy(alpha = 0.4f))
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
                            border = BorderStroke(1.dp, if (spooferCount > 0) alertRed.copy(alpha = 0.4f) else cardBorder)
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
                                .height(40.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = textPrimary),
                            border = BorderStroke(1.dp, cardBorder),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export CSV", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onResetSession,
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp),
                            shape = RoundedCornerShape(10.dp),
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
                        label = { Text("All (${finalizedRecords.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = primaryBlueBg,
                            selectedLabelColor = primaryBlue
                        )
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == "VERIFIED",
                        onClick = { selectedFilter = "VERIFIED" },
                        label = { Text("Verified ($verifiedCount)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = successGreenBg,
                            selectedLabelColor = successGreen
                        )
                    )
                }
                item {
                    FilterChip(
                        selected = selectedFilter == "WITNESSED",
                        onClick = { selectedFilter = "WITNESSED" },
                        label = { Text("Witnessed ($witnessedCount)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = primaryBlueBg,
                            selectedLabelColor = primaryBlue
                        )
                    )
                }
                if (spooferCount > 0) {
                    item {
                        FilterChip(
                            selected = selectedFilter == "FLAGGED",
                            onClick = { selectedFilter = "FLAGGED" },
                            label = { Text("Flagged ($spooferCount)", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = alertRedBg,
                                selectedLabelColor = alertRed
                            )
                        )
                    }
                }
            }

            // Filtered Attendance List
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
                        .background(cardBg, RoundedCornerShape(14.dp))
                        .border(1.dp, cardBorder, RoundedCornerShape(14.dp)),
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
                            border = BorderStroke(1.dp, if (isSpoofer) alertRed.copy(alpha = 0.4f) else cardBorder)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
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
                                                text = rec.bleDeviceId,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = textPrimary
                                            )
                                            Text(
                                                text = "${rec.verificationMethod} • ${(rec.affinityScore * 100).toInt()}% match",
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
                                        text = rec.remarks,
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
            // Course Selector
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
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = BorderStroke(1.dp, cardBorder)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("COURSE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textSecondary)
                            TextButton(
                                onClick = { showAddCourseDialog = true },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = primaryBlue)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("New Course", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = primaryBlue)
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
                                        label = { Text(course.courseCode, fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = primaryBlueBg,
                                            selectedLabelColor = primaryBlue
                                        )
                                    )
                                }
                            }
                        } else {
                            Text(
                                "No courses found. Tap 'New Course' to add one.",
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
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(
                    1.dp,
                    if (isSessionActive) successGreen else cardBorder
                )
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
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (isSessionActive) successGreen else textMuted)
                            )
                            Text(
                                text = if (isSessionActive) "BROADCASTING ACTIVE" else "STANDBY",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSessionActive) successGreen else textMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = if (isSessionActive) "${remainingSeconds}s" else "60s",
                        fontSize = 50.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = if (isSessionActive) successGreen else textPrimary
                    )
                    Text(
                        text = if (isSessionActive) "Attendance window active" else "Standard session length",
                        fontSize = 12.sp,
                        color = textSecondary
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = cardBorder)
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("SESSION ID", fontSize = 10.sp, color = textSecondary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = sessionId.ifEmpty { "--" },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = textPrimary
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("IN-ROOM NONCE", fontSize = 10.sp, color = textSecondary, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = sessionSalt.ifEmpty { "--" },
                                fontSize = 16.sp,
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
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, cardBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("BLE PAYLOAD TOKEN", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(logBg, RoundedCornerShape(8.dp))
                            .border(1.dp, cardBorder, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = broadcastPayload.ifEmpty { "Idle (ready to broadcast)" },
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = if (isSessionActive) textPrimary else textMuted,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Tx Power: HIGH (+4 dBm) • Interval: 100ms", fontSize = 10.sp, color = textMuted)
                }
            }

            // Cloud sync banner
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

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = { if (isSessionActive) onStopSession() else onStartSession() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isSessionActive) alertRed else primaryBlue
                )
            ) {
                Text(
                    text = if (isSessionActive) "End Session & Audit" else "Start 60s Session",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
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
