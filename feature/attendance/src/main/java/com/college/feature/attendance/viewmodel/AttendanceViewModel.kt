package com.college.feature.attendance.viewmodel

import android.app.Application
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.CountDownTimer
import android.os.PowerManager
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// K4: fixed-size RSSI sample ring — no per-sample List allocation/growth.
// Kept immutable-at-API (copy-on-write of the small ring array) so existing
// call sites and the @Immutable contract stay valid; bounded to maxSamples.
@Immutable
data class PeerData(
    val peerId: String,
    val rssiSamples: IntArray = IntArray(0),
    val sampleCount: Int = 0,
    val medianRssi: Int = -99
) {
    val count: Int get() = sampleCount

    fun withSample(rssi: Int, maxSamples: Int = 10): PeerData {
        val newCount = if (sampleCount < maxSamples) sampleCount + 1 else maxSamples
        val buf = IntArray(newCount)
        if (sampleCount < maxSamples) {
            if (sampleCount > 0) rssiSamples.copyInto(buf, 0, 0, sampleCount)
            buf[sampleCount] = rssi
        } else {
            // drop oldest (index 0), shift left, append newest
            rssiSamples.copyInto(buf, 0, 1, sampleCount)
            buf[newCount - 1] = rssi
        }
        val sorted = buf.copyOf()
        sorted.sort()
        val median = sorted[sorted.size / 2]
        return PeerData(
            peerId = peerId,
            rssiSamples = buf,
            sampleCount = newCount,
            medianRssi = median
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerData) return false
        return peerId == other.peerId && sampleCount == other.sampleCount &&
            medianRssi == other.medianRssi && rssiSamples.contentEquals(other.rssiSamples)
    }

    override fun hashCode(): Int {
        var result = peerId.hashCode()
        result = 31 * result + rssiSamples.contentHashCode()
        result = 31 * result + sampleCount
        result = 31 * result + medianRssi
        return result
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
    // M4: collision-resistant 4-char BLE/device key derived from canonical identity
    // (full roll preferred, else user UUID, else local id). Deterministic so Android
    // and backend derive the same key; 4-char wire format preserved.
    val studentShortId: String get() = deriveShortDeviceId(canonicalDeviceIdentity())

    private fun canonicalDeviceIdentity(): String {
        val user = SessionManager.default.currentUser
        return user?.rollNumber?.takeIf { it.isNotBlank() }
            ?: user?.id?.takeIf { it.isNotBlank() }
            ?: currentRollNumber.takeIf { it.isNotBlank() }
            ?: defaultLocalId
    }

    fun deriveShortDeviceId(canonical: String): String {
        val src = canonical.trim().uppercase().ifEmpty { defaultLocalId }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(src.toByteArray(Charsets.UTF_8))
        return digest.take(2).joinToString("") { "%02X".format(it) }
    }

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

    // H5 beacon: "/"+token(4)+wireSalt(4)+[semester(1)][G<k>] — fixed positions,
    // '/' distinguishes from 8-char peer probes and legacy S:/V: frames. The
    // G suffix carries the synchronized game start countdown (seconds remaining)
    // during the first SYNC seconds; old clients ignore it (fixed-position parse).
    val currentBroadcastToken: String
        get() = if (isSessionActive) {
            val sem = selectedClassSemester()
            val base = if (sem != null) "/$sessionId$sessionSalt$sem" else "/$sessionId$sessionSalt"
            synchronizedGameCountdownSeconds()?.let { "${base}G$it" } ?: base
        } else "IDLE"

    // Synchronized game start: every student phone that hears at least one
    // countdown beacon starts the chimp challenge at the SAME instant. Without
    // it, each phone latches the beacon (and starts its 25 s game) at its own
    // scan latency — a desync that lets a student split attention across two
    // phones and beat the challenge on both. Anchor is the teacher timer start
    // on the monotonic clock; students estimate the target as (received + k),
    // so delivery jitter never shifts the target.
    companion object {
        const val SYNC_GAME_START_OFFSET_SECONDS = 7
    }

    private var teacherSessionStartedElapsedMs = 0L
    private var lastBeaconCountdownEmitted: Int? = null

    private fun synchronizedGameCountdownSeconds(): Int? {
        if (!isSessionActive || teacherSessionStartedElapsedMs <= 0L) return null
        val k = SYNC_GAME_START_OFFSET_SECONDS -
            ((android.os.SystemClock.elapsedRealtime() - teacherSessionStartedElapsedMs) / 1000L).toInt()
        return if (k in 1..SYNC_GAME_START_OFFSET_SECONDS) k else null
    }

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

    var chimpChallengeFailed by mutableStateOf(false)
        private set
    var chimpScore: Int? by mutableStateOf(null)
        private set
    var isChimpChallengeActive by mutableStateOf(false)
        private set
    // Audit/verdict result deferred while game is mid-round (finish round first)
    private var deferredStudentPhase: StudentPhase? = null
    private var deferredStudentStatusMessage: String? = null
    private var deferredStopScanning: Boolean = false
    private var deferredStopBroadcasting: Boolean = false
    private var deferredStartScanning: Boolean = false

    val hasLocalDefenseFailed: Boolean
        get() = faceProxyDetected || ultrasonicProximityDetected || chimpChallengeFailed

    var capturedFaceBase64 by mutableStateOf<String?>(null)
    var capturedFaceBytes by mutableStateOf<ByteArray?>(null)

    fun updateChimpChallengeActive(active: Boolean) {
        isChimpChallengeActive = active
        if (!active) {
            val phase = deferredStudentPhase
            if (phase != null) {
                deferredStudentPhase = null
                val msg = deferredStudentStatusMessage
                deferredStudentStatusMessage = null
                val stopScan = deferredStopScanning
                val stopBcast = deferredStopBroadcasting
                val startScan = deferredStartScanning
                deferredStopScanning = false; deferredStopBroadcasting = false; deferredStartScanning = false
                studentStatusMessage = msg ?: studentStatusMessage
                studentPhase = phase
                if (stopScan) onStopScanning?.invoke()
                if (stopBcast) onStopBroadcasting?.invoke()
                if (startScan) onStartScanning?.invoke()
            }
        }
    }

    // Single choke point for student result-phase transitions: while the chimp
    // challenge is mid-round, audit/air-verdict outcomes are parked and applied
    // when the challenge reports finished (updateChimpChallengeActive(false)).
    private fun applyOrDeferStudentPhase(
        phase: StudentPhase,
        msg: String?,
        stopScan: Boolean = false,
        stopBcast: Boolean = false,
        startScan: Boolean = false
    ) {
        if (isChimpChallengeActive) {
            deferredStudentPhase = phase
            deferredStudentStatusMessage = msg
            deferredStopScanning = stopScan
            deferredStopBroadcasting = stopBcast
            deferredStartScanning = startScan
        } else {
            if (msg != null) studentStatusMessage = msg
            studentPhase = phase
            if (stopScan) onStopScanning?.invoke()
            if (stopBcast) onStopBroadcasting?.invoke()
            if (startScan) onStartScanning?.invoke()
        }
    }

    fun recordChimpChallengeScore(score: Int) {
        chimpScore = score
        // Verification threshold: 12 (server enforces the same gate)
        chimpChallengeFailed = score < 12
        if (chimpChallengeFailed) {
            studentStatusMessage = "Cognitive challenge failed: score $score < 12"
        }
    }

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
    private var finalizedRefreshJob: Job? = null

    val proximityManager by lazy {
        com.college.feature.attendance.audio.UltrasonicProximityManager(application.applicationContext)
    }
    private val testedUltrasonicPeers = mutableSetOf<String>()

    // R11-C (review §2.2): bumped by the peer-ingest path on every map write
    // (new peer or RSSI sample refresh); peerBatchJob flushes only when this
    // counter moves. Main-thread writes only (every bump site lives there).
    @Volatile
    private var peerRevision = 0L

    // K4: debounce/coalesce neighbor submits — trailing during discovery
    // window, immediate on boundary transitions (keepRadioActive=false).
    // Baseline (instrumented round): 5 submits in ~27s for 1 peer;
    // batching collapses periodic+event bursts to one trailing push.
    private var pendingNeighborSubmitJob: Job? = null

    // R11 (review §2.8): PARTIAL wake lock for the student probing phase — a
    // 25 s BLE discovery window plus device-to-device ultrasonic handshakes
    // can run while a pocketed phone has dozed the CPU, where scan callbacks
    // and AudioRecord reads drop and the peer graph silently starves.
    // PARTIAL (CPU on, no screen), bounded by a hard timeout as the crash
    // safety belt, and explicitly released on both student teardown paths.
    private val powerManager =
        application.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var probingWakeLock: PowerManager.WakeLock? = null

    @Suppress("WakelockTimeout")
    private fun acquireProbingWakeLock() {
        val pm = powerManager ?: return
        if (probingWakeLock?.isHeld == true) return
        val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "attendance:peer_probe")
        // 105 s timeout = crash-safety belt: happy paths release explicitly
        // (stopStudentScanning / resetStudentSession), but a crashed process
        // must never hold the SoC awake forever. Bounded well above the 25 s
        // window + all burst handshakes it may spawn.
        lock.acquire(105_000L)
        probingWakeLock = lock
    }

    private fun releaseProbingWakeLock() {
        probingWakeLock?.takeIf { it.isHeld }?.release()
        probingWakeLock = null
    }

    // Batched top 3 nearest desk/bench neighbors (median RSSI >= -92 dBm)
    var topDeskNeighbours by mutableStateOf<List<PeerData>>(emptyList())
        private set

    private fun startPeerBatchUpdates() {
        if (peerBatchJob?.isActive == true) return
        peerBatchJob = viewModelScope.launch(Dispatchers.Default) {
            // R11-C (review §2.2, deferred from chunk 11): change-driven
            // top-3 refresh. peerRevision bumps on every peer map write; a
            // silent classroom now skips the sort-plus-main-thread write
            // entirely (the old loop sorted and posted every 300ms for the
            // whole probing phase no matter what). Same 300ms cadence once
            // packets flow: seen is read BEFORE the flush, so packets landing
            // during the sort belong to the next tick — a change is never
            // lost. Equivalent to review's Flow::sample remedy without
            // pulling a state flow over the ConcurrentHashMap.
            var lastSeenRevision = -1L
            while (isActive) {
                delay(300L)
                val seen = peerRevision
                if (seen == lastSeenRevision) continue
                lastSeenRevision = seen
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

    // Payload-only beacon update (AdvertisingSet API): countdown values swap
    // the advertised bytes WITHOUT a stop/start cycle — legacy restart churn
    // silently killed the teacher beacon in the field.
    var onUpdateBroadcasting: ((token: String) -> Unit)? = null

    // R11-C (review §2.1, deferred from chunk 11): MainActivity wires this to
    // BleScanner.setScanLatency — density is pure state now; scan starts are
    // explicit and throttled by the caller.
    var onSwitchScanLatency: ((lowLatency: Boolean) -> Unit)? = null

    // Scan liveness (MainActivity wires BleScanner.onScanningChanged): the
    // listening watchdog times restarts against real scan state instead of
    // firing blind into Android's 5-scans/30s throttle.
    private var bleScanActive = false

    fun onBleScanStateChanged(active: Boolean) {
        bleScanActive = active
        if (!active) {
            android.util.Log.w("AttendanceViewModel", "BLE scan stopped/failed (listener reports inactive)")
        }
    }

    var studentDiscoverySecondsRemaining by mutableIntStateOf(0)
    private var teacherTimer: CountDownTimer? = null
    private var studentTimer: CountDownTimer? = null

    init {
        SessionManager.default.init(application.applicationContext)
        com.college.core.network.ServerConfigManager.default.init(application.applicationContext)
        currentRollNumber = SessionManager.default.currentUser?.rollNumber
            ?: prefs.getString("roll_number", defaultLocalId)
            ?: defaultLocalId
        // R9 (review §3.4): ServerConfigManager is the ONLY URL owner. It was
        // initialized just above; its persisted value is scheme-normalized,
        // already live for every request via HostSelectionInterceptor, and
        // AttendanceApiClient.baseUrl reads from it. The old block re-read the
        // same file/key, re-adjudicated the value with hand-rolled LAN-string
        // filters, then re-persisted through a second writer — three URL
        // sources of truth adjudicating each other on every cold start.
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
     * One-time legacy migration (R8-B): very old builds persisted the JWT in
     * this plaintext prefs file. The encrypted SessionManager store is the only
     * legitimate token persistence now — a found legacy token is migrated into
     * the session EXACTLY once, then its plaintext copy is destroyed. If the
     * Keystore is unavailable, startSession keeps the token RAM-only for this
     * process (fail-closed persistence, SessionManager R8) — the plaintext
     * copy is still gone either way. The migrated user record is only partially
     * faithful (email/id are best-effort from legacy prefs); the next fresh
     * login replaces it with the real backend profile.
     */
    private fun hydrateSessionFromPrefs() {
        if (SessionManager.default.isLoggedIn) {
            SessionManager.default.currentUser?.rollNumber?.let { currentRollNumber = it }
            return
        }
        val legacyToken = prefs.getString("auth_token", null) ?: return
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
            token = legacyToken
        )
        // Destroy the plaintext copy immediately after the one-time migration
        prefs.edit().remove("auth_token").apply()
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

    // R9 (review §3.4): single write path — the AttendanceApiClient.baseUrl
    // delegate persists through ServerConfigManager, which owns scheme
    // normalization. The removed direct prefs write was a second writer on the
    // same file/key, able to race the normalized value with raw user input.
    fun setServerUrl(url: String) {
        AttendanceApiClient.baseUrl = url
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
                            syncLocalSession(user.rollNumber, user.name, user.role)
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
                        syncLocalSession(user.rollNumber, user.name, user.role)
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

    private fun syncLocalSession(roll: String?, name: String, role: String) {
        // R8-B (found during chunk 8): this used to ALSO write the JWT into
        // this plaintext prefs file — every real login shadow-copied the
        // bearer token to disk beside the encrypted store (which
        // AuthRepository.applyAuthenticatedSession had already populated),
        // keeping an unencrypted token copy alive until logout. Plaintext
        // prefs keep only display metadata from now on; the encrypted
        // SessionManager store is the only token persistence.
        roll?.let { currentRollNumber = it }
        prefs.edit()
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
                        syncLocalSession(user.rollNumber, user.name, user.role)
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

    fun logout(notifyServer: Boolean = true) {
        // R8: fire-and-forget server-side revocation (chunk 7's route) BEFORE
        // the local teardown. The token is passed into the request explicitly —
        // AuthInterceptor reads SessionManager lazily at request time, and
        // endSession() below clears it before a coroutine would fire, which
        // would otherwise silently send the logout unauthenticated. Best-effort:
        // local logout must never depend on network availability, and the
        // 401-expiry path skips this (the server already revoked that token —
        // that is why the 401 happened).
        if (notifyServer) {
            val token = SessionManager.default.activeToken
            val hasRealJwt = token != null && token.contains(".") && !token.contains("mock")
            if (hasRealJwt) {
                // Audit review (Low): revocation must survive VM teardown —
                // logout can clear the ViewModel while the HTTP call is still
                // in flight. NonCancellable lets the request finish even if
                // viewModelScope is cancelled right after logout() returns.
                viewModelScope.launch {
                    withContext(NonCancellable + Dispatchers.IO) {
                        try {
                            AttendanceApiClient.service.logout("Bearer $token")
                        } catch (_: Exception) {
                            // Server unreachable or token already revoked — local
                            // logout proceeds either way
                        }
                    }
                }
            }
        }
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
            // notifyServer = false: the revocation ALREADY happened server-side
            // (that is what produced this 401); re-calling /logout would only
            // 401 again on a dead token
            logout(notifyServer = false)
            authErrorText = message
            cloudStatusMessage = message
            showAuthDialog = true
        }
    }

    // Fetch courses taught by teacher or college syllabus catalogue
    fun loadCourses() {
        isLoadingCourses = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = api.listCourses()
                withContext(Dispatchers.Main) {
                    isLoadingCourses = false
                    if (res.code() == 401) {
                        handleAuthExpired()
                    } else if (res.isSuccessful && res.body() != null) {
                        val fetched = res.body()!!
                        availableCourses = fetched
                        if (selectedCourse == null && fetched.isNotEmpty()) {
                            selectedCourse = fetched.first()
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

    // Upload student's own top neighbors over Wi-Fi / cellular.
    // K4: trailing debounce during active window (keepRadioActive=true) to
    // collapse periodic ticks and first-sight bursts; immediate flush on
    // boundary calls (keepRadioActive=false) so end-of-window / finalize
    // snapshots are never delayed. Server grace tolerates trailing delivery.
    fun submitMyNeighborsToCloud(keepRadioActive: Boolean = false) {
        if (!keepRadioActive) {
            pendingNeighborSubmitJob?.cancel()
            pendingNeighborSubmitJob = null
            submitMyNeighborsToCloudNow(keepRadioActive = false)
            return
        }
        if (pendingNeighborSubmitJob?.isActive == true) return
        pendingNeighborSubmitJob = viewModelScope.launch {
            delay(2500L)
            pendingNeighborSubmitJob = null
            submitMyNeighborsToCloudNow(keepRadioActive = true)
        }
    }

    private fun submitMyNeighborsToCloudNow(keepRadioActive: Boolean) {
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
                        gameScore = chimpScore,
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
                    challengeReport = chimpScore?.let { mapOf("chimp_score" to it) },
                    gameScore = chimpScore,
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
                            if (studentDiscoverySecondsRemaining <= 0) {
                                studentStatusMessage = detail
                                studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                                startPollingForAudit(sessId)
                                onStartScanning?.invoke()
                                onStopBroadcasting?.invoke()
                            } else {
                                // Mid-window (game still running): a 403 detail
                                // must never paint the game screen — the salt
                                // gate's "Nice try lil bro" taunted students
                                // mid-challenge. Log it; the window-end submit
                                // surfaces the real outcome.
                                android.util.Log.w("AttendanceViewModel", "Mid-window 403 suppressed from UI: $detail")
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
    fun startTeacherSession(durationSeconds: Int = 40, courseId: String? = null) {
        val targetCourseId = courseId ?: selectedCourse?.id
        backendSessionId = null
        sessionId = UUID.randomUUID().toString().replace("-", "").take(4).uppercase()
        sessionSalt = UUID.randomUUID().toString().replace("-", "").take(4).uppercase()
        finalizedRefreshJob?.cancel()
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
                            // H5: server generates canonical salt and returns the
                            // wire token for the BLE beacon — adopt it so broadcasts
                            // and check-ins agree.
                            result.data.sessionWireSalt?.takeIf { it.isNotBlank() }?.let { sessionSalt = it }
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

        teacherSessionStartedElapsedMs = android.os.SystemClock.elapsedRealtime()
        lastBeaconCountdownEmitted = null
        teacherTimer?.cancel()
        teacherTimer = object : CountDownTimer(durationSeconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                remainingSeconds = (millisUntilFinished / 1000L).toInt()
                // Advertising is a static packet: the countdown only advances if
                // the payload changes. Each value gets exactly one update via
                // AdvertisingSet.setAdvertisingData — the transmission never
                // restarts — and one final update drops the suffix for the rest
                // of class.
                val k = synchronizedGameCountdownSeconds()
                if (k != lastBeaconCountdownEmitted) {
                    lastBeaconCountdownEmitted = k
                    onUpdateBroadcasting?.invoke(currentBroadcastToken)
                }
            }

            override fun onFinish() {
                // Grace window: a student game (25 s + face capture) may still be
                // running — let its final check-in land before the audit pops the
                // staging buffer. A manual Stop during this window finalizes
                // immediately; this delayed call then exits idempotently.
                cloudStatusMessage = "Wrapping up — waiting for students to finish…"
                viewModelScope.launch(Dispatchers.Main) {
                    delay(3000L)
                    finalizeSessionAndAudit()
                }
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
                        // Late check-ins land server-side within the grace window —
                        // keep the instructor's list current as rescues flip records.
                        startFinalizedRecordsRefresh(sessId)
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

    // Post-finalize refresh: students finishing their game after the timer land
    // late check-ins that the server rescues within the 90 s grace window —
    // poll briefly so the instructor's list and counts reflect them.
    private fun startFinalizedRecordsRefresh(sessId: String) {
        finalizedRefreshJob?.cancel()
        finalizedRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            repeat(10) {
                delay(3000L)
                try {
                    val res = api.getSessionDetails(sessId)
                    val session = if (res.isSuccessful) res.body() ?: return@launch else return@launch
                    if (session.status == "CANCELLED" || session.status == "EXPIRED") return@launch
                    val records = session.records ?: return@launch
                    val previousVerified = finalizedRecords
                        .filter { it.status == "VERIFIED_PRESENT" }
                        .map { it.bleDeviceId }
                        .toSet()
                    withContext(Dispatchers.Main) {
                        finalizedRecords = records
                        val verifiedCount = records.count { it.status == "VERIFIED_PRESENT" }
                        val reviewCount = records.count {
                            it.status == "ISOLATED_FLAGGED" || it.status == "FLAGGED_WITNESSED" || it.status == "UNVERIFIED"
                        }
                        cloudStatusMessage = if (reviewCount > 0) {
                            "Completed • $verifiedCount present, $reviewCount need review"
                        } else {
                            "Completed • $verifiedCount present"
                        }
                    }
                    val newlyVerified = records.filter {
                        it.status == "VERIFIED_PRESENT" && it.bleDeviceId !in previousVerified
                    }
                    if (newlyVerified.isNotEmpty()) {
                        broadcastAirVerdicts(newlyVerified)
                    }
                } catch (_: Exception) { }
            }
        }
    }

    // Option 2: Sequentially broadcast BLE air verdicts (\<ID>) for verified students
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
                            onStartBroadcasting?.invoke("\\$id", teacherTxPower)
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

        // Option 2: Blast air verdict over BLE for manually confirmed student (\<ID>)
        val idsToBroadcast = mutableListOf(recordOrDeviceId)
        target?.bleDeviceId?.let { if (it !in idsToBroadcast) idsToBroadcast.add(it) }
        target?.rollNumber?.let { if (it.isNotBlank() && it !in idsToBroadcast) idsToBroadcast.add(it) }

        airVerdictJob?.cancel()
        airVerdictJob = viewModelScope.launch(Dispatchers.Default) {
            repeat(4) {
                for (id in idsToBroadcast) {
                    if (!isActive) break
                    withContext(Dispatchers.Main) {
                        onStartBroadcasting?.invoke("\\$id", teacherTxPower)
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
        capturedFaceBase64 = null
        capturedFaceBytes = null
        chimpScore = null
        chimpChallengeFailed = false
        isChimpChallengeActive = false
        deferredStudentPhase = null
        deferredStudentStatusMessage = null
        deferredStopScanning = false
        deferredStopBroadcasting = false
        deferredStartScanning = false
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
        // LOW_LATENCY listening: the synchronized start needs every phone to
        // latch fast (BALANCED duty cycle adds ~1-2 s of first-heard lag).
        // Density is pure state; this explicit start is the only scan op.
        onSwitchScanLatency?.invoke(true)
        ensureListeningWatchdog()
        onStartScanning?.invoke()
    }

    // Student scanning stop
    fun stopStudentScanning() {
        pendingNeighborSubmitJob?.cancel()
        pendingNeighborSubmitJob = null
        stopPeerBatchUpdates()
        releaseProbingWakeLock() // R11 (review §2.8): explicit CPU hold release
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
        pendingNeighborSubmitJob?.cancel()
        pendingNeighborSubmitJob = null
        stopPeerBatchUpdates()
        releaseProbingWakeLock() // R11 (review §2.8): explicit CPU hold release
        studentTimer?.cancel()
        studentTimer = null
        studentDiscoverySecondsRemaining = 0
        auditPollingJob?.cancel()
        auditPollingJob = null
        rawDiscoveredPeers.clear()
        topDeskNeighbours = emptyList()
        studentStatusMessage = null
        capturedFaceBase64 = null
        capturedFaceBytes = null
        chimpScore = null
        chimpChallengeFailed = false
        isChimpChallengeActive = false
        deferredStudentPhase = null
        deferredStudentStatusMessage = null
        deferredStopScanning = false
        deferredStopBroadcasting = false
        deferredStartScanning = false
        faceProxyDetected = false
        ultrasonicProximityDetected = false
        chimpChallengeFailed = false
        chimpScore = null
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
        // R11-C: drop scan density even when Next is tapped before the window
        // closes (fast game finish) — audit wait/verdict listening runs BALANCED.
        // Density is pure state now; restart the scan to apply it.
        onSwitchScanLatency?.invoke(false)
        onStartScanning?.invoke()
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
        // R11-C (review §2.1): the only phase that needs packet density —
        // 25 s peer discovery window (plus the reflex game that rides it).
        onSwitchScanLatency?.invoke(true)
        // R11 (review §2.8): hold the CPU for the whole probe sequence
        // (BLE window + ultrasonic handshakes) even if the user pockets the
        // phone; bounded by the 105 s timeout, released on teardown.
        acquireProbingWakeLock()
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
                // R11-C (review §2.1): the 25 s discovery window is closed —
                // the score window and audit wait run on BALANCED (battery).
                onSwitchScanLatency?.invoke(false)
                // Density change no longer restarts the scan itself — do it
                // here: BALANCED from this point (audit wait runs on battery).
                onStartScanning?.invoke()
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

    // Synchronized game start (teacher countdown beacons "G<k>"): the minimum
    // (received + k) estimate seen while listening. Cleared on every listening
    // reset so a stale target can never delay a fresh session's game.
    private var syncGameStartElapsedMs: Long? = null

    // Delay applied to the chimp challenge after latching — 0 when no countdown
    // was ever heard (old beacon / countdown expired). Exposed to the UI.
    var chimpStartDelayMs by mutableStateOf(0L)
        private set

    private val beaconCountdownRegex = Regex("G(\\d{1,2})$")

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
        syncGameStartElapsedMs = null
        chimpStartDelayMs = 0L
    }

    // Listening watchdog: Android can silently stop delivering scan results
    // (stack hiccup, throttling after repeated restarts) — the student then sits
    // on the check-in screen forever with no error. While LISTENING, any
    // 12 s packet silence restarts the scan; packet flow (any shape) proves the
    // scan is alive. 12 s keeps restarts under Android's scan-throttle budget.
    private var lastBlePacketHeardElapsedMs = 0L
    private var lastWatchdogRestartElapsedMs = 0L
    private var listeningWatchdogJob: Job? = null

    private fun ensureListeningWatchdog() {
        listeningWatchdogJob?.cancel()
        // Baseline = now: a scan that has never delivered anything since this
        // listening entry still gets restarted at the first 12 s tick.
        lastBlePacketHeardElapsedMs = android.os.SystemClock.elapsedRealtime()
        lastWatchdogRestartElapsedMs = 0L
        listeningWatchdogJob = viewModelScope.launch(Dispatchers.Main) {
            while (studentPhase == StudentPhase.LISTENING_FOR_TEACHER) {
                delay(12_000L)
                if (studentPhase != StudentPhase.LISTENING_FOR_TEACHER) return@launch
                val now = android.os.SystemClock.elapsedRealtime()
                if (lastBlePacketHeardElapsedMs > 0L && now - lastBlePacketHeardElapsedMs < 12_000L) {
                    continue
                }
                // Restart cadence: at most one restart per 15 s — two restarts
                // per 30 s plus phase switches stays inside Android's
                // 5-scans-per-30s throttle budget. Restarting faster would
                // trip the throttle and kill the scan for the whole window.
                if (now - lastWatchdogRestartElapsedMs < 15_000L) {
                    continue
                }
                lastWatchdogRestartElapsedMs = now
                android.util.Log.w(
                    "AttendanceViewModel",
                    "Scan watchdog: no packets for 12s while listening — restarting scan (scanActive=$bleScanActive)"
                )
                onStartScanning?.invoke()
            }
        }
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
        val syncTarget = syncGameStartElapsedMs
        invalidateBeaconLatch()
        detectedSessionId = chosen.sessionId
        detectedSessionSalt = chosen.sessionSalt
        // Synchronized game start: everyone who heard a countdown starts at the
        // same instant. Target already passed (very late latch) or never heard →
        // start immediately (legacy behavior).
        chimpStartDelayMs = syncTarget
            ?.let { it - android.os.SystemClock.elapsedRealtime() }
            ?.takeIf { it > 0L }
            ?.coerceAtMost(20_000L)
            ?: 0L
        studentPhase = StudentPhase.PROBING_NEIGHBORS
        startStudentDiscoveryWindow()
    }

    fun onPacketReceived(rssi: Int, messageText: String) {
        val cleanMsg = messageText.trim()

        if (isTeacherMode) return
        // Any received packet proves the scan is alive (watchdog silence check)
        lastBlePacketHeardElapsedMs = android.os.SystemClock.elapsedRealtime()

        // Process teacher air verdict packets with offline/online gating
        // H5: '\'+targetId (compact), legacy V:<id>:OK
        if (cleanMsg.startsWith("\\") || cleanMsg.startsWith("V:")) {
            val targetId = if (cleanMsg.startsWith("\\")) {
                cleanMsg.substring(1).trim()
            } else {
                val parts = cleanMsg.split(":")
                if (parts.size >= 3 && parts[2] == "OK") parts[1].trim() else ""
            }
            if (targetId.isNotEmpty()) {
                val isMatch = targetId.equals(studentShortId, ignoreCase = true) ||
                        targetId.equals(currentRollNumber, ignoreCase = true) ||
                        (targetId.isNotEmpty() && currentRollNumber.endsWith(targetId, ignoreCase = true))
                if (isMatch) {
                    stopPeerBatchUpdates()
                    val isPollingOnline = auditPollingJob?.isActive == true
                    if (!isPollingOnline) {
                        applyOrDeferStudentPhase(
                            StudentPhase.VERIFIED_PRESENT,
                            "Attendance Confirmed",
                            stopScan = true,
                            stopBcast = true
                        )
                    } else {
                        studentStatusMessage = "Air verdict detected; confirming with server"
                    }
                    return
                }
            }
            if (cleanMsg.startsWith("\\")) return
        }

        // Teacher beacon: H5 '/'+token(4)+wireSalt(4)+[semester], legacy S:/SESSION:
        val isBeacon = cleanMsg.startsWith("/") || cleanMsg.startsWith("S:") || cleanMsg.startsWith("SESSION:")
        if (isBeacon && studentPhase == StudentPhase.LISTENING_FOR_TEACHER) {
            var bToken = ""
            var bSalt = ""
            var bSem: Int? = null
            if (cleanMsg.startsWith("/") && cleanMsg.length >= 9) {
                bToken = cleanMsg.substring(1, 5)
                bSalt = cleanMsg.substring(5, 9)
                bSem = if (cleanMsg.length >= 10) cleanMsg.substring(9, 10).toIntOrNull() else null
            } else {
                val parts = cleanMsg.split(":")
                if (parts.size >= 2) {
                    bToken = parts[1]
                    bSalt = if (parts.size >= 3) parts[2] else ""
                    bSem = parts.getOrNull(3)?.toIntOrNull()
                }
            }
            if (bToken.isNotEmpty()) {
                // Local class pre-filter (UX only — server gate is authoritative):
                val profileSemester = SessionManager.default.currentUser?.semester
                if (bSem != null && profileSemester != null && bSem != profileSemester) {
                    studentStatusMessage = "Nearby class is Semester $bSem — your profile says Semester $profileSemester"
                    android.util.Log.i("AttendanceViewModel", "Beacon rejected by semester pre-filter: bSem=$bSem profile=$profileSemester")
                    return
                }
                // Synchronized game start estimate: (now + k) on the monotonic
                // clock; the minimum across all receptions converges to the
                // teacher's target regardless of BLE delivery jitter. Beacon
                // suffix is anchored ($ = end), so salt tokens containing G/digits
                // can never produce a false countdown.
                beaconCountdownRegex.find(cleanMsg)?.groupValues?.get(1)?.toIntOrNull()?.let { k ->
                    val est = android.os.SystemClock.elapsedRealtime() + k * 1000L
                    syncGameStartElapsedMs = minOf(est, syncGameStartElapsedMs ?: est)
                }
                collectBeaconCandidate(bToken, bSalt, rssi)
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
                !cleanMsg.startsWith("S:") && !cleanMsg.startsWith("SESSION:") &&
                !cleanMsg.startsWith("/") && !cleanMsg.startsWith("\\")

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
                peerRevision++ // R11-C: change-driven batch flush (both new peer and RSSI sample count)

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
            var started = false
            if (isEmitter) {
                // Emitter transmits sustained 30-burst pulse train (3.6s) to ensure listener captures it
                started = proximityManager.emitBurstSequence(count = 30, intervalMs = 120L)
            } else {
                // Listener listens for 3500ms to guarantee capture of incoming chirp ensemble
                started = proximityManager.collectBurstEnsemble(listenDurationMs = 3500L) { summary ->
                    handleEnsembleSummary(summary, peerId)
                }
            }

            // R11 (review §2.4): a BUSY handshake never actually tested the
            // peer — previously the device stayed in testedUltrasonicPeers
            // forever, permanently freezing that student pair's verification.
            // Un-blacklist only on busy (false); the real paths (true) either
            // own completion or deliberately keep the peer (permission-missing
            //  = not retriable). testedUltrasonicPeers must be touched only
            // from the main thread (every other access point lives there), so
            // the hop back to Main is mandatory, not cosmetic.
            if (!started) {
                withContext(Dispatchers.Main) {
                    testedUltrasonicPeers.remove(peerId)
                }
                android.util.Log.i(
                    "AttendanceViewModel",
                    "Ultrasonic handshake deferred for $peerId (role=${if (isEmitter) "emitter" else "listener"} mic/speaker busy) — peer unblacklisted, will retry on next sighting"
                )
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
                                        applyOrDeferStudentPhase(
                                            StudentPhase.VERIFIED_PRESENT,
                                            "Attendance Confirmed",
                                            stopScan = true,
                                            stopBcast = true
                                        )
                                    } else if (
                                        myRecord.status == "COGNITIVE_CHALLENGE_FAILED" &&
                                        chimpScore != null && (chimpScore as Int) >= 12
                                    ) {
                                        // Teacher finalized while this game was still running;
                                        // the server stamped a pending-score failure and our
                                        // final check-in rescues it within the grace window —
                                        // keep waiting instead of flashing a false failure.
                                        applyOrDeferStudentPhase(StudentPhase.WAITING_FOR_AUDIT, null)
                                    } else {
                                        val msg = when {
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
                                        applyOrDeferStudentPhase(StudentPhase.UNVERIFIED_FLAGGED, msg, startScan = true)
                                    }
                                } else {
                                    applyOrDeferStudentPhase(StudentPhase.UNVERIFIED_FLAGGED, "Attendance not confirmed", startScan = true)
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
            // Poll exhausted without a verdict — surface an honest result instead
            // of hanging on the waiting screen forever.
            withContext(Dispatchers.Main) {
                if (studentPhase == StudentPhase.WAITING_FOR_AUDIT) {
                    studentPhase = StudentPhase.UNVERIFIED_FLAGGED
                    studentStatusMessage = "Audit confirmation timed out — please ask your instructor"
                    onStartScanning?.invoke()
                }
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
        chimpScore = null
        chimpChallengeFailed = false
        testedUltrasonicPeers.clear()
        ultrasonicProximityDetected = false
        coLocatedPeerId = null
        auditedRecord = null
        invalidateBeaconLatch()
        detectedSessionId = "----"
        detectedSessionSalt = "------"
        studentPhase = StudentPhase.LISTENING_FOR_TEACHER
        studentStatusMessage = "Session cancelled by instructor — waiting for a new session"
        onSwitchScanLatency?.invoke(true)
        ensureListeningWatchdog()
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
        chimpScore = null
        chimpChallengeFailed = false
        testedUltrasonicPeers.clear()
        ultrasonicProximityDetected = false
        coLocatedPeerId = null
        auditedRecord = null
        invalidateBeaconLatch()
        detectedSessionId = "----"
        detectedSessionSalt = "------"
        studentPhase = StudentPhase.LISTENING_FOR_TEACHER
        studentStatusMessage = "Session expired before the audit completed — please ask your instructor"
        onSwitchScanLatency?.invoke(true)
        ensureListeningWatchdog()
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
                                    applyOrDeferStudentPhase(
                                        StudentPhase.VERIFIED_PRESENT,
                                        "Attendance Confirmed",
                                        stopScan = true,
                                        stopBcast = true
                                    )
                                } else if (
                                    myRecord.status == "COGNITIVE_CHALLENGE_FAILED" &&
                                    chimpScore != null && (chimpScore as Int) >= 12
                                ) {
                                    applyOrDeferStudentPhase(StudentPhase.WAITING_FOR_AUDIT, null)
                                } else {
                                    val msg = when {
                                        hasLocalDefenseFailed -> "Attendance not confirmed. Please see instructor."
                                        myRecord.status == "SPOOFER_REJECTED" -> "Peer claims unconfirmed — please ask your instructor to review"
                                        myRecord.status == "ISOLATED_FLAGGED" -> "Needs instructor confirmation"
                                        myRecord.status == "FLAGGED_WITNESSED" -> "Verified classmates witnessed you — instructor confirmation needed"
                                        else -> "Attendance not confirmed"
                                    }
                                    applyOrDeferStudentPhase(StudentPhase.UNVERIFIED_FLAGGED, msg, startScan = true)
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
        // R8 (review §0.2): drop the Activity-wired BLE bridge lambdas. The
        // cleared ViewModel used to keep referencing lambdas that capture the
        // scanner bound to the (destroying) Activity — an Activity leak for as
        // long as the VM lived on. All invoke sites are `?.invoke()`, so the
        // nulled bridges simply become no-ops.
        onStartBroadcasting = null
        onStopBroadcasting = null
        onStartScanning = null
        onStopScanning = null
        proximityManager.release()
    }
}
