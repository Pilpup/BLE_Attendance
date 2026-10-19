package com.attendance.ble.viewmodel

import android.app.Application
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.attendance.ble.network.ApiClient
import com.attendance.ble.network.AttendanceRecordItem
import com.attendance.ble.network.CourseResponse
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

class AttendanceViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("ble_attendance_prefs", Context.MODE_PRIVATE)

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

    // Courses for Teacher
    var availableCourses by mutableStateOf<List<CourseResponse>>(emptyList())
    var selectedCourse by mutableStateOf<CourseResponse?>(null)
    var isLoadingCourses by mutableStateOf(false)

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

    // Top 3 desk neighbours with median RSSI >= -78 dBm (calibrated for classroom RF attenuation)
    val topDeskNeighbours: List<PeerData>
        get() = discoveredPeers.values
            .filter { it.medianRssi >= -78 }
            .sortedByDescending { it.medianRssi }
            .take(3)

    // Activity radio callbacks
    var onStartBroadcasting: ((token: String, txPower: Int) -> Unit)? = null
    var onStopBroadcasting: (() -> Unit)? = null
    var onStartScanning: (() -> Unit)? = null
    var onStopScanning: (() -> Unit)? = null

    var studentDiscoverySecondsRemaining by mutableIntStateOf(0)
    private var teacherTimer: CountDownTimer? = null
    private var studentTimer: CountDownTimer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        authToken = prefs.getString("auth_token", null)
        currentUserName = prefs.getString("user_name", "Guest") ?: "Guest"
        currentUserRole = prefs.getString("user_role", "STUDENT") ?: "STUDENT"
        currentRollNumber = prefs.getString("roll_number", defaultLocalId) ?: defaultLocalId
        isTeacherMode = prefs.getBoolean("is_teacher_mode", currentUserRole == "TEACHER")
        val savedUrl = prefs.getString("server_url", null)
        if (!savedUrl.isNullOrBlank() && !savedUrl.contains("10.0.2.2")) {
            ApiClient.baseUrl = savedUrl
        } else {
            ApiClient.baseUrl = "https://ble-attendance-api-dbf1.onrender.com/"
            prefs.edit().putString("server_url", ApiClient.baseUrl).apply()
        }
        if (authToken != null && isTeacherMode) {
            loadCourses()
        }
    }

    // Persist and update student roll number (even offline)
    fun updateRollNumber(newRoll: String) {
        val trimmed = newRoll.trim().uppercase()
        if (trimmed.isNotEmpty()) {
            currentRollNumber = trimmed
            prefs.edit().putString("roll_number", trimmed).apply()
        }
    }

    // Persist mode toggle
    fun toggleMode(isTeacher: Boolean) {
        isTeacherMode = isTeacher
        prefs.edit().putBoolean("is_teacher_mode", isTeacher).apply()
        if (isTeacher && authToken != null && availableCourses.isEmpty()) {
            loadCourses()
        }
    }

    // Persist custom server URL
    fun setServerUrl(url: String) {
        ApiClient.baseUrl = url
        prefs.edit().putString("server_url", ApiClient.baseUrl).apply()
    }

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

                        prefs.edit()
                            .putString("auth_token", authToken)
                            .putString("user_name", currentUserName)
                            .putString("user_role", currentUserRole)
                            .putString("roll_number", currentRollNumber)
                            .putBoolean("is_teacher_mode", isTeacherMode)
                            .apply()

                        if (isTeacherMode) {
                            loadCourses()
                        }
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
        availableCourses = emptyList()
        selectedCourse = null
        prefs.edit()
            .remove("auth_token")
            .remove("user_name")
            .remove("user_role")
            .remove("roll_number")
            .apply()
    }

    // Fetch courses taught by teacher
    fun loadCourses() {
        val token = authToken ?: return
        isLoadingCourses = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = ApiClient.service.listCourses("Bearer $token")
                withContext(Dispatchers.Main) {
                    isLoadingCourses = false
                    if (res.isSuccessful && res.body() != null) {
                        availableCourses = res.body()!!
                        if (selectedCourse == null && availableCourses.isNotEmpty()) {
                            selectedCourse = availableCourses.first()
                        }
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { isLoadingCourses = false }
            }
        }
    }

    // Create a new course for the current teacher
    fun createCourse(courseCode: String, courseName: String, onComplete: (Boolean) -> Unit = {}) {
        val token = authToken ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = ApiClient.service.createCourse(
                    "Bearer $token",
                    com.attendance.ble.network.CourseCreateRequest(courseCode.trim().uppercase(), courseName.trim())
                )
                withContext(Dispatchers.Main) {
                    if (res.isSuccessful && res.body() != null) {
                        val created = res.body()!!
                        availableCourses = availableCourses + created
                        selectedCourse = created
                        onComplete(true)
                    } else {
                        onComplete(false)
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { onComplete(false) }
            }
        }
    }

    // Format attendance records as CSV for export
    fun generateCsvReport(): String {
        val sb = StringBuilder()
        sb.append("Roll Number,Status,Verification Method,Affinity Score,Remarks\n")
        for (rec in finalizedRecords) {
            val scorePct = (rec.affinityScore * 100).toInt()
            sb.append("${rec.bleDeviceId},${rec.status},${rec.verificationMethod},$scorePct%,${rec.remarks ?: ""}\n")
        }
        return sb.toString()
    }

    // Upload student's own top neighbors over Wi-Fi / cellular
    fun submitMyNeighborsToCloud() {
        val sessId = detectedSessionId.takeIf { it != "----" } ?: backendSessionId ?: return
        val neighborIds = topDeskNeighbours.map { it.peerId }
        val token = authToken
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val req = SubmitNeighborsRequest(
                    studentId = studentShortId,
                    neighbors = neighborIds
                )
                val authHeader = if (token != null) "Bearer $token" else null
                val res = ApiClient.service.submitNeighbors(authHeader, sessId, req)
                withContext(Dispatchers.Main) {
                    if (res.isSuccessful) {
                        studentPhase = StudentPhase.VERIFIED_PRESENT
                        onStopScanning?.invoke()
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // Start teacher session and initialize backend record
    fun startTeacherSession(durationSeconds: Int = 60, courseId: String? = null) {
        val targetCourseId = courseId ?: selectedCourse?.id
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
                    val req = SessionCreateRequest(courseId = targetCourseId, sessionToken = sessionId)
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

        // Broadcast teacher in-room beacon (teacher does not scan BLE)
        onStartBroadcasting?.invoke(currentBroadcastToken, teacherTxPower)

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
        val sessId = backendSessionId ?: sessionId.takeIf { it != "----" }

        if (token != null && sessId != null) {
            isCloudSyncing = true
            cloudStatusMessage = "Auditing classroom mesh..."
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val reportPayload = MeshAuditRunRequest(emptyMap())
                    val res = ApiClient.service.auditAndFinalize("Bearer $token", sessId, reportPayload)
                    withContext(Dispatchers.Main) {
                        isCloudSyncing = false
                        if (res.isSuccessful && res.body() != null) {
                            val body = res.body()!!
                            finalizedRecords = body.records ?: emptyList()
                            cloudStatusMessage = "Audit complete: ${body.totalVerified} verified, ${body.totalSpoofers} rejected"
                        } else {
                            cloudStatusMessage = "Audit failed: Server returned HTTP ${res.code()}"
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isCloudSyncing = false
                        cloudStatusMessage = "Finalize failed: ${e.localizedMessage}"
                    }
                }
            }
        }
    }

    fun stopTeacherSession() {
        teacherTimer?.cancel()
        teacherTimer = null
        isSessionActive = false
        remainingSeconds = 0
        onStopBroadcasting?.invoke()
    }

    // Student scanning start
    fun startStudentScanning() {
        studentPhase = StudentPhase.LISTENING_FOR_TEACHER
        detectedSessionId = "----"
        detectedSessionSalt = "------"
        discoveredPeers.clear()
        collectedMeshReports.clear()
        onStartScanning?.invoke()
    }

    // Student scanning stop
    fun stopStudentScanning() {
        studentTimer?.cancel()
        studentTimer = null
        studentDiscoverySecondsRemaining = 0
        studentPhase = StudentPhase.STANDBY
        onStopScanning?.invoke()
        onStopBroadcasting?.invoke()
    }

    private fun startStudentDiscoveryWindow() {
        studentDiscoverySecondsRemaining = 15
        val probePayload = "$detectedSessionId$studentShortId"
        onStartBroadcasting?.invoke(probePayload, AdvertiseSettings.ADVERTISE_TX_POWER_LOW)

        studentTimer?.cancel()
        studentTimer = object : CountDownTimer(15000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                studentDiscoverySecondsRemaining = (millisUntilFinished / 1000L).toInt()
            }

            override fun onFinish() {
                studentDiscoverySecondsRemaining = 0
                // Stop advertising probe
                onStopBroadcasting?.invoke()
                studentPhase = StudentPhase.DISCOVERY_COMPLETE

                // Upload discovered desk neighbors to server over Wi-Fi / cellular
                submitMyNeighborsToCloud()
            }
        }.start()
    }

    // Process incoming BLE packet
    fun onPacketReceived(rssi: Int, messageText: String) {
        val cleanMsg = messageText.trim()

        // Teacher does not process BLE packets
        if (isTeacherMode) return

        // Student hearing teacher beacon (S:<SessionID>:<Salt>)
        val isBeacon = cleanMsg.startsWith("S:") || cleanMsg.startsWith("SESSION:")
        if (isBeacon && studentPhase == StudentPhase.LISTENING_FOR_TEACHER) {
            val parts = cleanMsg.split(":")
            if (parts.size >= 3) {
                detectedSessionId = parts[1]
                detectedSessionSalt = parts[2]
                studentPhase = StudentPhase.PROBING_NEIGHBORS
                startStudentDiscoveryWindow()
            }
            return
        }

        // Student hearing peer probe (8 bytes: [SessionID (4)][PeerID (4)])
        if (cleanMsg.length == 8 && studentPhase == StudentPhase.PROBING_NEIGHBORS) {
            // Drop packet if it came from another classroom
            if (cleanMsg.startsWith(detectedSessionId)) {
                val peerId = cleanMsg.substring(4)
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
    }
}
