package com.attendance.ble.ui.screens

import android.bluetooth.le.AdvertiseSettings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.attendance.ble.viewmodel.AttendanceViewModel

@Composable
fun BleDebuggerScreen(
    myDeviceID: String,
    isBroadcasting: Boolean,
    isScanning: Boolean,
    broadcastTokken: String,
    sessionStatus: String,
    isChildBeacon: Boolean,
    electedChildId: String?,
    electedChild1Id: String? = null,
    electedChild2Id: String? = null,
    selectedTxPower: Int,
    selectedAdvMode: Int,
    teacherRssi: Int?,
    teacherAvgRssi: Double,
    teacherPacketCount: Int,
    teacherBufferStr: String,
    childRssi: Int?,
    childAvgRssi: Double,
    childPacketCount: Int,
    childBufferStr: String,
    child2Rssi: Int?,
    child2AvgRssi: Double,
    child2PacketCount: Int,
    child2BufferStr: String,
    deltaRssi: Double?,
    delta2Rssi: Double?,
    delta1Ratio: Double?,
    delta2Ratio: Double?,
    doorGateStatusText: String,
    diagonalGateStatusText: String,
    verificationStatus: AttendanceViewModel.VerificationStatus,
    estimatedCoordX: Double?,
    estimatedCoordY: Double?,
    isCoordinatesInside: Boolean?,
    perimeterCutoffDbm: Double,
    doorThresholdDbm: Double,
    doorBackCutoffDbm: Double,
    doorMaxDeltaDb: Double,
    pathLossExponent: Double,
    onAdjustPerimeter: (Double) -> Unit,
    onAdjustDoorThreshold: (Double) -> Unit,
    onAdjustDoorBackCutoff: (Double) -> Unit,
    onAdjustDoorMaxDelta: (Double) -> Unit,
    onAdjustPathLoss: (Double) -> Unit,
    onResetTuningDefaults: () -> Unit,
    avgLatencyMs: Double,
    scanLogs: List<String>,
    onToggleBroadcast: () -> Unit,
    onToggleScan: () -> Unit,
    onResetLogs: () -> Unit,
    onUpdateTxPower: (Int) -> Unit,
    onUpdateAdvMode: (Int) -> Unit,
    onCheckPermissions: () -> Unit
){
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(scrollState)
            .padding(12.dp)
    ){
        // 1. SIMPLE HEADER
        Text(
            text = "BLE Attendance Test Tool",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Text(
            text = "Device ID: $myDeviceID",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF0055AA)
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Role & State
        val roleText = when {
            broadcastTokken == "CHILD_1" || (isChildBeacon && electedChild1Id == myDeviceID) -> "Child 1 Anchor (Back-Right)"
            broadcastTokken == "CHILD_2" || (isChildBeacon && electedChild2Id == myDeviceID) -> "Child 2 Anchor (Back-Left)"
            isBroadcasting -> "Teacher Broadcaster"
            isScanning -> "Student Receiver (Scanning)"
            else -> "Student Receiver (Standby)"
        }
        val roleColor = when {
            isBroadcasting -> Color(0xFF0055AA)
            isChildBeacon -> Color(0xFF2E7D32)
            isScanning -> Color(0xFF2E7D32)
            else -> Color.DarkGray
        }

        Text(
            text = "Role: $roleText",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = roleColor
        )
        Text(
            text = "Session Status: $sessionStatus",
            fontSize = 12.sp,
            color = Color(0xFF0055AA)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 2. MAIN BUTTONS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ){
            Button(
                onClick = onToggleBroadcast,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if(isBroadcasting) Color(0xFFCC0000) else Color(0xFF0055AA)
                )
            ){
                Text(
                    text = if(isBroadcasting) "Stop Session" else "Start Session",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }

            Button(
                onClick = onToggleScan,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if(isScanning) Color(0xFFCC0000) else Color(0xFF2E7D32)
                )
            ){
                Text(
                    text = if(isScanning) "Stop Scanner" else "Start Scanner",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ){
            OutlinedButton(
                onClick = onResetLogs,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, Color.Gray)
            ){
                Text("Clear Logs", fontSize = 12.sp, color = Color.Black)
            }

            OutlinedButton(
                onClick = onCheckPermissions,
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, Color.Gray)
            ){
                Text("Permissions", fontSize = 12.sp, color = Color.Black)
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)
        Spacer(modifier = Modifier.height(8.dp))

        // 3. SETTINGS: POWER AND RATE (Each row has 3 buttons with weight(1f) - NEVER OVERFLOWS)
        Text(
            text = "Broadcast Power:",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ){
            val powers = listOf(
                "Low" to AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
                "Medium" to AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
                "High" to AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
            )
            powers.forEach { (label, value) ->
                val isSelected = selectedTxPower == value
                Button(
                    onClick = { onUpdateTxPower(value) },
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if(isSelected) Color(0xFF0055AA) else Color(0xFFEEEEEE),
                        contentColor = if(isSelected) Color.White else Color.Black
                    ),
                    contentPadding = PaddingValues(0.dp)
                ){
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if(isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Broadcast Rate (Speed):",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )
        Spacer(modifier = Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ){
            val modes = listOf(
                "1 / sec" to AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
                "4 / sec" to AdvertiseSettings.ADVERTISE_MODE_BALANCED,
                "10 / sec" to AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            )
            modes.forEach { (label, value) ->
                val isSelected = selectedAdvMode == value
                Button(
                    onClick = { onUpdateAdvMode(value) },
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if(isSelected) Color(0xFF0055AA) else Color(0xFFEEEEEE),
                        contentColor = if(isSelected) Color.White else Color.Black
                    ),
                    contentPadding = PaddingValues(0.dp)
                ){
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if(isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)
        Spacer(modifier = Modifier.height(8.dp))

        // 4. BEACON SIGNALS (Clean structured text lines)
        Text(
            text = "Beacon Signals:",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Teacher Beacon Line
        val teacherLine = buildString {
            append("Teacher: ")
            if(teacherRssi != null){
                append("$teacherRssi dBm")
                if(teacherPacketCount > 0){
                    append(" (Avg: ${"%.1f".format(teacherAvgRssi)} dBm, $teacherPacketCount pkts)")
                    if(teacherBufferStr != "[]") append(" $teacherBufferStr")
                }
            } else {
                append("No signal")
            }
        }
        Text(
            text = teacherLine,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if(teacherRssi != null) Color(0xFF0055AA) else Color.Gray
        )

        // Child 1 Beacon Line
        val childLine = buildString {
            append("Child 1: ")
            if(childRssi != null){
                append("$childRssi dBm")
                if(childPacketCount > 0){
                    append(" (Avg: ${"%.1f".format(childAvgRssi)} dBm, $childPacketCount pkts)")
                    if(childBufferStr != "[]") append(" $childBufferStr")
                }
            } else {
                append("No signal")
            }
        }
        Text(
            text = childLine,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if(childRssi != null) Color(0xFF7A1FA2) else Color.Gray
        )

        // Child 2 Beacon Line
        val child2Line = buildString {
            append("Child 2: ")
            if(child2Rssi != null){
                append("$child2Rssi dBm")
                if(child2PacketCount > 0){
                    append(" (Avg: ${"%.1f".format(child2AvgRssi)} dBm, $child2PacketCount pkts)")
                    if(child2BufferStr != "[]") append(" $child2BufferStr")
                }
            } else {
                append("No signal")
            }
        }
        Text(
            text = child2Line,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if(child2Rssi != null) Color(0xFFE65100) else Color.Gray
        )

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)
        Spacer(modifier = Modifier.height(8.dp))

        // 5. 2D DIFFERENCE & GATES CHECK
        Text(
            text = "2D Difference & Gate Status:",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        val diffText = if(deltaRssi != null){
            "Diff (T - C1): %+.1f dB".format(deltaRssi) +
                    (delta1Ratio?.let { " (Ratio: ${"%.1f".format(it)}x)" } ?: "") +
                    if(avgLatencyMs > 0) " (Lat: ${"%.0f".format(avgLatencyMs)}ms)" else ""
        } else {
            "Diff (T - C1): Awaiting packets..."
        }
        Text(
            text = diffText,
            fontSize = 12.sp,
            color = Color.Black
        )

        if(delta2Rssi != null){
            Text(
                text = "Diff (T - C2): %+.1f dB".format(delta2Rssi) +
                        (delta2Ratio?.let { " (Ratio: ${"%.1f".format(it)}x)" } ?: ""),
                fontSize = 12.sp,
                color = Color.Black
            )
        }

        Spacer(modifier = Modifier.height(3.dp))

        // 2D Relative Classroom Coordinates
        val coordText = if(estimatedCoordX != null && estimatedCoordY != null){
            val xSide = when {
                estimatedCoordX < 0.35 -> " (Left)"
                estimatedCoordX > 0.65 -> " (Right)"
                else -> " (Center)"
            }
            val yDepth = when {
                estimatedCoordY < 0.0 -> " [OUTSIDE: Negative Y]"
                estimatedCoordY < 0.35 -> " [Front: Near Teacher]"
                estimatedCoordY > 0.65 -> " [Back: Near Anchors]"
                else -> " [Mid-Room]"
            }
            "2D Position: (X: ${"%.2f".format(estimatedCoordX)}, Y: ${"%.2f".format(estimatedCoordY)})$xSide$yDepth"
        } else {
            "2D Position: Waiting for 3 anchors..."
        }
        val coordColor = when {
            estimatedCoordY != null && estimatedCoordY < 0.0 -> Color(0xFFCC0000)
            isCoordinatesInside == true -> Color(0xFF2E7D32)
            else -> Color.DarkGray
        }
        Text(
            text = coordText,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = coordColor
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Gate status rows
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ){
            Text(
                text = "Collinear Door Gate: $doorGateStatusText",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if(doorGateStatusText.startsWith("FAIL")) Color(0xFFCC0000) else if(doorGateStatusText == "PASS") Color(0xFF2E7D32) else Color.DarkGray
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ){
            Text(
                text = "Diagonal Perimeter Gate: $diagonalGateStatusText",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if(diagonalGateStatusText.startsWith("FAIL")) Color(0xFFCC0000) else if(diagonalGateStatusText == "PASS") Color(0xFF2E7D32) else Color.DarkGray
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Verdict banner
        val (verdictText, verdictColor, verdictBg) = when(verificationStatus){
            AttendanceViewModel.VerificationStatus.STANDBY -> Triple(
                "VERDICT: STANDBY (Scanner Not Running)",
                Color.DarkGray,
                Color(0xFFF2F2F2)
            )
            AttendanceViewModel.VerificationStatus.CALIBRATING -> Triple(
                "VERDICT: CALIBRATING (Need 3+ packets from each beacon)",
                Color.DarkGray,
                Color(0xFFF2F2F2)
            )
            AttendanceViewModel.VerificationStatus.ANCHOR_NODE -> Triple(
                "VERDICT: ANCHOR TRANSMITTER ACTIVE",
                Color(0xFF0055AA),
                Color(0xFFE3F2FD)
            )
            AttendanceViewModel.VerificationStatus.VERIFIED_INSIDE_2D -> Triple(
                "VERDICT: INSIDE CLASSROOM (2D Mesh Verified)",
                Color(0xFF2E7D32),
                Color(0xFFEEFFEE)
            )
            AttendanceViewModel.VerificationStatus.VERIFIED_INSIDE_LINEAR -> Triple(
                "VERDICT: INSIDE CLASSROOM (2-Beacon Linear Fallback)",
                Color(0xFF2E7D32),
                Color(0xFFEEFFEE)
            )
            AttendanceViewModel.VerificationStatus.REJECTED_DOORWAY_SPOOFER -> Triple(
                "VERDICT: REJECTED - DOORWAY SPOOFER DETECTED!",
                Color(0xFFCC0000),
                Color(0xFFFFEEEE)
            )
            AttendanceViewModel.VerificationStatus.REJECTED_OUT_OF_BOUNDS -> Triple(
                "VERDICT: REJECTED - OUTSIDE CLASSROOM BOUNDS!",
                Color(0xFFCC0000),
                Color(0xFFFFEEEE)
            )
        }

        Surface(
            color = verdictBg,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.fillMaxWidth()
        ){
            Text(
                text = verdictText,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = verdictColor
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 1.dp)
        Spacer(modifier = Modifier.height(8.dp))

        // 6. LIVE GEOFENCE CALIBRATION
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ){
            Text(
                text = "Live Geofence Calibration:",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            OutlinedButton(
                onClick = onResetTuningDefaults,
                modifier = Modifier.height(28.dp),
                shape = RoundedCornerShape(3.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                border = BorderStroke(1.dp, Color.Gray)
            ){
                Text("Reset Defaults", fontSize = 10.sp, color = Color.Black)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
            border = BorderStroke(1.dp, Color(0xFFE0E0E0))
        ){
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ){
                GeofenceTuningRow(
                    label = "Perimeter Max dBm:",
                    valueStr = "${"%.0f".format(perimeterCutoffDbm)} dBm",
                    onMinus = { onAdjustPerimeter(-1.0) },
                    onPlus = { onAdjustPerimeter(+1.0) }
                )
                GeofenceTuningRow(
                    label = "Door Teacher Proximity:",
                    valueStr = "${"%.0f".format(doorThresholdDbm)} dBm",
                    onMinus = { onAdjustDoorThreshold(-1.0) },
                    onPlus = { onAdjustDoorThreshold(+1.0) }
                )
                GeofenceTuningRow(
                    label = "Door Back Wall Min:",
                    valueStr = "${"%.0f".format(doorBackCutoffDbm)} dBm",
                    onMinus = { onAdjustDoorBackCutoff(-1.0) },
                    onPlus = { onAdjustDoorBackCutoff(+1.0) }
                )
                GeofenceTuningRow(
                    label = "Door Max Allowed Delta:",
                    valueStr = "${"%.0f".format(doorMaxDeltaDb)} dB",
                    onMinus = { onAdjustDoorMaxDelta(-1.0) },
                    onPlus = { onAdjustDoorMaxDelta(+1.0) }
                )
                GeofenceTuningRow(
                    label = "Path Loss Exponent (n):",
                    valueStr = "%.1f".format(pathLossExponent),
                    onMinus = { onAdjustPathLoss(-0.1) },
                    onPlus = { onAdjustPathLoss(+0.1) }
                )
            }
        }

        // 6. LOG MESSAGES (Plain box with fixed comfortable height)
        Text(
            text = "Log Messages (${scanLogs.size}):",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black
        )

        Spacer(modifier = Modifier.height(4.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
            shape = RoundedCornerShape(4.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F7F7)),
            border = BorderStroke(1.dp, Color(0xFFDDDDDD))
        ){
            if(scanLogs.isEmpty()){
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ){
                    Text(
                        text = "No logs yet. Click 'Start Session' or 'Start Scanner'.",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ){
                    items(scanLogs.reversed()){ log ->
                        Text(
                            text = log,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = when {
                                log.contains("SPOOFER") || log.contains("ALERT") -> Color(0xFFCC0000)
                                log.contains("CHILD BEACON") || log.contains("★") -> Color(0xFF7A1FA2)
                                log.contains("REPORT") -> Color(0xFF0055AA)
                                log.contains("TEACHER_PING") -> Color(0xFF2E7D32)
                                else -> Color(0xFF222222)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GeofenceTuningRow(
    label: String,
    valueStr: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit
){
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ){
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color.Black,
            modifier = Modifier.weight(1.5f)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ){
            OutlinedButton(
                onClick = onMinus,
                modifier = Modifier.size(width = 34.dp, height = 28.dp),
                shape = RoundedCornerShape(3.dp),
                contentPadding = PaddingValues(0.dp),
                border = BorderStroke(1.dp, Color.Gray)
            ){
                Text("-", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
            Text(
                text = valueStr,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0055AA),
                modifier = Modifier.width(64.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            OutlinedButton(
                onClick = onPlus,
                modifier = Modifier.size(width = 34.dp, height = 28.dp),
                shape = RoundedCornerShape(3.dp),
                contentPadding = PaddingValues(0.dp),
                border = BorderStroke(1.dp, Color.Gray)
            ){
                Text("+", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.Black)
            }
        }
    }
}
