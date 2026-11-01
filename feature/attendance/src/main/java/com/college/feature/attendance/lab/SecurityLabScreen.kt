package com.college.feature.attendance.lab

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.college.feature.attendance.audio.AcousticStatisticalSummary
import com.college.feature.attendance.audio.EnsembleVerdict
import com.college.feature.attendance.audio.GoertzelDetector
import com.college.feature.attendance.audio.UltrasonicLiveMetric
import com.college.feature.attendance.audio.UltrasonicProximityManager
import com.college.feature.attendance.audio.UltrasonicToneSynthesizer
import com.college.feature.attendance.ble.BleAdvertiser
import com.college.feature.attendance.ble.BleConstants
import com.college.feature.attendance.ble.BleLabPeer
import com.college.feature.attendance.ble.BleProximityEngine
import com.college.feature.attendance.ble.BleProximityZone
import com.college.feature.attendance.ble.BleScanner
import com.college.feature.attendance.camera.FaceImageProcessor
import com.college.feature.attendance.camera.PassiveFaceCaptureManager
import com.college.feature.attendance.network.AttendanceApiClient
import com.college.feature.attendance.network.FaceLabCheckRequest
import com.college.feature.attendance.network.FaceLabCompareRequest
import com.college.feature.attendance.viewmodel.AttendanceViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// Security Layer Lab diagnostic testing hub
@Composable
fun SecurityLabScreen(
    viewModel: AttendanceViewModel,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("🔊 Ultrasonic (L4)", "👤 Face (L3)", "🎯 Reflex (L2)", "📡 BLE (L1)")

    val bgDark = Color(0xFF070B0E)
    val cardBg = Color(0xFF0F171C)
    val cardBorder = Color(0xFF1E2D38)
    val neonTeal = Color(0xFF00F5A0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgDark)
            .padding(top = 16.dp, start = 12.dp, end = 12.dp, bottom = 8.dp)
    ) {
        // Lab top bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = "SECURITY LAYER LAB",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = Color.White
                    )
                    Text(
                        text = "Isolated Anti-Proxy Diagnostic Bench",
                        fontSize = 11.sp,
                        color = Color(0xFF90A4AE)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Layer selection tabs
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tabs.forEachIndexed { index, title ->
                val isSelected = selectedTab == index
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) Color(0x2800F5A0) else cardBg,
                    border = BorderStroke(1.dp, if (isSelected) neonTeal else cardBorder),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { selectedTab = index }
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) neonTeal else Color(0xFF90A4AE)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Content of selected security layer
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                0 -> UltrasonicDiagnosticLab()
                1 -> FaceBiometricDiagnosticLab()
                2 -> ReflexEngineDiagnosticLab(viewModel)
                3 -> BleRadioDiagnosticLab(viewModel)
            }
        }
    }
}

