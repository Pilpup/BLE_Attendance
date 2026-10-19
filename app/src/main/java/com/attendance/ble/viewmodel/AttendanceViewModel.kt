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
import androidx.lifecycle.viewModelScope
import com.attendance.ble.network.ApiClient
import com.attendance.ble.network.AttendanceRecordItem
import com.attendance.ble.network.LoginRequest
import com.attendance.ble.network.MeshAuditRunRequest
import com.attendance.ble.network.RegisterRequest
import com.attendance.ble.network.SessionCreateRequest
import com.attendance.ble.network.SubmitNeighborsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    DISCOVERY_COMPLETE,
    VERIFIED_PRESENT
}

class AttendanceViewModel : ViewModel() {
    // Mode switcher
    var isTeacherMode by mutableStateOf(true)

    // Auth & Identity state
    val defaultLocalId: String = UUID.randomUUID().toString().replace("-", "").take(4).uppercase()
    var authToken: String? by mutableStateOf(null)
    var currentUserName: String by mutableStateOf("Guest")
    var currentUserRole: String by mutableStateOf("STUDENT")
    var currentRollNumber: String by mutableStateOf(defaultLocalId)
    val studentId: String get() = currentRollNumber
    // Exactly 4 characters for fixed-width BLE packets
    val studentShortId: String get() = currentRollNumber.takeLast(4).padStart(4, '0').uppercase()
    var isAuthLoading: Boolean by mutableStateOf(false)
    var authErrorText: String? by mutableStateOf(null)

    // BLE session state
    var isSessionActive by mutableStateOf(false)
    var sessionId by mutableStateOf("----")
    var sessionSalt by mutableStateOf("------")
    var remainingSeconds by mutableIntStateOf(0)

    // Backend cloud session state
    var backendSessionId: String? by mutableStateOf(null)
    var finalizedRecords by mutableStateOf<List<AttendanceRecordItem>>(emptyList())
    var isCloudSyncing: Boolean by mutableStateOf(false)
    var cloudStatusMessage: String? by mutableStateOf(null)
    val collectedMeshReports = mutableStateMapOf<String, List<String>>()

    // Compact 13-byte teacher beacon
    val currentBroadcastToken: String
        get() = if (isSessionActive) "S:$sessionId:$sessionSalt" else "IDLE"

    // Radio power settings
    val teacherAdvMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
    val teacherTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH

    // Student scanning state
    var studentPhase by mutableStateOf(StudentPhase.STANDBY)
    var detectedSessionId by mutableStateOf("----")
    var detectedSessionSalt by mutableStateOf("------")
    val discoveredPeers = mutableStateMapOf<String, PeerData>()

    // Top 3 desk neighbours with median RSSI >= -70 dBm
    val topDeskNeighbours: List<PeerData>
        get() = discoveredPeers.values
            .filter { it.medianRssi >= -70 }
            .sortedByDescending { it.medianRssi }
            .take(3)

    // Activity radio callbacks
    var onStartBroadcasting: ((token: String, txPower: Int) -> Unit)? = null
    var onStopBroadcasting: (() -> Unit)? = null
    var onStartScanning: (() -> Unit)? = null
    var onStopScanning: (() -> Unit)? = null

