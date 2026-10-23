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
import com.college.feature.attendance.network.LoginResponse
import com.college.feature.attendance.network.MeshAuditRunRequest
import com.college.feature.attendance.network.OtpSentResponse
import com.college.feature.attendance.network.RegisterRequest
import com.college.feature.attendance.network.ResendOtpRequest
import com.college.feature.attendance.network.SessionCreateRequest
import com.college.feature.attendance.network.SubmitNeighborsRequest
import com.college.feature.attendance.network.TokenResponse
import com.college.feature.attendance.network.VerifyOtpRequest
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

enum class TeacherViewMode {
    MENU,
    TAKE_ATTENDANCE,
    OLDER_SESSIONS
}

/**
 * The attendance engine, now self-contained inside :feature:attendance.
 *
 * Step 4C simplification: identity & mode come exclusively from
 * :core:network's [SessionManager] (populated by the shell/login), NOT from
 * local auth state. JWT headers are attached by [AuthInterceptor] through
 * [AttendanceApiClient]; this class never touches tokens directly.
 * Prefs stay only for app-restart restore (server_url, roll_number, and a
 * token mirror used to re-hydrate the in-memory session).
 */
class AttendanceViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("ble_attendance_prefs", Context.MODE_PRIVATE)

    // Retrofit surface from the shared-session engine (:core:network's AuthInterceptor)
    private val api get() = AttendanceApiClient.service

    // ---- Identity & mode (derived from the active SessionManager session) ----
    val isTeacherMode: Boolean
        get() = SessionManager.default.currentUser?.canTakeAttendance() == true
    val isLoggedIn: Boolean
        get() = SessionManager.default.isLoggedIn
    val currentUserName: String
        get() = SessionManager.default.currentUser?.name ?: "Guest"
    val currentUserRole: String
        get() = SessionManager.default.currentUser?.primaryRole?.name ?: "STUDENT"

    // BLE-local roll id: seeded from the backend profile at login, or a random
    // local id for guest/mock usage inside the shell.
    val defaultLocalId: String = UUID.randomUUID().toString().replace("-", "").take(4).uppercase()
    var currentRollNumber: String by mutableStateOf(defaultLocalId)
    val studentId: String get() = currentRollNumber
    // Exactly 4 characters for fixed-width BLE packets
    val studentShortId: String get() = currentRollNumber.takeLast(4).padStart(4, '0').uppercase()

    var isAuthLoading: Boolean by mutableStateOf(false)
    var authErrorText: String? by mutableStateOf(null)
    var showAuthDialog: Boolean by mutableStateOf(false)

    // Courses for Teacher
    var availableCourses by mutableStateOf<List<CourseResponse>>(emptyList())
    var selectedCourse by mutableStateOf<CourseResponse?>(null)
    var isLoadingCourses by mutableStateOf(false)

    // BLE session state
    var isSessionActive by mutableStateOf(false)
    var sessionId by mutableStateOf("----")
    var sessionSalt by mutableStateOf("------")
    var remainingSeconds by mutableIntStateOf(0)

    // Teacher navigation view mode & history state
    var teacherViewMode by mutableStateOf(TeacherViewMode.MENU)
    var pastSessions by mutableStateOf<List<AttendanceSessionResponse>>(emptyList())
    var isLoadingHistory by mutableStateOf(false)
    var selectedSessionForDetails by mutableStateOf<AttendanceSessionResponse?>(null)

    // Backend cloud session state
    var backendSessionId: String? by mutableStateOf(null)
    var finalizedRecords by mutableStateOf<List<AttendanceRecordItem>>(emptyList())
    var isCloudSyncing: Boolean by mutableStateOf(false)
    var cloudStatusMessage: String? by mutableStateOf(null)
    // Shown on the student's screen when the server rejects their check-in (salt mismatch)
    var studentStatusMessage: String? by mutableStateOf(null)

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

    // Top 3 desk neighbours with median RSSI >= -90 dBm (calibrated for classroom RF attenuation)
    val topDeskNeighbours: List<PeerData>
        get() = discoveredPeers.values
            .filter { it.medianRssi >= -90 }
            .sortedByDescending { it.medianRssi }
            .take(3)

    // Activity radio callbacks (wired by the host Activity in :app)
    var onStartBroadcasting: ((token: String, txPower: Int) -> Unit)? = null
    var onStopBroadcasting: (() -> Unit)? = null
    var onStartScanning: (() -> Unit)? = null
    var onStopScanning: (() -> Unit)? = null

    var studentDiscoverySecondsRemaining by mutableIntStateOf(0)
    private var teacherTimer: CountDownTimer? = null
    private var studentTimer: CountDownTimer? = null

    init {
        currentRollNumber = prefs.getString("roll_number", defaultLocalId) ?: defaultLocalId
        val savedUrl = prefs.getString("server_url", null)
        if (!savedUrl.isNullOrBlank() && !savedUrl.contains("10.0.2.2")) {
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
     * SessionManager is in-memory by design (see :core:network). Until :app
     * gets its own persistence layer, this pref-backed mirror restores the
     * last login across process restarts. Runs only when no session is
     * active, so mock shells / account switching are never overwritten.
     */
    private fun hydrateSessionFromPrefs() {
        if (SessionManager.default.isLoggedIn) return
        val token = prefs.getString("auth_token", null) ?: return
        val role = Role.fromBackendName(prefs.getString("user_role", null)) ?: Role.STUDENT
        val roll = prefs.getString("roll_number", null)
        SessionManager.default.startSession(
            user = User(
                id = roll ?: "legacy-user",
                name = prefs.getString("user_name", "User") ?: "User",
                email = "",
                roles = setOf(role)
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
        SessionManager.default.startSession(
            user = User(
                id = body.user.id,
                name = body.user.name,
                email = body.user.email,
                roles = setOf(role)
            ),
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
    fun submitMyNeighborsToCloud() {
        val sessId = detectedSessionId.takeIf { it != "----" } ?: backendSessionId ?: return
        val neighborIds = topDeskNeighbours.map { it.peerId }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val req = SubmitNeighborsRequest(
                    studentId = studentShortId,
                    neighbors = neighborIds,
                    sessionSalt = detectedSessionSalt.takeIf { it != "------" }
                )
                // AuthInterceptor adds the bearer token when a session is active;
                // guest students simply submit anonymously.
                val res = api.submitNeighbors(sessId, req)
                withContext(Dispatchers.Main) {
                    if (res.isSuccessful) {
                        studentPhase = StudentPhase.VERIFIED_PRESENT
                        onStopScanning?.invoke()
                    } else if (res.code() == 403) {
                        // Server rejected this check-in: beacon salt mismatch (remote submission attempt) — show the banter
                        val detail = try { res.errorBody()?.string() } catch (_: Exception) { null }
                        val extracted = Regex("\"detail\"\\s*:\\s*\"(.*?)\"").find(detail ?: "")?.groupValues?.get(1)
                        studentStatusMessage = extracted ?: "Nice try lil bro"
                        studentPhase = StudentPhase.STANDBY
                        onStopScanning?.invoke()
                    } else {
                        // Offline or non-200: fallback to local discovery complete
                        // Student is verified via pure in-degree graph witnessing from online classmates
                        studentPhase = StudentPhase.DISCOVERY_COMPLETE
                        onStopScanning?.invoke()
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    // Offline fallback: BLE discovery was complete, near-field whisper was emitted.
                    // Student will be verified via pure in-degree graph witnessing from online desk neighbors.
                    studentPhase = StudentPhase.DISCOVERY_COMPLETE
                    onStopScanning?.invoke()
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
                        } else {
                            val err = try { res.errorBody()?.string() } catch (_: Exception) { null }
                            cloudStatusMessage = "Cloud sync warning (HTTP ${res.code()}): running locally ($sessionId)"
                            android.util.Log.e("AttendanceViewModel", "Session create failed: HTTP ${res.code()} - $err")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isCloudSyncing = false
                        cloudStatusMessage = "Cloud sync error: ${e.localizedMessage ?: "Offline"}"
                        android.util.Log.e("AttendanceViewModel", "Session create exception", e)
                    }
                }
            }
        } else {
            cloudStatusMessage = "Local session active (code: $sessionId)"
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
        studentPhase = StudentPhase.LISTENING_FOR_TEACHER
        detectedSessionId = "----"
        detectedSessionSalt = "------"
        discoveredPeers.clear()
        studentStatusMessage = null
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
        studentDiscoverySecondsRemaining = 20
        val probePayload = "$detectedSessionId$studentShortId"
        onStartBroadcasting?.invoke(probePayload, AdvertiseSettings.ADVERTISE_TX_POWER_LOW)

        studentTimer?.cancel()
        studentTimer = object : CountDownTimer(20000L, 1000L) {
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
                        val updatedSamples = existing.rssiSamples.toMutableList().apply { add(rssi) }
                        discoveredPeers[peerId] = existing.copy(rssiSamples = updatedSamples)
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