// Layer 4 Ultrasonic Proximity Diagnostic Lab with live distance and SNR metrics
@Composable
private fun UltrasonicDiagnosticLab() {
    val context = LocalContext.current
    val manager = remember { UltrasonicProximityManager(context) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
    }

    var isRadarRunning by remember { mutableStateOf(false) }
    var isBeaconRunning by remember { mutableStateOf(false) }
    var pingsSentCount by remember { mutableIntStateOf(0) }

    var currentSnrDb by remember { mutableDoubleStateOf(0.0) }
    var currentPowerDb by remember { mutableDoubleStateOf(0.0) }
    var lastDetectedPowerDb by remember { mutableDoubleStateOf(0.0) }
    var currentDistanceCm by remember { mutableDoubleStateOf(-1.0) }
    var isChirpDetected by remember { mutableStateOf(false) }
    var isCoLocated by remember { mutableStateOf(false) }
    var peakSnrDb by remember { mutableDoubleStateOf(0.0) }
    var calibratedRefPowerDb by remember { mutableDoubleStateOf(GoertzelDetector.CALIBRATED_REF_POWER_DB) }

    val recentPings = remember { mutableStateListOf<AcousticPingEvent>() }
    val burstHistory = remember { mutableStateListOf<Double>() }

    var isBurstSequenceRunning by remember { mutableStateOf(false) }
    var burstSequenceProgress by remember { mutableIntStateOf(0) }

    val ensembleStats = remember(burstHistory.size, calibratedRefPowerDb) {
        GoertzelDetector.computeTrimmedMeanStats(burstHistory.toList(), calibratedRefPowerDb)
    }

    DisposableEffect(Unit) {
        onDispose {
            manager.release()
        }
    }

    // Helper to start diagnostic radar with explicit reference power
    val startRadarWithRef: (Double) -> Unit = { refDb ->
        manager.startLiveDiagnostic(calibratedRefPowerDb = refDb) { metric ->
            currentSnrDb = metric.snrDb
            currentPowerDb = metric.powerLevelDb

            if (metric.snrDb > peakSnrDb) {
                peakSnrDb = metric.snrDb
            }

            isChirpDetected = metric.isChirpDetected

            // When a complete acoustic pulse envelope finishes, record peak measurement
            if (metric.isBurstFinalized) {
                currentDistanceCm = metric.estimatedDistanceCm
                lastDetectedPowerDb = metric.burstPeakPowerDb
                isCoLocated = metric.isCoLocatedSameDesk

                if (burstHistory.size >= 100) {
                    burstHistory.removeAt(0)
                }
                burstHistory.add(metric.burstPeakPowerDb)

                val event = AcousticPingEvent(
                    timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date()),
                    snrDb = metric.burstPeakSnrDb,
                    powerDb = metric.burstPeakPowerDb,
                    distanceCm = metric.estimatedDistanceCm,
                    isSameDesk = metric.isCoLocatedSameDesk
                )
                if (recentPings.size >= 15) recentPings.removeAt(recentPings.lastIndex)
                recentPings.add(0, event)
            }
        }
    }

    // Toggle radar listener
    val toggleRadar = {
        if (!hasMicPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            if (isRadarRunning) {
                manager.stopLiveDiagnostic()
                isRadarRunning = false
            } else {
                startRadarWithRef(calibratedRefPowerDb)
                isRadarRunning = true
            }
        }
    }

    // Toggle beacon transmitter
    val toggleBeacon = {
        if (isBeaconRunning) {
            manager.stopBeaconEmitter()
            isBeaconRunning = false
        } else {
            manager.startBeaconEmitter(intervalMs = 650L) {
                pingsSentCount++
            }
            isBeaconRunning = true
        }
    }

    // Run rapid 100-burst sequence for statistical ensemble evaluation
    val runBurstSequence = {
        if (isBurstSequenceRunning) {
            manager.stopBeaconEmitter()
            isBurstSequenceRunning = false
        } else {
            isBurstSequenceRunning = true
            burstSequenceProgress = 0
            manager.emitBurstSequence(
                count = 100,
                intervalMs = 130L,
                onProgress = { sent, _ ->
                    burstSequenceProgress = sent
                    pingsSentCount++
                },
                onComplete = {
                    isBurstSequenceRunning = false
                }
            )
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(650),
            repeatMode = RepeatMode.Reverse
        ),
        label = "radarPulse"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Permission alert if microphone not granted
        if (!hasMicPermission) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0x28FF1744),
                border = BorderStroke(1.dp, Color(0xFFFF1744)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Microphone Permission Required",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF1744)
                        )
                        Text(
                            text = "Needed to capture 19.2 kHz acoustic pings",
                            fontSize = 10.sp,
                            color = Color(0xFFCFD8DC)
                        )
                    }
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Grant", fontSize = 11.sp)
                    }
                }
            }
        }

        // Live Distance & Status Hero Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = when {
                isCoLocated -> Color(0x2400F5A0)
                currentDistanceCm > 0 -> Color(0x1800E5FF)
                else -> Color(0xFF0F171C)
            },
            border = BorderStroke(
                1.5.dp,
                when {
                    isCoLocated -> Color(0xFF00F5A0)
                    currentDistanceCm > 0 -> Color(0xFF00E5FF)
                    else -> Color(0xFF1E2D38)
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "ACOUSTIC PROXIMITY ESTIMATE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = Color(0xFF90A4AE)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Big distance readout in centimeters
                Text(
                    text = if (currentDistanceCm > 0) "${currentDistanceCm.roundToInt()} cm" else "-- cm",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        isCoLocated -> Color(0xFF00F5A0)
                        currentDistanceCm > 0 -> Color(0xFF00E5FF)
                        else -> Color(0xFF546E7A)
                    }
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Proximity classification badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = when {
                        isCoLocated -> Color(0x3300F5A0)
                        currentDistanceCm in 25.1..60.0 -> Color(0x33FFB300)
                        currentDistanceCm > 60.0 -> Color(0x3300E5FF)
                        isRadarRunning -> Color(0x2800E5FF)
                        else -> Color(0x2290A4AE)
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            isCoLocated -> Color(0xFF00F5A0)
                            currentDistanceCm in 25.1..60.0 -> Color(0xFFFFB300)
                            currentDistanceCm > 60.0 -> Color(0xFF00E5FF)
                            isRadarRunning -> Color(0xFF00E5FF)
                            else -> Color(0xFF546E7A)
                        }
                    )
                ) {
                    Text(
                        text = when {
                            isCoLocated -> "🚨 SAME DESK CO-LOCATED (< 25 cm)"
                            currentDistanceCm in 25.1..60.0 -> "ADJACENT DESK (${currentDistanceCm.roundToInt()} cm)"
                            currentDistanceCm > 60.0 -> "DISTANT DESK (${currentDistanceCm.roundToInt()} cm)"
                            isRadarRunning -> "RADAR LISTENING FOR 19.2 kHz PING..."
                            else -> "RADAR STANDBY"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            isCoLocated -> Color(0xFF00F5A0)
                            currentDistanceCm in 25.1..60.0 -> Color(0xFFFFB300)
                            currentDistanceCm > 60.0 -> Color(0xFF00E5FF)
                            isRadarRunning -> Color(0xFF00E5FF)
                            else -> Color(0xFF90A4AE)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Real-time SNR and Power gauge bar
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Live: ${if (currentPowerDb > 0) String.format(Locale.US, "%.1f dB", currentPowerDb) else "-- dB"}${if (lastDetectedPowerDb > 0) " (Peak: " + String.format(Locale.US, "%.1f dB", lastDetectedPowerDb) + ")" else ""}",
                            fontSize = 10.sp,
                            color = Color(0xFF90A4AE)
                        )
                        Text(
                            "SNR: ${String.format(Locale.US, "%.1f", currentSnrDb)} dB (Peak: ${String.format(Locale.US, "%.1f", peakSnrDb)} dB)",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (currentSnrDb >= 5.0) Color(0xFF00F5A0) else Color(0xFF90A4AE)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    val progress = (currentSnrDb.coerceIn(0.0, 30.0) / 30.0).toFloat()
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = if (isCoLocated) Color(0xFF00F5A0) else Color(0xFF00E5FF),
                        trackColor = Color(0xFF1E2D38)
                    )
                }
            }
        }

        // 100-Burst Statistical Ensemble Consensus Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = when (ensembleStats.verdict) {
                EnsembleVerdict.CONFIRMED_SAME_DESK -> Color(0x2800F5A0)
                EnsembleVerdict.CONFIRMED_NEXT_BENCH -> Color(0x2000E5FF)
                EnsembleVerdict.AMBIGUOUS_BOUNDARY -> Color(0x28FFB300)
                EnsembleVerdict.COLLECTING_SAMPLES -> Color(0xFF0F171C)
            },
            border = BorderStroke(
                1.5.dp,
                when (ensembleStats.verdict) {
                    EnsembleVerdict.CONFIRMED_SAME_DESK -> Color(0xFF00F5A0)
                    EnsembleVerdict.CONFIRMED_NEXT_BENCH -> Color(0xFF00E5FF)
                    EnsembleVerdict.AMBIGUOUS_BOUNDARY -> Color(0xFFFFB300)
                    EnsembleVerdict.COLLECTING_SAMPLES -> Color(0xFF1E2D38)
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "100-BURST ENSEMBLE CONSENSUS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp,
                        color = Color(0xFF90A4AE)
                    )
                    Text(
                        text = "${burstHistory.size}/100 Samples",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF)
                    )
                }

                // Consensus Decision Badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = when (ensembleStats.verdict) {
                        EnsembleVerdict.CONFIRMED_SAME_DESK -> Color(0x3300F5A0)
                        EnsembleVerdict.CONFIRMED_NEXT_BENCH -> Color(0x3300E5FF)
                        EnsembleVerdict.AMBIGUOUS_BOUNDARY -> Color(0x33FFB300)
                        EnsembleVerdict.COLLECTING_SAMPLES -> Color(0x2290A4AE)
                    },
                    border = BorderStroke(
                        1.dp,
                        when (ensembleStats.verdict) {
                            EnsembleVerdict.CONFIRMED_SAME_DESK -> Color(0xFF00F5A0)
                            EnsembleVerdict.CONFIRMED_NEXT_BENCH -> Color(0xFF00E5FF)
                            EnsembleVerdict.AMBIGUOUS_BOUNDARY -> Color(0xFFFFB300)
                            EnsembleVerdict.COLLECTING_SAMPLES -> Color(0xFF546E7A)
                        }
                    )
                ) {
                    Text(
                        text = when (ensembleStats.verdict) {
                            EnsembleVerdict.CONFIRMED_SAME_DESK -> "🚨 SAME DESK CO-LOCATED (${ensembleStats.greenPercentage}% Green Consensus)"
                            EnsembleVerdict.CONFIRMED_NEXT_BENCH -> "✅ SEPARATE BENCH CONFIRMED (${ensembleStats.bluePercentage}% Blue Consensus)"
                            EnsembleVerdict.AMBIGUOUS_BOUNDARY -> "⚠️ BOUNDARY AMBIGUOUS (${ensembleStats.greenPercentage}% Green / ${ensembleStats.bluePercentage}% Blue)"
                            EnsembleVerdict.COLLECTING_SAMPLES -> if (burstHistory.isEmpty()) "WAITING FOR BURST SEQUENCE (0/100)" else "COLLECTING SAMPLES (${burstHistory.size}/100)..."
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (ensembleStats.verdict) {
                            EnsembleVerdict.CONFIRMED_SAME_DESK -> Color(0xFF00F5A0)
                            EnsembleVerdict.CONFIRMED_NEXT_BENCH -> Color(0xFF00E5FF)
                            EnsembleVerdict.AMBIGUOUS_BOUNDARY -> Color(0xFFFFB300)
                            EnsembleVerdict.COLLECTING_SAMPLES -> Color(0xFF90A4AE)
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                // Green vs Blue ratio segmented gauge
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Green (<25cm): ${ensembleStats.greenCount} (${ensembleStats.greenPercentage}%)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00F5A0)
                        )
                        Text(
                            text = "Blue (Distant): ${ensembleStats.blueCount} (${ensembleStats.bluePercentage}%)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1E2D38))
                    ) {
                        if (ensembleStats.sampleCount > 0) {
                            val greenWeight = ensembleStats.greenPercentage.coerceAtLeast(0).toFloat()
                            val blueWeight = ensembleStats.bluePercentage.coerceAtLeast(0).toFloat()
                            if (greenWeight > 0f) {
                                Box(
                                    modifier = Modifier
                                        .weight(greenWeight)
                                        .fillMaxSize()
                                        .background(Color(0xFF00F5A0))
                                )
                            }
                            if (blueWeight > 0f) {
                                Box(
                                    modifier = Modifier
                                        .weight(blueWeight)
                                        .fillMaxSize()
                                        .background(Color(0xFF00E5FF))
                                )
                            }
                        }
                    }
                }

                // Statistical Metrics Data Table
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Trimmed Distance", fontSize = 10.sp, color = Color(0xFF90A4AE))
                        Text(
                            text = if (ensembleStats.estimatedDistanceCm > 0) "${ensembleStats.estimatedDistanceCm.roundToInt()} cm" else "-- cm",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = when (ensembleStats.verdict) {
                                EnsembleVerdict.CONFIRMED_SAME_DESK -> Color(0xFF00F5A0)
                                EnsembleVerdict.CONFIRMED_NEXT_BENCH -> Color(0xFF00E5FF)
                                else -> Color.White
                            }
                        )
                    }
                    Column {
                        Text("Trimmed Mean Power", fontSize = 10.sp, color = Color(0xFF90A4AE))
                        Text(
                            text = if (ensembleStats.sampleCount > 0) String.format(Locale.US, "%.1f dB", ensembleStats.trimmedMeanPowerDb) else "-- dB",
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Spikes Filtered", fontSize = 10.sp, color = Color(0xFF90A4AE))
                        Text(
                            text = "${burstHistory.size - ensembleStats.trimmedCount} outliers",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFFFFB300)
                        )
                    }
                }

                // Reset buffer button
                if (burstHistory.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { burstHistory.clear() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Clear 100-Burst Buffer", fontSize = 10.sp, color = Color(0xFF90A4AE))
                    }
                }
            }
        }

        // Action Controls (Receiver Radar vs Transmitter Beacon)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Radar Listener Button
            Button(
                onClick = toggleRadar,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRadarRunning) Color(0xFF00C853) else Color(0xFF1E2D38)
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isRadarRunning) Icons.Default.Stop else Icons.Default.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isRadarRunning) "Stop Radar" else "Start Radar",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Beacon Transmitter Button
            Button(
                onClick = toggleBeacon,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBeaconRunning) Color(0xFFFF9100) else Color(0xFF1E2D38)
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isBeaconRunning) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .then(if (isBeaconRunning) Modifier.scale(pulseScale) else Modifier)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isBeaconRunning) "Pinging ($pingsSentCount)" else "Transmit Beacon",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Transmitter Burst Action Buttons (Single vs 100x Ensemble Sequence)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Instant Single Ping Button
            OutlinedButton(
                onClick = {
                    manager.emitChirp()
                    pingsSentCount++
                },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0xFF00E5FF))
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Single Burst",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF)
                )
            }

            // Rapid 100-Burst Sequence Button
            Button(
                onClick = runBurstSequence,
                modifier = Modifier
                    .weight(1.3f)
                    .height(46.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBurstSequenceRunning) Color(0xFFFF9100) else Color(0xFF0091EA)
                )
            ) {
                Icon(
                    imageVector = if (isBurstSequenceRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isBurstSequenceRunning) "Pinging ($burstSequenceProgress/100)" else "Transmit 100x (13s)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        // Acoustic Hardware Calibration Card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF0F171C),
            border = BorderStroke(1.dp, Color(0xFF1E2D38)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HARDWARE ACOUSTIC CALIBRATION",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp,
                        color = Color(0xFF90A4AE)
                    )
                    Text(
                        text = "Ref: ${String.format(Locale.US, "%.1f", calibratedRefPowerDb)} dB",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF00E5FF)
                    )
                }
                Text(
                    text = "Calibrated for 20 cm desk separation. Fine-tune or tap below to auto-calibrate to your device speaker/mic.",
                    fontSize = 10.sp,
                    color = Color(0xFF78909C)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val newRef = (calibratedRefPowerDb - 2.0).coerceAtLeast(70.0)
                            calibratedRefPowerDb = newRef
                            if (isRadarRunning) {
                                manager.stopLiveDiagnostic()
                                startRadarWithRef(newRef)
                            }
                        },
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("-2 dB", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val newRef = (calibratedRefPowerDb + 2.0).coerceAtMost(130.0)
                            calibratedRefPowerDb = newRef
                            if (isRadarRunning) {
                                manager.stopLiveDiagnostic()
                                startRadarWithRef(newRef)
                            }
                        },
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("+2 dB", fontSize = 11.sp)
                    }
                    Button(
                        onClick = {
                            if (lastDetectedPowerDb > 0.0) {
                                calibratedRefPowerDb = lastDetectedPowerDb
                                if (isRadarRunning) {
                                    manager.stopLiveDiagnostic()
                                    startRadarWithRef(lastDetectedPowerDb)
                                }
                            }
                        },
                        enabled = lastDetectedPowerDb > 0.0,
                        modifier = Modifier.weight(2.2f).height(36.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0091EA)),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text(
                            text = if (lastDetectedPowerDb > 0.0) "Adopt Last (${lastDetectedPowerDb.roundToInt()} dB)" else "Calibrate at 20cm",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            calibratedRefPowerDb = GoertzelDetector.CALIBRATED_REF_POWER_DB
                            if (isRadarRunning) {
                                manager.stopLiveDiagnostic()
                                startRadarWithRef(GoertzelDetector.CALIBRATED_REF_POWER_DB)
                            }
                        },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Reset", fontSize = 10.sp)
                    }
                }
            }
        }

        // Ultrasonic Technical Metrics Spec Sheet
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF0F171C),
            border = BorderStroke(1.dp, Color(0xFF1E2D38)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "ACOUSTIC CHANNEL SPECIFICATION",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    color = Color(0xFF90A4AE)
                )
                SpecMetricRow("Center Frequency", "19,200 Hz (Inaudible to human ear)")
                SpecMetricRow("Window Envelope", "80 ms Hann-Windowed Burst (Steady-state resonance)")
                SpecMetricRow("Sampling Rate", "48,000 Hz PCM-16 Mono")
                SpecMetricRow("Audio Pipeline", "Raw Unprocessed / Voice Recognition (No OEM AGC)")
                SpecMetricRow("Guard Bands", "18.4 - 20.0 kHz (Mic-safe symmetric guard filters)")
                SpecMetricRow("Desk Proxy Boundary", "< 25.0 cm physical separation")
            }
        }

        // Live Detections Log
        Text(
            text = "ACOUSTIC PING LOG (${recentPings.size})",
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp,
            color = Color(0xFF90A4AE)
        )

        if (recentPings.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF0F171C),
                border = BorderStroke(1.dp, Color(0xFF1E2D38)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No acoustic pings captured yet.\nStart Radar on Phone B and tap 'Emit Single Ping' or 'Transmit Beacon' on Phone A.",
                        fontSize = 11.sp,
                        color = Color(0xFF546E7A),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            recentPings.forEach { ping ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (ping.isSameDesk) Color(0x2000F5A0) else Color(0xFF0F171C),
                    border = BorderStroke(1.dp, if (ping.isSameDesk) Color(0xFF00F5A0) else Color(0xFF1E2D38)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = ping.timestamp,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF90A4AE)
                            )
                            Text(
                                text = if (ping.isSameDesk) "SAME DESK PROXIMITY" else "DISTANT DESK",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (ping.isSameDesk) Color(0xFF00F5A0) else Color(0xFFFFB300)
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${ping.distanceCm.roundToInt()} cm",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = if (ping.isSameDesk) Color(0xFF00F5A0) else Color(0xFF00E5FF)
                            )
                            Text(
                                text = "Power: ${String.format(Locale.US, "%.1f", ping.powerDb)} dB | SNR: +${String.format(Locale.US, "%.1f", ping.snrDb)} dB",
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF90A4AE)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Layer 3 Face Biometric Deduplication Lab with Server-Side YuNet Detection & SFace Recognition
@Composable
private fun FaceBiometricDiagnosticLab() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    val faceManager = remember { PassiveFaceCaptureManager(context, lifecycleOwner) }
    var isCameraRunning by remember { mutableStateOf(false) }
    var currentSharpness by remember { mutableDoubleStateOf(0.0) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    var sampleABase64 by remember { mutableStateOf<String?>(null) }
    var sampleBBase64 by remember { mutableStateOf<String?>(null) }
    var sampleAInfo by remember { mutableStateOf<String?>(null) }
    var sampleBInfo by remember { mutableStateOf<String?>(null) }
    var statusFeedback by remember { mutableStateOf<String?>(null) }
    var isCheckingSample by remember { mutableStateOf(false) }
    var isComparing by remember { mutableStateOf(false) }
    var similarityScore by remember { mutableDoubleStateOf(-1.0) }
    var comparisonIsDuplicate by remember { mutableStateOf(false) }
    var comparisonRemarks by remember { mutableStateOf("") }

    val sampleABitmap = remember(sampleABase64) {
        sampleABase64?.let { b64 ->
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (_: Exception) { null }
        }
    }

    val sampleBBitmap = remember(sampleBBase64) {
        sampleBBase64?.let { b64 ->
            try {
                val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (_: Exception) { null }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            faceManager.release()
        }
    }

    val toggleCamera: () -> Unit = {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            if (isCameraRunning) {
                faceManager.stopCamera()
                isCameraRunning = false
            } else {
                faceManager.startCapture(
                    onScoreUpdate = { currentSharpness = it },
                    previewSurfaceProvider = previewViewRef?.surfaceProvider
                )
                isCameraRunning = true
            }
        }
    }

    val captureSample: (Boolean) -> Unit = { isA ->
        if (!isCameraRunning) {
            statusFeedback = "⚠️ Start the camera first before capturing."
        } else {
            val b64 = faceManager.captureCurrentCandidate()
            if (b64 != null) {
                isCheckingSample = true
                statusFeedback = "Verifying frame with server YuNet face detector..."
                scope.launch {
                    try {
                        val res = AttendanceApiClient.service.checkLabFace(FaceLabCheckRequest(b64))
                        if (res.isSuccessful && res.body()?.hasFace == true) {
                            if (isA) {
                                sampleABase64 = b64
                                sampleAInfo = "✅ YuNet Verified"
                                statusFeedback = "✅ Sample A locked & verified as human face by server!"
                            } else {
                                sampleBBase64 = b64
                                sampleBInfo = "✅ YuNet Verified"
                                statusFeedback = "✅ Sample B locked & verified as human face by server!"
                            }
                            similarityScore = -1.0
                        } else {
                            val reason = res.body()?.remarks ?: "No human face detected"
                            statusFeedback = "❌ $reason"
                        }
                    } catch (e: Exception) {
                        statusFeedback = "⚠️ Server connection error: ${e.message}"
                    } finally {
                        isCheckingSample = false
                    }
                }
            } else {
                statusFeedback = "⚠️ Focusing camera frame. Hold steady facing front camera."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Camera Permission Banner
        if (!hasCameraPermission) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0x28FF1744),
                border = BorderStroke(1.dp, Color(0xFFFF1744)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Camera Permission Required",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF1744)
                        )
                        Text(
                            text = "Needed to capture lightweight frame for server-side face verification",
                            fontSize = 10.sp,
                            color = Color(0xFFCFD8DC)
                        )
                    }
                    Button(
                        onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Grant", fontSize = 11.sp)
                    }
                }
            }
        }

        // Live Camera View Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF0F171C),
            border = BorderStroke(1.dp, if (isCameraRunning) Color(0xFF00F5A0) else Color(0xFF1E2D38)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "DEEP FACE RECOGNITION (SERVER YuNet + SFace)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp,
                            color = Color(0xFF90A4AE)
                        )
                        Text(
                            text = if (isCameraRunning) "Live front camera view (0% phone ML load)" else "Camera paused",
                            fontSize = 10.sp,
                            color = Color(0xFF78909C)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isCameraRunning) Color(0x3300F5A0) else Color(0x3390A4AE)
                    ) {
                        Text(
                            text = if (isCameraRunning) "CAMERA ACTIVE" else "IDLE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isCameraRunning) Color(0xFF00F5A0) else Color(0xFF90A4AE),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                // Camera Preview Container
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF070B0E)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isCameraRunning && hasCameraPermission) {
                        AndroidView(
                            factory = { ctx ->
                                PreviewView(ctx).apply {
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                    previewViewRef = this
                                    faceManager.startCapture(
                                        onScoreUpdate = { currentSharpness = it },
                                        previewSurfaceProvider = this.surfaceProvider
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Face,
                                contentDescription = null,
                                tint = Color(0xFF546E7A),
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                text = "Camera Inactive",
                                fontSize = 12.sp,
                                color = Color(0xFF90A4AE)
                            )
                        }
                    }
                }

                // Camera Toggle Button
                Button(
                    onClick = toggleCamera,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCameraRunning) Color(0xFFFF1744) else Color(0xFF00F5A0)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = if (isCameraRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = if (isCameraRunning) Color.White else Color.Black
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isCameraRunning) "Stop Camera" else "Start Camera",
                        fontWeight = FontWeight.Bold,
                        color = if (isCameraRunning) Color.White else Color.Black
                    )
                }
            }
        }

        // Status feedback if any
        statusFeedback?.let { feedback ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (feedback.startsWith("❌")) Color(0x33FF1744) else Color(0x3300F5A0),
                border = BorderStroke(1.dp, if (feedback.startsWith("❌")) Color(0xFFFF1744) else Color(0xFF00F5A0)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = feedback,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (feedback.startsWith("❌")) Color(0xFFFF8A80) else Color(0xFF00F5A0),
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        // Side-by-Side Face Comparator Card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF0F171C),
            border = BorderStroke(1.dp, Color(0xFF1E2D38)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "SIDE-BY-SIDE PROXY COMPARATOR",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = Color(0xFF90A4AE)
                )
                Text(
                    text = "Capture Sample A and Sample B. The server uses OpenCV YuNet to detect faces (rejecting room tops/ceilings) and SFace landmark alignment to verify identity across head angles.",
                    fontSize = 11.sp,
                    color = Color(0xFFCFD8DC)
                )

                // Samples Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Sample A Box
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(112.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF070B0E))
                                .border(BorderStroke(1.dp, if (sampleABitmap != null) Color(0xFF00F5A0) else Color(0xFF263238))),
                            contentAlignment = Alignment.Center
                        ) {
                            if (sampleABitmap != null) {
                                Image(
                                    bitmap = sampleABitmap,
                                    contentDescription = "Sample A",
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text("Sample A", fontSize = 11.sp, color = Color(0xFF546E7A))
                            }
                        }

                        Button(
                            onClick = { captureSample(true) },
                            enabled = isCameraRunning && !isCheckingSample,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2D38)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Capture A", fontSize = 11.sp, color = Color(0xFFECEFF1))
                        }

                        sampleAInfo?.let {
                            Text(it, fontSize = 9.sp, color = Color(0xFF00F5A0), maxLines = 1)
                        }
                    }

                    // Sample B Box
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(112.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF070B0E))
                                .border(BorderStroke(1.dp, if (sampleBBitmap != null) Color(0xFF00F5A0) else Color(0xFF263238))),
                            contentAlignment = Alignment.Center
                        ) {
                            if (sampleBBitmap != null) {
                                Image(
                                    bitmap = sampleBBitmap,
                                    contentDescription = "Sample B",
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Text("Sample B", fontSize = 11.sp, color = Color(0xFF546E7A))
                            }
                        }

                        Button(
                            onClick = { captureSample(false) },
                            enabled = isCameraRunning && !isCheckingSample,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2D38)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Capture B", fontSize = 11.sp, color = Color(0xFFECEFF1))
                        }

                        sampleBInfo?.let {
                            Text(it, fontSize = 9.sp, color = Color(0xFF00F5A0), maxLines = 1)
                        }
                    }
                }

                // Compare Action Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (sampleABase64 != null && sampleBBase64 != null) {
                                isComparing = true
                                statusFeedback = "Comparing face signatures via server SFace deep neural network..."
                                scope.launch {
                                    try {
                                        val res = AttendanceApiClient.service.compareLabFaces(
                                            FaceLabCompareRequest(imageABase64 = sampleABase64!!, imageBBase64 = sampleBBase64!!)
                                        )
                                        if (res.isSuccessful && res.body() != null) {
                                            val data = res.body()!!
                                            similarityScore = data.similarityScore
                                            comparisonIsDuplicate = data.isDuplicate
                                            comparisonRemarks = data.remarks
                                            statusFeedback = null
                                        } else {
                                            statusFeedback = "⚠️ Comparison failed: ${res.errorBody()?.string()}"
                                        }
                                    } catch (e: Exception) {
                                        statusFeedback = "⚠️ Comparison error: ${e.message}"
                                    } finally {
                                        isComparing = false
                                    }
                                }
                            }
                        },
                        enabled = sampleABase64 != null && sampleBBase64 != null && !isComparing,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isComparing) "Analyzing..." else "Compare Signatures (Server SFace)",
                            fontWeight = FontWeight.Bold,
                            color = Color.Black,
                            fontSize = 12.sp
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            sampleABase64 = null
                            sampleBBase64 = null
                            sampleAInfo = null
                            sampleBInfo = null
                            similarityScore = -1.0
                            comparisonIsDuplicate = false
                            comparisonRemarks = ""
                            statusFeedback = null
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF90A4AE))
                    ) {
                        Text("Reset", fontSize = 12.sp)
                    }
                }

                // Comparison Verdict Display
                if (similarityScore >= -1.0 && comparisonRemarks.isNotBlank()) {
                    val isDuplicate = comparisonIsDuplicate
                    val scorePct = (similarityScore * 100).toInt()
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isDuplicate) Color(0x28FF1744) else Color(0x2800F5A0),
                        border = BorderStroke(1.dp, if (isDuplicate) Color(0xFFFF1744) else Color(0xFF00F5A0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "SFace SIMILARITY: $scorePct%",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black,
                                color = if (isDuplicate) Color(0xFFFF1744) else Color(0xFF00F5A0)
                            )
                            Text(
                                text = if (isDuplicate) "🚨 DUPLICATE PROXY MATCH DETECTED (≥ 36%)" else "✅ UNIQUE INDIVIDUAL VERIFIED (< 36%)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDuplicate) Color(0xFFFF1744) else Color(0xFF00F5A0)
                            )
                            Text(
                                text = if (isDuplicate) "Both samples match the same person! SFace landmark alignment confirmed identical identity." else "Different human faces verified. Attendance accepted.",
                                fontSize = 10.sp,
                                color = Color(0xFFECEFF1)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Layer 2 Cognitive Reflex Engine Lab
@Composable
private fun ReflexEngineDiagnosticLab(viewModel: AttendanceViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF0F171C),
            border = BorderStroke(1.dp, Color(0xFF1E2D38)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "COGNITIVE REFLEX ENGINE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = Color(0xFF90A4AE)
                )
                Text(
                    text = "Tests human attention capacity through blooming target stimuli. Flagging activates when accuracy drops below 60% due to multi-device juggling.",
                    fontSize = 11.sp,
                    color = Color(0xFFCFD8DC)
                )

                viewModel.lastFocusReport?.let { report ->
                    SpecMetricRow("Hit Accuracy", "${report.hits} / ${report.totalSpawns} (${report.accuracyPercentage}%)")
                    SpecMetricRow("Median Latency", "${report.medianLatencyMs.toInt()} ms")
                    SpecMetricRow("Classification", report.classification.name)
                    SpecMetricRow("Reason", report.diagnosticReason)
                }
            }
        }
    }
}

