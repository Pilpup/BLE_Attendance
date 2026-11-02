package com.college.feature.attendance.viewmodel

import android.app.Application
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.CountDownTimer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.college.core.model.Role
import com.college.core.model.User
import com.college.core.network.SessionManager
import com.college.feature.attendance.network.AttendanceApiClient
import com.college.feature.attendance.network.AttendanceRecordItem
import com.college.feature.attendance.network.AttendanceSessionResponse
import com.college.feature.attendance.network.CourseCreateRequest
import com.college.feature.attendance.network.CourseResponse
import com.college.feature.attendance.network.LoginRequest
import com.college.feature.attendance.network.MeshAuditRunRequest
import com.college.feature.attendance.network.RecordOverrideRequest
import com.college.feature.attendance.network.RegisterRequest
import com.college.feature.attendance.network.ResendOtpRequest
import com.college.feature.attendance.network.SessionCreateRequest
import com.college.feature.attendance.network.SubmitNeighborsRequest
import com.college.feature.attendance.network.TokenResponse
import com.college.feature.attendance.network.VerifyOtpRequest
import com.college.feature.attendance.audio.AcousticStatisticalSummary
import com.college.feature.attendance.audio.EnsembleVerdict
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

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
    val discoveredPeers = mutableStateMapOf<String, PeerData>()

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

    // Records the locked face biometric payload and immediately uploads to cloud registry
    fun recordFaceCapture(faceB64: String) {
        capturedFaceBase64 = faceB64
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

    val proximityManager by lazy {
        com.college.feature.attendance.audio.UltrasonicProximityManager(application.applicationContext)
    }
    private val testedUltrasonicPeers = mutableSetOf<String>()

    // Top 3 nearest desk/bench neighbors in the classroom mesh (median RSSI >= -82 dBm)
    val topDeskNeighbours: List<PeerData>
        get() = discoveredPeers.values
            .filter { it.medianRssi >= -82 }
            .sortedByDescending { it.medianRssi }
            .take(3)

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

    // Authenticate user against FastAPI backend (Step 1: password -> triggers 2FA OTP)
    fun login(email: String, pass: String, onOtpRequired: (String) -> Unit = {}, onComplete: (Boolean) -> Unit = {}) {
        isAuthLoading = true
        authErrorText = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = api.login(LoginRequest(email, pass))
                withContext(Dispatchers.Main) {
                    isAuthLoading = false
                    if (res.isSuccessful && res.body() != null) {
                        val body = res.body()!!
                        if (body.requiresOtp) {
                            onOtpRequired(body.email)
                            onComplete(true)
                        } else if (body.accessToken != null && body.user != null) {
                            onAuthenticated(TokenResponse(body.accessToken, body.tokenType ?: "bearer", body.user))
                            if (isTeacherMode) {
                                loadCourses()
                            }
                            onComplete(true)
                        }
                    } else {
                        val err = try { res.errorBody()?.string() } catch (_: Exception) { null }
                        authErrorText = "Login failed: HTTP ${res.code()}${if (!err.isNullOrBlank()) " - $err" else ""}"
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

    // Step 2: Verify login 2FA OTP
    fun verifyLoginOtp(email: String, otpCode: String, onComplete: (Boolean) -> Unit = {}) {
        isAuthLoading = true
        authErrorText = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = api.verifyLoginOtp(VerifyOtpRequest(email, otpCode))
                withContext(Dispatchers.Main) {
                    isAuthLoading = false
                    if (res.isSuccessful && res.body() != null) {
                        onAuthenticated(res.body()!!)
                        if (isTeacherMode) {
                            loadCourses()
                        }
                        onComplete(true)
                    } else {
                        val err = try { res.errorBody()?.string() } catch (_: Exception) { null }
                        authErrorText = "Verification failed: HTTP ${res.code()}${if (!err.isNullOrBlank()) " - $err" else ""}"
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

    /**
     * Single write-through point for successful authentication: pushes
     * identity + JWT into :core:network's [SessionManager] (so AuthInterceptor
     * and every dashboard screen react) and keeps the prefs mirror for
     * app-restart restore.
     */
    private fun onAuthenticated(body: TokenResponse) {
        val role = Role.fromBackendName(body.user.role) ?: Role.STUDENT
        val authenticatedUser = User(
            id = body.user.id,
            name = body.user.name,
            email = body.user.email,
            roles = setOf(role),
            rollNumber = body.user.rollNumber,
            department = body.user.department,
            program = body.user.program,
            semester = body.user.semester,
            section = body.user.section
        )
        SessionManager.default.startSession(
            user = authenticatedUser,
            token = body.accessToken
        )
        body.user.rollNumber?.let { currentRollNumber = it }
        prefs.edit()
            .putString("auth_token", body.accessToken)
            .putString("user_name", body.user.name)
            .putString("user_role", body.user.role)
            .putString("roll_number", currentRollNumber)
            .apply()
    }

    // Register user against FastAPI backend (Step 1: details -> sends OTP)
    fun register(req: RegisterRequest, onOtpRequired: (String) -> Unit = {}, onComplete: (Boolean) -> Unit = {}) {
        isAuthLoading = true
        authErrorText = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = api.register(req)
                withContext(Dispatchers.Main) {
                    isAuthLoading = false
                    if (res.isSuccessful && res.body() != null) {
                        onOtpRequired(res.body()!!.email)
                        onComplete(true)
                    } else {
                        val err = try { res.errorBody()?.string() } catch (_: Exception) { null }
                        authErrorText = "Registration failed: HTTP ${res.code()}${if (!err.isNullOrBlank()) " - $err" else ""}"
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

    // Step 2: Verify registration OTP
    fun verifyRegisterOtp(email: String, otpCode: String, onComplete: (Boolean) -> Unit = {}) {
        isAuthLoading = true
        authErrorText = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = api.verifyRegisterOtp(VerifyOtpRequest(email, otpCode))
                withContext(Dispatchers.Main) {
                    isAuthLoading = false
                    if (res.isSuccessful && res.body() != null) {
                        onAuthenticated(res.body()!!)
                        if (isTeacherMode) {
                            loadCourses()
                        }
                        onComplete(true)
                    } else {
                        val err = try { res.errorBody()?.string() } catch (_: Exception) { null }
                        authErrorText = "Verification failed: HTTP ${res.code()}${if (!err.isNullOrBlank()) " - $err" else ""}"
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

    // Resend OTP
    fun resendOtp(email: String, purpose: String = "LOGIN", onComplete: (Boolean) -> Unit = {}) {
        isAuthLoading = true
        authErrorText = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = api.resendOtp(ResendOtpRequest(email, purpose))
                withContext(Dispatchers.Main) {
                    isAuthLoading = false
                    if (res.isSuccessful) {
                        onComplete(true)
                    } else {
                        val err = try { res.errorBody()?.string() } catch (_: Exception) { null }
                        authErrorText = "Resend failed: ${err ?: "HTTP ${res.code()}"}"
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
        val facePayload = capturedFaceBase64
        val dividedAttention = isDividedProxy
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val req = SubmitNeighborsRequest(
                    studentId = studentShortId,
                    neighbors = neighborIds,
                    sessionSalt = detectedSessionSalt.takeIf { it != "------" },
                    faceImageBase64 = facePayload,
                    ultrasonicProximityDetected = ultrasonicProximityDetected,
                    coLocatedPeerId = coLocatedPeerId,
                    dividedAttentionDetected = dividedAttention
                )
                // AuthInterceptor adds the bearer token when a session is active;
                val res = api.submitNeighbors(sessId, req)
                withContext(Dispatchers.Main) {
                    if (res.isSuccessful) {
                        if (!keepRadioActive && studentDiscoverySecondsRemaining == 0) {
                            if (hasLocalDefenseFailed) {
                                studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                            } else {
                                studentPhase = StudentPhase.WAITING_FOR_AUDIT
                                startPollingForAudit(sessId)
                            }
                        }
                        if (!keepRadioActive) {
                            onStopScanning?.invoke()
                            onStopBroadcasting?.invoke()
                        }
                    } else if (res.code() == 403) {
                        // Handle server rejection caused by session salt mismatch, duplicate face, or ultrasonic proxy
                        val detail = try { res.errorBody()?.string() } catch (_: Exception) { null }
                        val extracted = Regex("\"detail\"\\s*:\\s*\"(.*?)\"").find(detail ?: "")?.groupValues?.get(1)
                        if (extracted?.contains("Biometric", ignoreCase = true) == true || extracted?.contains("Duplicate face", ignoreCase = true) == true) {
                            faceProxyDetected = true
                        }
                        if (extracted?.contains("Ultrasonic", ignoreCase = true) == true || extracted?.contains("desk", ignoreCase = true) == true) {
                            ultrasonicProximityDetected = true
                        }
                        studentStatusMessage = extracted ?: "Security defense verification rejected"
                        // Only abort if this is the final check-in or discovery window has ended
                        if (!keepRadioActive || studentDiscoverySecondsRemaining == 0) {
                            studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                            onStopScanning?.invoke()
                            onStopBroadcasting?.invoke()
                        }
                    } else {
                        // Intermediate background peer sync (404/500) while session propagates must not crash/abort gameplay
                        val errDetail = try { res.errorBody()?.string() } catch (_: Exception) { null }
                        val extracted = Regex("\"detail\"\\s*:\\s*\"(.*?)\"").find(errDetail ?: "")?.groupValues?.get(1)
                        if (!keepRadioActive || studentDiscoverySecondsRemaining == 0) {
                            studentStatusMessage = extracted ?: "Attendance server error (${res.code()})"
                            studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                            onStopScanning?.invoke()
                            onStopBroadcasting?.invoke()
                        } else {
                            android.util.Log.w("AttendanceViewModel", "Intermediate peer sync pending: ${res.code()} - $extracted")
                        }
                    }
                    Unit
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (!keepRadioActive || studentDiscoverySecondsRemaining == 0) {
                        studentStatusMessage = "Connection failed: Please verify classroom Wi-Fi connection"
                        studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                        onStopScanning?.invoke()
                        onStopBroadcasting?.invoke()
                    } else {
                        android.util.Log.w("AttendanceViewModel", "Intermediate peer sync network error: ${e.message}")
                    }
                    Unit
                }
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
                try {
                    val req = SessionCreateRequest(
                        courseId = targetCourseId,
                        sessionToken = sessionId,
                        sessionSalt = sessionSalt,
                        courseCode = selectedCourse?.courseCode,
                        courseName = selectedCourse?.courseName
                    )
                    val res = api.createSession(req)
                    withContext(Dispatchers.Main) {
                        isCloudSyncing = false
                        if (res.isSuccessful && res.body() != null) {
                            backendSessionId = res.body()!!.id
                            cloudStatusMessage = "Cloud session active: $sessionId"
                            // Start broadcasting in-room beacon AFTER session is safely recorded in cloud DB
                            onStartBroadcasting?.invoke(currentBroadcastToken, teacherTxPower)
                        } else {
                            val err = try { res.errorBody()?.string() } catch (_: Exception) { null }
                            cloudStatusMessage = "Cloud sync warning (HTTP ${res.code()}): running locally ($sessionId)"
                            android.util.Log.e("AttendanceViewModel", "Session create failed: HTTP ${res.code()} - $err")
                            onStartBroadcasting?.invoke(currentBroadcastToken, teacherTxPower)
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isCloudSyncing = false
                        cloudStatusMessage = "Cloud sync error: ${e.localizedMessage ?: "Offline"}"
                        android.util.Log.e("AttendanceViewModel", "Session create exception", e)
                        onStartBroadcasting?.invoke(currentBroadcastToken, teacherTxPower)
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
        cloudStatusMessage = "Auditing classroom mesh..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val reportPayload = MeshAuditRunRequest(emptyMap())
                val res = api.auditAndFinalize(sessId, reportPayload)
                withContext(Dispatchers.Main) {
                    isCloudSyncing = false
                    if (res.code() == 401) {
                        handleAuthExpired()
                    } else if (res.isSuccessful && res.body() != null) {
                        val body = res.body()!!
                        finalizedRecords = body.records ?: emptyList()
                        recordSessionToHistory(finalizedRecords)
                        cloudStatusMessage = "Audit complete: ${body.totalVerified} verified, ${body.totalSpoofers} rejected"
                    } else {
                        val errBody = try { res.errorBody()?.string() } catch (_: Exception) { null }
                        cloudStatusMessage = "Audit failed: HTTP ${res.code()}${if (!errBody.isNullOrBlank()) " - $errBody" else ""}"
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
                    remarks = "Verified by instructor"
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
        onStopBroadcasting?.invoke()
    }

    // Student scanning start
    fun startStudentScanning() {
        discoveredPeers.clear()
        studentStatusMessage = null
        challengeScore = 0
        challengeTotalTargets = 0
        lastFocusReport = null
        capturedFaceBase64 = null
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
        studentTimer?.cancel()
        studentTimer = null
        studentDiscoverySecondsRemaining = 0
        auditPollingJob?.cancel()
        auditPollingJob = null
        discoveredPeers.clear()
        studentStatusMessage = null
        challengeScore = 0
        challengeTotalTargets = 0
        lastFocusReport = null
        capturedFaceBase64 = null
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
        val isDividedProxy = lastFocusReport?.classification == com.college.feature.attendance.telemetry.FocusClassification.DIVIDED_ATTENTION_PROXY
        if (isDividedProxy || !isChallengePassed || faceProxyDetected || ultrasonicProximityDetected) {
            if (studentStatusMessage == null) {
                studentStatusMessage = when {
                    faceProxyDetected -> "Biometric proxy rejected: Duplicate face detected in session"
                    ultrasonicProximityDetected -> "Ultrasonic proximity rejected: Co-located on same desk (< 25 cm) with $coLocatedPeerId"
                    isDividedProxy -> lastFocusReport?.diagnosticReason ?: "Divided attention proxy detected"
                    else -> "Attendance verification requirements not met"
                }
            }
            studentPhase = StudentPhase.UNVERIFIED_FLAGGED
            submitMyNeighborsToCloud(keepRadioActive = false)
        } else {
            studentPhase = StudentPhase.WAITING_FOR_AUDIT
            studentStatusMessage = null
            submitMyNeighborsToCloud(keepRadioActive = false)
            val sessId = detectedSessionId.takeIf { it != "----" } ?: backendSessionId ?: ""
            if (sessId.isNotBlank()) {
                startPollingForAudit(sessId)
            }
        }
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
                val elapsed = 15 - studentDiscoverySecondsRemaining
                // Initial quick cloud check-in after 3 seconds so the server records this student in the session
                if (elapsed == 3 || (elapsed > 3 && elapsed % 4 == 0 && topDeskNeighbours.isNotEmpty())) {
                    submitMyNeighborsToCloud(keepRadioActive = true)
                }
            }

            override fun onFinish() {
                studentDiscoverySecondsRemaining = 0
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
                    val existing = discoveredPeers[peerId]
                    val updatedPeer = if (existing != null) {
                        existing.withSample(rssi)
                    } else {
                        PeerData(peerId).withSample(rssi)
                    }
                    discoveredPeers[peerId] = updatedPeer

                    if (existing == null) {
                        // Push updated peer list to server
                        submitMyNeighborsToCloud(keepRadioActive = true)
                    }

                    // Layer 4 Ultrasonic Proximity Evaluation:
                    // 1) Extreme radio proximity: median RSSI >= -55 dBm indicates devices touching / held in the same hands (< 20 cm)
                    if (updatedPeer.medianRssi >= -55 || rssi >= -55) {
                        ultrasonicWasTriggered = true
                        ultrasonicProximityDetected = true
                        coLocatedPeerId = peerId
                        studentStatusMessage = "Co-location proxy rejected: Devices held on same desk (< 20 cm) with $peerId"
                        submitMyNeighborsToCloud(keepRadioActive = true)
                    } else {
                        // 2) Close proximity (-68 dBm to -56 dBm): activate ultrasonic acoustic distance handshake
                        val isClosePeer = rssi >= -68 || updatedPeer.medianRssi >= -68
                        if (isClosePeer && !testedUltrasonicPeers.contains(peerId)) {
                            testedUltrasonicPeers.add(peerId)
                            triggerUltrasonicProximityHandshake(peerId)
                        }
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

    // Handles statistical ensemble results: only flags when consensus confirms physical same-desk co-location
    private fun handleEnsembleSummary(summary: AcousticStatisticalSummary, peerId: String) {
        viewModelScope.launch(Dispatchers.Main) {
            if (summary.verdict == EnsembleVerdict.CONFIRMED_SAME_DESK || (summary.sampleCount >= 3 && summary.greenPercentage >= 60)) {
                // Statistical consensus confirms co-location on same desk (< 25 cm)
                ultrasonicProximityDetected = true
                coLocatedPeerId = peerId
                ultrasonicSnrDb = summary.trimmedMeanPowerDb
                studentStatusMessage = "Ultrasonic proximity rejected: Co-located on same desk (${summary.estimatedDistanceCm.roundToInt()} cm, ${summary.greenPercentage}% Green) with $peerId"
                submitMyNeighborsToCloud(keepRadioActive = true)
            } else {
                // Discarded outliers confirmed natural separation (next bench or distant)
                android.util.Log.i("AttendanceViewModel", "Ultrasonic separation verified for $peerId: ${summary.estimatedDistanceCm.roundToInt()} cm (${summary.bluePercentage}% Blue)")
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
                                    if (myRecord.status == "VERIFIED_PRESENT" && !hasLocalDefenseFailed) {
                                        studentPhase = StudentPhase.VERIFIED_PRESENT
                                        studentStatusMessage = myRecord.remarks ?: "Verified by classroom mesh graph"
                                    } else {
                                        studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                                        studentStatusMessage = when {
                                            hasLocalDefenseFailed -> studentStatusMessage ?: "Local anti-proxy defense rejected"
                                            myRecord.status == "ISOLATED_FLAGGED" -> myRecord.remarks ?: "Zero desk neighbors detected (isolated seat)"
                                            myRecord.status == "SPOOFER_REJECTED" -> myRecord.remarks ?: "Hallway spoofer detected (unreciprocated link)"
                                            myRecord.status == "DUPLICATE_FACE_PROXY" -> "Biometric proxy rejected: Duplicate face detected in session"
                                            myRecord.status == "CO_LOCATED_SAME_DESK_PROXY" -> "Ultrasonic proximity rejected: Co-located on same desk (< 25 cm)"
                                            myRecord.status == "DIVIDED_ATTENTION_PROXY" -> "Divided attention proxy rejected: Simultaneous multi-device gameplay"
                                            myRecord.status == "NO_FACE_DETECTED" -> "Biometric verification failed: No human face detected"
                                            else -> myRecord.remarks ?: "Verification criteria not met"
                                        }
                                    }
                                } else {
                                    studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                                    studentStatusMessage = "No attendance record finalized for this device"
                                }
                            }
                            break
                        } else if (session?.status == "CANCELLED") {
                            withContext(Dispatchers.Main) {
                                studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                                studentStatusMessage = "Session was cancelled by the instructor"
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
                                    studentStatusMessage = myRecord.remarks ?: "Verified by classroom mesh graph"
                                } else {
                                    studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                                    studentStatusMessage = when {
                                        hasLocalDefenseFailed -> studentStatusMessage ?: "Local anti-proxy defense rejected"
                                        myRecord.status == "ISOLATED_FLAGGED" -> myRecord.remarks ?: "Zero desk neighbors detected (isolated seat)"
                                        myRecord.status == "SPOOFER_REJECTED" -> myRecord.remarks ?: "Hallway spoofer detected (unreciprocated link)"
                                        myRecord.status == "DUPLICATE_FACE_PROXY" -> "Biometric proxy rejected: Duplicate face detected in session"
                                        myRecord.status == "CO_LOCATED_SAME_DESK_PROXY" -> "Ultrasonic proximity rejected: Co-located on same desk (< 25 cm)"
                                        myRecord.status == "DIVIDED_ATTENTION_PROXY" -> "Divided attention proxy rejected: Simultaneous multi-device gameplay"
                                        myRecord.status == "NO_FACE_DETECTED" -> "Biometric verification failed: No human face detected"
                                        else -> myRecord.remarks ?: "Verification criteria not met"
                                    }
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
        auditPollingJob?.cancel()
        auditPollingJob = null
        proximityManager.release()
    }
}
