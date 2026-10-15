package com.attendance.ble.viewmodel

import android.bluetooth.le.AdvertiseSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

data class StudentReport(val id: String, val rssi: Int)

class AttendanceViewModel : ViewModel() {
    val myDeviceID: String  = java.util.UUID.randomUUID().toString().take(4).uppercase()
    var isChildBeacon by mutableStateOf(false)
    val collectedReports = mutableListOf<StudentReport>()
    var onPingReceived: ((rssi: Int) -> Unit)? = null
    // () - empty input
    // -> - returns
    // Unit - void
    var onBecomeChild: (() -> Unit)? = null
    var isHandlingPing = false

    // remember - dont' forget the variable if screen rotates - <removed from this block of code>
    // mutableStateOf - magic variable (when var changed, reframe UI)
    var isBroadcasting by mutableStateOf(false)
    var isScanning by mutableStateOf(false)

    // mutableStateListOf - list of mutableStateOf
    val scanLogs = mutableStateListOf<String>()

    // Logger vars
    var avgLatencyMs by mutableStateOf(0.0)

    // teacher beacon logs
    var teacherRssi by mutableStateOf<Int?>(null)
    var teacherAvgRssi by mutableStateOf(0.0)
    var teacherPacketCount by mutableStateOf(0)
    val teacherRssiBuffer = mutableListOf<Int>()

    // child beacon logs
    var childRssi by mutableStateOf<Int?>(null)
    var childAvgRssi by mutableStateOf(0.0)
    var childPacketCount by mutableStateOf(0)
    val childRssiBuffer = mutableListOf<Int>()

    // differential RSSI  (delta = teacher - child)
    // cancels out hardware antenna bias
    val deltaRssi: Double?
        get() = if(teacherPacketCount > 0 && childPacketCount > 0){
            teacherAvgRssi - childAvgRssi
        } else null

    // buffer last 10 readings to calculate averages
    val latencyBuffer = mutableListOf<Double>()

    // reset function:
    fun resetLogs(){
        scanLogs.clear()

        teacherRssi = null
        teacherAvgRssi = 0.0
        teacherPacketCount = 0
        teacherRssiBuffer.clear()

        childRssi = null
        childAvgRssi = 0.0
        childPacketCount = 0
        childRssiBuffer.clear()

        avgLatencyMs = 0.0
        latencyBuffer.clear()
        isChildBeacon = false
        collectedReports.clear()
        sessionStatus = "STANDBY"
        electedChildId = null
        isHandlingPing = false
    }

    var selectedTxPower by mutableStateOf(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
    var selectedAdvMode by mutableStateOf(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
    var broadcastTokken by mutableStateOf("TEACHER")
    var isBroadcasterMode by mutableStateOf(true)
    var sessionStatus by mutableStateOf("STANDBY")
    var electedChildId by mutableStateOf<String?>(null)

    fun onPacketReceived(rssi: Int, latencyMs: Double, messageText: String){
        // 1. Check if Teacher pinged
        if(messageText == "TEACHER_PING"){
            if(!isHandlingPing){
                isHandlingPing = true
                sessionStatus = "HEARD TEACHER PING"
                val log = ">> TEACHER PING (RSSI: $rssi dBm)"
                if(scanLogs.size >= 5) scanLogs.removeAt(0)
                scanLogs.add(log)
                onPingReceived?.invoke(rssi)
            }
            return
        }

        // 2. Check if a Student is reporting in to Teacher
        if(messageText.startsWith("REPORT:")){
            val parts = messageText.split(":")
            if(parts.size == 3){
                val studentId = parts[1]
                val reportedRssi = parts[2].toIntOrNull() ?: rssi
                if(collectedReports.none { it.id == studentId }){
                    collectedReports.add(StudentReport(studentId, reportedRssi))
                    sessionStatus = "PHASE 2: REPORTS (${collectedReports.size})"
                    val log = ">> REPORT FROM [$studentId]: $reportedRssi dBm"
                    if(scanLogs.size >= 5) scanLogs.removeAt(0)
                    scanLogs.add(log)
                }
            }
            return
        }

        // check if child packet from teacher
        if(messageText.startsWith("Child:")){
            val targetId = messageText.removePrefix("Child:").trim()
            electedChildId = targetId
            if(targetId == myDeviceID && !isChildBeacon){
                isChildBeacon = true
                sessionStatus = "★ ELECTED AS CHILD BEACON ★"
                val log = ">> [★] YOU ARE THE CHILD BEACON!"
                if(scanLogs.size >= 5) scanLogs.removeAt(0)
                scanLogs.add(log)
                onBecomeChild?.invoke()
            } else {
                sessionStatus = "CHILD BEACON: [$targetId] ACTIVE"
                val log = ">> CHILD BEACON: [$targetId]"
                if(scanLogs.size >= 5) scanLogs.removeAt(0)
                scanLogs.add(log)
            }
            return
        }

        if(messageText.contains("TEACHER")){
            isHandlingPing = false
            // log math:
            teacherRssi = rssi
            teacherPacketCount++

            // average calc
            teacherRssiBuffer.add(rssi)
            if(teacherRssiBuffer.size > 10) teacherRssiBuffer.removeAt(0)
            teacherAvgRssi = teacherRssiBuffer.average()
        } else if(messageText.contains("CHILD")){
            // log math:
            childRssi = rssi
            childPacketCount++

            // average calc
            childRssiBuffer.add(rssi)
            if(childRssiBuffer.size > 10) childRssiBuffer.removeAt(0)
            childAvgRssi = childRssiBuffer.average()
        }

        // highest and lowest signal
        // !! - shut the fuck up this variable will never be null at the moment
        // - because {minRssi == null} is already true, before the next condition, it will never do {50 < null}
        // if(minRssi == null || rssi < minRssi!!) minRssi = rssi
        // if(maxRssi == null || rssi > maxRssi!!) maxRssi = rssi

        // average latency calc
        latencyBuffer.add(latencyMs)
        if(latencyBuffer.size > 10) latencyBuffer.removeAt(0)
        avgLatencyMs = latencyBuffer.average()

        val logEntry = "[$messageText] Signal: ${rssi} dBm | Lat: ${"%.2f".format(latencyMs)}ms"

        // keep only 5 readings
        if(scanLogs.size >= 5){
            scanLogs.removeAt(0)
        }
        scanLogs.add(logEntry)
    }
}
