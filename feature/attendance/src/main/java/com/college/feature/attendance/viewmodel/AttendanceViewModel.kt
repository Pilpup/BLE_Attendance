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
        get() = SessionManager.default.currentUser?.name ?: "Student"
    val currentUserRole: String
        get() = SessionManager.default.currentUser?.primaryRole?.name ?: "STUDENT"

    // Device roll number used for BLE broadcast packets
    val defaultLocalId: String = UUID.randomUUID().toString().replace("-", "").take(4).uppercase()
    var currentRollNumber: String by mutableStateOf(defaultLocalId)
    val studentId: String get() = currentRollNumber
    val studentShortId: String get() = currentRollNumber.takeLast(4).padStart(4, '0').uppercase()

    // Valid neighbor short id: 4 chars of A–Z / 0–9 / '-' (roll-derived ids like
    // "-001" or hex-like default ids). Anything outside this alphabet is wire junk.
    private val peerIdPattern = Regex("[0-9A-Z-]{4}")

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
    var isCancelling: Boolean by mutableStateOf(false)
    var cloudStatusMessage: String? by mutableStateOf(null)
    var studentStatusMessage: String? by mutableStateOf(null)

    // Class semester parsed from the selected course ("Semester 3" -> 3), null
    // when no class is picked. Drives the beacon tag and the create-payload
    // class identity used by the server eligibility gate.
    private fun selectedClassSemester(): Int? =
        selectedCourse?.semester?.filter { it.isDigit() }?.toIntOrNull()

    // Beacon broadcast payload: "S:<ID>:<SALT>[:<SEMESTER>]" — the optional 4th
    // field tags the class semester so student phones can skip foreign-class
    // beacons locally (UX only; the server eligibility gate is authoritative).
    val currentBroadcastToken: String
        get() = if (isSessionActive) {
            val sem = selectedClassSemester()
            if (sem != null) "S:$sessionId:$sessionSalt:$sem" else "S:$sessionId:$sessionSalt"
        } else "IDLE"

    // Radio advertising configuration
    val teacherAdvMode = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
    val teacherTxPower = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH

    // Student scanning state and discovered peers
    var studentPhase by mutableStateOf(StudentPhase.STANDBY)
    var detectedSessionId by mutableStateOf("----")
    var detectedSessionSalt by mutableStateOf("------")
    private val rawDiscoveredPeers = java.util.concurrent.ConcurrentHashMap<String, PeerData>()
    private var peerBatchJob: Job? = null

    // Throttle for the unclassified-packet diagnostic log (one line / 2s max)
    private var lastNeglectedPacketLogMs = 0L
    val discoveredPeers: Map<String, PeerData> get() = rawDiscoveredPeers

    // Interactive reflex challenge state (score is cosmetic, informational only)
    var challengeScore by mutableIntStateOf(0)
    var challengeTotalTargets by mutableIntStateOf(0)
    val challengeScorePercentage: Int
        get() = if (challengeTotalTargets > 0) (challengeScore * 100) / challengeTotalTargets else 0

    val hasLocalDefenseFailed: Boolean
        get() = faceProxyDetected || ultrasonicProximityDetected

    fun recordChallengeResult(hit: Int, total: Int) {
        challengeScore = hit
        challengeTotalTargets = total
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

    // Batched top 3 nearest desk/bench neighbors (median RSSI >= -92 dBm)
    var topDeskNeighbours by mutableStateOf<List<PeerData>>(emptyList())
        private set

    private fun startPeerBatchUpdates() {
        if (peerBatchJob?.isActive == true) return
        peerBatchJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(300L)
                val top = rawDiscoveredPeers.values
                    .filter { it.medianRssi >= -92 }
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
            .filter { it.medianRssi >= -92 }
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
        // Prevent double-taps and overlapping cancel/finalize requests
        if (isCancelling || isCloudSyncing) return
        val sessId = backendSessionId
        val wasSessionActive = isSessionActive
        stopTeacherSession()
        airVerdictJob?.cancel()
        airVerdictJob = null

        if (wasSessionActive && SessionManager.default.isLoggedIn && sessId != null) {
            isCancelling = true
            cloudStatusMessage = "Cancelling session…"
            viewModelScope.launch(Dispatchers.IO) {
                val result = safeApiCall { api.cancelSession(sessId) }
                withContext(Dispatchers.Main) {
                    isCancelling = false
                    backendSessionId = null
                    finalizedRecords = emptyList()
                    cloudStatusMessage = when (result) {
                        is NetworkResult.Success ->
                            "Session cancelled • student records retained on server"
                        is NetworkResult.Error ->
                            "Session cancelled • couldn't reach server, it will auto-expire"
                        is NetworkResult.Exception ->
                            "Cancelled locally • offline — server session will auto-expire"
                    }
                }
            }
        } else {
            backendSessionId = null
            finalizedRecords = emptyList()
            cloudStatusMessage = null
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

    // Format attendance records and session details as CSV for export
    fun generateCsvReport(): String {
        val activeSession = selectedSessionForDetails
        val code = activeSession?.courseCode ?: selectedCourse?.courseCode ?: "B-Tech • CSE Semester 3"
        val name = activeSession?.courseName ?: selectedCourse?.courseName ?: "Data Structures and Algorithms • Regular Lecture (Unit 1 • Data Structure Operations and Asymptotic Notations)"
        val sid = activeSession?.sessionToken ?: sessionId
        val recordsToExport = activeSession?.records ?: finalizedRecords

        val sb = StringBuilder()

        // Session Details
        sb.append("Session Details\n")

        val courseProg = when {
            code.contains("BCA", ignoreCase = true) -> "BCA"
            code.contains("B-Tech", ignoreCase = true) || code.contains("BTECH", ignoreCase = true) -> "B-Tech"
            else -> "B-Tech"
        }
        val branch = when {
            code.contains("CSE", ignoreCase = true) -> "CSE"
            code.contains("ME", ignoreCase = true) -> "ME"
            code.contains("ECE", ignoreCase = true) -> "ECE"
            code.contains("EE", ignoreCase = true) -> "EE"
            code.contains("CE", ignoreCase = true) -> "CE"
            code.contains("IT", ignoreCase = true) -> "IT"
            else -> if (courseProg == "BCA") "N/A" else "CSE"
        }
        val sem = Regex("Semester\\s*\\d", RegexOption.IGNORE_CASE).find(code)?.value
            ?: Regex("Sem\\s*\\d", RegexOption.IGNORE_CASE).find(code)?.value
            ?: "Semester 3"
        val subject = com.college.feature.attendance.extractSubjectName(name, code)
        val remainder = if (name.contains("•")) name.substringAfter("•").trim() else name
        val clsType = when {
            remainder.contains("Extra", ignoreCase = true) -> "Extra"
            remainder.contains("Adjustment", ignoreCase = true) -> "Adjustment"
            else -> "Regular"
        }
        val batch = when {
            remainder.contains("Lab", ignoreCase = true) -> "Lab"
            remainder.contains("Tutorial", ignoreCase = true) -> "Tutorial"
            else -> "Lecture"
        }
        val unit = Regex("Unit\\s*\\d", RegexOption.IGNORE_CASE).find(remainder)?.value ?: "Unit 1"
        val topic = if (remainder.contains("(") && remainder.contains(")")) {
            val inside = remainder.substringAfter("(").substringBefore(")")
            if (inside.contains("•")) inside.substringAfter("•").trim() else inside.trim()
        } else {
            "NONE"
        }

        sb.append("Course,$courseProg\n")
        if (courseProg != "BCA") {
            sb.append("Branch,$branch\n")
        }
        sb.append("Semester,$sem\n")
        sb.append("Subject Name,\"$subject\"\n")
        sb.append("Class Type,$clsType\n")
        sb.append("Batch,$batch\n")
        sb.append("Unit,$unit\n")
        sb.append("Topic Covered,\"$topic\"\n")
        val timestamp = activeSession?.startedAt ?: java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        sb.append("Date,$timestamp\n")
        sb.append("Session Code,$sid\n\n")

        // Attendance Roster
        sb.append("Roll Number,Student Name,Status\n")
        val sortedList = recordsToExport.sortedWith { r1, r2 ->
            val roll1 = r1.rollNumber?.takeIf { it.isNotBlank() } ?: r1.bleDeviceId.removePrefix("BLE-")
            val roll2 = r2.rollNumber?.takeIf { it.isNotBlank() } ?: r2.bleDeviceId.removePrefix("BLE-")
            val d1 = roll1.filter { it.isDigit() }.toIntOrNull() ?: 0
            val d2 = roll2.filter { it.isDigit() }.toIntOrNull() ?: 0
            if (d1 != d2) d1.compareTo(d2) else roll1.compareTo(roll2)
        }

        for (rec in sortedList) {
            val roll = rec.rollNumber?.takeIf { it.isNotBlank() } ?: rec.bleDeviceId.removePrefix("BLE-")
            val studentName = com.college.feature.attendance.getDisplayStudentName(rec)
            val isPresent = com.college.feature.attendance.isRecordPresent(rec)
            val status = if (isPresent) "Present" else "Absent"
            sb.append("$roll,\"$studentName\",$status\n")
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
                        ultrasonicProximityDetected = ultrasonicProximityDetected,
                        challengeReport = "hits=${challengeScore},targets_spawned=${challengeTotalTargets}".takeIf { challengeTotalTargets > 0 },
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
                    challengeReport = if (challengeTotalTargets > 0) {
                        mapOf("hits" to challengeScore, "targets_spawned" to challengeTotalTargets)
                    } else null
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
                        // Keep probe advertising alive until the discovery window ends so
                        // mutual claims converge before finalize (an early tap-Next submit
                        // must not freeze its phone's peer snapshot mid-accumulation)
                        if (!keepRadioActive && studentDiscoverySecondsRemaining <= 0) {
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
                                onStartScanning?.invoke()
                                onStopBroadcasting?.invoke()
                            }
                        } else {
                            if (studentDiscoverySecondsRemaining <= 0) {
                                studentStatusMessage = detail
                                studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                                startPollingForAudit(sessId)
                                onStartScanning?.invoke()
                                onStopBroadcasting?.invoke()
                            } else {
                                // Mid-window rejection (e.g. session state flipped mid-submit):
                                // stay in the discovery view and keep probing — the window-end
                                // submit retries and the audit poll resolves the final state
                                android.util.Log.w("AttendanceViewModel", "Mid-window peer sync pending: ${result.code} - $detail")
                            }
                        }
                    }
                    is NetworkResult.Exception -> {
                        if (studentDiscoverySecondsRemaining <= 0) {
                            studentStatusMessage = "Connection failed: Please verify classroom Wi-Fi connection"
                            studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                            onStartScanning?.invoke()
                            onStopBroadcasting?.invoke()
                        } else {
                            // Mid-window network failure (e.g. cold backend latency): keep probing
                            // and let the window-end submit retry instead of freezing early
                            android.util.Log.w("AttendanceViewModel", "Mid-window peer sync network error (retry at window end): ${result.throwable.message}")
                        }
                    }
                }
                Unit
            }
        }
    }

    // Start teacher session and initialize backend record
    fun startTeacherSession(durationSeconds: Int = 30, courseId: String? = null) {
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
                    courseName = selectedCourse?.courseName,
                    // Teacher-declared class identity — powers the eligibility
                    // snapshot the server gates student check-ins against
                    courseProgram = selectedCourse?.program,
                    courseSemester = selectedClassSemester()
                )
                // Cold-start resilience: Render free tier may still be booting when
                // the create lands — a timed-out/502 first attempt gets one retry
                // so the session actually exists for submit/finalize instead of
                // class-wide 404s.
                var result = safeApiCall { api.createSession(req) }
                if (result is NetworkResult.Exception ||
                    (result as? NetworkResult.Error)?.code?.let { it in 500..599 } == true
                ) {
                    result = safeApiCall { api.createSession(req) }
                }
                withContext(Dispatchers.Main) {
                    isCloudSyncing = false
                    when (result) {
                        is NetworkResult.Success -> {
                            backendSessionId = result.data.id
                            cloudStatusMessage = "Cloud session active: $sessionId"
                            onStartBroadcasting?.invoke(currentBroadcastToken, teacherTxPower)
                        }
                        is NetworkResult.Error -> {
                            if (result.code == 409) {
                                // M2: token collision with an ACTIVE session of another
                                // teacher/class. Broadcasting now would cross-route student
                                // submissions into that other session (resolves newest-wins),
                                // so stay silent until the teacher picks a new ID.
                                cloudStatusMessage = "Session ID $sessionId is already in use by an active session — pick a new ID before broadcasting"
                                android.util.Log.e("AttendanceViewModel", "Session create rejected: HTTP 409 - ${result.message}")
                            } else {
                                cloudStatusMessage = "Cloud sync warning (HTTP ${result.code}): session NOT created — students cannot check in until you STOP and start a new class with a new ID"
                                android.util.Log.e("AttendanceViewModel", "Session create failed: HTTP ${result.code} - ${result.message}")
                                onStartBroadcasting?.invoke(currentBroadcastToken, teacherTxPower)
                            }
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
        // Re-entry guard: the auto-finalize timer and a manual Stop tap can overlap;
        // only one finalize request may be in flight at a time
        if (isCloudSyncing) return
        stopTeacherSession()

        val sessId = backendSessionId ?: sessionId.takeIf { it != "----" }
        val token = SessionManager.default.activeToken
        val hasRealCloudJwt = token?.contains(".") == true && !token.contains("mock")

        if (!SessionManager.default.isLoggedIn || !hasRealCloudJwt || sessId == null) {
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
                        val reviewCount = finalizedRecords.count { it.status == "ISOLATED_FLAGGED" || it.status == "FLAGGED_WITNESSED" || it.status == "UNVERIFIED" }
                        cloudStatusMessage = if (reviewCount > 0) {
                            "Completed • $verifiedCount present, $reviewCount need review"
                        } else {
                            "Completed • $verifiedCount present"
                        }
                        broadcastAirVerdicts(finalizedRecords)
                    }
                    is NetworkResult.Error -> {
                        cloudStatusMessage = if (result.code == 404) {
                            "Finalization failed (HTTP 404): the cloud session was never created — the create request at class start failed. See the banner shown then."
                        } else {
                            "Finalization failed: HTTP ${result.code} - ${result.message}"
                        }
                        finalizedRecords = emptyList()
                    }
                    is NetworkResult.Exception -> {
                        cloudStatusMessage = "Finalization error: ${result.throwable.localizedMessage ?: "Offline"}"
                        finalizedRecords = emptyList()
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
            courseName = selectedCourse?.courseName ?: "",
            subjectName = com.college.feature.attendance.extractSubjectName(selectedCourse?.courseName, selectedCourse?.courseCode)
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
        if (!SessionManager.default.isLoggedIn) return
        isLoadingHistory = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = api.getSessionHistory()
                withContext(Dispatchers.Main) {
                    isLoadingHistory = false
                    if (res.isSuccessful && !res.body().isNullOrEmpty()) {
                        pastSessions = res.body()!!
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    isLoadingHistory = false
                }
            }
        }
    }

    // Realistic dummy sessions for reviewing historical attendance records
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
        invalidateBeaconLatch()
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
        invalidateBeaconLatch()
        studentPhase = StudentPhase.STANDBY
        onStopScanning?.invoke()
        onStopBroadcasting?.invoke()
    }

    // Student proceeds past attention score screen
    fun proceedStudentToNext() {
        studentTimer?.cancel()
        studentTimer = null
        studentDiscoverySecondsRemaining = 0
        val sessId = detectedSessionId.takeIf { it.isNotBlank() && it != "----" } ?: backendSessionId ?: ""
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
        // Fresh discovery window → fresh peer graph: stale claims from a prior
        // session (rapid back-to-back retests) must never leak into this report
        rawDiscoveredPeers.clear()
        testedUltrasonicPeers.clear()
        topDeskNeighbours = emptyList()
        startPeerBatchUpdates()
        studentDiscoverySecondsRemaining = 25
        val probePayload = "$detectedSessionId$studentShortId"
        if (currentRollNumber == defaultLocalId) {
            // A device without a bound roll would broadcast a RANDOM default id —
            // every listener nearby would ingest it as a phantom classmate that
            // can never check in or reciprocate. Listen-only mode instead.
            android.util.Log.w("AttendanceViewModel", "Probe broadcast suppressed: no real roll bound — default random id would fabricate a phantom peer for classmates")
        } else {
            onStartBroadcasting?.invoke(probePayload, AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
        }

        studentTimer?.cancel()
        studentTimer = object : CountDownTimer(25000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                studentDiscoverySecondsRemaining = (millisUntilFinished / 1000L).toInt()
                val elapsed = 25 - studentDiscoverySecondsRemaining
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
    // ------------------------------------------------------------------
    // Cross-class beacon latch hardening.
    //
    // Two adjacent rooms can run attendance simultaneously and BLE crosses
    // walls. Latching the FIRST beacon heard let a wall-side student bind to
    // the neighboring class (wrong roster, wrong salt). Instead the first
    // beacon OPENS this decision window; later beacons only refresh the pool
    // and peak RSSI. At window close the strongest candidate latches — the
    // room the student physically sits in hears its podium beacon strongest
    // by a wide margin versus a wall-crossed foreign one.
    // ------------------------------------------------------------------

    // 2 s covers several beacon repeats: enough peak-RSSI samples to separate
    // the podium beacon from a wall-crossed foreign one, short enough that
    // check-in pacing is unaffected.
    private val latchDecisionWindowMs = 2_000L

    private data class BeaconCandidate(
        val sessionId: String,
        val sessionSalt: String,
        var peakRssi: Int
    )

    private val beaconCandidates = LinkedHashMap<String, BeaconCandidate>()

    // Guards the pending decision job: every reset/lifecycle transition bumps
    // the epoch so a stale timer can never latch a pre-reset candidate pool.
    private var latchEpoch = 0

    private fun invalidateBeaconLatch() {
        latchEpoch++
        beaconCandidates.clear()
    }

    private fun collectBeaconCandidate(sessionId: String, sessionSalt: String, rssi: Int) {
        val existing = beaconCandidates[sessionId]
        if (existing != null) {
            if (rssi > existing.peakRssi) existing.peakRssi = rssi
            return
        }
        beaconCandidates[sessionId] = BeaconCandidate(sessionId, sessionSalt, rssi)
        android.util.Log.i(
            "AttendanceViewModel",
            "Beacon candidate: $sessionId @${rssi}dBm (pool size ${beaconCandidates.size})"
        )
        if (beaconCandidates.size == 1) {
            val epoch = ++latchEpoch
            viewModelScope.launch {
                delay(latchDecisionWindowMs)
                resolveBeaconLatch(epoch)
            }
        }
    }

    private fun resolveBeaconLatch(epoch: Int) {
        // A reset (start-listening / cancel / expiry paths) bumped the epoch,
        // or the student left the listening phase — discard this era's pool.
        if (epoch != latchEpoch || studentPhase != StudentPhase.LISTENING_FOR_TEACHER) return
        // Highest observed RSSI wins; deterministic lexicographic tie-break
        // keeps the (pathological) equal-peak case from flip-flopping.
        val chosen = beaconCandidates.values
            .sortedWith(compareBy<BeaconCandidate> { -it.peakRssi }.thenBy { it.sessionId })
            .firstOrNull() ?: return
        val pool = beaconCandidates.entries
            .joinToString(",") { "${it.key}@${it.value.peakRssi}dBm" }
        android.util.Log.i(
            "AttendanceViewModel",
            "Beacon latch: ${chosen.sessionId} @${chosen.peakRssi}dBm [candidates: $pool]"
        )
        invalidateBeaconLatch()
        detectedSessionId = chosen.sessionId
        detectedSessionSalt = chosen.sessionSalt
        studentPhase = StudentPhase.PROBING_NEIGHBORS
        startStudentDiscoveryWindow()
    }

    fun onPacketReceived(rssi: Int, messageText: String) {
        val cleanMsg = messageText.trim()

        if (isTeacherMode) return

        // Process teacher air verdict packets with offline/online gating
        if (cleanMsg.startsWith("V:")) {
            val parts = cleanMsg.split(":")
            if (parts.size >= 3 && parts[2] == "OK") {
                val targetId = parts[1].trim()
                val isMatch = targetId.equals(studentShortId, ignoreCase = true) ||
                        targetId.equals(currentRollNumber, ignoreCase = true) ||
                        (targetId.isNotEmpty() && currentRollNumber.endsWith(targetId, ignoreCase = true))
                if (isMatch) {
                    stopPeerBatchUpdates()
                    val isPollingOnline = auditPollingJob?.isActive == true
                    if (!isPollingOnline) {
                        studentPhase = StudentPhase.VERIFIED_PRESENT
                        studentStatusMessage = "Attendance Confirmed"
                        onStopScanning?.invoke()
                        onStopBroadcasting?.invoke()
                    } else {
                        studentStatusMessage = "Air verdict detected; confirming with server"
                    }
                    return
                }
            }
        }

        // Teacher beacon format (S:<SessionID>:<Salt>[:<Semester>]). Beacons are
        // POOLED, not latched: see the latch-hardening block above. The first
        // beacon opens the decision window; the strongest candidate wins.
        val isBeacon = cleanMsg.startsWith("S:") || cleanMsg.startsWith("SESSION:")
        if (isBeacon && studentPhase == StudentPhase.LISTENING_FOR_TEACHER) {
            val parts = cleanMsg.split(":")
            if (parts.size >= 2) {
                // Local class pre-filter (UX only — server gate is authoritative):
                // a beacon tagged with a different semester is skipped WITHOUT
                // latching, so the phone keeps listening for its own class.
                val beaconSemester = parts.getOrNull(3)?.toIntOrNull()
                val profileSemester = SessionManager.default.currentUser?.semester
                if (beaconSemester != null && profileSemester != null && beaconSemester != profileSemester) {
                    studentStatusMessage = "Nearby class is Semester $beaconSemester — your profile says Semester $profileSemester"
                    return
                }
                collectBeaconCandidate(parts[1], if (parts.size >= 3) parts[2] else "", rssi)
            }
            return
        }

        // Check for student peer discovery packet. Wire contract is EXACT:
        // "<latched session token (4 chars)><peer short id (4 chars)>". Anything
        // else — foreign advertisers sharing our service UUID, stray app
        // instances, garbled fragments — must never fabricate a classmate via
        // takeLast(4) of arbitrary-length text (the phantom-peer vector).
        // Ingest peer probes only during active probing phase to keep submitted snapshot stable
        val canReceiveProbe = studentPhase == StudentPhase.PROBING_NEIGHBORS
        val sessionLen = detectedSessionId.length
        val isPeerProbe = cleanMsg.length == sessionLen + 4 &&
                cleanMsg.startsWith(detectedSessionId) &&
                !cleanMsg.startsWith("S:") && !cleanMsg.startsWith("SESSION:")

        if (isPeerProbe && canReceiveProbe && detectedSessionId != "----") {
            val peerId = cleanMsg.substring(sessionLen).uppercase()
            if (peerId != studentShortId && peerIdPattern.matches(peerId)) {
                val existing = rawDiscoveredPeers[peerId]
                val updatedPeer = if (existing != null) {
                    existing.withSample(rssi)
                } else {
                    PeerData(peerId).withSample(rssi)
                }
                rawDiscoveredPeers[peerId] = updatedPeer

                if (existing == null) {
                    // First sight of this peer this window — one diagnostic line
                    // so phantom emitters are identifiable from logcat alone
                    android.util.Log.i("AttendanceViewModel", "Peer ingested: id=$peerId msg='$cleanMsg' rssi=$rssi session=$detectedSessionId")
                    // Push updated peer list to server
                    submitMyNeighborsToCloud(keepRadioActive = true)
                }

                // Layer 4 Ultrasonic Proximity Evaluation:
                // Close proximity (RSSI >= -68 dBm) activates the ultrasonic acoustic distance handshake.
                // Neither radio nor ultrasonic alone can flag a student: both must independently agree.
                val isClosePeer = rssi >= -68 || updatedPeer.medianRssi >= -68
                if (isClosePeer && !testedUltrasonicPeers.contains(peerId)) {
                    testedUltrasonicPeers.add(peerId)
                    triggerUltrasonicProximityHandshake(peerId)
                }
                return
            }
            // Shaped-but-invalid probe (own echo / malformed id) falls through to
            // the throttled neglect log below, with full packet text
        }

        // Anything reaching the scanner that matched nothing we speak — verdicts
        // for other students, foreign-class beacons, unclassified shapes — gets
        // one throttled diagnostic line per 2s: enough to identify phantom
        // emitters from logcat, without flooding the log
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - lastNeglectedPacketLogMs > 2000) {
            lastNeglectedPacketLogMs = nowMs
            android.util.Log.i("AttendanceViewModel", "BLE packet not ingested: '$cleanMsg' (rssi=$rssi phase=$studentPhase session=$detectedSessionId)")
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
                                rec.bleDeviceId.endsWith(studentShortId, ignoreCase = true) ||
                                (rec.rollNumber != null && rec.rollNumber.equals(currentRollNumber, ignoreCase = true)) ||
                                (rec.rollNumber != null && rec.rollNumber.endsWith(studentShortId, ignoreCase = true)) ||
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
                                            myRecord.status == "SALT_REJECTED" -> "In-room beacon verification failed"
                                            myRecord.status == "SPOOFER_REJECTED" && myRecord.verificationMethod == "SALT_REJECTED" -> "In-room beacon verification failed"
                                            myRecord.status == "SPOOFER_REJECTED" -> "Peer claims unconfirmed — please ask your instructor to review"
                                            myRecord.status == "ISOLATED_FLAGGED" -> "Needs instructor confirmation"
                                            myRecord.status == "FLAGGED_WITNESSED" -> "Verified classmates witnessed you — instructor confirmation needed"
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
                            if (myRecord?.status == "VERIFIED_PRESENT") {
                                break
                            }
                        } else if (session?.status == "CANCELLED") {
                            withContext(Dispatchers.Main) {
                                handleSessionCancelled()
                            }
                            break
                        } else if (session?.status == "EXPIRED") {
                            withContext(Dispatchers.Main) {
                                handleSessionExpired()
                            }
                            break
                        }
                    }
                } catch (_: Exception) { }
            }
        }
    }

    // Reset student state and resume scanning when session is cancelled
    private fun handleSessionCancelled() {
        studentTimer?.cancel()
        studentTimer = null
        studentDiscoverySecondsRemaining = 0
        stopPeerBatchUpdates()
        onStopBroadcasting?.invoke()
        rawDiscoveredPeers.clear()
        topDeskNeighbours = emptyList()
        capturedFaceBase64 = null
        capturedFaceBytes = null
        testedUltrasonicPeers.clear()
        ultrasonicProximityDetected = false
        coLocatedPeerId = null
        auditedRecord = null
        invalidateBeaconLatch()
        detectedSessionId = "----"
        detectedSessionSalt = "------"
        studentPhase = StudentPhase.LISTENING_FOR_TEACHER
        studentStatusMessage = "Session cancelled by instructor — waiting for a new session"
        onStartScanning?.invoke()
    }

    // Reset student state and resume scanning when the session expired before the audit
    // completed (H3: teacher re-created the session or it lapsed mid-audit — students should
    // not hang on a session that will never reach COMPLETED).
    private fun handleSessionExpired() {
        studentTimer?.cancel()
        studentTimer = null
        studentDiscoverySecondsRemaining = 0
        stopPeerBatchUpdates()
        onStopBroadcasting?.invoke()
        rawDiscoveredPeers.clear()
        topDeskNeighbours = emptyList()
        capturedFaceBase64 = null
        capturedFaceBytes = null
        testedUltrasonicPeers.clear()
        ultrasonicProximityDetected = false
        coLocatedPeerId = null
        auditedRecord = null
        invalidateBeaconLatch()
        detectedSessionId = "----"
        detectedSessionSalt = "------"
        studentPhase = StudentPhase.LISTENING_FOR_TEACHER
        studentStatusMessage = "Session expired before the audit completed — please ask your instructor"
        onStartScanning?.invoke()
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
                                        myRecord.status == "SPOOFER_REJECTED" -> "Peer claims unconfirmed — please ask your instructor to review"
                                        myRecord.status == "ISOLATED_FLAGGED" -> "Needs instructor confirmation"
                                        myRecord.status == "FLAGGED_WITNESSED" -> "Verified classmates witnessed you — instructor confirmation needed"
                                        else -> "Attendance not confirmed"
                                    }
                                    onStartScanning?.invoke()
                                }
                            }
                        }
                    } else if (session?.status == "CANCELLED") {
                        withContext(Dispatchers.Main) {
                            handleSessionCancelled()
                        }
                    } else if (session?.status == "EXPIRED") {
                        withContext(Dispatchers.Main) {
                            handleSessionExpired()
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