    private var teacherTimer: CountDownTimer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Authenticate user against FastAPI backend
    fun login(email: String, pass: String, onComplete: (Boolean) -> Unit = {}) {
        isAuthLoading = true
        authErrorText = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = ApiClient.service.login(LoginRequest(email, pass))
                withContext(Dispatchers.Main) {
                    isAuthLoading = false
                    if (res.isSuccessful && res.body() != null) {
                        val body = res.body()!!
                        authToken = body.accessToken
                        currentUserName = body.user.name
                        currentUserRole = body.user.role
                        body.user.rollNumber?.let { currentRollNumber = it }
                        isTeacherMode = (body.user.role == "TEACHER")
                        onComplete(true)
                    } else {
                        authErrorText = "Login failed: HTTP ${res.code()}"
                        onComplete(false)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isAuthLoading = false
                    authErrorText = "Network error: ${e.localizedMessage ?: "Cannot reach server"}"
                    onComplete(false)
                }
            }
        }
    }

    // Register user against FastAPI backend
    fun register(req: RegisterRequest, onComplete: (Boolean) -> Unit = {}) {
        isAuthLoading = true
        authErrorText = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = ApiClient.service.register(req)
                withContext(Dispatchers.Main) {
                    isAuthLoading = false
                    if (res.isSuccessful) {
                        onComplete(true)
                    } else {
                        authErrorText = "Registration failed: HTTP ${res.code()}"
                        onComplete(false)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isAuthLoading = false
                    authErrorText = "Network error: ${e.localizedMessage ?: "Cannot reach server"}"
                    onComplete(false)
                }
            }
        }
    }

    fun logout() {
        authToken = null
        currentUserName = "Guest"
        currentUserRole = "STUDENT"
        currentRollNumber = defaultLocalId
        backendSessionId = null
        finalizedRecords = emptyList()
        authErrorText = null
    }

    fun setServerUrl(url: String) {
        ApiClient.baseUrl = url
    }

    // Upload student's own top neighbors over Wi-Fi / cellular
    fun submitMyNeighborsToCloud() {
        val token = authToken ?: return
        val sessId = backendSessionId ?: return
        val neighborIds = topDeskNeighbours.map { it.peerId }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val req = SubmitNeighborsRequest(neighbors = neighborIds)
                ApiClient.service.submitNeighbors("Bearer $token", sessId, req)
            } catch (_: Exception) {}
        }
    }

    // Start teacher session and initialize backend record
    fun startTeacherSession(durationSeconds: Int = 60, courseId: String? = null) {
        sessionId = UUID.randomUUID().toString().replace("-", "").take(4).uppercase()
        sessionSalt = UUID.randomUUID().toString().replace("-", "").take(4).uppercase()
        collectedMeshReports.clear()
        finalizedRecords = emptyList()
        cloudStatusMessage = null

        isSessionActive = true
        remainingSeconds = durationSeconds

        val token = authToken
        if (token != null) {
            isCloudSyncing = true
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val req = SessionCreateRequest(courseId = courseId, sessionToken = sessionId)
                    val res = ApiClient.service.createSession("Bearer $token", req)
                    withContext(Dispatchers.Main) {
                        isCloudSyncing = false
                        if (res.isSuccessful && res.body() != null) {
                            backendSessionId = res.body()!!.id
                            cloudStatusMessage = "Cloud session active: $sessionId"
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isCloudSyncing = false
                        cloudStatusMessage = "Running offline mode (server unreachable)"
                    }
                }
            }
        }

        // Start broadcasting beacon and scan for incoming vouchers
        onStartBroadcasting?.invoke(currentBroadcastToken, teacherTxPower)
        onStartScanning?.invoke()

        teacherTimer?.cancel()
        teacherTimer = object : CountDownTimer(durationSeconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                remainingSeconds = (millisUntilFinished / 1000L).toInt()
            }

            override fun onFinish() {
                finalizeSessionAndAudit()
            }
        }.start()
    }

    // Finalize session and trigger server-side mesh audit
    fun finalizeSessionAndAudit() {
        stopTeacherSession()

        val token = authToken
        val sessId = backendSessionId
        if (token != null && sessId != null) {
            isCloudSyncing = true
            cloudStatusMessage = "Auditing classroom mesh..."
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val reportPayload = MeshAuditRunRequest(collectedMeshReports.toMap())
                    val res = ApiClient.service.auditAndFinalize("Bearer $token", sessId, reportPayload)
                    withContext(Dispatchers.Main) {
                        isCloudSyncing = false
                        if (res.isSuccessful && res.body() != null) {
                            val body = res.body()!!
                            finalizedRecords = body.records ?: emptyList()
                            cloudStatusMessage = "Audit complete: ${body.totalVerified} verified, ${body.totalSpoofers} rejected"

                            // Broadcast air verdicts for verified students
                            val verifiedList = finalizedRecords
                                .filter { it.status == "VERIFIED_PRESENT" }
                                .map { it.bleDeviceId }
                            broadcastVerdictsSequence(verifiedList)
                        } else {
                            cloudStatusMessage = "Audit failed: HTTP ${res.code()}"
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isCloudSyncing = false
                        cloudStatusMessage = "Cloud finalize failed: ${e.localizedMessage}"
                    }
                }
            }
        }
    }

    // Sequence through verified students, broadcasting 400ms verdict bursts
    private fun broadcastVerdictsSequence(verifiedRolls: List<String>) {
        if (verifiedRolls.isEmpty()) return
        var idx = 0
        fun sendNext() {
            if (idx < verifiedRolls.size) {
                val target = verifiedRolls[idx]
                onStartBroadcasting?.invoke("V:$target:OK", AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                idx++
                mainHandler.postDelayed({ sendNext() }, 400L)
            } else {
                onStopBroadcasting?.invoke()
            }
        }
        sendNext()
    }

    fun stopTeacherSession() {
        teacherTimer?.cancel()
        teacherTimer = null
        isSessionActive = false
        remainingSeconds = 0
        onStopBroadcasting?.invoke()
        onStopScanning?.invoke()
    }

    // Student scanning actions
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

    // Process incoming BLE packet
    fun onPacketReceived(rssi: Int, messageText: String) {
        // Teacher receiving student vouchers (12, 16, or 20 bytes matching active session ID)
        if (isTeacherMode && isSessionActive) {
            if (messageText.length in listOf(12, 16, 20) && messageText.startsWith(sessionId)) {
                val senderId = messageText.substring(4, 8)
                val neighbors = messageText.substring(8).chunked(4)
                collectedMeshReports[senderId] = neighbors
            }
            return
        }

        // Student hearing teacher beacon (S:<SessionID>:<Salt>)
        val isBeacon = messageText.startsWith("S:") || messageText.startsWith("SESSION:")
        if (!isTeacherMode && isBeacon && studentPhase == StudentPhase.LISTENING_FOR_TEACHER) {
            val parts = messageText.split(":")
            if (parts.size >= 3) {
                detectedSessionId = parts[1]
                detectedSessionSalt = parts[2]
                studentPhase = StudentPhase.PROBING_NEIGHBORS

                // Broadcast 8-byte probe: [SessionID (4)][SelfID (4)] at TX_POWER_LOW
                val probePayload = "$detectedSessionId$studentShortId"
                onStartBroadcasting?.invoke(probePayload, AdvertiseSettings.ADVERTISE_TX_POWER_LOW)

                mainHandler.postDelayed({
                    onStopBroadcasting?.invoke()
                    studentPhase = StudentPhase.DISCOVERY_COMPLETE

                    // Upload via Wi-Fi/5G if online
                    submitMyNeighborsToCloud()

                    // Broadcast 12-20 byte relay voucher at TX_POWER_HIGH
                    val nbrString = topDeskNeighbours.map { it.peerId }.take(3).joinToString("")
                    if (nbrString.isNotEmpty()) {
                        val voucher = "$detectedSessionId$studentShortId$nbrString"
                        onStartBroadcasting?.invoke(voucher, AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                        mainHandler.postDelayed({
                            onStopBroadcasting?.invoke()
                        }, 1500L)
                    }
                }, 2000L)
            }
            return
        }

        // Student hearing peer probe (8 bytes: [SessionID (4)][PeerID (4)])
        if (!isTeacherMode && messageText.length == 8 && studentPhase == StudentPhase.PROBING_NEIGHBORS) {
            // Drop packet in 0ms if it came from next door's classroom
            if (messageText.startsWith(detectedSessionId)) {
                val peerId = messageText.substring(4)
                if (peerId != studentShortId) {
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
            return
        }

        // Student hearing offline peer relay voucher (12, 16, or 20 bytes)
        if (!isTeacherMode && messageText.length in listOf(12, 16, 20) && messageText.startsWith(detectedSessionId)) {
            val senderId = messageText.substring(4, 8)
            val neighbors = messageText.substring(8).chunked(4)
            collectedMeshReports[senderId] = neighbors
            return
        }

        // Student hearing teacher air verdict (V:<ID>:OK)
        if (!isTeacherMode && messageText.startsWith("V:")) {
            val parts = messageText.split(":")
            if (parts.size >= 2) {
                val targetId = parts[1].trim()
                if (targetId == studentShortId || targetId == currentRollNumber) {
                    studentPhase = StudentPhase.VERIFIED_PRESENT
                }
            }
            return
        }
    }

    override fun onCleared() {
        super.onCleared()
        teacherTimer?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
    }
}
