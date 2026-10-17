package com.attendance.ble

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.bluetooth.BluetoothManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import com.attendance.ble.ble.BleAdvertiser
import com.attendance.ble.ble.BleConstants
import com.attendance.ble.ble.BleScanner
import com.attendance.ble.ui.screens.BleDebuggerScreen
import com.attendance.ble.ui.theme.*
import com.attendance.ble.viewmodel.AttendanceViewModel

// Power Child class of ComponentActivity
class MainActivity : ComponentActivity(){

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

    // parameter defintion is reverse order (name: type)
    // the ? tells compiler that this parameter is allowed to be null
    override fun onCreate(savedInstanceState: Bundle?){
        super.onCreate(savedInstanceState)

        // AUTO-FLIP: When Teacher elects this phone as Child 1, broadcast C1_PING then steady state CHILD_1 (or directly CHILD_1 if reassignment)
        viewModel.onBecomeChild1 = {
            val isReassignment = viewModel.lastMeasuredChild1Rssi != null
            viewModel.broadcastTokken = if (isReassignment) "CHILD_1" else "C1_PING"
            viewModel.isBroadcasterMode = true
            viewModel.sessionStatus = if (isReassignment) "CHILD 1: STEADY STATE" else "CHILD 1: BROADCASTING C1_PING..."

            // Keep scanning active so this phone can hear steady-state packets and/or reassignments
            try {
                bleScanner.startScanning()
            } catch (_: Exception) {}

            bleAdvertiser.startBroadcasting(
                viewModel.selectedAdvMode,
                viewModel.selectedTxPower,
                viewModel.broadcastTokken
            )

            if (!isReassignment) {
                // Broadcast C1_PING for 1.8s, then switch to steady-state CHILD_1 only if still C1
                sessionHandler.postDelayed({
                    if (viewModel.isChild1Beacon) {
                        try {
                            bleAdvertiser.stopBroadcasting()
                        } catch (_: Exception) {}
                        viewModel.broadcastTokken = "CHILD_1"
                        viewModel.sessionStatus = "CHILD 1: STEADY STATE"
                        bleAdvertiser.startBroadcasting(
                            viewModel.selectedAdvMode,
                            viewModel.selectedTxPower,
                            viewModel.broadcastTokken
                        )
                    }
                }, 1800)
            }
        }

        // AUTO-FLIP: When Teacher elects this phone as Child 2, broadcast steady state CHILD_2
        viewModel.onBecomeChild2 = {
            viewModel.broadcastTokken = "CHILD_2"
            viewModel.isBroadcasterMode = true
            viewModel.sessionStatus = "CHILD 2: STEADY STATE"
            try {
                bleScanner.startScanning()
            } catch (_: Exception) {}
            bleAdvertiser.startBroadcasting(
                viewModel.selectedAdvMode,
                viewModel.selectedTxPower,
                viewModel.broadcastTokken
            )
        }

        // AUTO-RELEASE: When this phone is demoted from beacon back to regular student
        viewModel.onReleaseAnchor = {
            try {
                bleAdvertiser.stopBroadcasting()
            } catch (_: Exception) {}
            viewModel.isBroadcasterMode = false
            try {
                bleScanner.startScanning()
            } catch (_: Exception) {}
        }

        // AUTO-RESPONSE: When student finishes sampling Teacher's ping, wait for teacher to enter scan mode (Phase 2), then report
        viewModel.onPingReceived = { measuredRssi ->
            viewModel.sessionStatus = "PING SAMPLED ($measuredRssi dBm): PREPARING REPORT..."
            // Jitter delay (0-300ms) to avoid ALOHA packet collisions
            val jitter = kotlin.random.Random.nextLong(0, 300)
            sessionHandler.postDelayed({
                bleScanner.stopScanning()
                val reportToken = "REPORT:${viewModel.myDeviceID}:$measuredRssi"
                viewModel.sessionStatus = "SENDING REPORT TO TEACHER..."
                bleAdvertiser.startBroadcasting(
                    viewModel.selectedAdvMode,
                    viewModel.selectedTxPower,
                    reportToken
                )
                sessionHandler.postDelayed({
                    bleAdvertiser.stopBroadcasting()
                    viewModel.sessionStatus = "REPORT SENT: AWAITING C1..."
                    bleScanner.startScanning()
                }, 2000)
            }, 600 + jitter)
        }

        // AUTO-RESPONSE: When student finishes sampling Child 1's ping (C1_PING), report SpatialDelta = measuredC1Rssi - teacherRssi
        // (RSSI_probe - RSSI_teacher) cancels individual device antenna bias completely!
        viewModel.onC1PingReceived = { measuredC1Rssi ->
            viewModel.sessionStatus = "C1 SAMPLED ($measuredC1Rssi dBm): PREPARING REPORT2..."
            val teacherRssi = viewModel.lastMeasuredTeacherRssi ?: -70
            val spatialDelta = measuredC1Rssi - teacherRssi
            val jitter = kotlin.random.Random.nextLong(0, 300)
            sessionHandler.postDelayed({
                bleScanner.stopScanning()
                val report2Token = "REPORT2:${viewModel.myDeviceID}:$spatialDelta"
                val deltaStr = if(spatialDelta > 0) "+$spatialDelta" else "$spatialDelta"
                viewModel.sessionStatus = "SENDING REPORT2 (Delta: $deltaStr dB)..."
                bleAdvertiser.startBroadcasting(
                    viewModel.selectedAdvMode,
                    viewModel.selectedTxPower,
                    report2Token
                )
                sessionHandler.postDelayed({
                    bleAdvertiser.stopBroadcasting()
                    viewModel.sessionStatus = "REPORT2 SENT: AWAITING C2..."
                    bleScanner.startScanning()
                }, 2000)
            }, 600 + jitter)
        }

        setContent {
            // screen context - physical address of the current screen
            val context = androidx.compose.ui.platform.LocalContext.current

            // permission block
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ){permission ->
                val allGranted = permission.values.all {it == true}
                if(allGranted){
                    println("User granted all permissions")
                }
            }

            // launcher that will ask OS to turn on bluetooth
            val enableBluetoothLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ){ }

            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

