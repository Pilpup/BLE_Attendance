package com.attendance.ble.viewmodel

import android.bluetooth.le.AdvertiseSettings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class StudentReport(
    val id: String,
    val reportedRssi: Int,
    val teacherRxSamples: MutableList<Int> = CopyOnWriteArrayList()
) {
    // Teacher's receiver antenna median RSSI (Uplink)
    fun getTeacherRxMedian(): Int {
        if (teacherRxSamples.isEmpty()) return reportedRssi
        val sorted = teacherRxSamples.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    // Bidirectional distance score (dBm):
    // Combines Teacher's antenna measurement (60%) with Student's reported measurement (40%).
    // Teacher is elevated at podium (stationary reference for entire room).
    // This cancels out individual student receiver antenna sensitivity bias!
    val bidirectionalScore: Double
        get() = (0.6 * getTeacherRxMedian()) + (0.4 * reportedRssi)
}

data class StudentSpreadReport(
    val id: String,
    val reportedSpreadScore: Int, // Differential Spatial Delta: RSSI(Probe) - RSSI(Teacher), cancels antenna bias!
    val teacherRxSamples: MutableList<Int> = CopyOnWriteArrayList()
) {
    fun getTeacherRxMedian(): Int {
        if (teacherRxSamples.isEmpty()) return -75
        val sorted = teacherRxSamples.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    // Bias-free spatial perimeter score:
    // Higher values = farther back from teacher, closer to back wall.
    // Highly negative values (<= -15 dB) = doorway spoofer / outside hallway!
    val finalSpreadScore: Double
        get() = reportedSpreadScore.toDouble()
}

class AttendanceViewModel : ViewModel() {
    val myDeviceID: String = java.util.UUID.randomUUID().toString().replace("-", "").take(6).uppercase()
    var isChildBeacon by mutableStateOf(false)
    var isChild1Beacon by mutableStateOf(false)
    var isChild2Beacon by mutableStateOf(false)

    val collectedReports = CopyOnWriteArrayList<StudentReport>()
    val collectedSpreadReports = CopyOnWriteArrayList<StudentSpreadReport>()

    var lastMeasuredTeacherRssi by mutableStateOf<Int?>(null)
    var lastMeasuredChild1Rssi by mutableStateOf<Int?>(null)

    var onPingReceived: ((rssi: Int) -> Unit)? = null
    var onBecomeChild1: (() -> Unit)? = null
    var onBecomeChild: (() -> Unit)?
        get() = onBecomeChild1
        set(value) { onBecomeChild1 = value }
    var onC1PingReceived: ((rssi: Int) -> Unit)? = null
    var onBecomeChild2: (() -> Unit)? = null
    var onReleaseAnchor: (() -> Unit)? = null

    var isHandlingPing = false
    var isHandlingC1Ping = false

    private val pingHandler = Handler(Looper.getMainLooper())
    val teacherPingSamples = mutableListOf<Int>()
    var isCollectingTeacherPing by mutableStateOf(false)

    val c1PingSamples = mutableListOf<Int>()
    var isCollectingC1Ping by mutableStateOf(false)

    fun calculateMedianRssi(samples: List<Int>): Int {
        if (samples.isEmpty()) return -80
        val sorted = samples.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopSession()
    }

    var isTeacherSession by mutableStateOf(false)

    fun stopSession() {
        pingHandler.removeCallbacksAndMessages(null)
        isTeacherSession = false
        isHandlingPing = false
        isHandlingC1Ping = false
        isCollectingTeacherPing = false
        isCollectingC1Ping = false
        isChildBeacon = false
        isChild1Beacon = false
        isChild2Beacon = false
        sessionStatus = "STANDBY"
    }

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

    // child 1 beacon logs
    var childRssi by mutableStateOf<Int?>(null)
    var childAvgRssi by mutableStateOf(0.0)
    var childPacketCount by mutableStateOf(0)
    val childRssiBuffer = mutableListOf<Int>()

    // child 2 beacon logs (for 3-beacon 2D mesh)
    var child2Rssi by mutableStateOf<Int?>(null)
    var child2AvgRssi by mutableStateOf(0.0)
    var child2PacketCount by mutableStateOf(0)
    val child2RssiBuffer = mutableListOf<Int>()

    // differential RSSI  (delta = teacher - child)
    // cancels out hardware antenna bias
    val deltaRssi: Double?
        get() = if(teacherPacketCount > 0 && childPacketCount > 0){
            teacherAvgRssi - childAvgRssi
        } else null

    val delta2Rssi: Double?
        get() = if(teacherPacketCount > 0 && child2PacketCount > 0){
            teacherAvgRssi - child2AvgRssi
        } else null

    // Tunable Geofencing Parameters (Live adjustable from UI)
    // perimeterCutoffDbm set to -86.0 to prevent rejecting legitimate students in the middle room dead-zone (-80 to -84 dBm)
    var perimeterCutoffDbm by mutableStateOf(-86.0)
    var doorThresholdDbm by mutableStateOf(-72.0)
    var doorBackCutoffDbm by mutableStateOf(-86.0)
    var doorMaxDeltaDb by mutableStateOf(16.0)
    var pathLossExponent by mutableStateOf(2.2)

    fun resetTuningDefaults(){
        perimeterCutoffDbm = -86.0
        doorThresholdDbm = -72.0
        doorBackCutoffDbm = -86.0
        doorMaxDeltaDb = 16.0
        pathLossExponent = 2.2
    }

    // Estimated distance ratios based on path loss exponent n
    val delta1Ratio: Double?
        get() = deltaRssi?.let { Math.pow(10.0, it / (10.0 * pathLossExponent)) }

    val delta2Ratio: Double?
        get() = delta2Rssi?.let { Math.pow(10.0, it / (10.0 * pathLossExponent)) }

    // 2D Relative Classroom Coordinates (Normalized [0, 1])
    // X axis: 0.0 = Left wall (C2), 0.5 = Center, 1.0 = Right wall (C1)
    // Y axis: < 0.0 = Outside door (Hallway), 0.0 = Front door (Teacher), 1.0 = Back wall
    val estimatedCoordX: Double?
        get() {
            if (childPacketCount < 3 || child2PacketCount < 3) return null
            val deltaSide = childAvgRssi - child2AvgRssi
            val rSide = Math.pow(10.0, deltaSide / (10.0 * pathLossExponent))
            return rSide / (1.0 + rSide)
        }

    val estimatedCoordY: Double?
        get() {
            if (teacherPacketCount < 3 || (childPacketCount < 3 && child2PacketCount < 3)) return null
            val maxBack = if (childPacketCount >= 3 && child2PacketCount >= 3) {
                maxOf(childAvgRssi, child2AvgRssi)
            } else if (childPacketCount >= 3) {
                childAvgRssi
            } else child2AvgRssi

            val maxAnchorSignal = maxOf(teacherAvgRssi, maxBack)

            if (maxAnchorSignal < perimeterCutoffDbm) {
                val overshoot = (perimeterCutoffDbm - maxAnchorSignal) * 0.05
                return -(0.2 + overshoot)
            }

            val d1 = deltaRssi ?: 0.0
            val d2 = delta2Rssi ?: 0.0
            if (teacherAvgRssi >= doorThresholdDbm && (maxBack < doorBackCutoffDbm && (d1 > doorMaxDeltaDb && (child2PacketCount < 3 || d2 > doorMaxDeltaDb)))) {
                return -0.15
            }

            val deltaDepth = teacherAvgRssi - maxBack
            val rDepth = Math.pow(10.0, deltaDepth / (10.0 * pathLossExponent))
            return 1.0 / (1.0 + rDepth)
        }

    val isCoordinatesInside: Boolean?
        get() {
            val x = estimatedCoordX ?: return null
            val y = estimatedCoordY ?: return null
            // Account for metal classroom RF variations: allow slight boundary tolerance [ -0.05 .. 1.15 ]
            return x in -0.05..1.05 && y in -0.05..1.15
        }

    val teacherBufferString: String
        get() = if(teacherRssiBuffer.isEmpty()) "[]" else "[${teacherRssiBuffer.takeLast(6).joinToString(",")}]"

    val childBufferString: String
        get() = if(childRssiBuffer.isEmpty()) "[]" else "[${childRssiBuffer.takeLast(6).joinToString(",")}]"

    val child2BufferString: String
        get() = if(child2RssiBuffer.isEmpty()) "[]" else "[${child2RssiBuffer.takeLast(6).joinToString(",")}]"

    enum class VerificationStatus {
        STANDBY,
        CALIBRATING,
        ANCHOR_NODE,
        VERIFIED_INSIDE_2D,
        VERIFIED_INSIDE_LINEAR,
        REJECTED_DOORWAY_SPOOFER,
        REJECTED_OUT_OF_BOUNDS
    }

    val verificationStatus: VerificationStatus
        get() {
            if (isBroadcasting || isChild1Beacon || isChild2Beacon) return VerificationStatus.ANCHOR_NODE
            if (!isScanning) return VerificationStatus.STANDBY
            if (teacherPacketCount < 3) return VerificationStatus.CALIBRATING

            val hasChild1 = childPacketCount >= 3
            val hasChild2 = child2PacketCount >= 3

            if (hasChild1 && hasChild2) {
                // 3-Beacon 2D Mesh Evaluation
                val d1 = deltaRssi ?: return VerificationStatus.CALIBRATING
                val d2 = delta2Rssi ?: return VerificationStatus.CALIBRATING
                val r1 = delta1Ratio ?: 1.0
                val r2 = delta2Ratio ?: 1.0
                val maxAnchorSignal = maxOf(teacherAvgRssi, childAvgRssi, child2AvgRssi)
                val maxBackSignal = maxOf(childAvgRssi, child2AvgRssi)

                // Gate 1: Far-Field / Hallway Perimeter Gate
                if (maxAnchorSignal < perimeterCutoffDbm) {
                    return VerificationStatus.REJECTED_OUT_OF_BOUNDS
                }

                // Gate 2: Collinear Door Gate (Doorway Trap)
                if (teacherAvgRssi >= doorThresholdDbm && (maxBackSignal < doorBackCutoffDbm && (d1 > doorMaxDeltaDb && d2 > doorMaxDeltaDb))) {
                    return VerificationStatus.REJECTED_DOORWAY_SPOOFER
                }

                // Gate 3: 2D Coordinates / Bounding Box Check (Negative coordinates = outside)
                val isInside = isCoordinatesInside
                if (isInside == false || (r1 > 8.5 && r2 > 8.5)) {
                    return if ((estimatedCoordY ?: 0.0) < 0.0) {
                        VerificationStatus.REJECTED_DOORWAY_SPOOFER
                    } else {
                        VerificationStatus.REJECTED_OUT_OF_BOUNDS
                    }
                }

                return VerificationStatus.VERIFIED_INSIDE_2D
            } else if (hasChild1) {
                // 2-Beacon Linear Fallback
                val d1 = deltaRssi ?: return VerificationStatus.CALIBRATING
                val maxAnchor = maxOf(teacherAvgRssi, childAvgRssi)
                if (maxAnchor < perimeterCutoffDbm) return VerificationStatus.REJECTED_OUT_OF_BOUNDS
                if (teacherAvgRssi >= doorThresholdDbm && childAvgRssi < doorBackCutoffDbm && d1 > doorMaxDeltaDb) {
                    return VerificationStatus.REJECTED_DOORWAY_SPOOFER
                }
                return VerificationStatus.VERIFIED_INSIDE_LINEAR
            } else {
                return VerificationStatus.CALIBRATING
            }
        }

    val doorGateStatusText: String
        get() {
            if (teacherPacketCount < 3 || childPacketCount < 3) return "CALIBRATING"
            val d1 = deltaRssi ?: return "CALIBRATING"
            val d2 = delta2Rssi
            if (d2 != null && child2PacketCount >= 3) {
                val maxBack = maxOf(childAvgRssi, child2AvgRssi)
                return if (teacherAvgRssi >= doorThresholdDbm && (maxBack < doorBackCutoffDbm && (d1 > doorMaxDeltaDb && d2 > doorMaxDeltaDb))) "FAIL (Hallway Wall Trap)" else "PASS"
            }
            return if (teacherAvgRssi >= doorThresholdDbm && childAvgRssi < doorBackCutoffDbm && d1 > doorMaxDeltaDb) "FAIL (Door Delta > ${"%.0f".format(doorMaxDeltaDb)}dB)" else "PASS"
        }

    val diagonalGateStatusText: String
        get() {
            if (!isScanning) return "STANDBY"
            if (teacherPacketCount < 3 || (childPacketCount < 3 && child2PacketCount < 3)) {
                return "CALIBRATING"
            }
            if (child2PacketCount < 3) {
                // 2-Beacon Linear Fallback
                val maxAnchor = maxOf(teacherAvgRssi, childAvgRssi)
                return if (maxAnchor < perimeterCutoffDbm) "FAIL (All Anchors Weak: ${"%.1f".format(maxAnchor)} dBm)" else "PASS"
            }
            // 3-Beacon 2D Mesh Mode
            val maxAnchor = maxOf(teacherAvgRssi, childAvgRssi, child2AvgRssi)
            if (maxAnchor < perimeterCutoffDbm) {
                return "FAIL (Outside: Max ${"%.1f".format(maxAnchor)} < ${"%.0f".format(perimeterCutoffDbm)}dBm)"
            }
            val r1 = delta1Ratio ?: 1.0
            val r2 = delta2Ratio ?: 1.0
            return if (r1 > 8.5 && r2 > 8.5) "FAIL (Beyond Hull Ratio)" else "PASS"
        }

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

        child2Rssi = null
        child2AvgRssi = 0.0
        child2PacketCount = 0
        child2RssiBuffer.clear()

        avgLatencyMs = 0.0
        latencyBuffer.clear()
        isChildBeacon = false
        isChild1Beacon = false
        isChild2Beacon = false
        collectedReports.clear()
        collectedSpreadReports.clear()
        sessionStatus = "STANDBY"
        electedChildId = null
        electedChild1Id = null
        electedChild2Id = null
        lastMeasuredTeacherRssi = null
        lastMeasuredChild1Rssi = null
        pingHandler.removeCallbacksAndMessages(null)
        teacherPingSamples.clear()
        c1PingSamples.clear()
        isCollectingTeacherPing = false
        isCollectingC1Ping = false
        isHandlingPing = false
        isHandlingC1Ping = false
    }

    var selectedTxPower by mutableStateOf(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
    var selectedAdvMode by mutableStateOf(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
    var broadcastTokken by mutableStateOf("TEACHER")
    var isBroadcasterMode by mutableStateOf(true)
    var sessionStatus by mutableStateOf("STANDBY")
    var electedChildId by mutableStateOf<String?>(null)
    var electedChild1Id by mutableStateOf<String?>(null)
    var electedChild2Id by mutableStateOf<String?>(null)

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun onPacketReceived(rssi: Int, latencyMs: Double, messageText: String){
        val timeStr = timeFormat.format(Date())

        // 1. Check if Teacher pinged (Phase 1)
        if(messageText == "TEACHER_PING"){
            isTeacherSession = false
            isHandlingC1Ping = false
            if(isChild1Beacon || isChild2Beacon || isChildBeacon){
                isChildBeacon = false
                isChild1Beacon = false
                isChild2Beacon = false
                onReleaseAnchor?.invoke()
            }
            if(!isHandlingPing){
                isHandlingPing = true
                isCollectingTeacherPing = true
                teacherPingSamples.clear()
                teacherPingSamples.add(rssi)
                sessionStatus = "SAMPLING TEACHER PING (1)..."
                val log = "[$timeStr] >> TEACHER_PING started | Sampling window (1200ms)..."
                if(scanLogs.size >= 50) scanLogs.removeAt(0)
                scanLogs.add(log)

                // 1200ms sampling window to filter out multipath fading nulls
                pingHandler.postDelayed({
                    isCollectingTeacherPing = false
                    val count = teacherPingSamples.size
                    val medianRssi = calculateMedianRssi(teacherPingSamples)
                    lastMeasuredTeacherRssi = medianRssi
                    val min = teacherPingSamples.minOrNull() ?: medianRssi
                    val max = teacherPingSamples.maxOrNull() ?: medianRssi
                    sessionStatus = "PING SAMPLED: $medianRssi dBm ($count pkts)"
                    val doneLog = "[$timeStr] >> PING DONE ($count pkts) | Median: $medianRssi dBm [min: $min, max: $max]"
                    if(scanLogs.size >= 50) scanLogs.removeAt(0)
                    scanLogs.add(doneLog)

                    onPingReceived?.invoke(medianRssi)

                    // Auto-release handling flag after 3.5s so subsequent teacher pings can be processed cleanly
                    pingHandler.postDelayed({
                        isHandlingPing = false
                    }, 3500)
                }, 1200)
            } else if(isCollectingTeacherPing){
                teacherPingSamples.add(rssi)
                sessionStatus = "SAMPLING TEACHER PING (${teacherPingSamples.size})..."
            }
            return
        }

        // 2. Check if a Student is reporting Teacher RSSI (Phase 2)
        if(messageText.startsWith("REPORT:")){
            // Only the Teacher collects student reports
            if(isTeacherSession || isBroadcasting){
                val parts = messageText.split(":")
                if(parts.size == 3){
                    val studentId = parts[1]
                    val reportedRssi = parts[2].toIntOrNull() ?: rssi
                    val existing = collectedReports.find { it.id == studentId }
                    if(existing == null){
                        val newReport = StudentReport(studentId, reportedRssi)
                        newReport.teacherRxSamples.add(rssi)
                        collectedReports.add(newReport)
                        sessionStatus = "PHASE 2: REPORTS (${collectedReports.size})"
                        val log = "[$timeStr] >> REPORT from [$studentId] | SRx: $reportedRssi dBm | TRx: $rssi dBm"
                        if(scanLogs.size >= 50) scanLogs.removeAt(0)
                        scanLogs.add(log)
                    } else {
                        existing.teacherRxSamples.add(rssi)
                    }
                }
            }
            return
        }

        // 3. Check if Child 1 is elected (Phase 3)
        if(messageText.startsWith("Child:") || messageText.startsWith("ELECT_C1:")){
            val targetId = if(messageText.startsWith("Child:")) messageText.removePrefix("Child:").trim() else messageText.removePrefix("ELECT_C1:").trim()
            electedChildId = targetId
            electedChild1Id = targetId
            if(targetId == myDeviceID && !isChild1Beacon){
                isChildBeacon = true
                isChild1Beacon = true
                sessionStatus = "★ ELECTED AS CHILD 1 BEACON ★"
                val log = "[$timeStr] >> [★] YOU ARE CHILD 1! ($targetId)"
                if(scanLogs.size >= 50) scanLogs.removeAt(0)
                scanLogs.add(log)
                onBecomeChild1?.invoke() ?: onBecomeChild?.invoke()
            } else if(targetId != myDeviceID && isChild1Beacon){
                isChildBeacon = false
                isChild1Beacon = false
                sessionStatus = "C1 REASSIGNED TO [$targetId] | Reverting to Student"
                val log = "[$timeStr] >> C1 REASSIGNED TO [$targetId] | Reverting to Student"
                if(scanLogs.size >= 50) scanLogs.removeAt(0)
                scanLogs.add(log)
                onReleaseAnchor?.invoke()
            } else {
                sessionStatus = "CHILD 1: [$targetId] ELECTED"
                val log = "[$timeStr] >> CHILD 1: [$targetId] ELECTED"
                if(scanLogs.size >= 50) scanLogs.removeAt(0)
                scanLogs.add(log)
            }
            return
        }

        // 4. Check if Child 1 pinged (Phase 4)
        if(messageText == "C1_PING"){
            if(!isChild1Beacon && !isHandlingC1Ping){
                isHandlingC1Ping = true
                isCollectingC1Ping = true
                c1PingSamples.clear()
                c1PingSamples.add(rssi)
                sessionStatus = "SAMPLING C1 PING (1)..."
                val log = "[$timeStr] >> C1_PING started | Sampling window (1200ms)..."
                if(scanLogs.size >= 50) scanLogs.removeAt(0)
                scanLogs.add(log)

                // 1200ms sampling window to filter out multipath fading nulls
                pingHandler.postDelayed({
                    isCollectingC1Ping = false
                    val count = c1PingSamples.size
                    val medianRssi = calculateMedianRssi(c1PingSamples)
                    lastMeasuredChild1Rssi = medianRssi
                    val min = c1PingSamples.minOrNull() ?: medianRssi
                    val max = c1PingSamples.maxOrNull() ?: medianRssi
                    sessionStatus = "C1 SAMPLED: $medianRssi dBm ($count pkts)"
                    val doneLog = "[$timeStr] >> C1 DONE ($count pkts) | Median: $medianRssi dBm [min: $min, max: $max]"
                    if(scanLogs.size >= 50) scanLogs.removeAt(0)
                    scanLogs.add(doneLog)

                    onC1PingReceived?.invoke(medianRssi)

                    // Auto-release handling flag after 3.5s so subsequent C1 pings can be processed cleanly
                    pingHandler.postDelayed({
                        isHandlingC1Ping = false
                    }, 3500)
                }, 1200)
            } else if(isCollectingC1Ping && !isChild1Beacon){
                c1PingSamples.add(rssi)
                sessionStatus = "SAMPLING C1 PING (${c1PingSamples.size})..."
            }
            return
        }

        // 5. Check if a Student is reporting SpreadScore (Phase 5)
        if(messageText.startsWith("REPORT2:")){
            // Only the Teacher collects spread reports
            if(isTeacherSession || isBroadcasting){
                val parts = messageText.split(":")
                if(parts.size == 3){
                    val studentId = parts[1]
                    val reportedScore = parts[2].toIntOrNull() ?: (rssi - (lastMeasuredTeacherRssi ?: -70))
                    if(studentId != electedChild1Id){
                        val existing = collectedSpreadReports.find { it.id == studentId }
                        if(existing == null){
                            val newSpread = StudentSpreadReport(studentId, reportedScore)
                            newSpread.teacherRxSamples.add(rssi)
                            collectedSpreadReports.add(newSpread)
                            sessionStatus = "PHASE 5: SPREAD REPORTS (${collectedSpreadReports.size})"
                            val deltaStr = if(reportedScore > 0) "+$reportedScore" else "$reportedScore"
                            val log = "[$timeStr] >> REPORT2 from [$studentId] | Delta: $deltaStr dB | TRx: $rssi dBm"
                            if(scanLogs.size >= 50) scanLogs.removeAt(0)
                            scanLogs.add(log)
                        } else {
                            existing.teacherRxSamples.add(rssi)
                        }
                    }
                }
            }
            return
        }

        // 6. Check if Child 2 is elected (Phase 6)
        if(messageText.startsWith("ELECT_C2:")){
            val targetId = messageText.removePrefix("ELECT_C2:").trim()
            electedChild2Id = targetId
            if(targetId == myDeviceID && !isChild2Beacon && !isChild1Beacon){
                isChildBeacon = true
                isChild2Beacon = true
                sessionStatus = "★ ELECTED AS CHILD 2 BEACON ★"
                val log = "[$timeStr] >> [★] YOU ARE CHILD 2! ($targetId)"
                if(scanLogs.size >= 50) scanLogs.removeAt(0)
                scanLogs.add(log)
                onBecomeChild2?.invoke()
            } else if(targetId != myDeviceID && isChild2Beacon){
                isChildBeacon = false
                isChild2Beacon = false
                sessionStatus = "C2 REASSIGNED TO [$targetId] | Reverting to Student"
                val log = "[$timeStr] >> C2 REASSIGNED TO [$targetId] | Reverting to Student"
                if(scanLogs.size >= 50) scanLogs.removeAt(0)
                scanLogs.add(log)
                onReleaseAnchor?.invoke()
            } else {
                sessionStatus = "CHILD 2: [$targetId] ELECTED"
                val log = "[$timeStr] >> CHILD 2: [$targetId] ELECTED"
                if(scanLogs.size >= 50) scanLogs.removeAt(0)
                scanLogs.add(log)
            }
            return
        }

        // 7. Steady-state beacons
        if(messageText == "TEACHER"){
            isHandlingPing = false
            // log math:
            teacherRssi = rssi
            teacherPacketCount++

            // average calc
            teacherRssiBuffer.add(rssi)
            if(teacherRssiBuffer.size > 10) teacherRssiBuffer.removeAt(0)
            teacherAvgRssi = teacherRssiBuffer.average()
        } else if(messageText == "CHILD_2"){
            isHandlingC1Ping = false
            child2Rssi = rssi
            child2PacketCount++
            child2RssiBuffer.add(rssi)
            if(child2RssiBuffer.size > 10) child2RssiBuffer.removeAt(0)
            child2AvgRssi = child2RssiBuffer.average()
        } else if(messageText == "CHILD_1" || messageText == "CHILD"){
            isHandlingPing = false
            isHandlingC1Ping = false
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

        val logEntry = "[$timeStr] $messageText | $rssi dBm | lat: ${"%.1f".format(latencyMs)}ms"

        // keep up to 50 readings for debugger console
        if(scanLogs.size >= 50){
            scanLogs.removeAt(0)
        }
        scanLogs.add(logEntry)
    }
}
