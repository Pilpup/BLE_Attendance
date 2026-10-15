package com.attendance.ble.ui.screens

import android.bluetooth.le.AdvertiseSettings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
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
fun ColumnScope.BroadcasterScreen(
    isBroadcasting: Boolean,
    selectedTxPower: Int,
    selectedAdvMode: Int,
    broadcastTokken: String = "TEACHER",
    sessionStatus: String = "STANDBY",
    isChildBeacon: Boolean = false,
    electedChildId: String? = null,
    onToggleBroadcast: () -> Unit,
    onUpdateTxPower: (Int) -> Unit,
    onUpdateAdvMode: (Int) -> Unit,
    onCheckPermissions: () -> Unit
){
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cyberSurface),
        border = BorderStroke(1.dp, if(isBroadcasting) cyberGreen else cyberBorder),
        shape = RoundedCornerShape(8.dp)
    ){
        Column(modifier = Modifier.padding(12.dp)){
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ){
                Text(
                    text = if(isBroadcasting) "STATUS: TRANSMITTING" else "STATUS: STANDBY",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if(isBroadcasting) cyberGreen else cyberTextDim
                )
                Text(
                    text = if(isBroadcasting) "[ON AIR: $broadcastTokken]" else "[OFFLINE]",
                    fontFamily = FontFamily.Monospace,
                    color = if(isBroadcasting) cyberGreen else cyberTextDim
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
                    text = "CHILD BEACON: [$electedChildId]",
                    fontFamily = FontFamily.Monospace,
                    color = cyberGreenDim
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    Button(
        onClick = onToggleBroadcast,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if(isBroadcasting) cyberRed else cyberGreen,
            contentColor = if(isBroadcasting) Color.White else cyberBg
        ),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if(isBroadcasting) cyberRed else cyberGreen)
    ){
        Text(
            text = if(isBroadcasting) "[!] ABORT TRANSMISSION" else ">> ENGAGE BROADCAST",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }

    Spacer(modifier = Modifier.height(14.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cyberSurface),
        border = BorderStroke(1.dp, cyberBorder),
        shape = RoundedCornerShape(8.dp)
    ){
        Column(modifier = Modifier.padding(16.dp)){
            Text(
                text = "// TX_POWER_MODULATION",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = cyberGreen
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Level: " + when(selectedTxPower){
                    AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW -> "ULTRA_LOW (-21dBm / ~1-3m)"
                    AdvertiseSettings.ADVERTISE_TX_POWER_LOW -> "LOW (-15dBm / ~3-8m)"
                    AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM -> "MEDIUM (-7dBm / ~8-15m)"
                    else -> "HIGH (+1dBm / ~20m+)"
                },
                fontFamily = FontFamily.Monospace,
                color = cyberText
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ){
                val powers = listOf(
                    "ULOW" to AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW,
                    "LOW" to AdvertiseSettings.ADVERTISE_TX_POWER_LOW,
                    "MED" to AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM,
                    "HIGH" to AdvertiseSettings.ADVERTISE_TX_POWER_HIGH
                )
                powers.forEach { (label, value) ->
                    val isSelected = selectedTxPower == value
                    Button(
                        onClick = {onUpdateTxPower(value)},
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if(isSelected) cyberGreen else cyberBg,
                            contentColor = if(isSelected) cyberBg else cyberTextDim
                        ),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, if(isSelected) cyberGreen else cyberBorder),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ){
                        Text(
                            text = label,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if(isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "// PACKET_FREQUENCY",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = cyberGreen
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Rate: " + when(selectedAdvMode){
                    AdvertiseSettings.ADVERTISE_MODE_LOW_POWER -> "1 Hz (BATTERY_SAVER)"
                    AdvertiseSettings.ADVERTISE_MODE_BALANCED -> "4 Hz (BALANCED)"
                    else -> "10 Hz (LOW_LATENCY)"
                },
                fontFamily = FontFamily.Monospace,
                color = cyberText
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ){
                val rates = listOf(
                    "1 Hz" to AdvertiseSettings.ADVERTISE_MODE_LOW_POWER,
                    "4 Hz" to AdvertiseSettings.ADVERTISE_MODE_BALANCED,
                    "10 Hz" to AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                )
                rates.forEach { (label, value) ->
                    val isSelected = selectedAdvMode == value
                    Button(
                        onClick = {onUpdateAdvMode(value)},
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if(isSelected) cyberGreen else cyberBg,
                            contentColor = if(isSelected) cyberBg else cyberTextDim
                        ),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, if(isSelected) cyberGreen else cyberBorder),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ){
                        Text(
                            text = label,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if(isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(14.dp))

    Button(
        onClick = onCheckPermissions,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(
            containerColor = cyberSurface,
            contentColor = cyberTextDim
        ),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, cyberBorder)
    ){
        Text(
            text = "CHECK_PERMISSIONS",
            fontFamily = FontFamily.Monospace
        )
    }
}
