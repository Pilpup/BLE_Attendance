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
    currentRssi: Int?,
    avgRssi: Double,
    minRssi: Int?,
    maxRssi: Int?,
    packetCount: Int,
    avgLatencyMs: Double,
    latestMessage: String,
    scanLogs: List<String>,
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
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
        border = BorderStroke(1.dp, if(currentRssi != null) cyberGreen else cyberBorder),
        shape = RoundedCornerShape(8.dp)
    ){
        Column(modifier = Modifier.padding(16.dp)){
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ){
                Text(
                    text = "// TELEMETRY_HUD",
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ){
                Text(
                    text = "RSSI: " + (currentRssi?.let { "$it dBm" } ?: "--"),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if(currentRssi != null) cyberGreen else cyberTextDim
                )
                Text(
                    text = "AVG: " + if(packetCount > 0) "%.1f dBm".format(avgRssi) else "--",
                    fontFamily = FontFamily.Monospace,
                    color = cyberText
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ){
                Text(
                    text = "SPREAD: ${minRssi ?: "--"} / ${maxRssi ?: "--"} dBm",
                    fontFamily = FontFamily.Monospace,
                    color = cyberTextDim
                )
                Text(
                    text = "PKTS: $packetCount",
                    fontFamily = FontFamily.Monospace,
                    color = cyberText
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ){
                Text(
                    text = "LATENCY: " + if(packetCount > 0) "%.2f ms".format(avgLatencyMs) else "--",
                    fontFamily = FontFamily.Monospace,
                    color = cyberTextDim
                )
                Text(
                    text = "MSG: \"$latestMessage\"",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = cyberGreen
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
