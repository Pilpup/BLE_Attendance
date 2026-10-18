package com.attendance.ble.viewmodel

import android.bluetooth.le.AdvertiseSettings
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import java.util.UUID

data class PeerData(
    val peerId: String,
    val rssiSamples: MutableList<Int> = mutableListOf()
) {
    val medianRssi: Int
        get() = if (rssiSamples.isEmpty()) -99 else {
            val sorted = rssiSamples.sorted()
            sorted[sorted.size / 2]
        }
    val count: Int get() = rssiSamples.size
}

enum class StudentPhase {
    STANDBY,
    LISTENING_FOR_TEACHER,
    PROBING_NEIGHBORS,
    DISCOVERY_COMPLETE
}

class AttendanceViewModel : ViewModel() {
    // roles
    var isTeacherMode by mutableStateOf(true)

    // session variables
    var isSessionActive by mutableStateOf(false)
    var sessionId by mutableStateOf("----")
    var sessionSalt by mutableStateOf("------")
    var remainingSeconds by mutableIntStateOf(0)

    // ble payload
    val currentBroadcastToken: String
        get() = if (isSessionActive) "SESSION:$sessionId:$sessionSalt" else "IDLE"

    // radio settings
    val teacherAdvMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
    val teacherTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH

    // student state
    val studentId: String = UUID.randomUUID().toString().replace("-", "").take(4).uppercase()
    var studentPhase by mutableStateOf(StudentPhase.STANDBY)
    var detectedSessionId by mutableStateOf("----")
    var detectedSessionSalt by mutableStateOf("------")
    val discoveredPeers = mutableStateMapOf<String, PeerData>()

    // top 3 neighbours with median Rssi >= -70dbm
    val topDeskNeighbours: List<PeerData>
        get() = discoveredPeers.values
            .filter { it.medianRssi >= -70 }
            .sortedByDescending { it.medianRssi }
            .take(3)

    // activity callbacks
    var onStartBroadcasting: ((token: String, txPower: Int) -> Unit)? = null
    var onStopBroadcasting: (() -> Unit)? = null
    var onStartScanning: (() -> Unit)? = null
    var onStopScanning: (() -> Unit)? = null

    private var teacherTimer: CountDownTimer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // teacher actions
    fun startTeacherSession(durationSeconds: Int = 60) {
        // 4 char session ID and 6 char in room salt
        sessionId = UUID.randomUUID().toString().replace("-", "").take(4).uppercase()
        sessionSalt = UUID.randomUUID().toString().replace("-", "").take(6).uppercase()

        isSessionActive = true
        remainingSeconds = durationSeconds

        // trigger broadcasting
        onStartBroadcasting?.invoke(currentBroadcastToken, teacherTxPower)

        teacherTimer?.cancel()

        teacherTimer = object : CountDownTimer(durationSeconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                remainingSeconds = (millisUntilFinished / 1000L).toInt()
            }

            override fun onFinish() {
                stopTeacherSession()
            }
        }.start()
    }

    fun stopTeacherSession() {
        teacherTimer?.cancel()
        teacherTimer = null
        isSessionActive = false
        remainingSeconds = 0
        onStopBroadcasting?.invoke()
    }

    // student actions
    fun startStudentScanning() {
        discoveredPeers.clear()
        detectedSessionId = "----"
        detectedSessionSalt = "------"
        studentPhase = StudentPhase.LISTENING_FOR_TEACHER
        onStartScanning?.invoke()
    }

    fun stopStudentScanning() {
        studentPhase = StudentPhase.STANDBY
        onStopScanning?.invoke()
        onStopBroadcasting?.invoke()
    }

    // called when scanner receives a valid packet
    fun onPacketReceived(rssi: Int, messageText: String) {
        if (isTeacherMode) return

        // student hears teacher "SESSION:89F2:SALT99"
        if (messageText.startsWith("SESSION:") && studentPhase == StudentPhase.LISTENING_FOR_TEACHER) {
            val parts = messageText.split(":")
            if (parts.size >= 3) {
                detectedSessionId = parts[1]
                detectedSessionSalt = parts[2]
                studentPhase = StudentPhase.PROBING_NEIGHBORS

                // broadcast 6 byte identity at low tx power
                onStartBroadcasting?.invoke("P:$studentId", AdvertiseSettings.ADVERTISE_TX_POWER_LOW)

                mainHandler.postDelayed({
                    onStopBroadcasting?.invoke()
                    studentPhase = StudentPhase.DISCOVERY_COMPLETE
                }, 2000L)
            }
            return
        }

        if (messageText.startsWith("P:") && studentPhase == StudentPhase.PROBING_NEIGHBORS) {
            val peerId = messageText.removePrefix("P:").trim()
            if (peerId != studentId && peerId.isNotEmpty()) {
                val existing = discoveredPeers[peerId]
                if (existing != null) {
                    existing.rssiSamples.add(rssi)
                } else {
                    val newPeer = PeerData(peerId)
                    newPeer.rssiSamples.add(rssi)
                    discoveredPeers[peerId] = newPeer
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        teacherTimer?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
