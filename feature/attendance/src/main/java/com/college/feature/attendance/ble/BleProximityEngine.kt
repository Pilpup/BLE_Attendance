package com.college.feature.attendance.ble

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.roundToInt

// Physical path-loss propagation model for 2.4 GHz Bluetooth Low Energy
object BleProximityEngine {

    // Calibrated reference RSSI at 1 meter separation for mobile phones (ADVERTISE_TX_POWER_LOW)
    const val REF_RSSI_AT_1M = -65.0

    // Path-loss exponent for indoor classroom environments with desks and obstacles (free space is 2.0)
    const val PATH_LOSS_EXPONENT = 2.2

    // Precomputed divisor for default path-loss calculation: 10.0 * 2.2 = 22.0
    private const val DEFAULT_PATH_LOSS_DIVISOR = 22.0

    // Main app threshold: peers with RSSI >= -70 dBm (~1.6m) trigger Layer 4 ultrasonic handshake
    const val ULTRASONIC_TRIGGER_RSSI = -70

    // Immediate desk threshold (< 30 cm)
    const val IMMEDIATE_DESK_RSSI = -55

    // Estimates physical distance in meters using the Log-Distance Path Loss model
    fun estimateDistanceMeters(
        rssi: Int,
        refRssi: Double = REF_RSSI_AT_1M,
        pathLossExponent: Double = PATH_LOSS_EXPONENT
    ): Double {
        if (rssi >= 0 || rssi <= -100) return -1.0
        val divisor = if (pathLossExponent == PATH_LOSS_EXPONENT) DEFAULT_PATH_LOSS_DIVISOR else 10.0 * pathLossExponent
        val ratio = (refRssi - rssi) / divisor
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

    // Classifies peer into physical desk proximity zones based on calibrated RSSI
    fun getProximityZone(medianRssi: Int): BleProximityZone {
        return when {
            medianRssi >= IMMEDIATE_DESK_RSSI -> BleProximityZone.IMMEDIATE_DESK
            medianRssi >= ULTRASONIC_TRIGGER_RSSI -> BleProximityZone.ADJACENT_BENCH_TRIGGER
            else -> BleProximityZone.DISTANT_CLASSROOM
        }
    }

    // Overload for backward compatibility with 2-parameter callers
    fun getProximityZone(medianRssi: Int, @Suppress("UNUSED_PARAMETER") distMeters: Double): BleProximityZone {
        return getProximityZone(medianRssi)
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

    // Observable cached properties updated only on new sample addition
    var confirmedMedianRssi by mutableIntStateOf(-99)
        private set
    var minRssi by mutableIntStateOf(-99)
        private set
    var maxRssi by mutableIntStateOf(-99)
        private set
    var estimatedDistanceMeters by mutableDoubleStateOf(-1.0)
        private set
    var proximityZone by mutableStateOf(BleProximityZone.DISTANT_CLASSROOM)
        private set
    var isUltrasonicEligible by mutableStateOf(false)
        private set

    // Adds a newly received radio packet sample (retaining a sliding window of 100 samples)
    fun addSample(rssi: Int, latencyMs: Double) {
        lastRssi = rssi
        lastLatencyMs = latencyMs
        lastSeenTimestamp = System.currentTimeMillis()
        if (rssiSamples.size >= 100) {
            rssiSamples.removeAt(0)
        }
        rssiSamples.add(rssi)

        // Recompute derived statistics once per sample rather than on every Compose recomposition
        val sorted = rssiSamples.sorted()
        val median = sorted[sorted.size / 2]
        confirmedMedianRssi = median
        minRssi = sorted.first()
        maxRssi = sorted.last()

        val estDist = BleProximityEngine.estimateDistanceMeters(median)
        estimatedDistanceMeters = estDist
        proximityZone = BleProximityEngine.getProximityZone(median)
        isUltrasonicEligible = median >= BleProximityEngine.ULTRASONIC_TRIGGER_RSSI
    }

    val sampleCount: Int get() = rssiSamples.size

    // Instantaneous distance based on latest live RSSI packet
    val instantaneousDistanceMeters: Double
        get() = BleProximityEngine.estimateDistanceMeters(lastRssi)
}
