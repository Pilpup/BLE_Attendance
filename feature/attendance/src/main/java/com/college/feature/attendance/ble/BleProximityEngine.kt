package com.college.feature.attendance.ble

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.roundToInt

// Physical path-loss propagation model for 2.4 GHz Bluetooth Low Energy
object BleProximityEngine {

    // Calibrated reference RSSI at 1 meter separation for mobile phones (ADVERTISE_TX_POWER_LOW)
    const val REF_RSSI_AT_1M = -65.0

    // Path-loss exponent for indoor classroom environments with desks and obstacles (free space is 2.0)
    const val PATH_LOSS_EXPONENT = 2.2

    // Main app threshold: peers with RSSI >= -70 dBm (~1.6m) trigger Layer 4 ultrasonic handshake
    const val ULTRASONIC_TRIGGER_RSSI = -70

    // Estimates physical distance in meters using the Log-Distance Path Loss model
    fun estimateDistanceMeters(
        rssi: Int,
        refRssi: Double = REF_RSSI_AT_1M,
        pathLossExponent: Double = PATH_LOSS_EXPONENT
    ): Double {
        if (rssi >= 0 || rssi <= -100) return -1.0
        val ratio = (refRssi - rssi) / (10.0 * pathLossExponent)
        val dist = Math.pow(10.0, ratio)
        return dist.coerceIn(0.05, 30.0)
    }

    // Formats distance into readable centimeters or meters
    fun formatDistance(meters: Double): String {
        return when {
            meters <= 0 -> "--"
            meters < 1.0 -> "${(meters * 100).roundToInt()} cm"
            else -> String.format(Locale.US, "%.2f m", meters)
        }
    }

    // Classifies peer into physical desk proximity zones
    fun getProximityZone(medianRssi: Int, distMeters: Double): BleProximityZone {
        return when {
            medianRssi >= -55 || (distMeters in 0.01..0.30) -> BleProximityZone.IMMEDIATE_DESK
            medianRssi >= ULTRASONIC_TRIGGER_RSSI || (distMeters in 0.31..1.60) -> BleProximityZone.ADJACENT_BENCH_TRIGGER
            else -> BleProximityZone.DISTANT_CLASSROOM
        }
    }
}

// Physical proximity zone classification for BLE radio signals
enum class BleProximityZone(val label: String, val badgeColor: Long) {
    IMMEDIATE_DESK("🚨 IMMEDIATE DESK (< 30 cm)", 0xFF00F5A0),
    ADJACENT_BENCH_TRIGGER("⚡ ADJACENT BENCH (Ultrasonic Trigger Zone)", 0xFF00E5FF),
    DISTANT_CLASSROOM("DISTANT PEER (> 1.6 m)", 0xFF90A4AE)
}

// Observable peer state tracking live packets, median RSSI, and distance
class BleLabPeer(val peerId: String) {
    var lastRssi by mutableIntStateOf(-99)
    val rssiSamples = mutableStateListOf<Int>()
    var lastLatencyMs by mutableDoubleStateOf(0.0)
    var lastSeenTimestamp by mutableLongStateOf(System.currentTimeMillis())

    // Adds a newly received radio packet sample (retaining a sliding window of 100 samples)
    fun addSample(rssi: Int, latencyMs: Double) {
        lastRssi = rssi
        lastLatencyMs = latencyMs
        lastSeenTimestamp = System.currentTimeMillis()
        if (rssiSamples.size >= 100) {
            rssiSamples.removeAt(0)
        }
        rssiSamples.add(rssi)
    }

    // Confirmed RSSI: exact same median calculation as main app's PeerData.medianRssi
    val confirmedMedianRssi: Int
        get() = if (rssiSamples.isEmpty()) -99 else {
            val sorted = rssiSamples.sorted()
            sorted[sorted.size / 2]
        }

    val sampleCount: Int get() = rssiSamples.size

    val minRssi: Int get() = if (rssiSamples.isEmpty()) -99 else rssiSamples.minOrNull() ?: -99
    val maxRssi: Int get() = if (rssiSamples.isEmpty()) -99 else rssiSamples.maxOrNull() ?: -99

    // Estimated distance based on confirmed median RSSI
    val estimatedDistanceMeters: Double
        get() = BleProximityEngine.estimateDistanceMeters(confirmedMedianRssi)

    // Instantaneous distance based on latest live RSSI packet
    val instantaneousDistanceMeters: Double
        get() = BleProximityEngine.estimateDistanceMeters(lastRssi)

    val proximityZone: BleProximityZone
        get() = BleProximityEngine.getProximityZone(confirmedMedianRssi, estimatedDistanceMeters)

    // Whether this peer's RSSI satisfies the main app's >= -70 dBm threshold for Layer 4 handshake
    val isUltrasonicEligible: Boolean
        get() = confirmedMedianRssi >= BleProximityEngine.ULTRASONIC_TRIGGER_RSSI
}