// Layer 1 BLE Radio Proximity Lab with live RSSI, distance estimation, and confirmed median logic
@Composable
private fun BleRadioDiagnosticLab(viewModel: AttendanceViewModel) {
    val context = LocalContext.current
    val bluetoothManager = remember { context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager }
    val isBtEnabled = bluetoothManager?.adapter?.isEnabled == true

    var hasBlePermissions by remember {
        mutableStateOf(
            BleConstants.neededPermissions.all { perm ->
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val blePermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasBlePermissions = results.values.all { it }
    }

    // Device advertising ID (same roll-number logic as main app studentShortId, with fallback)
    val myDeviceShortId = remember {
        viewModel.studentShortId.takeIf { it != "0000" && it.isNotBlank() } ?: "D" + (100..999).random()
    }

    var isScanning by remember { mutableStateOf(false) }
    var isBroadcasting by remember { mutableStateOf(false) }
    val discoveredPeers = remember { mutableStateMapOf<String, BleLabPeer>() }

    val scanner = remember {
        BleScanner(
            context = context,
            onScanningChanged = { isScanning = it },
            onPacketReceived = { rssi, latencyMs, msg ->
                val clean = msg.trim()
                val peerId = when {
                    clean.startsWith("LAB:") -> clean.removePrefix("LAB:")
                    clean.startsWith("S:") -> "BEACON:" + clean.removePrefix("S:").take(4)
                    clean.length >= 4 -> clean.takeLast(4)
                    else -> clean
                }
                if (peerId.isNotBlank() && peerId != myDeviceShortId) {
                    val existing = discoveredPeers[peerId]
                    if (existing != null) {
                        existing.addSample(rssi, latencyMs)
                    } else {
                        val newPeer = BleLabPeer(peerId)
                        newPeer.addSample(rssi, latencyMs)
                        discoveredPeers[peerId] = newPeer
                    }
                }
            }
        )
    }

    val advertiser = remember {
        BleAdvertiser(context) { isBroadcasting = it }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                scanner.stopScanning()
                advertiser.stopBroadcasting()
            } catch (_: Exception) {}
        }
    }

    // Toggle BLE scanning
    val toggleScan = {
        if (!hasBlePermissions) {
            blePermissionsLauncher.launch(BleConstants.neededPermissions)
        } else {
            if (isScanning) {
                scanner.stopScanning()
                isScanning = false
            } else {
                scanner.startScanning()
                isScanning = true
            }
        }
    }

    // Toggle BLE broadcasting
    val toggleBroadcast = {
        if (!hasBlePermissions) {
            blePermissionsLauncher.launch(BleConstants.neededPermissions)
        } else {
            if (isBroadcasting) {
                advertiser.stopBroadcasting()
                isBroadcasting = false
            } else {
                advertiser.startBroadcasting(
                    selectedAdvMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
                    selectedTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
                    broadcastToken = "LAB:$myDeviceShortId"
                )
                isBroadcasting = true
            }
        }
    }

    // Toggle Full Link (both Scan + Broadcast simultaneously)
    val toggleFullLink = {
        if (!hasBlePermissions) {
            blePermissionsLauncher.launch(BleConstants.neededPermissions)
        } else {
            if (isScanning || isBroadcasting) {
                scanner.stopScanning()
                advertiser.stopBroadcasting()
                isScanning = false
                isBroadcasting = false
            } else {
                advertiser.startBroadcasting(
                    selectedAdvMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY,
                    selectedTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
                    broadcastToken = "LAB:$myDeviceShortId"
                )
                scanner.startScanning()
                isScanning = true
                isBroadcasting = true
            }
        }
    }

    val sortedPeers = discoveredPeers.values.sortedByDescending { it.confirmedMedianRssi }
    val primaryPeer = sortedPeers.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Permission check banner
        if (!hasBlePermissions) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0x28FF1744),
                border = BorderStroke(1.dp, Color(0xFFFF1744)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Bluetooth Permissions Required",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF1744)
                        )
                        Text(
                            text = "Needed to scan and advertise 2.4 GHz proximity packets",
                            fontSize = 10.sp,
                            color = Color(0xFFCFD8DC)
                        )
                    }
                    Button(
                        onClick = { blePermissionsLauncher.launch(BleConstants.neededPermissions) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Grant", fontSize = 11.sp)
                    }
                }
            }
        } else if (!isBtEnabled) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0x28FFB300),
                border = BorderStroke(1.dp, Color(0xFFFFB300)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚠️ Bluetooth is turned off. Please enable Bluetooth to test proximity.",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFB300)
                    )
                }
            }
        }

        // Hero Card: Primary Target Peer (Closest / Highest Signal)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = when {
                primaryPeer == null -> Color(0xFF0F171C)
                primaryPeer.proximityZone == BleProximityZone.IMMEDIATE_DESK -> Color(0x2400F5A0)
                primaryPeer.proximityZone == BleProximityZone.ADJACENT_BENCH_TRIGGER -> Color(0x1800E5FF)
                else -> Color(0xFF0F171C)
            },
            border = BorderStroke(
                1.5.dp,
                when {
                    primaryPeer == null -> Color(0xFF1E2D38)
                    primaryPeer.proximityZone == BleProximityZone.IMMEDIATE_DESK -> Color(0xFF00F5A0)
                    primaryPeer.proximityZone == BleProximityZone.ADJACENT_BENCH_TRIGGER -> Color(0xFF00E5FF)
                    else -> Color(0xFF1E2D38)
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "BLE RADIO PROXIMITY ESTIMATE",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = Color(0xFF90A4AE)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Big Distance Readout
                Text(
                    text = if (primaryPeer != null) BleProximityEngine.formatDistance(primaryPeer.estimatedDistanceMeters) else "--",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = when {
                        primaryPeer == null -> Color(0xFF546E7A)
                        primaryPeer.proximityZone == BleProximityZone.IMMEDIATE_DESK -> Color(0xFF00F5A0)
                        primaryPeer.proximityZone == BleProximityZone.ADJACENT_BENCH_TRIGGER -> Color(0xFF00E5FF)
                        else -> Color(0xFF90A4AE)
                    }
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Proximity Badge
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = when {
                        primaryPeer == null -> Color(0x2290A4AE)
                        primaryPeer.proximityZone == BleProximityZone.IMMEDIATE_DESK -> Color(0x3300F5A0)
                        primaryPeer.proximityZone == BleProximityZone.ADJACENT_BENCH_TRIGGER -> Color(0x3300E5FF)
                        else -> Color(0x2290A4AE)
                    },
                    border = BorderStroke(
                        1.dp,
                        when {
                            primaryPeer == null -> Color(0xFF546E7A)
                            primaryPeer.proximityZone == BleProximityZone.IMMEDIATE_DESK -> Color(0xFF00F5A0)
                            primaryPeer.proximityZone == BleProximityZone.ADJACENT_BENCH_TRIGGER -> Color(0xFF00E5FF)
                            else -> Color(0xFF546E7A)
                        }
                    )
                ) {
                    Text(
                        text = when {
                            primaryPeer == null -> if (isScanning) "SCANNING 2.4 GHz CHANNELS..." else "BLE RADAR STANDBY"
                            else -> "${primaryPeer.proximityZone.label} • PEER: ${primaryPeer.peerId}"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            primaryPeer == null -> Color(0xFF90A4AE)
                            primaryPeer.proximityZone == BleProximityZone.IMMEDIATE_DESK -> Color(0xFF00F5A0)
                            primaryPeer.proximityZone == BleProximityZone.ADJACENT_BENCH_TRIGGER -> Color(0xFF00E5FF)
                            else -> Color(0xFF90A4AE)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Dual RSSI Display: Confirmed Median vs Instantaneous Live
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (primaryPeer != null) "Confirmed Median: ${primaryPeer.confirmedMedianRssi} dBm" else "Confirmed Median: -- dBm",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (primaryPeer?.isUltrasonicEligible == true) Color(0xFF00F5A0) else Color(0xFF00E5FF)
                        )
                        Text(
                            text = if (primaryPeer != null) "Live: ${primaryPeer.lastRssi} dBm (${primaryPeer.sampleCount} pkts)" else "Live: -- dBm",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF90A4AE)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    val rssiNorm = primaryPeer?.let { ((it.confirmedMedianRssi + 95).coerceIn(0, 55) / 55f) } ?: 0f
                    LinearProgressIndicator(
                        progress = { rssiNorm },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = when {
                            primaryPeer?.proximityZone == BleProximityZone.IMMEDIATE_DESK -> Color(0xFF00F5A0)
                            primaryPeer?.proximityZone == BleProximityZone.ADJACENT_BENCH_TRIGGER -> Color(0xFF00E5FF)
                            else -> Color(0xFF546E7A)
                        },
                        trackColor = Color(0xFF1E2D38)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Layer 4 Handshake Eligibility Tag
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (primaryPeer?.isUltrasonicEligible == true) Color(0x1800F5A0) else Color(0x1800E5FF),
                    border = BorderStroke(1.dp, if (primaryPeer?.isUltrasonicEligible == true) Color(0x4400F5A0) else Color(0x4400E5FF)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (primaryPeer?.isUltrasonicEligible == true)
                            "⚡ RSSI >= -70 dBm: ELIGIBLE FOR LAYER 4 ULTRASONIC HANDSHAKE"
                        else if (primaryPeer != null)
                            "NORMAL SEPARATION (RSSI < -70 dBm, Layer 4 Handshake Idle)"
                        else
                            "My Broadcast ID: LAB:$myDeviceShortId (TX_POWER_LOW)",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (primaryPeer?.isUltrasonicEligible == true) Color(0xFF00F5A0) else Color(0xFF00E5FF),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }

        // Action Controls (Scanner, Advertiser, Full Link)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = toggleScan,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScanning) Color(0xFF00C853) else Color(0xFF1E2D38)
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isScanning) Icons.Default.Stop else Icons.AutoMirrored.Filled.BluetoothSearching,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isScanning) "Stop Radar" else "Start Radar",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Button(
                onClick = toggleBroadcast,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBroadcasting) Color(0xFFFF9100) else Color(0xFF1E2D38)
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isBroadcasting) Icons.Default.Stop else Icons.Default.Sensors,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isBroadcasting) "Broadcasting" else "Broadcast Probe",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // One-Tap Mutual Link & Clear Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = toggleFullLink,
                modifier = Modifier
                    .weight(2f)
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isScanning && isBroadcasting) Color(0xFF6200EA) else Color(0xFF0091EA)
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BluetoothConnected,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isScanning && isBroadcasting) "Stop Full Link (Scan+Tx)" else "One-Tap Full Link (Scan+Tx)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (discoveredPeers.isNotEmpty()) {
                OutlinedButton(
                    onClick = { discoveredPeers.clear() },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Clear", fontSize = 11.sp, color = Color(0xFF90A4AE))
                }
            }
        }

        // BLE Radio Channel Specification Card
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF0F171C),
            border = BorderStroke(1.dp, Color(0xFF1E2D38)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "BLE RADIO CHANNEL SPECIFICATION",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    color = Color(0xFF90A4AE)
                )
                SpecMetricRow("Radio Frequency", "2.4 GHz ISM Band (Bluetooth 4.2 / 5.0 LE)")
                SpecMetricRow("Service UUID", "00001234-0000-1000-8000-00805F9B34FB")
                SpecMetricRow("Path-Loss Model", "Log-Distance Path Loss (n = 2.2, Ref = -65 dBm @ 1m)")
                SpecMetricRow("Confirmed Median Logic", "Sorted sliding median filter (exact main app match)")
                SpecMetricRow("Layer 4 Handshake Gate", "Confirmed Median RSSI >= -70 dBm (~1.6 m)")
                SpecMetricRow("My Broadcast Probe", "LAB:$myDeviceShortId (TX_POWER_LOW)")
            }
        }

        // Live Discovered Peers List
        Text(
            text = "DISCOVERED RADIO PEERS (${discoveredPeers.size})",
            fontSize = 11.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp,
            color = Color(0xFF90A4AE)
        )

        if (discoveredPeers.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF0F171C),
                border = BorderStroke(1.dp, Color(0xFF1E2D38)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No BLE radio peers detected yet.\nTap 'One-Tap Full Link' on both phones to mutually scan and broadcast.",
                        fontSize = 11.sp,
                        color = Color(0xFF546E7A),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            sortedPeers.forEach { peer ->
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = when (peer.proximityZone) {
                        BleProximityZone.IMMEDIATE_DESK -> Color(0x2000F5A0)
                        BleProximityZone.ADJACENT_BENCH_TRIGGER -> Color(0x1800E5FF)
                        else -> Color(0xFF0F171C)
                    },
                    border = BorderStroke(
                        1.dp,
                        when (peer.proximityZone) {
                            BleProximityZone.IMMEDIATE_DESK -> Color(0xFF00F5A0)
                            BleProximityZone.ADJACENT_BENCH_TRIGGER -> Color(0xFF00E5FF)
                            else -> Color(0xFF1E2D38)
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = peer.peerId,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (peer.isUltrasonicEligible) Color(0x3300F5A0) else Color(0x2290A4AE)
                                ) {
                                    Text(
                                        text = if (peer.isUltrasonicEligible) "L4 TRIGGER" else "DISTANT",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (peer.isUltrasonicEligible) Color(0xFF00F5A0) else Color(0xFF90A4AE),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "${peer.sampleCount} samples • Min: ${peer.minRssi} / Max: ${peer.maxRssi} dBm",
                                fontSize = 10.sp,
                                color = Color(0xFF90A4AE)
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = BleProximityEngine.formatDistance(peer.estimatedDistanceMeters),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = when (peer.proximityZone) {
                                    BleProximityZone.IMMEDIATE_DESK -> Color(0xFF00F5A0)
                                    BleProximityZone.ADJACENT_BENCH_TRIGGER -> Color(0xFF00E5FF)
                                    else -> Color.White
                                }
                            )
                            Text(
                                text = "Median: ${peer.confirmedMedianRssi} dBm (Live: ${peer.lastRssi})",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (peer.isUltrasonicEligible) Color(0xFF00F5A0) else Color(0xFF00E5FF)
                            )
                        }
                    }
                }
            }
        }
    }
}

// Helper spec row formatter
@Composable
private fun SpecMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 11.sp, color = Color(0xFF90A4AE))
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

// Event data class representing a single detected acoustic ping
data class AcousticPingEvent(
    val timestamp: String,
    val snrDb: Double,
    val powerDb: Double = 0.0,
    val distanceCm: Double,
    val isSameDesk: Boolean
)
