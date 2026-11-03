package com.college.feature.attendance

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.college.feature.attendance.camera.PassiveFaceCaptureManager
import com.college.feature.attendance.game.GameTelemetryEngine
import com.college.feature.attendance.game.OrbPoppingGame
import com.college.feature.attendance.network.AttendanceRecordItem
import com.college.feature.attendance.telemetry.CognitiveFocusReport
import com.college.feature.attendance.telemetry.FocusClassification
import com.college.feature.attendance.ui.theme.*
import com.college.feature.attendance.viewmodel.StudentPhase

@Composable
internal fun StudentSection(
    studentPhase: StudentPhase,
    detectedSessionId: String,
    sessionSalt: String = "",
    studentId: String = "",
    studentStatusMessage: String? = null,
    lastFocusReport: CognitiveFocusReport? = null,
    capturedFacePayload: String? = null,
    auditedRecord: AttendanceRecordItem? = null,
    onRefreshAudit: (() -> Unit)? = null,
    isRefreshingAudit: Boolean = false,
    onChallengeResult: (hit: Int, total: Int) -> Unit = { _, _ -> },
    onBehavioralReport: (CognitiveFocusReport) -> Unit = {},
    onFaceCaptured: (String, ByteArray?) -> Unit = { _, _ -> },
    onStartScanning: () -> Unit,
    onStopScanning: () -> Unit,
    onNext: () -> Unit = {}
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
                            val faceResult = faceCaptureManager.stopAndExtractResult()
                            if (faceResult != null) {
                                lastCapturedFaceBase64 = faceResult.base64
                                onFaceCaptured(faceResult.base64, faceResult.bytes)
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
                                text = if (liveFaceSharpness > 40.0) "Ready" else "Position Face",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (liveFaceSharpness > 40.0) neonMint else solarAmber
                            )
                        }
                    }
                }

                isGameOver && !hasProceededToNext -> {
                    // Performance score screen
                    val isCheaterCaught = activeReport?.classification == FocusClassification.DIVIDED_ATTENTION_PROXY ||
                            activeReport?.classification == FocusClassification.AUTOMATED_SCRIPT

                    val (ratingText, ratingColor) = when {
                        isCheaterCaught -> "Needs Review" to warningOrange
                        else -> "Completed" to neonMint
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
                                color = warningOrangeBg,
                                border = BorderStroke(1.dp, warningOrange)
                            ) {
                                Text(
                                    text = "CHECK-IN PENDING REVIEW",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = warningOrange,
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
                        studentStatusMessage = studentStatusMessage,
                        capturedFaceBase64 = lastCapturedFaceBase64 ?: capturedFacePayload,
                        auditedRecord = auditedRecord
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
                                isDividedAttention -> "Check In Again"
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

// Displays server-authoritative attendance audit result
@Composable
private fun AntiProxyDefenseResultsScreen(
    studentStatusMessage: String?,
    capturedFaceBase64: String? = null,
    auditedRecord: AttendanceRecordItem? = null
) {
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

    // Server-authoritative audit verification
    val isOverallVerified = auditedRecord?.status == "VERIFIED_PRESENT"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val heroColor = if (isOverallVerified) successGreen else warningOrange
        val heroBg = if (isOverallVerified) successGreenBg else warningOrangeBg
        val heroIcon = if (isOverallVerified) Icons.Default.CheckCircle else Icons.Default.Warning

        // Status Icon Circle
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(heroBg)
                .border(2.dp, heroColor.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = heroIcon,
                contentDescription = null,
                tint = heroColor,
                modifier = Modifier.size(44.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = if (isOverallVerified) "Attendance Confirmed" else "Needs Review",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = textPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isOverallVerified) {
                "You have been successfully checked in."
            } else {
                studentStatusMessage ?: "Please show this screen to your instructor."
            },
            fontSize = 14.sp,
            color = textSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Minimal Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (faceBitmap != null) {
                        Image(
                            bitmap = faceBitmap,
                            contentDescription = "Photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .border(1.dp, cardBorder, CircleShape)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(logBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Face,
                                contentDescription = null,
                                tint = textMuted,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Column {
                        Text(
                            text = if (isOverallVerified) "Verified Present" else "Under Review",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            color = textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isOverallVerified) "Confirmed in classroom" else "Check in with instructor",
                            fontSize = 12.sp,
                            color = textMuted
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = heroBg,
                    border = BorderStroke(1.dp, heroColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = if (isOverallVerified) "Present" else "Review",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = heroColor
                    )
                }
            }
        }
    }
}


