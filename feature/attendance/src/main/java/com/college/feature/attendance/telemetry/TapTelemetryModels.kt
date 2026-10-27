package com.college.feature.attendance.telemetry

import com.google.gson.annotations.SerializedName

/**
 * High-precision tap telemetry record captured on each physical screen interaction.
 */
data class TapEvent(
    val targetId: Int,
    val tapTimeMillis: Long,
    val spawnTimeMillis: Long,
    val latencyMs: Long,
    val targetRelX: Float,
    val targetRelY: Float,
    val touchOffsetX: Float, // distance in px from orb center
    val touchOffsetY: Float, // distance in px from orb center
    val normalizedRadialOffset: Float // [0.0 = dead center, 1.0 = boundary]
)

/**
 * Record of an orb that reached its 1.2s lifetime ceiling without being tapped.
 */
data class ExpiredTargetEvent(
    val targetId: Int,
    val spawnTimeMillis: Long,
    val expiredTimeMillis: Long,
    val targetRelX: Float,
    val targetRelY: Float
)

/**
 * Classification of the user's cognitive state and physical device operation.
 */
enum class FocusClassification {
    /** Single dedicated human holding phone; natural unimodal reaction latency & steady rhythm */
    @SerializedName("FOCUSED_SINGLE_DEVICE")
    FOCUSED_SINGLE_DEVICE,

    /** High latency oscillations (sawtooth pattern), prolonged inter-tap gaze gaps, or high miss rate characteristic of overt visual time-sharing across 2 phones */
    @SerializedName("DIVIDED_ATTENTION_PROXY")
    DIVIDED_ATTENTION_PROXY,

    /** Superhuman reaction time (<140ms), robotic zero-variance timing, or single-pixel repeated coordinate taps */
    @SerializedName("AUTOMATED_SCRIPT")
    AUTOMATED_SCRIPT,

    /** Unusually slow reaction times or multiple missed targets without alternating proxy pattern */
    @SerializedName("INATTENTIVE")
    INATTENTIVE
}

/**
 * Comprehensive cognitive focus and anti-proxy telemetry report.
 */
data class CognitiveFocusReport(
    val totalTaps: Int,
    val totalSpawns: Int,
    val hits: Int,
    val misses: Int,
    val accuracyPercentage: Int,
    val meanLatencyMs: Float,
    val medianLatencyMs: Float,
    val latencyStdDevMs: Float,
    val coeffOfVariation: Float, // sigma / mu
    val meanConsecutiveDiffMs: Float, // Mean Absolute Consecutive Difference (MCD)
    val latencyOscillationIndex: Float, // MCD / mean latency (peaks when switching between 2 phones)
    val maxInterTapGapMs: Long, // longest gap between taps (gaze stalled on 2nd device)
    val meanRadialOffset: Float, // average distance from orb center [0..1]
    val spatialDispersionStdDev: Float, // spread of touch offsets
    val focusIndex: Float, // Composite score [0.0 - 1.0]
    val classification: FocusClassification,
    val diagnosticReason: String
)
