package com.attendance.ble.viewmodel

import android.bluetooth.le.AdvertiseSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class AttendanceViewModel : ViewModel() {

    // moved outside of onCreate so advertiseCallback can handle these
    // remember - dont' forget the variable if screen rotates - <removed from this block of code>
    // mutableStateOf - magic variable (when var changed, reframe UI)
    var isBroadcasting by mutableStateOf(false)
    var isScanning by mutableStateOf(false)

    // mutableStateListOf - list of mutableStateOf
    val scanLogs = mutableStateListOf<String>()

    // Logger vars
    var currentRssi by mutableStateOf<Int?>(null)
    var avgRssi by mutableStateOf(0.0)
    var minRssi by mutableStateOf<Int?>(null)
    var maxRssi by mutableStateOf<Int?>(null)
    var packetCount by mutableStateOf(0)
    var avgLatencyMs by mutableStateOf(0.0)
    var latestMessage by mutableStateOf("No Data")

    // buffer last 10 readings to calculate averages
    val rssiBuffer = mutableListOf<Int>()
    val latencyBuffer = mutableListOf<Double>()

    // reset function:
    fun resetLogs(){
        scanLogs.clear()
        currentRssi = null
        avgRssi = 0.0
        minRssi = null
        maxRssi = null
        packetCount = 0
        avgLatencyMs = 0.0
        latestMessage = "No Data"
        rssiBuffer.clear()
        latencyBuffer.clear()
    }

    var selectedTxPower by mutableStateOf(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
    var selectedAdvMode by mutableStateOf(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
    var broadcastTokken by mutableStateOf("SECRET")
    var isBroadcasterMode by mutableStateOf(true)

    fun onPacketReceived(rssi: Int, latencyMs: Double, messageText: String){
        // log math:
        currentRssi = rssi
        packetCount++
        latestMessage = messageText

        // average calc
        rssiBuffer.add(rssi)
        if(rssiBuffer.size > 10) rssiBuffer.removeAt(0)
        avgRssi = rssiBuffer.average()

        // highest and lowest signal
        // !! - shut the fuck up this variable will never be null at the moment
        // - because {minRssi == null} is already true, before the next condition, it will never do {50 < null}
        if(minRssi == null || rssi < minRssi!!) minRssi = rssi
        if(maxRssi == null || rssi > maxRssi!!) maxRssi = rssi

        // average latency calc
        latencyBuffer.add(latencyMs)
        if(latencyBuffer.size > 10) latencyBuffer.removeAt(0)
        avgLatencyMs = latencyBuffer.average()

        val logEntry = "Signal: ${rssi} dBm | Latency: ${"%.2f".format(latencyMs)} ms\nData: $messageText"

        // keep only 5 readings
        if(scanLogs.size >= 5){
            scanLogs.removeAt(0)
        }
        scanLogs.add(logEntry)
    }
}
