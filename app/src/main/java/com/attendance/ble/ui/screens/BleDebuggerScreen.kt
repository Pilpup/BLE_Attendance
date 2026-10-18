package com.attendance.ble.ui.screens

import android.bluetooth.le.AdvertiseSettings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendance.ble.ui.theme.*
import com.attendance.ble.viewmodel.PeerDiscovery
import com.attendance.ble.viewmodel.StudentAuditRecord
import com.attendance.ble.viewmodel.StudentStatus

@Composable
fun BleDebuggerScreen(
    myDeviceID: String,
    cohort4: Int,
    cohort3: Int,
    isTeacher: Boolean,
    isBroadcasting: Boolean,
    isScanning: Boolean,
    currentPhase: String,
    sessionStatus: String,
    myAttendanceStatus: StudentStatus,
    myReportedPeers: List<String>,
    discoveredPeers: Map<String, PeerDiscovery>,
    auditResults: List<StudentAuditRecord>,
    verifiedCount: Int,
    spooferCount: Int,
    isolatedCount: Int,
    avgLatencyMs: Double,
    scanLogs: List<String>,
    onToggleRole: () -> Unit,
    onStartTeacherSession: () -> Unit,
    onStopTeacherSession: () -> Unit,
    onToggleStudentScan: () -> Unit,
    onResetSession: () -> Unit,
    onCheckPermissions: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(appBg)
            .verticalScroll(scrollState)
            .padding(14.dp)
    ) {
        // 1. TOP HEADER & IDENTITY
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "BLE Mesh Attendance",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimary
                )
                Text(
                    text = "Peer-to-Peer Reciprocal Proximity",
                    fontSize = 12.sp,
                    color = textSecondary
                )
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = primaryBlueBg,
                border = BorderStroke(1.dp, primaryBlue.copy(alpha = 0.3f))
            ) {
                Text(
                    text = "ID: $myDeviceID",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = primaryBlue
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. ROLE TOGGLE & ACTIVE COHORTS
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(if (isTeacher) childPurple else successGreen, CircleShape)
                        )
                        Text(
                            text = if (isTeacher) "ROLE: TEACHER (ORCHESTRATOR)" else "ROLE: STUDENT (MESH NODE)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (isTeacher) childPurple else successGreen
                        )
                    }

                    OutlinedButton(
                        onClick = onToggleRole,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, cardBorder)
                    ) {
                        Text(
                            text = if (isTeacher) "Switch to Student" else "Switch to Teacher",
                            fontSize = 11.sp,
                            color = textSecondary
                        )
                    }
                }

                if (!isTeacher) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Assigned Cohorts: Round 1 -> Cohort #$cohort4 (Mod 4) | Round 2 -> Cohort #$cohort3 (Mod 3)",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = textMuted
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3. SESSION STATUS & PHASE TIMELINE
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    myAttendanceStatus == StudentStatus.VERIFIED_PRESENT -> successGreenBg
                    myAttendanceStatus == StudentStatus.SPOOFER_REJECTED -> alertRedBg
                    isBroadcasting || isScanning -> primaryBlueBg
                    else -> cardBg
                }
            ),
            border = BorderStroke(
                1.dp,
                when {
                    myAttendanceStatus == StudentStatus.VERIFIED_PRESENT -> successGreen
                    myAttendanceStatus == StudentStatus.SPOOFER_REJECTED -> alertRed
                    isBroadcasting || isScanning -> primaryBlue
                    else -> cardBorder
                }
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PHASE: $currentPhase",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = when {
                            myAttendanceStatus == StudentStatus.VERIFIED_PRESENT -> successGreen
                            myAttendanceStatus == StudentStatus.SPOOFER_REJECTED -> alertRed
                            else -> primaryBlue
                        }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (avgLatencyMs > 0.0) {
                            Text(
                                text = "lat: ${"%.0f".format(avgLatencyMs)}ms",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = textMuted
                            )
                        }
                        Text(
                            text = if (isBroadcasting) "[TX ACTIVE]" else if (isScanning) "[RX ACTIVE]" else "[IDLE]",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = if (isBroadcasting) childPurple else if (isScanning) successGreen else textMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = sessionStatus,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. ACTION BUTTONS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isTeacher) {
                Button(
                    onClick = {
                        if (isBroadcasting || isScanning) onStopTeacherSession() else onStartTeacherSession()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isBroadcasting || isScanning) alertRed else primaryBlue
                    )
                ) {
                    Text(
                        text = if (isBroadcasting || isScanning) "Stop Session" else "Start Mesh Attendance",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            } else {
                Button(
                    onClick = onToggleStudentScan,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isScanning) alertRed else successGreen
                    )
                ) {
                    Text(
                        text = if (isScanning) "Stop Listening" else "Listen for Teacher",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            OutlinedButton(
                onClick = onResetSession,
                modifier = Modifier
                    .weight(0.5f)
                    .height(44.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, cardBorder)
            ) {
                Text(text = "Reset", fontSize = 12.sp, color = textSecondary)
            }

            OutlinedButton(
                onClick = onCheckPermissions,
                modifier = Modifier
                    .weight(0.5f)
                    .height(44.dp),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, cardBorder)
            ) {
                Text(text = "Perms", fontSize = 12.sp, color = textSecondary)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 5. TEACHER VIEW: METRICS & MESH AUDIT RESULTS
        if (isTeacher) {
            Text(
                text = "Classroom Mesh Audit Roster:",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = textPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))

            // Metrics Summary Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricChip(label = "Verified", count = verifiedCount, color = successGreen, bg = successGreenBg, modifier = Modifier.weight(1f))
                MetricChip(label = "Spoofers", count = spooferCount, color = alertRed, bg = alertRedBg, modifier = Modifier.weight(1f))
                MetricChip(label = "Isolated", count = isolatedCount, color = warningOrange, bg = warningOrangeBg, modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (auditResults.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = BorderStroke(1.dp, cardBorder)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No audit data yet. Tap 'Start Mesh Attendance' to begin.",
                            fontSize = 12.sp,
                            color = textMuted
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    auditResults.forEach { record ->
                        StudentAuditCard(record)
                    }
                }
            }
        } else {
            // 6. STUDENT VIEW: DISCOVERED NEARBY PEERS
            Text(
                text = "Discovered Nearby Peers (${discoveredPeers.size}):",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = textPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (discoveredPeers.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = BorderStroke(1.dp, cardBorder)
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Listening for nearby classmates... No peers heard yet.",
                            fontSize = 12.sp,
                            color = textMuted
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    discoveredPeers.values.sortedByDescending { it.medianRssi }.forEach { peer ->
                        PeerDiscoveryCard(peer)
                    }
                }
            }

            if (myReportedPeers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = primaryBlueBg,
                    border = BorderStroke(1.dp, primaryBlue.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Reported to Teacher: [${myReportedPeers.joinToString(", ")}]",
                        modifier = Modifier.padding(10.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = primaryBlue
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 7. EVENT LOGS
        Text(
            text = "Radio Event Logs (${scanLogs.size}):",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = textPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = logBg),
            border = BorderStroke(1.dp, cardBorder)
        ) {
            if (scanLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(text = "No logs yet.", fontSize = 11.sp, color = textMuted)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(scanLogs.reversed()) { log ->
                        Text(
                            text = log,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = when {
                                log.contains("SPOOFER") || log.contains("ALERT") || log.contains("❌") -> alertRed
                                log.contains("VERIFIED") || log.contains("✅") -> successGreen
                                log.contains("REPORT") -> primaryBlue
                                log.contains("TICK") -> childPurple
                                else -> textPrimary
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricChip(label: String, count: Int, color: Color, bg: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = bg),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "$count", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
            Text(text = label, fontSize = 10.sp, color = color)
        }
    }
}

@Composable
private fun StudentAuditCard(record: StudentAuditRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(
            1.dp,
            when (record.status) {
                StudentStatus.VERIFIED_PRESENT -> successGreen.copy(alpha = 0.5f)
                StudentStatus.SPOOFER_REJECTED -> alertRed.copy(alpha = 0.5f)
                StudentStatus.ISOLATED_FLAGGED -> warningOrange.copy(alpha = 0.5f)
                else -> cardBorder
            }
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Student [${record.studentId}]",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = textPrimary
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = when (record.status) {
                        StudentStatus.VERIFIED_PRESENT -> successGreenBg
                        StudentStatus.SPOOFER_REJECTED -> alertRedBg
                        StudentStatus.ISOLATED_FLAGGED -> warningOrangeBg
                        else -> cardBg
                    }
                ) {
                    Text(
                        text = when (record.status) {
                            StudentStatus.VERIFIED_PRESENT -> "VERIFIED PRESENT"
                            StudentStatus.SPOOFER_REJECTED -> "SPOOFER REJECTED"
                            StudentStatus.ISOLATED_FLAGGED -> "ISOLATED SEAT"
                            else -> "PENDING"
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = when (record.status) {
                            StudentStatus.VERIFIED_PRESENT -> successGreen
                            StudentStatus.SPOOFER_REJECTED -> alertRed
                            StudentStatus.ISOLATED_FLAGGED -> warningOrange
                            else -> textMuted
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = record.reason,
                fontSize = 11.sp,
                color = textSecondary
            )

            if (record.mutualPeers.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val isBridge = record.reason.contains("Triadic closure")
                    record.mutualPeers.forEach { peer ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isBridge) primaryBlueBg else successGreenBg,
                            border = BorderStroke(1.dp, if (isBridge) primaryBlue.copy(alpha = 0.3f) else successGreen.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = "${if (isBridge) "⤹ Bridge: " else "↔ "}$peer",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isBridge) primaryBlue else successGreen
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PeerDiscoveryCard(peer: PeerDiscovery) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Peer [${peer.peerId}]",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = textPrimary
                )
                Text(
                    text = "${peer.count} packets heard",
                    fontSize = 11.sp,
                    color = textMuted
                )
            }

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = if (peer.medianRssi >= -70) successGreenBg else primaryBlueBg,
                border = BorderStroke(1.dp, if (peer.medianRssi >= -70) successGreen.copy(alpha = 0.3f) else primaryBlue.copy(alpha = 0.3f))
            ) {
                Text(
                    text = "${peer.medianRssi} dBm",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = if (peer.medianRssi >= -70) successGreen else primaryBlue
                )
            }
        }
    }
}
