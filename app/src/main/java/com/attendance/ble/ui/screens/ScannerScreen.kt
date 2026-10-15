package com.attendance.ble.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.attendance.ble.ui.theme.*

@Composable
fun ColumnScope.ScannerScreen(
    isScanning: Boolean,
    myDeviceID: String,
    teacherRssi: Int?,
    teacherAvgRssi: Double,
    teacherPacketCount: Int,
    childRssi: Int?,
    childAvgRssi: Double,
    childPacketCount: Int,
    deltaRssi: Double?,
    avgLatencyMs: Double,
    scanLogs: List<String>,
    sessionStatus: String = "STANDBY",
    isChildBeacon: Boolean = false,
    electedChildId: String? = null,
    onToggleScan: () -> Unit,
    onResetLogs: () -> Unit,
    onCheckPermissions: () -> Unit
){
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cyberSurface),
        border = BorderStroke(1.dp, if(isScanning) cyberGreen else cyberBorder),
        shape = RoundedCornerShape(8.dp)
    ){
        Column(modifier = Modifier.padding(12.dp)){
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ){
                Text(
                    text = if(isScanning) "STATUS: SCANNING_AIRWAVES" else "STATUS: RECEIVER_OFFLINE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if(isScanning) cyberGreen else cyberTextDim
                )
                Text(
                    text = if(isScanning) "[RX ACTIVE]" else "[IDLE]",
                    fontFamily = FontFamily.Monospace,
                    color = if(isScanning) cyberGreen else cyberTextDim
                )
            }
            if(sessionStatus.isNotEmpty() && sessionStatus != "STANDBY"){
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = ">> $sessionStatus",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = cyberCyan
                )
            }
            if(isChildBeacon){
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "★ YOU ARE THE CHILD BEACON ★",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = cyberYellow
                )
            } else if(electedChildId != null){
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "CHILD BEACON: [$electedChildId] ACTIVE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = cyberGreenDim
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    Button(
        onClick = onToggleScan,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if(isScanning) cyberRed else cyberGreen,
            contentColor = if(isScanning) Color.White else cyberBg
        ),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if(isScanning) cyberRed else cyberGreen)
    ){
        Text(
            text = if(isScanning) "[!] HALT SCANNER" else ">> INITIALIZE SCANNER",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }

    Spacer(modifier = Modifier.height(14.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cyberSurface),
        border = BorderStroke(1.dp, if(teacherRssi != null || childRssi != null) cyberGreen else cyberBorder),
        shape = RoundedCornerShape(8.dp)
    ){
        Column(modifier = Modifier.padding(14.dp)){
            // Header with Device ID and Clear Buffer button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ){
                Text(
                    text = "// RADAR: [$myDeviceID]",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = cyberGreen
                )
                Button(
                    onClick = onResetLogs,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cyberBg,
                        contentColor = cyberRed
                    ),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, cyberBorder),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ){
                    Text(
                        text = "CLR_BUF",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 1. TEACHER BEACON STATS
            Text(
                text = "BEACON 1: TEACHER (PODIUM)",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if(teacherRssi != null) cyberGreen else cyberTextDim
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ){
                Text(
                    text = "RSSI: " + (teacherRssi?.let { "$it dBm" } ?: "--"),
                    fontFamily = FontFamily.Monospace,
                    color = cyberText
                )
                Text(
                    text = "AVG: " + if(teacherPacketCount > 0) "%.1f dBm".format(teacherAvgRssi) else "--",
                    fontFamily = FontFamily.Monospace,
                    color = cyberText
                )
                Text(
                    text = "PKTS: $teacherPacketCount",
                    fontFamily = FontFamily.Monospace,
                    color = cyberTextDim
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 2. CHILD BEACON STATS
            Text(
                text = "BEACON 2: CHILD (BACK)",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if(childRssi != null) cyberGreen else cyberTextDim
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ){
                Text(
                    text = "RSSI: " + (childRssi?.let { "$it dBm" } ?: "--"),
                    fontFamily = FontFamily.Monospace,
                    color = cyberText
                )
                Text(
                    text = "AVG: " + if(childPacketCount > 0) "%.1f dBm".format(childAvgRssi) else "--",
                    fontFamily = FontFamily.Monospace,
                    color = cyberText
                )
                Text(
                    text = "PKTS: $childPacketCount",
                    fontFamily = FontFamily.Monospace,
                    color = cyberTextDim
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3. DIFFERENTIAL ANALYSIS (DELTA)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ){
                Text(
                    text = "DELTA [T - C]:",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = cyberTextDim
                )
                Text(
                    text = deltaRssi?.let { "%+.1f dB".format(it) } ?: "CALCULATING...",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if(deltaRssi != null) cyberGreen else cyberTextDim
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ){
                Text(
                    text = "SYS_LATENCY:",
                    fontFamily = FontFamily.Monospace,
                    color = cyberTextDim
                )
                Text(
                    text = "%.1f ms".format(avgLatencyMs),
                    fontFamily = FontFamily.Monospace,
                    color = cyberTextDim
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ){
        Text(
            text = "// AIRWAVE_PACKET_STREAM",
            fontFamily = FontFamily.Monospace,
            color = cyberTextDim
        )
        Button(
            onClick = onCheckPermissions,
            colors = ButtonDefaults.buttonColors(
                containerColor = cyberSurface,
                contentColor = cyberTextDim
            ),
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, cyberBorder),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        ){
            Text(
                text = "PERMS",
                fontFamily = FontFamily.Monospace
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ){
        items(scanLogs.reversed()){ log ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = cyberSurface),
                border = BorderStroke(0.5.dp, cyberBorder),
                shape = RoundedCornerShape(4.dp)
            ){
                Text(
                    text = "> $log",
                    modifier = Modifier.padding(8.dp),
                    fontFamily = FontFamily.Monospace,
                    color = cyberGreenDim
                )
            }
        }
    }
}