            val toggleBroadcast = {
                val hasPermissions = BleConstants.neededPermissions.all { perm ->
                    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                }

                if (!hasPermissions) {
                    android.widget.Toast.makeText(context, "Click Request Permissions first", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    try {
                        if (bluetoothManager.adapter?.isEnabled == false) {
                            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            enableBluetoothLauncher.launch(enableBtIntent)
                        } else {
                            if (!viewModel.isBroadcasting && !viewModel.isScanning) {
                                startAutoAttendanceSession()
                            } else {
                                stopAutoAttendanceSession()
                            }
                        }
                    } catch (e: SecurityException) {
                        android.widget.Toast.makeText(context, "Click Request Permissions first", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }

            val toggleScan = {
                val hasPermissions = BleConstants.neededPermissions.all { perm ->
                    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                }

                if (!hasPermissions) {
                    android.widget.Toast.makeText(context, "Click Request Permissions first", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    try {
                        if (bluetoothManager.adapter?.isEnabled == false) {
                            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            enableBluetoothLauncher.launch(enableBtIntent)
                        } else {
                            if (!viewModel.isScanning) {
                                viewModel.resetLogs()
                                bleScanner.startScanning()
                            } else {
                                bleScanner.stopScanning()
                            }
                        }
                    } catch (e: SecurityException) {
                        android.widget.Toast.makeText(context, "Bluetooth Permission error", android.widget.Toast.LENGTH_LONG).show()
                        viewModel.isScanning = false
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.White
            ){
                BleDebuggerScreen(
                    myDeviceID = viewModel.myDeviceID,
                    isBroadcasting = viewModel.isBroadcasting,
                    isScanning = viewModel.isScanning,
                    broadcastTokken = viewModel.broadcastTokken,
                    sessionStatus = viewModel.sessionStatus,
                    isChildBeacon = viewModel.isChildBeacon,
                    electedChildId = viewModel.electedChildId,
                    electedChild1Id = viewModel.electedChild1Id,
                    electedChild2Id = viewModel.electedChild2Id,
                    selectedTxPower = viewModel.selectedTxPower,
                    selectedAdvMode = viewModel.selectedAdvMode,
                    teacherRssi = viewModel.teacherRssi,
                    teacherAvgRssi = viewModel.teacherAvgRssi,
                    teacherPacketCount = viewModel.teacherPacketCount,
                    teacherBufferStr = viewModel.teacherBufferString,
                    childRssi = viewModel.childRssi,
                    childAvgRssi = viewModel.childAvgRssi,
                    childPacketCount = viewModel.childPacketCount,
                    childBufferStr = viewModel.childBufferString,
                    child2Rssi = viewModel.child2Rssi,
                    child2AvgRssi = viewModel.child2AvgRssi,
                    child2PacketCount = viewModel.child2PacketCount,
                    child2BufferStr = viewModel.child2BufferString,
                    deltaRssi = viewModel.deltaRssi,
                    delta2Rssi = viewModel.delta2Rssi,
                    delta1Ratio = viewModel.delta1Ratio,
                    delta2Ratio = viewModel.delta2Ratio,
                    doorGateStatusText = viewModel.doorGateStatusText,
                    diagonalGateStatusText = viewModel.diagonalGateStatusText,
                    verificationStatus = viewModel.verificationStatus,
                    estimatedCoordX = viewModel.estimatedCoordX,
                    estimatedCoordY = viewModel.estimatedCoordY,
                    isCoordinatesInside = viewModel.isCoordinatesInside,
                    perimeterCutoffDbm = viewModel.perimeterCutoffDbm,
                    doorThresholdDbm = viewModel.doorThresholdDbm,
                    doorBackCutoffDbm = viewModel.doorBackCutoffDbm,
                    doorMaxDeltaDb = viewModel.doorMaxDeltaDb,
                    pathLossExponent = viewModel.pathLossExponent,
                    onAdjustPerimeter = { delta ->
                        viewModel.perimeterCutoffDbm = (viewModel.perimeterCutoffDbm + delta).coerceIn(-95.0, -60.0)
                    },
                    onAdjustDoorThreshold = { delta ->
                        viewModel.doorThresholdDbm = (viewModel.doorThresholdDbm + delta).coerceIn(-90.0, -55.0)
                    },
                    onAdjustDoorBackCutoff = { delta ->
                        viewModel.doorBackCutoffDbm = (viewModel.doorBackCutoffDbm + delta).coerceIn(-95.0, -65.0)
                    },
                    onAdjustDoorMaxDelta = { delta ->
                        viewModel.doorMaxDeltaDb = (viewModel.doorMaxDeltaDb + delta).coerceIn(6.0, 30.0)
                    },
                    onAdjustPathLoss = { delta ->
                        viewModel.pathLossExponent = (Math.round((viewModel.pathLossExponent + delta) * 10.0) / 10.0).coerceIn(1.5, 3.5)
                    },
                    onResetTuningDefaults = { viewModel.resetTuningDefaults() },
                    avgLatencyMs = viewModel.avgLatencyMs,
                    scanLogs = viewModel.scanLogs,
                    onToggleBroadcast = toggleBroadcast,
                    onToggleScan = toggleScan,
                    onResetLogs = { viewModel.resetLogs() },
                    onUpdateTxPower = { newPower ->
                        viewModel.selectedTxPower = newPower
                        if(viewModel.isBroadcasting){
                            bleAdvertiser.stopBroadcasting()
                            bleAdvertiser.startBroadcasting(
                                viewModel.selectedAdvMode,
                                viewModel.selectedTxPower,
                                viewModel.broadcastTokken
                            )
                        }
                    },
                    onUpdateAdvMode = { newMode ->
                        viewModel.selectedAdvMode = newMode
                        if(viewModel.isBroadcasting){
                            bleAdvertiser.stopBroadcasting()
                            bleAdvertiser.startBroadcasting(
                                viewModel.selectedAdvMode,
                                viewModel.selectedTxPower,
                                viewModel.broadcastTokken
                            )
                        }
                    },
                    onCheckPermissions = { permissionLauncher.launch(BleConstants.neededPermissions) }
                )
            }
        }
    }

    private fun startAutoAttendanceSession(){
        sessionHandler.removeCallbacksAndMessages(null)
        viewModel.isTeacherSession = true
        viewModel.collectedReports.clear()
        viewModel.collectedSpreadReports.clear()
        viewModel.electedChildId = null
        viewModel.electedChild1Id = null
        viewModel.electedChild2Id = null
        viewModel.sessionStatus = "PHASE 1: PINGING CLASS..."

        // Phase 1: Broadcast TEACHER_PING for 1.8 seconds (T = 0.0s to 1.8s)
        bleAdvertiser.startBroadcasting(
            viewModel.selectedAdvMode,
            viewModel.selectedTxPower,
            "TEACHER_PING"
        )

        // Phase 2: At T = 1.8s, switch to scanning for 2.5s to collect student reports (T = 1.8s to 4.3s)
        sessionHandler.postDelayed({
            bleAdvertiser.stopBroadcasting()
            viewModel.sessionStatus = "PHASE 2: GATHERING REPORTS..."
            bleScanner.startScanning()

            // Phase 3: At T = 4.3s (after 2.5s scan), pick Child 1 targeting the back perimeter cluster
            sessionHandler.postDelayed({
                bleScanner.stopScanning()

                // Diagnostic log for all candidates
                viewModel.collectedReports.forEach { report ->
                    val tRx = report.getTeacherRxMedian()
                    val biScore = report.bidirectionalScore
                    val count = report.teacherRxSamples.size
                    val log = "[C1 CANDIDATE] [${report.id}] BiDir: ${"%.1f".format(biScore)} dBm (TRx: $tRx dBm [$count pkts], SRx: ${report.reportedRssi} dBm)"
                    if(viewModel.scanLogs.size >= 50) viewModel.scanLogs.removeAt(0)
                    viewModel.scanLogs.add(log)
                }

                // Empirical back wall cluster: backbenchers read -66 to -76 dBm (waveguide along metal perimeters).
                // Center dead-zone reads -80+ dBm (destructive nulls + body shielding).
                // Doorway spoofers read > -62 dBm (close front uplink).
                val roomCandidates = viewModel.collectedReports.filter { it.bidirectionalScore <= -62.0 }
                val perimeterCandidates = roomCandidates.filter { it.bidirectionalScore in -77.0..-66.0 }

                val farthest = when {
                    perimeterCandidates.isNotEmpty() -> {
                        // Target the center of the back perimeter cluster (-71 dBm)
                        perimeterCandidates.minByOrNull { Math.abs(it.bidirectionalScore - (-71.0)) }
                    }
                    roomCandidates.isNotEmpty() -> {
                        // If room shifts, pick the least negative among the back candidates (avoiding the -80+ dead zone)
                        roomCandidates.maxByOrNull { it.bidirectionalScore }
                    }
                    else -> viewModel.collectedReports.minByOrNull { it.bidirectionalScore }
                }

                if(farthest != null){
                    viewModel.electedChildId = farthest.id
                    viewModel.electedChild1Id = farthest.id
                    viewModel.sessionStatus = "PHASE 3: ELECTED C1 [${farthest.id}]"
                    val winLog = "[★ ELECTED C1] [${farthest.id}] Score: ${"%.1f".format(farthest.bidirectionalScore)} dBm"
                    if(viewModel.scanLogs.size >= 50) viewModel.scanLogs.removeAt(0)
                    viewModel.scanLogs.add(winLog)

                    // Broadcast election packet for 1.0 second (T = 4.3s to 5.3s)
                    bleAdvertiser.startBroadcasting(
                        viewModel.selectedAdvMode,
                        viewModel.selectedTxPower,
                        "ELECT_C1:${farthest.id}"
                    )

                    // Phase 4: At T = 5.3s, stop electing C1. C1 will ping C1_PING.
                    sessionHandler.postDelayed({
                        bleAdvertiser.stopBroadcasting()
                        viewModel.sessionStatus = "PHASE 4: C1 PINGING CLASS..."

                        // If only 1 student was present in total, skip Phase 5 & 6 and jump to steady state
                        if(viewModel.collectedReports.size < 2){
                            sessionHandler.postDelayed({
                                startTeacherSteadyState("2-BEACON MODE: TEACHER + C1")
                            }, 1800)
                        } else {
                            // Phase 5: Start scanning at T = 5.5s (200ms after ELECT_C1 stops) to ensure Teacher catches all REPORT2 broadcasts
                            sessionHandler.postDelayed({
                                viewModel.sessionStatus = "PHASE 5: GATHERING REPORT2..."
                                bleScanner.startScanning()

                                // Phase 6: Scan for 3.5s (T = 5.5s to 9.0s), then pick Child 2
                                sessionHandler.postDelayed({
                                    bleScanner.stopScanning()

                                    val candidates = viewModel.collectedSpreadReports.filter { it.id != farthest.id }
                                    candidates.forEach { spread ->
                                        val tRx = spread.getTeacherRxMedian()
                                        val delta = spread.reportedSpreadScore
                                        val count = spread.teacherRxSamples.size
                                        val isHacker = delta <= -15 || tRx >= -62
                                        val tag = if (isHacker) "[⚠️ HACKER/DOORWAY]" else "[CANDIDATE]"
                                        val deltaStr = if(delta > 0) "+$delta" else "$delta"
                                        val log = "$tag [${spread.id}] SpatialDelta: $deltaStr dB | TRx: $tRx dBm ($count pkts)"
                                        if(viewModel.scanLogs.size >= 50) viewModel.scanLogs.removeAt(0)
                                        viewModel.scanLogs.add(log)
                                    }

                                    // Filter out blatant doorway hackers (delta <= -15 or TRx >= -62) when electing beacons
                                    val validCandidates = candidates.filter { it.reportedSpreadScore > -15 && it.getTeacherRxMedian() < -62 }
                                    val pool = if (validCandidates.isNotEmpty()) validCandidates else candidates

                                    if(pool.size >= 2){
                                        // Sort descending: highest spatial delta = farthest back from teacher relative to probe
                                        val sorted = pool.sortedByDescending { it.reportedSpreadScore }
                                        val cand0 = sorted[0]
                                        val cand1 = sorted[1]
                                        val scoreGap = cand0.reportedSpreadScore - cand1.reportedSpreadScore

                                        val cand0Str = if(cand0.reportedSpreadScore > 0) "+${cand0.reportedSpreadScore}" else "${cand0.reportedSpreadScore}"
                                        val cand1Str = if(cand1.reportedSpreadScore > 0) "+${cand1.reportedSpreadScore}" else "${cand1.reportedSpreadScore}"
                                        val gapLog = "[PERIMETER CHECK] Top: [${cand0.id}] (${cand0Str}dB) vs [${cand1.id}] (${cand1Str}dB) | Gap: ${scoreGap} dB"
                                        if(viewModel.scanLogs.size >= 50) viewModel.scanLogs.removeAt(0)
                                        viewModel.scanLogs.add(gapLog)

                                        // If Probe was in the middle:
                                        // Both back corners hear probe well (cand1 >= -2 dB) and have symmetric separation (scoreGap <= 7 dB)
                                        val probeWasInMiddle = cand1.reportedSpreadScore >= -2 && scoreGap <= 7

                                        if(probeWasInMiddle){
                                            // Center Rejection: Probe was in the middle!
                                            // Demote farthest, elect cand0 as C1, cand1 as C2
                                            viewModel.electedChildId = cand0.id
                                            viewModel.electedChild1Id = cand0.id
                                            viewModel.electedChild2Id = cand1.id
                                            viewModel.sessionStatus = "PHASE 6: PROBE WAS CENTER -> ELECTING C1 [${cand0.id}] & C2 [${cand1.id}]"
                                            val electLog = "[★ CENTER REJECTED] Demoting [${farthest.id}]. Electing C1: [${cand0.id}], C2: [${cand1.id}]"
                                            if(viewModel.scanLogs.size >= 50) viewModel.scanLogs.removeAt(0)
                                            viewModel.scanLogs.add(electLog)

                                            // Broadcast ELECT_C1 to elect cand0 and demote probe farthest (1.0s)
                                            bleAdvertiser.startBroadcasting(
                                                viewModel.selectedAdvMode,
                                                viewModel.selectedTxPower,
                                                "ELECT_C1:${cand0.id}"
                                            )
                                            sessionHandler.postDelayed({
                                                bleAdvertiser.stopBroadcasting()
                                                // Broadcast ELECT_C2 to elect cand1 (1.0s)
                                                bleAdvertiser.startBroadcasting(
                                                    viewModel.selectedAdvMode,
                                                    viewModel.selectedTxPower,
                                                    "ELECT_C2:${cand1.id}"
                                                )
                                                sessionHandler.postDelayed({
                                                    startTeacherSteadyState("3-BEACON 2D MESH ACTIVE (CENTER REJECTED)")
                                                }, 1000)
                                            }, 1000)
                                        } else {
                                            // Probe was genuine corner! Keep farthest as C1, elect cand0 as C2
                                            viewModel.electedChild1Id = farthest.id
                                            viewModel.electedChild2Id = cand0.id
                                            viewModel.sessionStatus = "PHASE 6: PROBE CONFIRMED -> ELECTED C2 [${cand0.id}]"
                                            val c2WinLog = "[★ CORNER CONFIRMED] C1: [${farthest.id}], C2: [${cand0.id}] Delta: ${cand0Str} dB"
                                            if(viewModel.scanLogs.size >= 50) viewModel.scanLogs.removeAt(0)
                                            viewModel.scanLogs.add(c2WinLog)

                                            bleAdvertiser.startBroadcasting(
                                                viewModel.selectedAdvMode,
                                                viewModel.selectedTxPower,
                                                "ELECT_C2:${cand0.id}"
                                            )
                                            sessionHandler.postDelayed({
                                                startTeacherSteadyState("3-BEACON 2D MESH ACTIVE")
                                            }, 1000)
                                        }
                                    } else if(pool.size == 1){
                                        val bestChild2 = pool[0]
                                        viewModel.electedChild2Id = bestChild2.id
                                        viewModel.sessionStatus = "PHASE 6: ELECTED C2 [${bestChild2.id}]"
                                        val deltaStr = if(bestChild2.reportedSpreadScore > 0) "+${bestChild2.reportedSpreadScore}" else "${bestChild2.reportedSpreadScore}"
                                        val c2WinLog = "[★ ELECTED C2] [${bestChild2.id}] Delta: $deltaStr dB"
                                        if(viewModel.scanLogs.size >= 50) viewModel.scanLogs.removeAt(0)
                                        viewModel.scanLogs.add(c2WinLog)

                                        bleAdvertiser.startBroadcasting(
                                            viewModel.selectedAdvMode,
                                            viewModel.selectedTxPower,
                                            "ELECT_C2:${bestChild2.id}"
                                        )
                                        sessionHandler.postDelayed({
                                            startTeacherSteadyState("3-BEACON 2D MESH ACTIVE")
                                        }, 1000)
                                    } else {
                                        // Fallback to 2-beacon steady state if no REPORT2 arrived
                                        startTeacherSteadyState("2-BEACON FALLBACK (C2 TIMEOUT)")
                                    }
                                }, 3500)
                            }, 200)
                        }
                    }, 1000)
                } else {
                    // Fallback if no student was detected at all: broadcast TEACHER solo
                    startTeacherSteadyState("FALLBACK: TEACHER ALONE")
                }
            }, 2500)
        }, 1800)
    }

    private fun startTeacherSteadyState(status: String){
        bleAdvertiser.stopBroadcasting()
        bleScanner.stopScanning()
        viewModel.broadcastTokken = "TEACHER"
        viewModel.sessionStatus = status
        bleAdvertiser.startBroadcasting(
            viewModel.selectedAdvMode,
            viewModel.selectedTxPower,
            viewModel.broadcastTokken
        )
    }

    private fun stopAutoAttendanceSession(){
        sessionHandler.removeCallbacksAndMessages(null)
        bleAdvertiser.stopBroadcasting()
        bleScanner.stopScanning()
        viewModel.stopSession()
    }

    override fun onDestroy() {
        super.onDestroy()
        sessionHandler.removeCallbacksAndMessages(null)
        try {
            bleAdvertiser.stopBroadcasting()
        } catch (_: Exception) {}
        try {
            bleScanner.stopScanning()
        } catch (_: Exception) {}
    }
}
