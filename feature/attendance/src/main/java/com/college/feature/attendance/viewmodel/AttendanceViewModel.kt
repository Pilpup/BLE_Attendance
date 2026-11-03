package com.college.feature.attendance.viewmodel

import android.app.Application
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.CountDownTimer
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.college.core.model.Role
import com.college.core.model.User
import com.college.core.network.NetworkResult
import com.college.core.network.SessionManager
import com.college.core.network.repository.AuthRepository
import com.college.core.network.safeApiCall
import com.college.core.network.model.RegisterRequest
import com.college.feature.attendance.network.AttendanceApiClient
import com.college.feature.attendance.network.AttendanceRecordItem
import com.college.feature.attendance.network.AttendanceSessionResponse
import com.college.feature.attendance.network.CourseCreateRequest
import com.college.feature.attendance.network.CourseResponse
import com.college.feature.attendance.network.MeshAuditRunRequest
import com.college.feature.attendance.network.RecordOverrideRequest
import com.college.feature.attendance.network.SessionCreateRequest
import com.college.feature.attendance.network.SubmitNeighborsRequest
import com.college.feature.attendance.audio.AcousticStatisticalSummary
import com.college.feature.attendance.audio.EnsembleVerdict
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Immutable
data class PeerData(
    val peerId: String,
    val rssiSamples: List<Int> = emptyList(),
    val medianRssi: Int = -99
) {
    val count: Int get() = rssiSamples.size

    fun withSample(rssi: Int, maxSamples: Int = 10): PeerData {
        val updated = (rssiSamples + rssi).takeLast(maxSamples)
        val sorted = updated.sorted()
        val median = sorted[sorted.size / 2]
        return PeerData(
            peerId = peerId,
            rssiSamples = updated,
            medianRssi = median
        )
    }
}

enum class StudentPhase {
    STANDBY,
    LISTENING_FOR_TEACHER,
    PROBING_NEIGHBORS,
    DISCOVERY_COMPLETE,
    WAITING_FOR_AUDIT,
    VERIFIED_PRESENT,
    UNVERIFIED_FLAGGED
}

enum class TeacherViewMode {
    MENU,
    TAKE_ATTENDANCE,
    OLDER_SESSIONS
}

