package com.attendance.ble.viewmodel

import android.bluetooth.le.AdvertiseSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

enum class StudentStatus {
    PENDING,
    VERIFIED_PRESENT,
    SPOOFER_REJECTED,
    ISOLATED_FLAGGED
}

data class PeerDiscovery(
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

data class StudentAuditRecord(
    val studentId: String,
    val reportedPeers: List<String>,
    val mutualPeers: List<String>,
    val status: StudentStatus,
    val reason: String
)

class AttendanceViewModel : ViewModel() {
    // 4-character hex device ID (65,536 space, compact for BLE legacy payload)
    val myDeviceID: String = java.util.UUID.randomUUID().toString().replace("-", "").take(4).uppercase()

    // Deterministic cohort assignment for time-slotted ALOHA (Teacher-conducted shuffling)
    val cohort4: Int get() = Math.abs(myDeviceID.hashCode()) % 4
    val cohort3: Int get() = Math.abs(myDeviceID.hashCode()) % 3

    // System State & Role
    var isTeacher by mutableStateOf(false)
    var isBroadcasting by mutableStateOf(false)
    var isScanning by mutableStateOf(false)
    var sessionStatus by mutableStateOf("STANDBY")
    var currentPhase by mutableStateOf("IDLE")

    // Student Peer Map (Observed 1m neighbors during discovery rounds)
    val discoveredPeers = mutableStateMapOf<String, PeerDiscovery>()
    var myReportedPeers by mutableStateOf<List<String>>(emptyList())
    var myAttendanceStatus by mutableStateOf(StudentStatus.PENDING)

    // Teacher Roster & Audit Results
    val studentReportsMap = CopyOnWriteArrayList<Pair<String, List<String>>>()
    val auditResults = mutableStateListOf<StudentAuditRecord>()

    // Audit summary counters
    var verifiedCount by mutableStateOf(0)
    var spooferCount by mutableStateOf(0)
    var isolatedCount by mutableStateOf(0)

    // Callbacks to Activity for radio actions
    var onBroadcastProbe: ((cohortSlot: String) -> Unit)? = null
    var onBroadcastReport: ((peers: List<String>) -> Unit)? = null

    // Scanner / Radio settings
    var selectedTxPower by mutableStateOf(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
    var selectedAdvMode by mutableStateOf(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
    var broadcastToken by mutableStateOf("STANDBY")

    // Live logs & timing
    val scanLogs = mutableStateListOf<String>()
    val latencyBuffer = mutableListOf<Double>()
    var avgLatencyMs by mutableStateOf(0.0)

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun resetSession() {
        mainHandler.removeCallbacksAndMessages(null)
        discoveredPeers.clear()
        studentReportsMap.clear()
        auditResults.clear()
        myReportedPeers = emptyList()
        myAttendanceStatus = StudentStatus.PENDING
        verifiedCount = 0
        spooferCount = 0
        isolatedCount = 0
        sessionStatus = "STANDBY"
        currentPhase = "IDLE"
    }

    fun resetLogs() {
        scanLogs.clear()
        latencyBuffer.clear()
        avgLatencyMs = 0.0
        resetSession()
    }

    override fun onCleared() {
        super.onCleared()
        mainHandler.removeCallbacksAndMessages(null)
    }

    // Graph Audit Engine: Evaluates mutual reciprocity and triadic closure (shared bridge)
    fun performMeshAudit() {
        auditResults.clear()
        val reports = studentReportsMap.toMap() // Map<StudentId, List<ClaimedNeighborIds>>

        var verified = 0
        var spoofers = 0
        var isolated = 0

        // Step 1: Identify all direct mutual pairs (A claims B AND B claims A)
        val directMutualMap = mutableMapOf<String, MutableSet<String>>()
        for ((studentId, claimedNeighbors) in reports) {
            val mutuals = claimedNeighbors.filter { peerId ->
                val peerClaims = reports[peerId]
                peerClaims != null && peerClaims.contains(studentId)
            }
            directMutualMap[studentId] = mutuals.toMutableSet()
        }

        // Set of students directly verified via reciprocal match
        val verifiedSet = directMutualMap.filter { it.value.isNotEmpty() }.keys.toMutableSet()

        // Step 2: Triadic Closure / Shared Bridge Resolution (The Facebook / Matchmaking Metric)
        // If student A is not yet directly verified, check if A shares a common bridge node M
        // with other verified nodes, or if A claims a verified hub M who confirmed presence in the cluster
        for ((studentId, claimedNeighbors) in reports) {
            if (!verifiedSet.contains(studentId) && claimedNeighbors.isNotEmpty()) {
                // Check if any claimed neighbor M is already verified in the cluster
                val bridgeNode = claimedNeighbors.firstOrNull { verifiedSet.contains(it) }
                if (bridgeNode != null) {
                    val bridgeMutuals = directMutualMap[bridgeNode] ?: emptySet()
                    if (bridgeMutuals.isNotEmpty()) {
                        // Triadic closure satisfied: student belongs to the connected physical desk cluster
                        verifiedSet.add(studentId)
                        directMutualMap.getOrPut(studentId) { mutableSetOf() }.add(bridgeNode)
                    }
                }
            }
        }

        // Step 3: Final Classification & Justification
        for ((studentId, claimedNeighbors) in reports) {
            val mutuals = directMutualMap[studentId] ?: emptySet()
            val status: StudentStatus
            val reason: String

            when {
                mutuals.isNotEmpty() && claimedNeighbors.any { reports[it]?.contains(studentId) == true } -> {
                    status = StudentStatus.VERIFIED_PRESENT
                    reason = "Direct mutual match with [${mutuals.joinToString(", ")}]"
                    verified++
                }
                verifiedSet.contains(studentId) -> {
                    val bridge = claimedNeighbors.firstOrNull { verifiedSet.contains(it) } ?: "Peer"
                    status = StudentStatus.VERIFIED_PRESENT
                    reason = "Triadic closure via bridge [$bridge] in classroom cluster"
                    verified++
                }
                claimedNeighbors.isEmpty() -> {
                    status = StudentStatus.ISOLATED_FLAGGED
                    reason = "Zero neighbors detected (Isolated seating)"
                    isolated++
                }
                else -> {
                    status = StudentStatus.SPOOFER_REJECTED
                    reason = "Unreciprocated link to [${claimedNeighbors.joinToString(", ")}] (Hallway Spoofer)"
                    spoofers++
                }
            }

            auditResults.add(
                StudentAuditRecord(
                    studentId = studentId,
                    reportedPeers = claimedNeighbors,
                    mutualPeers = mutuals.toList(),
                    status = status,
                    reason = reason
                )
            )
        }

        verifiedCount = verified
        spooferCount = spoofers
        isolatedCount = isolated
        sessionStatus = "AUDIT COMPLETE: $verified Verified, $spoofers Spoofers, $isolated Isolated"
        currentPhase = "AUDIT_FINISHED"
    }

    // Packet Dispatcher
    fun onPacketReceived(rssi: Int, latencyMs: Double, messageText: String) {
        val timeStr = timeFormat.format(Date())

        // 1. Teacher Conductor Ticks: Round 1 (Mod 4)
        if (messageText.startsWith("TICK:R1:G")) {
            val groupTarget = messageText.removePrefix("TICK:R1:G").toIntOrNull() ?: -1
            currentPhase = "ROUND 1: COHORT $groupTarget/4"
            if (!isTeacher) {
                if (cohort4 == groupTarget) {
                    sessionStatus = "BROADCASTING PROBE (Round 1: Cohort $groupTarget)"
                    onBroadcastProbe?.invoke("R1:G$groupTarget")
                } else {
                    sessionStatus = "SCANNING (Round 1: Listening to Cohort $groupTarget)"
                }
            }
            logEvent("[$timeStr] TICK R1 Cohort $groupTarget")
            return
        }

        // 2. Teacher Conductor Ticks: Round 2 (Mod 3)
        if (messageText.startsWith("TICK:R2:H")) {
            val groupTarget = messageText.removePrefix("TICK:R2:H").toIntOrNull() ?: -1
            currentPhase = "ROUND 2: COHORT $groupTarget/3"
            if (!isTeacher) {
                if (cohort3 == groupTarget) {
                    sessionStatus = "BROADCASTING PROBE (Round 2: Cohort $groupTarget)"
                    onBroadcastProbe?.invoke("R2:H$groupTarget")
                } else {
                    sessionStatus = "SCANNING (Round 2: Listening to Cohort $groupTarget)"
                }
            }
            logEvent("[$timeStr] TICK R2 Cohort $groupTarget")
            return
        }

        // 3. Teacher Collection Command
        if (messageText == "COLLECT_REPORTS") {
            currentPhase = "COLLECTING REPORTS"
            if (!isTeacher) {
                // Select Top 3 closest peers with calibrated proximity threshold >= -70 dBm
                val top = discoveredPeers.values
                    .filter { it.medianRssi >= -70 }
                    .sortedByDescending { it.medianRssi }
                    .take(3)
                    .map { it.peerId }

                myReportedPeers = top
                sessionStatus = "SENDING REPORT: [${top.joinToString(", ")}]"
                onBroadcastReport?.invoke(top)
            }
            logEvent("[$timeStr] >> COLLECT_REPORTS Triggered")
            return
        }

        // 4. Student Probes (Peer Discovery)
        if (messageText.startsWith("P:")) {
            val peerId = messageText.removePrefix("P:").trim()
            if (peerId != myDeviceID && !isTeacher) {
                val peer = discoveredPeers[peerId]
                if (peer == null) {
                    val newPeer = PeerDiscovery(peerId)
                    newPeer.rssiSamples.add(rssi)
                    discoveredPeers[peerId] = newPeer
                } else {
                    peer.rssiSamples.add(rssi)
                }
                logEvent("[$timeStr] >> PROBE from [$peerId] | $rssi dBm")
            }
            return
        }

        // 5. Student Reports (Uplink to Teacher)
        if (messageText.startsWith("R:")) {
            if (isTeacher) {
                val parts = messageText.split(":")
                if (parts.size >= 2) {
                    val studentId = parts[1]
                    val peers = parts.drop(2).filter { it.isNotEmpty() }
                    val existingIndex = studentReportsMap.indexOfFirst { it.first == studentId }
                    if (existingIndex == -1) {
                        studentReportsMap.add(studentId to peers)
                        sessionStatus = "COLLECTING: ${studentReportsMap.size} Reports"
                        logEvent("[$timeStr] >> REPORT from [$studentId] -> [${peers.joinToString(",")}]")
                    }
                }
            }
            return
        }

        // 6. Teacher Verdict Notification (Direct & Bulk OK tokens)
        if (messageText.startsWith("VERDICT:")) {
            val parts = messageText.split(":")
            if (parts.size >= 3) {
                val targetId = parts[1]
                val statusStr = parts[2]
                if (targetId == myDeviceID) {
                    if (statusStr == "VERIFIED") {
                        myAttendanceStatus = StudentStatus.VERIFIED_PRESENT
                        sessionStatus = "✅ ATTENDANCE CONFIRMED (VERIFIED PRESENT)"
                    } else if (statusStr == "REJECTED") {
                        myAttendanceStatus = StudentStatus.SPOOFER_REJECTED
                        sessionStatus = "❌ REJECTED (FLAGGED AS HALLWAY SPOOFER)"
                    }
                }
            }
            return
        }

        // 7. Bulk Teacher Verdict (e.g. OK:A4B1:C2D3:E5F6)
        if (messageText.startsWith("OK:")) {
            val confirmedIds = messageText.removePrefix("OK:").split(":")
            if (confirmedIds.contains(myDeviceID)) {
                myAttendanceStatus = StudentStatus.VERIFIED_PRESENT
                sessionStatus = "✅ ATTENDANCE CONFIRMED (VERIFIED PRESENT)"
            }
            return
        }

        // Standard diagnostic log
        latencyBuffer.add(latencyMs)
        if (latencyBuffer.size > 10) latencyBuffer.removeAt(0)
        avgLatencyMs = latencyBuffer.average()

        logEvent("[$timeStr] $messageText | $rssi dBm | lat: ${"%.1f".format(latencyMs)}ms")
    }

    private fun logEvent(log: String) {
        if (scanLogs.size >= 60) {
            scanLogs.removeAt(0)
        }
        scanLogs.add(log)
    }
}
