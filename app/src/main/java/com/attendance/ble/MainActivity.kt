package com.attendance.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.attendance.ble.ble.BleAdvertiser
import com.attendance.ble.ble.BleConstants
import com.attendance.ble.ble.BleScanner
import com.attendance.ble.ui.screens.BleDebuggerScreen
import com.attendance.ble.viewmodel.AttendanceViewModel
import com.attendance.ble.viewmodel.StudentStatus

class MainActivity : ComponentActivity() {

    private val viewModel: AttendanceViewModel by viewModels()
    private val sessionHandler = Handler(Looper.getMainLooper())

    private val bleAdvertiser by lazy {
        BleAdvertiser(this) { isBroadcasting ->
            viewModel.isBroadcasting = isBroadcasting
        }
    }

    private val bleScanner by lazy {
        BleScanner(
            context = this,
            onScanningChanged = { isScanning ->
                viewModel.isScanning = isScanning
            },
            onPacketReceived = { rssi, latencyMs, messageText ->
                viewModel.onPacketReceived(rssi, latencyMs, messageText)
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // AUTO-ACTION: Student broadcasts its 6-byte probe ping (P:<myDeviceID>) during its assigned cohort slot
        viewModel.onBroadcastProbe = { _ ->
            val probeToken = "P:${viewModel.myDeviceID}"
            try {
                bleScanner.stopScanning()
            } catch (_: Exception) {}

            // Student probe uses LOW TX power to ensure near-field physical attenuation (<= 1.5m)
            bleAdvertiser.startBroadcasting(
                viewModel.selectedAdvMode,
                AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
                probeToken
            )

            // Broadcast probe for 1600ms, then resume scanning
            sessionHandler.postDelayed({
                try {
                    bleAdvertiser.stopBroadcasting()
                } catch (_: Exception) {}
                try {
                    bleScanner.startScanning()
                } catch (_: Exception) {}
            }, 1600)
        }

        // AUTO-ACTION: Student broadcasts its top closest neighbors (R:<myID>:<p1>:<p2>:<p3>) to Teacher
        viewModel.onBroadcastReport = { topPeers ->
            val jitter = kotlin.random.Random.nextLong(50, 350)
            sessionHandler.postDelayed({
                try {
                    bleScanner.stopScanning()
                } catch (_: Exception) {}

                val reportToken = if (topPeers.isEmpty()) {
                    "R:${viewModel.myDeviceID}"
                } else {
                    "R:${viewModel.myDeviceID}:${topPeers.joinToString(":")}"
                }

                // Report to teacher uses HIGH TX power to reliably reach the podium at the front
                bleAdvertiser.startBroadcasting(
                    viewModel.selectedAdvMode,
                    AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
                    reportToken
                )

                // Broadcast report for 2000ms, then resume scanning to receive verdict
                sessionHandler.postDelayed({
                    try {
                        bleAdvertiser.stopBroadcasting()
                    } catch (_: Exception) {}
                    try {
                        bleScanner.startScanning()
                    } catch (_: Exception) {}
                }, 2000)
            }, jitter)
        }

        setContent {
            val context = androidx.compose.ui.platform.LocalContext.current

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { }

            val enableBluetoothLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { }

            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

            val checkAndExecute = { action: () -> Unit ->
                val hasPermissions = BleConstants.neededPermissions.all { perm ->
                    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                }
                if (!hasPermissions) {
                    permissionLauncher.launch(BleConstants.neededPermissions)
                } else {
                    try {
                        if (bluetoothManager.adapter?.isEnabled == false) {
                            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            enableBluetoothLauncher.launch(enableBtIntent)
                        } else {
                            action()
                        }
                    } catch (_: SecurityException) {
                        permissionLauncher.launch(BleConstants.neededPermissions)
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.White
            ) {
                BleDebuggerScreen(
                    myDeviceID = viewModel.myDeviceID,
                    cohort4 = viewModel.cohort4,
                    cohort3 = viewModel.cohort3,
                    isTeacher = viewModel.isTeacher,
                    isBroadcasting = viewModel.isBroadcasting,
                    isScanning = viewModel.isScanning,
                    currentPhase = viewModel.currentPhase,
                    sessionStatus = viewModel.sessionStatus,
                    myAttendanceStatus = viewModel.myAttendanceStatus,
                    myReportedPeers = viewModel.myReportedPeers,
                    discoveredPeers = viewModel.discoveredPeers,
                    auditResults = viewModel.auditResults,
                    verifiedCount = viewModel.verifiedCount,
                    spooferCount = viewModel.spooferCount,
                    isolatedCount = viewModel.isolatedCount,
                    avgLatencyMs = viewModel.avgLatencyMs,
                    scanLogs = viewModel.scanLogs,
                    onToggleRole = {
                        stopAllRadio()
                        viewModel.isTeacher = !viewModel.isTeacher
                        viewModel.resetSession()
                    },
                    onStartTeacherSession = {
                        checkAndExecute { startTeacherMeshSession() }
                    },
                    onStopTeacherSession = {
                        stopAllRadio()
                        viewModel.resetSession()
                    },
                    onToggleStudentScan = {
                        checkAndExecute {
                            if (viewModel.isScanning) {
                                bleScanner.stopScanning()
                            } else {
                                viewModel.resetSession()
                                bleScanner.startScanning()
                            }
                        }
                    },
                    onResetSession = {
                        stopAllRadio()
                        viewModel.resetLogs()
                    },
                    onCheckPermissions = {
                        permissionLauncher.launch(BleConstants.neededPermissions)
                    }
                )
            }
        }
    }

    // TEACHER ORCHESTRATION: Conducts Round 1 (Mod 4), Round 2 (Mod 3), and Report Collection
    private fun startTeacherMeshSession() {
        stopAllRadio()
        viewModel.resetSession()
        viewModel.isTeacher = true
        viewModel.sessionStatus = "ORCHESTRATING MESH ATTENDANCE..."

        val slotDurationMs = 1800L
        val slotIntervalMs = 2000L

        fun broadcastTick(tickToken: String, nextAction: () -> Unit) {
            try {
                bleScanner.stopScanning()
            } catch (_: Exception) {}

            bleAdvertiser.startBroadcasting(
                viewModel.selectedAdvMode,
                viewModel.selectedTxPower,
                tickToken
            )

            sessionHandler.postDelayed({
                try {
                    bleAdvertiser.stopBroadcasting()
                } catch (_: Exception) {}
                nextAction()
            }, slotDurationMs)
        }

        // --- ROUND 1: 4 COHORTS (Mod 4) ---
        // T = 0.0s: Cohort 0
        broadcastTick("TICK:R1:G0") {
            sessionHandler.postDelayed({
                // T = 2.0s: Cohort 1
                broadcastTick("TICK:R1:G1") {
                    sessionHandler.postDelayed({
                        // T = 4.0s: Cohort 2
                        broadcastTick("TICK:R1:G2") {
                            sessionHandler.postDelayed({
                                // T = 6.0s: Cohort 3
                                broadcastTick("TICK:R1:G3") {
                                    sessionHandler.postDelayed({
                                        startRound2()
                                    }, slotIntervalMs - slotDurationMs)
                                }
                            }, slotIntervalMs - slotDurationMs)
                        }
                    }, slotIntervalMs - slotDurationMs)
                }
            }, slotIntervalMs - slotDurationMs)
        }
    }

    // --- ROUND 2: 3 COHORTS (Mod 3) --- (Guarantees cross-scanning for intra-group peers)
    private fun startRound2() {
        val slotDurationMs = 1800L
        val slotIntervalMs = 2000L

        fun broadcastTick(tickToken: String, nextAction: () -> Unit) {
            bleAdvertiser.startBroadcasting(
                viewModel.selectedAdvMode,
                viewModel.selectedTxPower,
                tickToken
            )
            sessionHandler.postDelayed({
                try {
                    bleAdvertiser.stopBroadcasting()
                } catch (_: Exception) {}
                nextAction()
            }, slotDurationMs)
        }

        // T = 8.0s: Cohort 0
        broadcastTick("TICK:R2:H0") {
            sessionHandler.postDelayed({
                // T = 10.0s: Cohort 1
                broadcastTick("TICK:R2:H1") {
                    sessionHandler.postDelayed({
                        // T = 12.0s: Cohort 2
                        broadcastTick("TICK:R2:H2") {
                            sessionHandler.postDelayed({
                                startReportCollectionPhase()
                            }, slotIntervalMs - slotDurationMs)
                        }
                    }, slotIntervalMs - slotDurationMs)
                }
            }, slotIntervalMs - slotDurationMs)
        }
    }

    // --- ROUND 3: REPORT COLLECTION & AUDIT ---
    private fun startReportCollectionPhase() {
        // T = 14.0s: Broadcast COLLECT_REPORTS for 1.2s
        viewModel.sessionStatus = "TRIGGERING REPORT COLLECTION..."
        bleAdvertiser.startBroadcasting(
            viewModel.selectedAdvMode,
            viewModel.selectedTxPower,
            "COLLECT_REPORTS"
        )

        sessionHandler.postDelayed({
            try {
                bleAdvertiser.stopBroadcasting()
            } catch (_: Exception) {}

            // T = 15.2s: Open Scanner for 3.5s to gather all student reports
            viewModel.sessionStatus = "SCANNING FOR STUDENT REPORTS..."
            viewModel.currentPhase = "COLLECTING REPORTS"
            bleScanner.startScanning()

            sessionHandler.postDelayed({
                try {
                    bleScanner.stopScanning()
                } catch (_: Exception) {}

                // T = 18.7s: Run Graph Audit
                viewModel.performMeshAudit()

                // T = 19.0s: Broadcast Verdict Pings for verified students
                broadcastVerdicts()
            }, 3500)
        }, 1200)
    }

    private fun broadcastVerdicts() {
        val verifiedIds = viewModel.auditResults
            .filter { it.status == StudentStatus.VERIFIED_PRESENT }
            .map { it.studentId }

        if (verifiedIds.isNotEmpty()) {
            // Group verified IDs into batches of up to 4 (e.g. OK:A4B1:C2D3:E5F6 fits in 21 bytes)
            val chunks = verifiedIds.chunked(4)
            var chunkIndex = 0

            fun broadcastChunk() {
                if (chunkIndex < chunks.size) {
                    val chunk = chunks[chunkIndex++]
                    val token = "OK:${chunk.joinToString(":")}"
                    bleAdvertiser.startBroadcasting(
                        viewModel.selectedAdvMode,
                        AdvertiseSettings.ADVERTISE_TX_POWER_HIGH,
                        token
                    )
                    // Broadcast each chunk for 1800ms so student phones reliably catch it!
                    sessionHandler.postDelayed({
                        try {
                            bleAdvertiser.stopBroadcasting()
                        } catch (_: Exception) {}
                        sessionHandler.postDelayed({ broadcastChunk() }, 150)
                    }, 1800)
                } else {
                    viewModel.sessionStatus = "SESSION COMPLETE: ${verifiedIds.size} VERIFIED"
                }
            }
            broadcastChunk()
        }
    }

    private fun stopAllRadio() {
        sessionHandler.removeCallbacksAndMessages(null)
        try {
            bleAdvertiser.stopBroadcasting()
        } catch (_: Exception) {}
        try {
            bleScanner.stopScanning()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAllRadio()
    }
}