// Manages classroom attendance session state, BLE discovery, and cloud synchronization
class AttendanceViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("ble_attendance_prefs", Context.MODE_PRIVATE)

    // Retrofit service handle
    private val api get() = AttendanceApiClient.service
    private val authRepo get() = AuthRepository.default

    // Current user identity and role derived from active session
    val isTeacherMode: Boolean
        get() = SessionManager.default.currentUser?.canTakeAttendance() == true
    val isLoggedIn: Boolean
        get() = SessionManager.default.isLoggedIn
    val currentUserName: String
        get() = SessionManager.default.currentUser?.name ?: "Guest"
    val currentUserRole: String
        get() = SessionManager.default.currentUser?.primaryRole?.name ?: "STUDENT"

    // Device roll number used for BLE broadcast packets
    val defaultLocalId: String = UUID.randomUUID().toString().replace("-", "").take(4).uppercase()
    var currentRollNumber: String by mutableStateOf(defaultLocalId)
    val studentId: String get() = currentRollNumber
    val studentShortId: String get() = currentRollNumber.takeLast(4).padStart(4, '0').uppercase()

    var isAuthLoading: Boolean by mutableStateOf(false)
    var authErrorText: String? by mutableStateOf(null)
    var showAuthDialog: Boolean by mutableStateOf(false)

    // Course selection for teachers
    var availableCourses by mutableStateOf<List<CourseResponse>>(emptyList())
    var selectedCourse by mutableStateOf<CourseResponse?>(null)
    var isLoadingCourses by mutableStateOf(false)

    // Active BLE session state and countdown timer
    var isSessionActive by mutableStateOf(false)
    var sessionId by mutableStateOf("----")
    var sessionSalt by mutableStateOf("------")
    var remainingSeconds by mutableIntStateOf(0)

    // Teacher navigation and history
    var teacherViewMode by mutableStateOf(TeacherViewMode.MENU)
    var pastSessions by mutableStateOf<List<AttendanceSessionResponse>>(emptyList())
    var isLoadingHistory by mutableStateOf(false)
    var selectedSessionForDetails by mutableStateOf<AttendanceSessionResponse?>(null)

    // Cloud session state and sync messaging
    var backendSessionId: String? by mutableStateOf(null)
    var finalizedRecords by mutableStateOf<List<AttendanceRecordItem>>(emptyList())
    var isCloudSyncing: Boolean by mutableStateOf(false)
    var cloudStatusMessage: String? by mutableStateOf(null)
    var studentStatusMessage: String? by mutableStateOf(null)

    // Beacon broadcast payload
    val currentBroadcastToken: String
        get() = if (isSessionActive) "S:$sessionId:$sessionSalt" else "IDLE"

    // Radio advertising configuration
    val teacherAdvMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
    val teacherTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH

    // Student scanning state and discovered peers
    var studentPhase by mutableStateOf(StudentPhase.STANDBY)
    var detectedSessionId by mutableStateOf("----")
    var detectedSessionSalt by mutableStateOf("------")
    private val rawDiscoveredPeers = java.util.concurrent.ConcurrentHashMap<String, PeerData>()
    private var peerBatchJob: Job? = null
    val discoveredPeers: Map<String, PeerData> get() = rawDiscoveredPeers

    // Interactive reflex challenge state and telemetry
    var challengeScore by mutableIntStateOf(0)
    var challengeTotalTargets by mutableIntStateOf(0)
    var lastFocusReport by mutableStateOf<com.college.feature.attendance.telemetry.CognitiveFocusReport?>(null)
    val challengeScorePercentage: Int
        get() = if (challengeTotalTargets > 0) (challengeScore * 100) / challengeTotalTargets else 0
    val isChallengePassed: Boolean
        get() = (challengeTotalTargets == 0 || challengeScorePercentage >= 55) &&
                (lastFocusReport == null || lastFocusReport?.isPassing == true)

    val isDividedProxy: Boolean
        get() = lastFocusReport?.classification == com.college.feature.attendance.telemetry.FocusClassification.DIVIDED_ATTENTION_PROXY

    val hasLocalDefenseFailed: Boolean
        get() = isDividedProxy || !isChallengePassed || faceProxyDetected || ultrasonicProximityDetected

    fun recordChallengeResult(hit: Int, total: Int) {
        challengeScore = hit
        challengeTotalTargets = total
    }

    fun recordBehavioralReport(report: com.college.feature.attendance.telemetry.CognitiveFocusReport) {
        lastFocusReport = report
    }

    var capturedFaceBase64 by mutableStateOf<String?>(null)
    var capturedFaceBytes by mutableStateOf<ByteArray?>(null)

    // Records the locked face biometric payload and immediately uploads to cloud registry
    fun recordFaceCapture(faceB64: String, faceBytes: ByteArray? = null) {
        capturedFaceBase64 = faceB64
        capturedFaceBytes = faceBytes
        submitMyNeighborsToCloud(keepRadioActive = true)
    }

    // Layer 3 and Layer 4 anti-proxy state flags
    var faceProxyDetected by mutableStateOf(false)
        private set
    var ultrasonicProximityDetected by mutableStateOf(false)
        private set
    var coLocatedPeerId by mutableStateOf<String?>(null)
        private set
    var ultrasonicSnrDb by mutableStateOf(0.0)
        private set
    var auditedRecord by mutableStateOf<AttendanceRecordItem?>(null)
        private set
    var ultrasonicWasTriggered by mutableStateOf(false)
        private set
    var isRefreshingAudit by mutableStateOf(false)
        private set
    private var auditPollingJob: Job? = null
    private var airVerdictJob: Job? = null

    val proximityManager by lazy {
        com.college.feature.attendance.audio.UltrasonicProximityManager(application.applicationContext)
    }
    private val testedUltrasonicPeers = mutableSetOf<String>()

    // Batched top 3 nearest desk/bench neighbors (median RSSI >= -82 dBm)
    var topDeskNeighbours by mutableStateOf<List<PeerData>>(emptyList())
        private set

    private fun startPeerBatchUpdates() {
        if (peerBatchJob?.isActive == true) return
        peerBatchJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(300L)
                val top = rawDiscoveredPeers.values
                    .filter { it.medianRssi >= -82 }
                    .sortedByDescending { it.medianRssi }
                    .take(3)
                withContext(Dispatchers.Main) {
                    topDeskNeighbours = top
                }
            }
        }
    }

    private fun stopPeerBatchUpdates() {
        peerBatchJob?.cancel()
        peerBatchJob = null
    }

    private fun flushPeerBatchImmediately() {
        topDeskNeighbours = rawDiscoveredPeers.values
            .filter { it.medianRssi >= -82 }
            .sortedByDescending { it.medianRssi }
            .take(3)
    }

    // Radio callbacks bound by host activity
    var onStartBroadcasting: ((token: String, txPower: Int) -> Unit)? = null
    var onStopBroadcasting: (() -> Unit)? = null
    var onStartScanning: (() -> Unit)? = null
    var onStopScanning: (() -> Unit)? = null

    var studentDiscoverySecondsRemaining by mutableIntStateOf(0)
    private var teacherTimer: CountDownTimer? = null
    private var studentTimer: CountDownTimer? = null

    init {
        SessionManager.default.init(application.applicationContext)
        com.college.core.network.ServerConfigManager.default.init(application.applicationContext)
        currentRollNumber = SessionManager.default.currentUser?.rollNumber
            ?: prefs.getString("roll_number", defaultLocalId)
            ?: defaultLocalId
        val savedUrl = prefs.getString("server_url", null)
        if (!savedUrl.isNullOrBlank() && !savedUrl.contains("10.0.2.2") && !savedUrl.contains("10.115.") && !savedUrl.contains(":53317") && !savedUrl.contains(":8000")) {
            AttendanceApiClient.baseUrl = savedUrl
        } else {
            AttendanceApiClient.baseUrl = "https://ble-attendance-api-dbf1.onrender.com/"
            prefs.edit().putString("server_url", AttendanceApiClient.baseUrl).apply()
        }
        hydrateSessionFromPrefs()
        if (isTeacherMode) {
            loadCourses()
        }
        viewModelScope.launch {
            SessionManager.default.state.collect { state ->
                if (state is com.college.core.model.SessionState.Authenticated && isTeacherMode) {
                    loadCourses()
                }
            }
        }
    }

    /**
     * SessionManager is now backed by persistent SharedPreferences.
     * This fallback ensures legacy pref entries are migrated if SessionManager
     * has not yet been initialized with an active session.
     */
    private fun hydrateSessionFromPrefs() {
        if (SessionManager.default.isLoggedIn) {
            SessionManager.default.currentUser?.rollNumber?.let { currentRollNumber = it }
            return
        }
        val token = prefs.getString("auth_token", null) ?: return
        val role = Role.fromBackendName(prefs.getString("user_role", null)) ?: Role.STUDENT
        val roll = prefs.getString("roll_number", null)
        SessionManager.default.startSession(
            user = User(
                id = roll ?: "legacy-user",
                name = prefs.getString("user_name", "User") ?: "User",
                email = "",
                roles = setOf(role),
                rollNumber = roll
            ),
            token = token
        )
    }

    // Clear previous audit results or cancel active session to start fresh
    fun resetSession() {
        val sessId = backendSessionId
        if (isSessionActive && SessionManager.default.isLoggedIn && sessId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    api.cancelSession(sessId)
                } catch (_: Exception) { }
            }
        }
        stopTeacherSession()
        airVerdictJob?.cancel()
        airVerdictJob = null
        backendSessionId = null
        finalizedRecords = emptyList()
        cloudStatusMessage = null
    }

    // Persist and update student roll number (even offline)
    fun updateRollNumber(newRoll: String) {
        val trimmed = newRoll.trim().uppercase()
        if (trimmed.isNotEmpty()) {
            currentRollNumber = trimmed
            prefs.edit().putString("roll_number", trimmed).apply()
        }
    }

    // Persist custom server URL
    fun setServerUrl(url: String) {
        AttendanceApiClient.baseUrl = url
        prefs.edit().putString("server_url", AttendanceApiClient.baseUrl).apply()
    }

    fun login(email: String, pass: String, onOtpRequired: (String) -> Unit = {}, onComplete: (Boolean) -> Unit = {}) {
        isAuthLoading = true
        authErrorText = null
        viewModelScope.launch(Dispatchers.IO) {
            val result = authRepo.login(email, pass)
            withContext(Dispatchers.Main) {
                isAuthLoading = false
                when (result) {
                    is NetworkResult.Success -> {
                        val body = result.data
                        val token = body.accessToken
                        val user = body.user
                        if (body.requiresOtp) {
                            onOtpRequired(body.email)
                            onComplete(true)
                        } else if (token != null && user != null) {
                            syncLocalSession(user.rollNumber, token, user.name, user.role)
                            if (isTeacherMode) {
                                loadCourses()
                            }
                            onComplete(true)
                        } else {
                            onComplete(true)
                        }
                    }
                    is NetworkResult.Error -> {
                        authErrorText = "Login failed: ${result.message}"
                        onComplete(false)
                    }
                    is NetworkResult.Exception -> {
                        authErrorText = "Network error: ${result.throwable.localizedMessage ?: "Cannot reach server"}"
                        onComplete(false)
                    }
                }
            }
        }
    }

    fun verifyLoginOtp(email: String, otpCode: String, onComplete: (Boolean) -> Unit = {}) {
        isAuthLoading = true
        authErrorText = null
        viewModelScope.launch(Dispatchers.IO) {
            val result = authRepo.verifyLoginOtp(email, otpCode)
            withContext(Dispatchers.Main) {
                isAuthLoading = false
                when (result) {
                    is NetworkResult.Success -> {
                        val user = result.data.user
                        syncLocalSession(user.rollNumber, result.data.accessToken, user.name, user.role)
                        if (isTeacherMode) {
                            loadCourses()
                        }
                        onComplete(true)
                    }
                    is NetworkResult.Error -> {
                        authErrorText = "Verification failed: ${result.message}"
                        onComplete(false)
                    }
                    is NetworkResult.Exception -> {
                        authErrorText = "Network error: ${result.throwable.localizedMessage ?: "Cannot reach server"}"
                        onComplete(false)
                    }
                }
            }
        }
    }

    private fun syncLocalSession(roll: String?, token: String, name: String, role: String) {
        roll?.let { currentRollNumber = it }
        prefs.edit()
            .putString("auth_token", token)
            .putString("user_name", name)
            .putString("user_role", role)
            .putString("roll_number", currentRollNumber)
            .apply()
    }

    fun register(req: RegisterRequest, onOtpRequired: (String) -> Unit = {}, onComplete: (Boolean) -> Unit = {}) {
        isAuthLoading = true
        authErrorText = null
        viewModelScope.launch(Dispatchers.IO) {
            val result = authRepo.register(req)
            withContext(Dispatchers.Main) {
                isAuthLoading = false
                when (result) {
                    is NetworkResult.Success -> {
                        onOtpRequired(result.data.email)
                        onComplete(true)
                    }
                    is NetworkResult.Error -> {
                        authErrorText = "Registration failed: ${result.message}"
                        onComplete(false)
                    }
                    is NetworkResult.Exception -> {
                        authErrorText = "Network error: ${result.throwable.localizedMessage ?: "Cannot reach server"}"
                        onComplete(false)
                    }
                }
            }
        }
    }

    fun verifyRegisterOtp(email: String, otpCode: String, onComplete: (Boolean) -> Unit = {}) {
        isAuthLoading = true
        authErrorText = null
        viewModelScope.launch(Dispatchers.IO) {
            val result = authRepo.verifyRegisterOtp(email, otpCode)
            withContext(Dispatchers.Main) {
                isAuthLoading = false
                when (result) {
                    is NetworkResult.Success -> {
                        val user = result.data.user
                        syncLocalSession(user.rollNumber, result.data.accessToken, user.name, user.role)
                        if (isTeacherMode) {
                            loadCourses()
                        }
                        onComplete(true)
                    }
                    is NetworkResult.Error -> {
                        authErrorText = "Verification failed: ${result.message}"
                        onComplete(false)
                    }
                    is NetworkResult.Exception -> {
                        authErrorText = "Network error: ${result.throwable.localizedMessage ?: "Cannot reach server"}"
                        onComplete(false)
                    }
                }
            }
        }
    }

    fun resendOtp(email: String, purpose: String = "LOGIN", onComplete: (Boolean) -> Unit = {}) {
        isAuthLoading = true
        authErrorText = null
        viewModelScope.launch(Dispatchers.IO) {
            val result = authRepo.resendOtp(email, purpose)
            withContext(Dispatchers.Main) {
                isAuthLoading = false
                when (result) {
                    is NetworkResult.Success -> onComplete(true)
                    is NetworkResult.Error -> {
                        authErrorText = "Resend failed: ${result.message}"
                        onComplete(false)
                    }
                    is NetworkResult.Exception -> {
                        authErrorText = "Network error: ${result.throwable.localizedMessage ?: "Cannot reach server"}"
                        onComplete(false)
                    }
                }
            }
        }
    }

    fun logout() {
        SessionManager.default.endSession()
        stopTeacherSession()
        backendSessionId = null
        finalizedRecords = emptyList()
        authErrorText = null
        availableCourses = emptyList()
        selectedCourse = null
        currentRollNumber = defaultLocalId
        prefs.edit()
            .remove("auth_token")
            .remove("user_name")
            .remove("user_role")
            .remove("roll_number")
            .apply()
    }

    // Handles 401 Unauthorized from backend gracefully (AuthInterceptor has
    // already cleared the shared session; this surfaces the messaging).
    fun handleAuthExpired(message: String = "Login session expired. Please sign in again.") {
        val token = SessionManager.default.activeToken
        val hasRealCloudJwt = token?.contains(".") == true && !token.contains("mock")
        if (hasRealCloudJwt) {
            logout()
            authErrorText = message
            cloudStatusMessage = message
            showAuthDialog = true
        }
    }

    // Fetch courses taught by teacher
    fun loadCourses() {
        if (!SessionManager.default.isLoggedIn) return
        isLoadingCourses = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = api.listCourses()
                withContext(Dispatchers.Main) {
                    isLoadingCourses = false
                    if (res.code() == 401) {
                        handleAuthExpired()
                    } else if (res.isSuccessful && res.body() != null) {
                        availableCourses = res.body()!!
                        if (selectedCourse == null && availableCourses.isNotEmpty()) {
                            selectedCourse = availableCourses.first()
                        }
                    } else {
                        availableCourses = emptyList()
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { isLoadingCourses = false }
            }
        }
    }

    // Create a new course for the current teacher
    fun createCourse(courseCode: String, courseName: String, onComplete: (Boolean) -> Unit = {}) {
        if (!SessionManager.default.isLoggedIn) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = api.createCourse(
                    CourseCreateRequest(courseCode.trim().uppercase(), courseName.trim())
                )
                withContext(Dispatchers.Main) {
                    if (res.code() == 401) {
                        handleAuthExpired()
                        onComplete(false)
                    } else if (res.isSuccessful && res.body() != null) {
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
    fun submitMyNeighborsToCloud(keepRadioActive: Boolean = false) {
        val sessId = detectedSessionId.takeIf { it != "----" } ?: backendSessionId ?: return
        val neighborIds = topDeskNeighbours.map { it.peerId }
        val closeNeighborIds = rawDiscoveredPeers.values
            .filter { it.medianRssi >= -65 }
            .map { it.peerId }
        val facePayload = capturedFaceBase64
        val faceBytes = capturedFaceBytes
        val dividedAttention = isDividedProxy
        val challengePassed = isChallengePassed
        val challengeHits = challengeScore
        viewModelScope.launch(Dispatchers.IO) {
            val result = if (faceBytes != null) {
                // Submits attendance report with raw binary JPEG payload bypassing Base64 overhead
                val requestBody = faceBytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                val saltHeader = detectedSessionSalt.takeIf { it != "------" }
                val neighborsHeader = neighborIds.joinToString(",")
                val closeHeader = closeNeighborIds.joinToString(",").takeIf { it.isNotEmpty() }
                safeApiCall {
                    api.submitNeighborsBinary(
                        sessionId = sessId,
                        sessionSalt = saltHeader,
                        studentId = studentShortId,
                        neighbors = neighborsHeader,
                        closeNeighbors = closeHeader,
                        coLocatedPeerId = coLocatedPeerId,
                        challengePassed = challengePassed,
                        challengeScore = challengeHits,
                        dividedAttentionDetected = dividedAttention,
                        ultrasonicProximityDetected = ultrasonicProximityDetected,
                        relayedReportsJson = null,
                        imageBytes = requestBody
                    )
                }
            } else {
                val req = SubmitNeighborsRequest(
                    studentId = studentShortId,
                    neighbors = neighborIds,
                    closeNeighbors = closeNeighborIds,
                    sessionSalt = detectedSessionSalt.takeIf { it != "------" },
                    faceImageBase64 = facePayload,
                    ultrasonicProximityDetected = ultrasonicProximityDetected,
                    coLocatedPeerId = coLocatedPeerId,
                    dividedAttentionDetected = dividedAttention,
                    challengePassed = challengePassed,
                    challengeScore = challengeHits
                )
                safeApiCall { api.submitNeighbors(sessId, req) }
            }
            withContext(Dispatchers.Main) {
                when (result) {
                    is NetworkResult.Success -> {
                        if (!keepRadioActive && studentDiscoverySecondsRemaining == 0) {
                            studentPhase = if (hasLocalDefenseFailed) StudentPhase.UNVERIFIED_FLAGGED else StudentPhase.WAITING_FOR_AUDIT
                            startPollingForAudit(sessId)
                        }
                        if (!keepRadioActive) {
                            onStopScanning?.invoke()
                            onStopBroadcasting?.invoke()
                        }
                    }
                    is NetworkResult.Error -> {
                        val detail = result.errorPayload?.detail ?: result.message
                        if (result.code == 403) {
                            if (detail.contains("Biometric", ignoreCase = true) || detail.contains("Duplicate face", ignoreCase = true)) {
                                faceProxyDetected = true
                            }
                            if (detail.contains("Ultrasonic", ignoreCase = true) || detail.contains("desk", ignoreCase = true)) {
                                ultrasonicProximityDetected = true
                            }
                            studentStatusMessage = detail
                            if (!keepRadioActive || studentDiscoverySecondsRemaining == 0) {
                                studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                                startPollingForAudit(sessId)
                                onStopScanning?.invoke()
                                onStopBroadcasting?.invoke()
                            }
                        } else {
                            if (!keepRadioActive || studentDiscoverySecondsRemaining == 0) {
                                studentStatusMessage = detail
                                studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                                startPollingForAudit(sessId)
                                onStopScanning?.invoke()
                                onStopBroadcasting?.invoke()
                            } else {
                                android.util.Log.w("AttendanceViewModel", "Intermediate peer sync pending: ${result.code} - $detail")
                            }
                        }
                    }
                    is NetworkResult.Exception -> {
                        if (!keepRadioActive || studentDiscoverySecondsRemaining == 0) {
                            studentStatusMessage = "Connection failed: Please verify classroom Wi-Fi connection"
                            studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                            onStopScanning?.invoke()
                            onStopBroadcasting?.invoke()
                        } else {
                            android.util.Log.w("AttendanceViewModel", "Intermediate peer sync network error: ${result.throwable.message}")
                        }
                    }
                }
                Unit
            }
        }
    }

    // Start teacher session and initialize backend record
    fun startTeacherSession(durationSeconds: Int = 60, courseId: String? = null) {
        val targetCourseId = courseId ?: selectedCourse?.id
        backendSessionId = null
        sessionId = UUID.randomUUID().toString().replace("-", "").take(4).uppercase()
        sessionSalt = UUID.randomUUID().toString().replace("-", "").take(4).uppercase()
        finalizedRecords = emptyList()
        cloudStatusMessage = null

        isSessionActive = true
        remainingSeconds = durationSeconds

        val token = SessionManager.default.activeToken
        val hasRealCloudJwt = token?.contains(".") == true && !token.contains("mock")

        if (SessionManager.default.isLoggedIn && hasRealCloudJwt) {
            isCloudSyncing = true
            viewModelScope.launch(Dispatchers.IO) {
                val req = SessionCreateRequest(
                    courseId = targetCourseId,
                    sessionToken = sessionId,
                    sessionSalt = sessionSalt,
                    courseCode = selectedCourse?.courseCode,
                    courseName = selectedCourse?.courseName
                )
                val result = safeApiCall { api.createSession(req) }
                withContext(Dispatchers.Main) {
                    isCloudSyncing = false
                    when (result) {
                        is NetworkResult.Success -> {
                            backendSessionId = result.data.id
                            cloudStatusMessage = "Cloud session active: $sessionId"
                            onStartBroadcasting?.invoke(currentBroadcastToken, teacherTxPower)
                        }
                        is NetworkResult.Error -> {
                            cloudStatusMessage = "Cloud sync warning (HTTP ${result.code}): running locally ($sessionId)"
                            android.util.Log.e("AttendanceViewModel", "Session create failed: HTTP ${result.code} - ${result.message}")
                            onStartBroadcasting?.invoke(currentBroadcastToken, teacherTxPower)
                        }
                        is NetworkResult.Exception -> {
                            cloudStatusMessage = "Cloud sync error: ${result.throwable.localizedMessage ?: "Offline"}"
                            android.util.Log.e("AttendanceViewModel", "Session create exception", result.throwable)
                            onStartBroadcasting?.invoke(currentBroadcastToken, teacherTxPower)
                        }
                    }
                }
            }
        } else {
            cloudStatusMessage = "Local session active (code: $sessionId)"
            onStartBroadcasting?.invoke(currentBroadcastToken, teacherTxPower)
        }

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

        val sessId = backendSessionId ?: sessionId.takeIf { it != "----" }

        if (!SessionManager.default.isLoggedIn || sessId == null) {
            finalizedRecords = emptyList()
            cloudStatusMessage = "Cannot finalize: No active cloud session or not signed in."
            return
        }

        isCloudSyncing = true
        cloudStatusMessage = "Finalizing attendance..."
        viewModelScope.launch(Dispatchers.IO) {
            val reportPayload = MeshAuditRunRequest(emptyMap())
            val result = safeApiCall { api.auditAndFinalize(sessId, reportPayload) }
            withContext(Dispatchers.Main) {
                isCloudSyncing = false
                when (result) {
                    is NetworkResult.Success -> {
                        val body = result.data
                        finalizedRecords = body.records ?: emptyList()
                        recordSessionToHistory(finalizedRecords)
                        val verifiedCount = finalizedRecords.count { it.status == "VERIFIED_PRESENT" }
                        val reviewCount = finalizedRecords.count { it.status == "ISOLATED_FLAGGED" || it.status == "UNVERIFIED" }
                        cloudStatusMessage = if (reviewCount > 0) {
                            "Completed • $verifiedCount present, $reviewCount need review"
                        } else {
                            "Completed • $verifiedCount present"
                        }
                        broadcastAirVerdicts(finalizedRecords)
                    }
                    is NetworkResult.Error -> {
                        if (result.code == 401) {
                            handleAuthExpired()
                        } else {
                            cloudStatusMessage = "Audit failed: HTTP ${result.code} - ${result.message}"
                        }
                    }
                    is NetworkResult.Exception -> {
                        cloudStatusMessage = "Finalize failed: ${result.throwable.localizedMessage}"
                    }
                }
            }
        }
    }

    // Option 2: Sequentially broadcast BLE air verdicts (V:<ID>:OK) for verified students
    fun broadcastAirVerdicts(records: List<AttendanceRecordItem>) {
        val verified = records.filter { it.status == "VERIFIED_PRESENT" }
        if (verified.isEmpty()) return

        airVerdictJob?.cancel()
        airVerdictJob = viewModelScope.launch(Dispatchers.Default) {
            repeat(2) {
                for (rec in verified) {
                    if (!isActive) break
                    val ids = mutableListOf(rec.bleDeviceId)
                    rec.rollNumber?.let { roll ->
                        if (roll.isNotBlank() && roll != rec.bleDeviceId) {
                            ids.add(roll)
                        }
                    }
                    for (id in ids) {
                        if (!isActive) break
                        withContext(Dispatchers.Main) {
                            onStartBroadcasting?.invoke("V:$id:OK", teacherTxPower)
                        }
                        delay(350L)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                onStopBroadcasting?.invoke()
            }
        }
    }

    private fun recordSessionToHistory(records: List<AttendanceRecordItem>) {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val completedSession = AttendanceSessionResponse(
            id = backendSessionId ?: sessionId,
            courseId = selectedCourse?.id ?: "",
            sessionToken = sessionId,
            status = "COMPLETED",
            totalVerified = records.count { it.status == "VERIFIED_PRESENT" },
            totalSpoofers = records.count { it.status == "SPOOFER_REJECTED" },
            startedAt = timestamp,
            records = records,
            courseCode = selectedCourse?.courseCode ?: "",
            courseName = selectedCourse?.courseName ?: ""
        )
        pastSessions = listOf(completedSession) + pastSessions.filter { it.id != completedSession.id }
    }

    fun markStudentVerified(recordOrDeviceId: String) {
        val target = finalizedRecords.firstOrNull { it.bleDeviceId == recordOrDeviceId || it.rollNumber == recordOrDeviceId }
        finalizedRecords = finalizedRecords.map { rec ->
            if (rec.bleDeviceId == recordOrDeviceId || rec.rollNumber == recordOrDeviceId) {
                rec.copy(
                    status = "VERIFIED_PRESENT",
                    verificationMethod = "MANUAL_OVERRIDE",
                    affinityScore = 1.0,
                    remarks = "Confirmed by instructor"
                )
            } else {
                rec
            }
        }
        if (target?.id != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    api.overrideRecord(target.id, RecordOverrideRequest())
                } catch (_: Exception) { }
            }
        }

        // Option 2: Blast air verdict over BLE for manually confirmed student
        val idsToBroadcast = mutableListOf(recordOrDeviceId)
        target?.bleDeviceId?.let { if (it !in idsToBroadcast) idsToBroadcast.add(it) }
        target?.rollNumber?.let { if (it.isNotBlank() && it !in idsToBroadcast) idsToBroadcast.add(it) }

        airVerdictJob?.cancel()
        airVerdictJob = viewModelScope.launch(Dispatchers.Default) {
            repeat(4) {
                for (id in idsToBroadcast) {
                    if (!isActive) break
                    withContext(Dispatchers.Main) {
                        onStartBroadcasting?.invoke("V:$id:OK", teacherTxPower)
                    }
                    delay(350L)
                }
            }
            withContext(Dispatchers.Main) {
                onStopBroadcasting?.invoke()
            }
        }
    }

    fun loadSessionHistory() {
        isLoadingHistory = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = api.getSessionHistory()
                withContext(Dispatchers.Main) {
                    isLoadingHistory = false
                    if (res.isSuccessful && res.body() != null) {
                        pastSessions = res.body()!!
                    } else {
                        pastSessions = emptyList()
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    isLoadingHistory = false
                    pastSessions = emptyList()
                }
            }
        }
    }

    fun stopTeacherSession() {
        teacherTimer?.cancel()
        teacherTimer = null
        isSessionActive = false
        remainingSeconds = 0
        airVerdictJob?.cancel()
        airVerdictJob = null
        onStopBroadcasting?.invoke()
    }

    // Student scanning start
    fun startStudentScanning() {
        stopPeerBatchUpdates()
        rawDiscoveredPeers.clear()
        topDeskNeighbours = emptyList()
        studentStatusMessage = null
        challengeScore = 0
        challengeTotalTargets = 0
        lastFocusReport = null
        capturedFaceBase64 = null
        capturedFaceBytes = null
        faceProxyDetected = false
        ultrasonicProximityDetected = false
        coLocatedPeerId = null
        ultrasonicSnrDb = 0.0
        testedUltrasonicPeers.clear()
        auditedRecord = null
        ultrasonicWasTriggered = false
        isRefreshingAudit = false
        auditPollingJob?.cancel()
        auditPollingJob = null

        // Real classroom mode: Listen for teacher BLE beacon broadcast
        detectedSessionId = "----"
        detectedSessionSalt = "------"
        studentPhase = StudentPhase.LISTENING_FOR_TEACHER
        onStartScanning?.invoke()
    }

    // Student scanning stop
    fun stopStudentScanning() {
        stopPeerBatchUpdates()
        studentTimer?.cancel()
        studentTimer = null
        studentDiscoverySecondsRemaining = 0
        auditPollingJob?.cancel()
        auditPollingJob = null
        studentPhase = StudentPhase.STANDBY
        onStopScanning?.invoke()
        onStopBroadcasting?.invoke()
    }

    // Reset student attendance session back to standby
    fun resetStudentSession() {
        stopPeerBatchUpdates()
        studentTimer?.cancel()
        studentTimer = null
        studentDiscoverySecondsRemaining = 0
        auditPollingJob?.cancel()
        auditPollingJob = null
        rawDiscoveredPeers.clear()
        topDeskNeighbours = emptyList()
        studentStatusMessage = null
        challengeScore = 0
        challengeTotalTargets = 0
        lastFocusReport = null
        capturedFaceBase64 = null
        capturedFaceBytes = null
        faceProxyDetected = false
        ultrasonicProximityDetected = false
        coLocatedPeerId = null
        ultrasonicSnrDb = 0.0
        testedUltrasonicPeers.clear()
        auditedRecord = null
        ultrasonicWasTriggered = false
        isRefreshingAudit = false
        detectedSessionId = "----"
        detectedSessionSalt = "------"
        studentPhase = StudentPhase.STANDBY
        onStopScanning?.invoke()
        onStopBroadcasting?.invoke()
    }

    // Student proceeds past attention score screen
    fun proceedStudentToNext() {
        studentTimer?.cancel()
        studentTimer = null
        studentDiscoverySecondsRemaining = 0
        val sessId = detectedSessionId.takeIf { it != "----" } ?: backendSessionId ?: ""
        if (hasLocalDefenseFailed) {
            if (studentStatusMessage == null) {
                studentStatusMessage = "Attendance not confirmed. Please see instructor."
            }
            studentPhase = StudentPhase.UNVERIFIED_FLAGGED
            submitMyNeighborsToCloud(keepRadioActive = false)
            if (sessId.isNotBlank()) {
                startPollingForAudit(sessId)
            }
            onStartScanning?.invoke()
            onStopBroadcasting?.invoke()
        } else {
            studentPhase = StudentPhase.WAITING_FOR_AUDIT
            studentStatusMessage = null
            submitMyNeighborsToCloud(keepRadioActive = false)
            if (sessId.isNotBlank()) {
                startPollingForAudit(sessId)
            }
            onStartScanning?.invoke()
            onStopBroadcasting?.invoke()
        }
    }

    private fun startStudentDiscoveryWindow() {
        startPeerBatchUpdates()
        studentDiscoverySecondsRemaining = 15
        val probePayload = "$detectedSessionId$studentShortId"
        onStartBroadcasting?.invoke(probePayload, AdvertiseSettings.ADVERTISE_TX_POWER_LOW)

        studentTimer?.cancel()
        studentTimer = object : CountDownTimer(15000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                studentDiscoverySecondsRemaining = (millisUntilFinished / 1000L).toInt()
                val elapsed = 15 - studentDiscoverySecondsRemaining
                // Initial quick cloud check-in after 3 seconds so the server records this student in the session
                if (elapsed == 3 || (elapsed > 3 && elapsed % 4 == 0 && topDeskNeighbours.isNotEmpty())) {
                    submitMyNeighborsToCloud(keepRadioActive = true)
                }
            }

            override fun onFinish() {
                studentDiscoverySecondsRemaining = 0
                flushPeerBatchImmediately()
                // Background BLE discovery window complete; sync neighbors to cloud
                submitMyNeighborsToCloud(keepRadioActive = true)
                // studentPhase remains PROBING_NEIGHBORS so the score window stays visible
                // until the student reviews their metrics and explicitly taps 'Next'
            }
        }.start()
    }

    // Processes incoming BLE advertising packets for beacon and peer discovery
    fun onPacketReceived(rssi: Int, messageText: String) {
        val cleanMsg = messageText.trim()

        if (isTeacherMode) return

        // Option 2: Air Verdict packet broadcast by teacher (V:<ID>:OK)
        if (cleanMsg.startsWith("V:")) {
            val parts = cleanMsg.split(":")
            if (parts.size >= 3 && parts[2] == "OK") {
                val targetId = parts[1].trim()
                val isMatch = targetId.equals(studentShortId, ignoreCase = true) ||
                        targetId.equals(currentRollNumber, ignoreCase = true)
                if (isMatch) {
                    stopPeerBatchUpdates()
                    studentPhase = StudentPhase.VERIFIED_PRESENT
                    studentStatusMessage = "Attendance Confirmed"
                    auditPollingJob?.cancel()
                    auditPollingJob = null
                    onStopScanning?.invoke()
                    onStopBroadcasting?.invoke()
                    return
                }
            }
        }

        // Check for teacher beacon packet format (S:<SessionID>:<Salt>)
        val isBeacon = cleanMsg.startsWith("S:") || cleanMsg.startsWith("SESSION:")
        if (isBeacon && studentPhase == StudentPhase.LISTENING_FOR_TEACHER) {
            val parts = cleanMsg.split(":")
            if (parts.size >= 2) {
                detectedSessionId = parts[1]
                detectedSessionSalt = if (parts.size >= 3) parts[2] else ""
                studentPhase = StudentPhase.PROBING_NEIGHBORS
                startStudentDiscoveryWindow()
            }
            return
        }

        // Check for student peer discovery packet
        val isPeerProbe = (cleanMsg.length == 8 || (cleanMsg.length > 8 && cleanMsg.startsWith(detectedSessionId)))
                && !cleanMsg.startsWith("S:") && !cleanMsg.startsWith("SESSION:")
        val canReceiveProbe = studentPhase == StudentPhase.PROBING_NEIGHBORS ||
                studentPhase == StudentPhase.WAITING_FOR_AUDIT ||
                studentPhase == StudentPhase.VERIFIED_PRESENT ||
                studentPhase == StudentPhase.DISCOVERY_COMPLETE ||
                studentPhase == StudentPhase.UNVERIFIED_FLAGGED

        if (isPeerProbe && canReceiveProbe && detectedSessionId != "----") {
            // Verify packet matches active session ID
            if (cleanMsg.startsWith(detectedSessionId)) {
                val peerId = cleanMsg.removePrefix(detectedSessionId).takeLast(4)
                if (peerId.isNotEmpty() && peerId != studentShortId) {
                    val existing = rawDiscoveredPeers[peerId]
                    val updatedPeer = if (existing != null) {
                        existing.withSample(rssi)
                    } else {
                        PeerData(peerId).withSample(rssi)
                    }
                    rawDiscoveredPeers[peerId] = updatedPeer

                    if (existing == null) {
                        // Push updated peer list to server
                        submitMyNeighborsToCloud(keepRadioActive = true)
                    }

                    // Layer 4 Ultrasonic Proximity Evaluation:
                    // Close proximity (RSSI >= -65 dBm, including touching >= -55 dBm) activates the ultrasonic acoustic distance handshake.
                    // Neither radio nor ultrasonic alone can flag a student: both must independently agree.
                    val isClosePeer = rssi >= -65 || updatedPeer.medianRssi >= -65
                    if (isClosePeer && !testedUltrasonicPeers.contains(peerId)) {
                        testedUltrasonicPeers.add(peerId)
                        triggerUltrasonicProximityHandshake(peerId)
                    }
                }
            }
            return
        }
    }

    // Layer 4 ultrasonic proximity handshake using multi-burst statistical consensus
    private fun triggerUltrasonicProximityHandshake(peerId: String) {
        ultrasonicWasTriggered = true
        viewModelScope.launch(Dispatchers.Default) {
            val isEmitter = studentShortId < peerId
            if (isEmitter) {
                // Emitter transmits sustained 30-burst pulse train (3.6s) to ensure listener captures it
                proximityManager.emitBurstSequence(count = 30, intervalMs = 120L)
            } else {
                // Listener listens for 3500ms to guarantee capture of incoming chirp ensemble
                proximityManager.collectBurstEnsemble(listenDurationMs = 3500L) { summary ->
                    handleEnsembleSummary(summary, peerId)
                }
            }
        }
    }

    // Handles statistical ensemble results: only flags when BOTH radio touching (RSSI >= -55 dBm)
    // AND ultrasonic acoustic distance (< 25 cm) independently agree, preventing false positives from either sensor alone.
    private fun handleEnsembleSummary(summary: AcousticStatisticalSummary, peerId: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val peer = rawDiscoveredPeers[peerId]
            val peerMedianRssi = peer?.medianRssi ?: -100
            val isRadioTouching = peerMedianRssi >= -55 || (peer?.rssiSamples?.any { it >= -55 } == true)
            val isAcousticClose = summary.verdict == EnsembleVerdict.CONFIRMED_SAME_DESK ||
                    (summary.sampleCount >= 3 && summary.greenPercentage >= 60) ||
                    (summary.estimatedDistanceCm in 1.0..25.0)

            // Strict Sensor Fusion: "touching (rssi >= -55) && ultrasonic < 25cm"
            if (isRadioTouching && isAcousticClose) {
                // Both independent physical domains confirmed extreme co-location (< 25 cm)
                ultrasonicProximityDetected = true
                coLocatedPeerId = peerId
                ultrasonicSnrDb = summary.trimmedMeanPowerDb
                studentStatusMessage = "Attendance not confirmed. Please see instructor."
                submitMyNeighborsToCloud(keepRadioActive = true)
            } else {
                // Discarded false alarms: one or both sensors confirmed natural physical separation
                android.util.Log.i(
                    "AttendanceViewModel",
                    "Proximity separation verified for $peerId: Radio=$peerMedianRssi dBm (touching=$isRadioTouching), Ultrasonic=${summary.estimatedDistanceCm.roundToInt()} cm (acoustic_close=$isAcousticClose)"
                )
            }
        }
    }

    fun startPollingForAudit(sessionId: String) {
        if (sessionId.isBlank() || sessionId == "----") return
        auditPollingJob?.cancel()
        auditPollingJob = viewModelScope.launch(Dispatchers.IO) {
            var attempts = 0
            while (isActive && attempts < 90) { // Poll for ~3 minutes while teacher session completes
                attempts++
                delay(2000L)
                try {
                    val res = api.getSessionDetails(sessionId)
                    if (res.isSuccessful) {
                        val session = res.body()
                        if (session != null && session.status == "COMPLETED") {
                            val currentUserId = SessionManager.default.currentUser?.id
                            val myRecord = session.records?.firstOrNull { rec ->
                                rec.bleDeviceId.equals(studentShortId, ignoreCase = true) ||
                                (rec.rollNumber != null && rec.rollNumber.equals(currentRollNumber, ignoreCase = true)) ||
                                (currentUserId != null && rec.studentId == currentUserId)
                            }
                            withContext(Dispatchers.Main) {
                                auditedRecord = myRecord
                                if (myRecord != null) {
                                    if (myRecord.status == "VERIFIED_PRESENT") {
                                        studentPhase = StudentPhase.VERIFIED_PRESENT
                                        studentStatusMessage = "Attendance Confirmed"
                                        onStopScanning?.invoke()
                                        onStopBroadcasting?.invoke()
                                    } else {
                                        studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                                        studentStatusMessage = when {
                                            myRecord.status == "COGNITIVE_CHALLENGE_FAILED" -> "Cognitive challenge failed"
                                            myRecord.status == "DIVIDED_ATTENTION_PROXY" -> "Divided attention proxy detected"
                                            myRecord.status == "CO_LOCATED_SAME_DESK_PROXY" -> "Desk co-location rejected"
                                            myRecord.status == "DUPLICATE_FACE_PROXY" -> "Duplicate face biometric rejected"
                                            myRecord.status == "NO_FACE_DETECTED" -> "No face detected in check-in"
                                            myRecord.status == "SALT_REJECTED" || myRecord.status == "SPOOFER_REJECTED" -> "In-room beacon verification failed"
                                            myRecord.status == "ISOLATED_FLAGGED" -> "Needs instructor confirmation"
                                            else -> myRecord.remarks ?: "Attendance not confirmed"
                                        }
                                        onStartScanning?.invoke()
                                    }
                                } else {
                                    studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                                    studentStatusMessage = "Attendance not confirmed"
                                    onStartScanning?.invoke()
                                }
                            }
                            break
                        } else if (session?.status == "CANCELLED") {
                            withContext(Dispatchers.Main) {
                                studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                                studentStatusMessage = "Session was cancelled by the instructor"
                                onStopScanning?.invoke()
                            }
                            break
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    fun refreshAuditStatus() {
        val sessId = detectedSessionId.takeIf { it != "----" } ?: backendSessionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { isRefreshingAudit = true }
            try {
                val res = api.getSessionDetails(sessId)
                if (res.isSuccessful) {
                    val session = res.body()
                    if (session != null && session.status == "COMPLETED") {
                        val currentUserId = SessionManager.default.currentUser?.id
                        val myRecord = session.records?.firstOrNull { rec ->
                            rec.bleDeviceId.equals(studentShortId, ignoreCase = true) ||
                            (rec.rollNumber != null && rec.rollNumber.equals(currentRollNumber, ignoreCase = true)) ||
                            (currentUserId != null && rec.studentId == currentUserId)
                        }
                        withContext(Dispatchers.Main) {
                            auditedRecord = myRecord
                            if (myRecord != null) {
                                if (myRecord.status == "VERIFIED_PRESENT" && !hasLocalDefenseFailed) {
                                    studentPhase = StudentPhase.VERIFIED_PRESENT
                                    studentStatusMessage = "Attendance Confirmed"
                                    onStopScanning?.invoke()
                                    onStopBroadcasting?.invoke()
                                } else {
                                    studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                                    studentStatusMessage = when {
                                        hasLocalDefenseFailed -> "Attendance not confirmed. Please see instructor."
                                        myRecord.status == "ISOLATED_FLAGGED" -> "Needs instructor confirmation"
                                        else -> "Attendance not confirmed"
                                    }
                                    onStartScanning?.invoke()
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                withContext(Dispatchers.Main) { isRefreshingAudit = false }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        teacherTimer?.cancel()
        teacherTimer = null
        studentTimer?.cancel()
        studentTimer = null
        airVerdictJob?.cancel()
        airVerdictJob = null
        auditPollingJob?.cancel()
        auditPollingJob = null
        proximityManager.release()
    }
}
